package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GoOutRepository {
    private val cityPages = mapOf(
        "Ostrava" to "https://goout.net/cs/ostrava/akce/lezaawlkk/",
        "Praha" to "https://goout.net/cs/praha/akce/leznyvlkk/",
        "Brno" to "https://goout.net/cs/brno/akce/lezjyvlkk/",
        "Olomouc" to "https://goout.net/cs/olomouc/akce/lezeawlkk/",
        "Opava" to "https://goout.net/cs/opava/akce/lezryvlkk/",
        "Plzeň" to "https://goout.net/cs/plzen/akce/lezlyvlkk/",
        "Pardubice" to "https://goout.net/cs/pardubice/akce/lezpyvlkk/",
        "Zlín" to "https://goout.net/cs/zlin/akce/lezhyvlkk/",
        "Frýdek-Místek" to "https://goout.net/cs/frydek-mistek/akce/lezcawlkk/"
    )

    fun load(city: String?, keyword: String?): EventLoadResult {
        val requested = city?.takeIf { it.isNotBlank() && it != "Všechna města" }
        val pages = if (requested != null) {
            cityPages[requested]?.let { listOf(requested to it) } ?: emptyList()
        } else {
            cityPages.entries.map { it.key to it.value }
        }
        if (pages.isEmpty()) return EventLoadResult(emptyList(), "GoOut pro toto město nemá veřejnou stránku v Kulturadaru.")

        return try {
            val all = mutableListOf<CulturalEvent>()
            for ((cityName, pageUrl) in pages) {
                all += loadCity(cityName, pageUrl)
                if (requested == null && all.size >= 120) break
            }
            val q = keyword.orEmpty().trim()
            val filtered = if (q.isBlank()) all else all.filter {
                listOf(it.title, it.subtitle, it.venue, it.city, it.genre.orEmpty()).any { value -> value.contains(q, true) }
            }
            EventLoadResult(filtered.distinctBy { canonicalKey(it) }, if (filtered.isEmpty()) "GoOut pro tento výběr nic nenašel." else null)
        } catch (_: Exception) {
            EventLoadResult(emptyList(), "GoOut se nepodařilo načíst.")
        }
    }

    private fun loadCity(city: String, pageUrl: String): List<CulturalEvent> {
        val listing = connect(pageUrl)
        val links = listing.select("a[href]")
            .mapNotNull { element ->
                val href = element.absUrl("href").ifBlank {
                    runCatching { URL(URL(pageUrl), element.attr("href")).toString() }.getOrNull().orEmpty()
                }
                href.takeIf { it.contains("goout.net/") && EVENT_LINK.containsMatchIn(it) }
            }
            .distinct()
            .take(18)

        if (links.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(6)
        return try {
            val futures = links.map { url -> Callable { loadEvent(url, city) } }.map(pool::submit)
            futures.mapNotNull { future -> runCatching { future.get(4, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadEvent(url: String, fallbackCity: String): CulturalEvent? {
        val doc = connect(url)
        val eventJson = findEventJson(doc) ?: return fallbackFromMeta(doc, url, fallbackCity)

        val name = eventJson.optString("name").trim().ifBlank { return null }
        val startRaw = eventJson.optString("startDate").trim()
        val date = parseDate(startRaw) ?: return null
        val time = parseTime(startRaw)
        val location = eventJson.optJSONObject("location")
        val address = location?.optJSONObject("address")
        val venue = location?.optString("name").orEmpty().ifBlank { "Místo neuvedeno" }
        val city = address?.optString("addressLocality").orEmpty().ifBlank { fallbackCity }
        val image = extractImage(eventJson, doc)
        val offer = firstObject(eventJson.opt("offers"))
        val price = offer?.optDouble("price")?.takeIf { !it.isNaN() }?.toInt()
        val highPrice = offer?.optDouble("highPrice")?.takeIf { !it.isNaN() }?.toInt()
        val currency = offer?.optString("priceCurrency")?.takeIf(String::isNotBlank)
        val availability = offer?.optString("availability")?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        val description = eventJson.optString("description").trim().ifBlank {
            doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        }.ifBlank { "Podrobnosti jsou dostupné na stránce pořadatele." }
        val text = (doc.title() + " " + doc.body().text().take(1200))
        val type = inferType(text)
        val geo = location?.optJSONObject("geo")

        return CulturalEvent(
            id = "goout-${url.hashCode()}",
            title = name,
            subtitle = inferSubtitle(text),
            type = type,
            city = city,
            venue = venue,
            dateLabel = date,
            timeLabel = time,
            priceCzk = price,
            imageUrl = image,
            description = Jsoup.parse(description).text(),
            ticketUrl = eventJson.optString("url").takeIf(String::isNotBlank) ?: url,
            source = "GoOut",
            genre = inferGenre(text),
            status = availability,
            latitude = geo?.optDouble("latitude")?.takeIf { !it.isNaN() },
            longitude = geo?.optDouble("longitude")?.takeIf { !it.isNaN() },
            imageIsFallback = false,
            imageAttribution = null,
            address = address?.optString("streetAddress")?.takeIf(String::isNotBlank),
            postalCode = address?.optString("postalCode")?.takeIf(String::isNotBlank),
            priceMaxCzk = highPrice,
            currency = currency,
            salesStart = offer?.optString("validFrom")?.takeIf(String::isNotBlank),
            salesEnd = null,
            timezone = null
        )
    }

    private fun fallbackFromMeta(doc: Document, url: String, fallbackCity: String): CulturalEvent? {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val text = doc.body().text()
        val date = DATE_ISO.find(text)?.value ?: return null
        val time = TIME.find(text)?.value.orEmpty()
        return CulturalEvent(
            id = "goout-${url.hashCode()}",
            title = title,
            subtitle = inferSubtitle(text),
            type = inferType(text),
            city = fallbackCity,
            venue = findVenue(doc, fallbackCity),
            dateLabel = date,
            timeLabel = time,
            priceCzk = null,
            imageUrl = image,
            description = description.ifBlank { "Podrobnosti jsou dostupné na GoOut." },
            ticketUrl = url,
            source = "GoOut",
            genre = inferGenre(text)
        )
    }

    private fun findEventJson(doc: Document): JSONObject? {
        doc.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) return@forEach
            val parsed = runCatching {
                when {
                    raw.startsWith("[") -> JSONArray(raw)
                    raw.startsWith("{") -> JSONObject(raw)
                    else -> null
                }
            }.getOrNull() ?: return@forEach
            findEventRecursive(parsed)?.let { return it }
        }
        return null
    }

    private fun findEventRecursive(value: Any?): JSONObject? = when (value) {
        is JSONObject -> {
            if (isEventType(value.opt("@type"))) value
            else value.keys().asSequence().mapNotNull { key -> findEventRecursive(value.opt(key)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { i -> findEventRecursive(value.opt(i)) }.firstOrNull()
        else -> null
    }

    private fun isEventType(type: Any?): Boolean = when (type) {
        is String -> type.contains("Event", true)
        is JSONArray -> (0 until type.length()).any { type.optString(it).contains("Event", true) }
        else -> false
    }

    private fun extractImage(event: JSONObject, doc: Document): String? {
        return when (val image = event.opt("image")) {
            is String -> image.takeIf(String::isNotBlank)
            is JSONArray -> image.optString(0).takeIf(String::isNotBlank)
            is JSONObject -> image.optString("url").takeIf(String::isNotBlank)
            else -> null
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
    }

    private fun firstObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is JSONArray -> value.optJSONObject(0)
        else -> null
    }

    private fun parseDate(value: String): String? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10), DateTimeFormatter.ISO_LOCAL_DATE).toString() }.getOrNull()
    }

    private fun parseTime(value: String): String {
        return runCatching { OffsetDateTime.parse(value).toLocalTime().toString().take(5) }.getOrNull()
            ?: if (value.length >= 16 && value[10] == 'T') value.substring(11, 16) else ""
    }

    private fun inferType(text: String): EventType = when {
        text.contains("koncert", true) || text.contains("music", true) -> EventType.CONCERT
        text.contains("divadlo", true) || text.contains("theatre", true) -> EventType.THEATRE
        text.contains("film", true) || text.contains("kino", true) || text.contains("cinema", true) -> EventType.CINEMA
        text.contains("výstav", true) || text.contains("exhibition", true) || text.contains("galer", true) -> EventType.EXHIBITION
        else -> EventType.EVENT
    }

    private fun inferSubtitle(text: String): String = when {
        text.contains("festival", true) -> "Festival"
        text.contains("koncert", true) -> "Koncert"
        text.contains("divadlo", true) -> "Divadlo"
        text.contains("výstav", true) -> "Výstava"
        text.contains("film", true) || text.contains("kino", true) -> "Film"
        text.contains("party", true) -> "Party"
        else -> "Akce"
    }

    private fun inferGenre(text: String): String? = listOf(
        "Rock", "Metal", "Pop", "Jazz", "Hip hop", "Rap", "Elektronika", "Klasická hudba", "Stand-up", "Drama", "Komedie"
    ).firstOrNull { text.contains(it, true) }

    private fun findVenue(doc: Document, fallbackCity: String): String {
        val text = doc.body().text()
        val marker = "Místo"
        val index = text.indexOf(marker, ignoreCase = true)
        if (index >= 0) {
            val part = text.substring(index + marker.length).take(120).trim()
            return part.substringBefore("Adresa").substringBefore(fallbackCity).trim().takeIf { it.length in 2..80 } ?: "Místo neuvedeno"
        }
        return "Místo neuvedeno"
    }

    private fun canonicalKey(event: CulturalEvent): String =
        "${event.title.lowercase().replace(Regex("\\s+"), " ").trim()}|${event.dateLabel}|${event.city.lowercase()}"

    private fun connect(url: String): Document = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Kulturadar/1.8")
        .referrer("https://www.google.com/")
        .timeout(4500)
        .followRedirects(true)
        .get()

    companion object {
        private val EVENT_LINK = Regex("/s[a-z0-9]{5,}/?($|[?#])", RegexOption.IGNORE_CASE)
        private val DATE_ISO = Regex("\\b20\\d{2}-\\d{2}-\\d{2}\\b")
        private val TIME = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b")
    }
}
