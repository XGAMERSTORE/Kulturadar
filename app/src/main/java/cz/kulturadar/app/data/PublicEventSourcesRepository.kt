package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Best-effort aggregator over public Czech event calendars.
 * No API key is required. Sources are isolated: one broken/blocked site never breaks the feed.
 * Local calendars carry a fallback city so their events are not discarded when JSON-LD omits locality.
 */
class PublicEventSourcesRepository {
    private data class Source(val name: String, val url: String, val fallbackCity: String? = null)

    private val sources = listOf(
        Source("Kudy z nudy", "https://www.kudyznudy.cz/akce"),
        Source("Informuji.cz", "https://www.informuji.cz/akce/"),
        Source("CityBee", "https://www.citybee.cz/kalendar-akci/", "Praha"),
        Source("SMSticket", "https://www.smsticket.cz/vstupenky"),
        Source("Ticketportal", "https://www.ticketportal.cz/"),
        Source("Ticketstream", "https://www.ticketstream.cz/"),
        Source("TicketLIVE", "https://www.ticketlive.cz/"),
        Source("ColosseumTicket", "https://www.colosseumticket.cz/"),
        Source("Prague City Tourism", "https://www.prague.eu/cs/akce/", "Praha"),
        Source("Praha.eu", "https://praha.eu/web/praha/kalendar-akci", "Praha"),
        Source("OstravaInfo", "https://www.ostravainfo.cz/cz/akce/", "Ostrava"),
        Source("Slezská Ostrava", "https://slezska.ostrava.cz/cs/o-slezske-ostrave/kalendar-akci", "Ostrava"),
        Source("GoToBrno", "https://www.gotobrno.cz/akce/", "Brno"),
        Source("Olomouc Tourism", "https://tourism.olomouc.eu/akce/", "Olomouc"),
        Source("Visit Plzeň", "https://www.visitplzen.eu/akce/", "Plzeň"),
        Source("Liberec", "https://www.liberec.cz/cz/obcan/aktuality/akce/", "Liberec"),
        Source("HKinfo", "https://www.hkinfo.cz/cs/kalendar-akci", "Hradec Králové"),
        Source("Pardubice", "https://www.pardubice.eu/volny-cas/kalendar-akci/", "Pardubice"),
        Source("Zlín", "https://www.zlin.eu/kalendar-akci", "Zlín"),
        Source("Karlovy Vary", "https://www.karlovyvary.cz/cs/kalendar-akci", "Karlovy Vary"),
        Source("Budejce.cz", "https://www.budejce.cz/kalendar-akci", "České Budějovice"),
        Source("Ústí nad Labem", "https://www.usti.cz/cz/volny-cas/kalendar-akci/", "Ústí nad Labem"),
        Source("VisitCzechia", "https://www.visitczechia.com/en-US/Things-to-Do/Events")
    )

    fun load(city: String?, keyword: String?): EventLoadResult {
        val pool = Executors.newFixedThreadPool(7)
        return try {
            val futures = sources.map { source -> Callable { loadSource(source) } }.map(pool::submit)
            val all = futures.flatMap { future ->
                runCatching { future.get(15, TimeUnit.SECONDS) }.getOrElse { emptyList() }
            }
            val requestedCity = city?.takeIf { it.isNotBlank() && it != "Všechna města" }
            val q = keyword.orEmpty().trim()
            val filtered = all.asSequence()
                .filter { e -> requestedCity == null || cityMatches(e, requestedCity) }
                .filter { e -> q.isBlank() || listOf(e.title, e.subtitle, e.venue, e.city, e.genre.orEmpty(), e.description).any { it.contains(q, true) } }
                .distinctBy(::canonicalKey)
                .take(650)
                .toList()
            EventLoadResult(filtered, if (filtered.isEmpty() && all.isEmpty()) "Veřejné kalendáře se teď nepodařilo načíst." else null)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun cityMatches(e: CulturalEvent, requested: String): Boolean {
        if (e.city.equals("Česko", true)) return e.venue.contains(requested, true) || e.description.contains(requested, true)
        return e.city.contains(requested, true) || requested.contains(e.city, true) || e.venue.contains(requested, true)
    }

    private fun loadSource(source: Source): List<CulturalEvent> {
        val listing = runCatching { connect(source.url) }.getOrNull() ?: return emptyList()
        val direct = parseEvents(listing, source, source.url)
        if (direct.size >= 10) return direct.take(60)

        val host = runCatching { URL(source.url).host.removePrefix("www.") }.getOrDefault("")
        val links = listing.select("a[href]").asSequence()
            .mapNotNull { a ->
                val absolute = a.absUrl("href").ifBlank {
                    runCatching { URL(URL(source.url), a.attr("href")).toString() }.getOrNull().orEmpty()
                }
                absolute.takeIf { url ->
                    url.startsWith("http") &&
                        (host.isBlank() || runCatching { URL(url).host.removePrefix("www.") == host }.getOrDefault(false)) &&
                        EVENTISH.containsMatchIn(url.lowercase())
                }
            }
            .distinct()
            .take(30)
            .toList()

        val detailPool = Executors.newFixedThreadPool(5)
        val details = try {
            links.map { url -> Callable {
                val doc = runCatching { connect(url) }.getOrNull() ?: return@Callable emptyList<CulturalEvent>()
                parseEvents(doc, source, url)
            } }.map(detailPool::submit).flatMap { f -> runCatching { f.get(8, TimeUnit.SECONDS) }.getOrElse { emptyList() } }
        } finally { detailPool.shutdownNow() }

        return (direct + details).distinctBy(::canonicalKey).take(90)
    }

    private fun parseEvents(doc: Document, source: Source, pageUrl: String): List<CulturalEvent> {
        val jsonEvents = mutableListOf<JSONObject>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) return@forEach
            val parsed: Any = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: return@forEach
            collectEvents(parsed, jsonEvents)
        }
        return jsonEvents.mapNotNull { toEvent(it, doc, source, pageUrl) }
    }

    private fun collectEvents(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                if (isEventType(value.opt("@type"))) out += value
                value.keys().forEach { key -> collectEvents(value.opt(key), out) }
            }
            is JSONArray -> for (i in 0 until value.length()) collectEvents(value.opt(i), out)
        }
    }

    private fun isEventType(value: Any?): Boolean = when (value) {
        is String -> value.contains("Event", true)
        is JSONArray -> (0 until value.length()).any { value.optString(it).contains("Event", true) }
        else -> false
    }

    private fun toEvent(json: JSONObject, doc: Document, source: Source, pageUrl: String): CulturalEvent? {
        val title = json.optString("name").trim().ifBlank { return null }
        val startRaw = json.optString("startDate").trim()
        val date = parseDate(startRaw) ?: return null
        val time = parseTime(startRaw)
        val location = firstObject(json.opt("location"))
        val address = firstObject(location?.opt("address"))
        val city = address?.optString("addressLocality").orEmpty()
            .ifBlank { address?.optString("addressRegion").orEmpty() }
            .ifBlank { source.fallbackCity.orEmpty() }
            .ifBlank { inferCityFromDocument(doc) }
            .ifBlank { "Česko" }
        val venue = location?.optString("name").orEmpty().ifBlank { inferVenue(doc, source.fallbackCity) }
        val geo = firstObject(location?.opt("geo"))
        val offer = firstObject(json.opt("offers"))
        val text = listOf(title, json.optString("description"), doc.title(), source.name).joinToString(" ")
        val price = numberInt(offer, "lowPrice") ?: numberInt(offer, "price")
        val maxPrice = numberInt(offer, "highPrice")
        val url = json.optString("url").takeIf { it.startsWith("http") } ?: pageUrl
        val image = imageUrl(json.opt("image"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val description = Jsoup.parse(json.optString("description")).text().ifBlank {
            doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        }.ifBlank { "Podrobnosti jsou dostupné u pořadatele." }
        val availability = offer?.optString("availability")?.substringAfterLast('/')?.takeIf(String::isNotBlank)

        return CulturalEvent(
            id = "public-${source.name.hashCode()}-${url.hashCode()}-${title.hashCode()}-$date",
            title = title,
            subtitle = inferSubtitle(text),
            type = inferType(text),
            city = city,
            venue = venue,
            dateLabel = date,
            timeLabel = time,
            priceCzk = price,
            imageUrl = image,
            description = description,
            ticketUrl = url,
            source = source.name,
            genre = inferGenre(text),
            status = availability,
            latitude = numberDouble(geo, "latitude"),
            longitude = numberDouble(geo, "longitude"),
            imageIsFallback = false,
            address = address?.optString("streetAddress")?.takeIf(String::isNotBlank),
            postalCode = address?.optString("postalCode")?.takeIf(String::isNotBlank),
            priceMaxCzk = maxPrice,
            currency = offer?.optString("priceCurrency")?.takeIf(String::isNotBlank),
            salesStart = offer?.optString("validFrom")?.takeIf(String::isNotBlank)
        )
    }

    private fun inferVenue(doc: Document, fallbackCity: String?): String {
        val h = doc.selectFirst("[itemprop=location], [class*=venue], [class*=place]")?.text()?.trim().orEmpty()
        return h.takeIf { it.length in 2..120 && !it.equals(fallbackCity, true) } ?: "Místo neuvedeno"
    }

    private fun inferCityFromDocument(doc: Document): String {
        val text = listOf(doc.title(), doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()).joinToString(" ")
        return KNOWN_CITIES.firstOrNull { text.contains(it, true) }.orEmpty()
    }

    private fun firstObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is JSONArray -> value.optJSONObject(0)
        else -> null
    }

    private fun imageUrl(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
        is JSONObject -> value.optString("url").takeIf(String::isNotBlank)
        else -> null
    }

    private fun numberInt(obj: JSONObject?, key: String): Int? {
        if (obj == null || !obj.has(key)) return null
        return obj.optDouble(key).takeIf { !it.isNaN() }?.toInt()
            ?: obj.optString(key).replace(',', '.').toDoubleOrNull()?.toInt()
    }

    private fun numberDouble(obj: JSONObject?, key: String): Double? {
        if (obj == null || !obj.has(key)) return null
        return obj.optDouble(key).takeIf { !it.isNaN() }
            ?: obj.optString(key).replace(',', '.').toDoubleOrNull()
    }

    private fun parseDate(value: String): String? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10), DateTimeFormatter.ISO_LOCAL_DATE).toString() }.getOrNull()
    }

    private fun parseTime(value: String): String =
        runCatching { OffsetDateTime.parse(value).toLocalTime().toString().take(5) }.getOrNull()
            ?: if (value.length >= 16 && value[10] == 'T') value.substring(11, 16) else ""

    private fun inferType(text: String): EventType = when {
        text.contains("koncert", true) || text.contains("music", true) -> EventType.CONCERT
        text.contains("divad", true) || text.contains("theatre", true) || text.contains("theater", true) -> EventType.THEATRE
        text.contains("film", true) || text.contains("kino", true) || text.contains("cinema", true) -> EventType.CINEMA
        text.contains("výstav", true) || text.contains("exhibition", true) || text.contains("galer", true) -> EventType.EXHIBITION
        else -> EventType.EVENT
    }

    private fun inferSubtitle(text: String): String = when {
        text.contains("festival", true) -> "Festival"
        text.contains("koncert", true) -> "Koncert"
        text.contains("divad", true) -> "Divadlo"
        text.contains("výstav", true) -> "Výstava"
        text.contains("film", true) || text.contains("kino", true) -> "Film"
        text.contains("party", true) -> "Party"
        text.contains("sport", true) -> "Sport"
        text.contains("workshop", true) -> "Workshop"
        else -> "Akce"
    }

    private fun inferGenre(text: String): String? = listOf(
        "Rock", "Metal", "Pop", "Jazz", "Rap", "Hip hop", "Elektronika", "Klasická hudba", "Stand-up", "Komedie", "Drama", "Techno", "Folk", "Punk"
    ).firstOrNull { text.contains(it, true) }

    private fun canonicalKey(event: CulturalEvent): String =
        "${event.title.lowercase().replace(Regex("\\s+"), " ").trim()}|${event.dateLabel}|${event.city.lowercase()}"

    private fun connect(url: String): Document = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Kulturadar/2.2")
        .referrer("https://www.google.com/")
        .timeout(7000)
        .followRedirects(true)
        .maxBodySize(3_000_000)
        .get()

    companion object {
        private val EVENTISH = Regex("(akce|event|program|kalendar|calendar|vstupenk|ticket|koncert|festival|divad|kino|detail)")
        private val KNOWN_CITIES = listOf(
            "Praha", "Brno", "Ostrava", "Plzeň", "Olomouc", "Liberec", "Hradec Králové", "Pardubice", "Zlín", "České Budějovice",
            "Ústí nad Labem", "Jihlava", "Karlovy Vary", "Opava", "Frýdek-Místek", "Karviná", "Havířov", "Třinec", "Kladno",
            "Mladá Boleslav", "Most", "Teplice", "Děčín", "Chomutov", "Jablonec nad Nisou", "Znojmo", "Přerov", "Prostějov", "Šumperk", "Vsetín"
        )
    }
}
