package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Public SMSticket source. No API key. Uses city pages when possible and falls back to the nationwide listing. */
class SmsTicketRepository {
    fun load(city: String?): EventLoadResult {
        val requested = city?.trim()?.takeIf { it.isNotBlank() && it != "Všechna města" }
        val urls = buildList {
            if (requested != null) add("https://www.smsticket.cz/akce/${slug(requested)}")
            add("https://www.smsticket.cz/vstupenky")
        }.distinct()

        val links = linkedSetOf<String>()
        urls.forEach { listingUrl ->
            val doc = runCatching { connect(listingUrl) }.getOrNull() ?: return@forEach
            doc.select("a[href*=/vstupenky/]").forEach { a ->
                val u = a.absUrl("href").ifBlank { runCatching { URL(URL(listingUrl), a.attr("href")).toString() }.getOrNull().orEmpty() }
                if (u.startsWith("https://www.smsticket.cz/vstupenky/")) links += u.substringBefore('?').substringBefore('#')
            }
        }
        if (links.isEmpty()) return EventLoadResult(emptyList())

        val pool = Executors.newFixedThreadPool(6)
        val parsed = try {
            links.take(if (requested != null) 100 else 70)
                .map { url -> Callable { parseDetail(url) } }
                .map(pool::submit)
                .mapNotNull { f -> runCatching { f.get(8, TimeUnit.SECONDS) }.getOrNull() }
        } finally { pool.shutdownNow() }

        val filtered = if (requested == null) parsed else parsed.filter { e ->
            e.city.contains(requested, true) || requested.contains(e.city, true) ||
                e.venue.contains(requested, true) || e.description.contains(requested, true)
        }
        return EventLoadResult(filtered.distinctBy { key(it) })
    }

    private fun parseDetail(url: String): CulturalEvent? {
        val doc = connect(url)
        val json = findEvent(doc)
        if (json != null) return fromJson(json, doc, url)

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val body = doc.body().text()
        val date = CZ_DATE.find(body)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let null
            val mo = m.groupValues[2].toIntOrNull() ?: return@let null
            val y = m.groupValues[3].toIntOrNull() ?: return@let null
            runCatching { LocalDate.of(y, mo, d).toString() }.getOrNull()
        } ?: return null
        val time = TIME.find(body)?.value.orEmpty()
        val city = doc.select("a[href*=/akce/]").map { it.text().trim() }.firstOrNull { it.isNotBlank() } ?: inferCity(body)
        val venue = doc.select("a[href*=/mista/]").firstOrNull()?.text()?.trim().orEmpty().ifBlank { "Místo neuvedeno" }
        val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty().ifBlank { body.take(900) }
        val price = PRICE.find(body)?.groupValues?.getOrNull(1)?.replace(" ", "")?.toIntOrNull()
        return CulturalEvent(
            id = "sms-${url.hashCode()}", title = title, subtitle = inferSubtitle(body), type = inferType(body),
            city = city.ifBlank { "Česko" }, venue = venue, dateLabel = date, timeLabel = time, priceCzk = price,
            imageUrl = img, description = desc, ticketUrl = url, source = "SMSticket", genre = inferGenre(body)
        )
    }

    private fun fromJson(j: JSONObject, doc: Document, url: String): CulturalEvent? {
        val title = j.optString("name").trim().ifBlank { return null }
        val start = j.optString("startDate").trim()
        val date = runCatching { OffsetDateTime.parse(start).toLocalDate().toString() }.getOrNull()
            ?: runCatching { LocalDate.parse(start.take(10)).toString() }.getOrNull() ?: return null
        val time = runCatching { OffsetDateTime.parse(start).toLocalTime().toString().take(5) }.getOrNull()
            ?: start.takeIf { it.length >= 16 }?.substring(11,16).orEmpty()
        val location = firstObject(j.opt("location"))
        val address = firstObject(location?.opt("address"))
        val city = address?.optString("addressLocality").orEmpty().ifBlank { inferCity(doc.body().text()) }
        val venue = location?.optString("name").orEmpty().ifBlank { doc.select("a[href*=/mista/]").firstOrNull()?.text().orEmpty() }.ifBlank { "Místo neuvedeno" }
        val geo = firstObject(location?.opt("geo"))
        val offer = firstObject(j.opt("offers"))
        val body = doc.body().text()
        val image = image(j.opt("image")) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val price = offer?.optDouble("price")?.takeIf { !it.isNaN() }?.toInt() ?: PRICE.find(body)?.groupValues?.getOrNull(1)?.replace(" ", "")?.toIntOrNull()
        return CulturalEvent(
            id = "sms-${url.hashCode()}", title = title, subtitle = inferSubtitle(body), type = inferType(body),
            city = city.ifBlank { "Česko" }, venue = venue, dateLabel = date, timeLabel = time, priceCzk = price,
            imageUrl = image, description = Jsoup.parse(j.optString("description")).text().ifBlank { doc.selectFirst("meta[name=description]")?.attr("content").orEmpty() }.ifBlank { "Podrobnosti jsou dostupné u pořadatele." },
            ticketUrl = j.optString("url").takeIf(String::isNotBlank) ?: url, source = "SMSticket", genre = inferGenre(body),
            status = offer?.optString("availability")?.substringAfterLast('/'),
            latitude = geo?.optDouble("latitude")?.takeIf { !it.isNaN() }, longitude = geo?.optDouble("longitude")?.takeIf { !it.isNaN() },
            address = address?.optString("streetAddress")?.takeIf(String::isNotBlank), postalCode = address?.optString("postalCode")?.takeIf(String::isNotBlank)
        )
    }

    private fun findEvent(doc: Document): JSONObject? {
        doc.select("script[type=application/ld+json]").forEach { s ->
            val raw = s.data().ifBlank { s.html() }.trim()
            val root: Any = runCatching { if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull() ?: return@forEach
            findRecursive(root)?.let { return it }
        }
        return null
    }
    private fun findRecursive(v: Any?): JSONObject? = when (v) {
        is JSONObject -> if (v.optString("@type").contains("Event", true)) v else v.keys().asSequence().mapNotNull { findRecursive(v.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until v.length()).asSequence().mapNotNull { findRecursive(v.opt(it)) }.firstOrNull()
        else -> null
    }
    private fun firstObject(v: Any?): JSONObject? = when(v) { is JSONObject -> v; is JSONArray -> v.optJSONObject(0); else -> null }
    private fun image(v: Any?): String? = when(v) { is String -> v.takeIf(String::isNotBlank); is JSONArray -> v.optString(0).takeIf(String::isNotBlank); is JSONObject -> v.optString("url").takeIf(String::isNotBlank); else -> null }

    private fun slug(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun inferCity(text: String): String = CITY_HINT.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    private fun inferType(t: String) = when { t.contains("koncert", true) || t.contains("hudba", true) -> EventType.CONCERT; t.contains("divad", true) -> EventType.THEATRE; t.contains("kino", true) || t.contains("film", true) -> EventType.CINEMA; t.contains("výstav", true) -> EventType.EXHIBITION; else -> EventType.EVENT }
    private fun inferSubtitle(t: String) = when { t.contains("festival", true) -> "Festival"; t.contains("stand-up", true) -> "Stand-up"; t.contains("koncert", true) -> "Koncert"; t.contains("divad", true) -> "Divadlo"; t.contains("kino", true) || t.contains("film", true) -> "Film"; else -> "Akce" }
    private fun inferGenre(t: String): String? = listOf("Rock","Metal","Pop","Jazz","Rap","Hip hop","Elektronika","Techno","Punk","Folk","Stand-up","Komedie").firstOrNull { t.contains(it, true) }
    private fun key(e: CulturalEvent) = "${e.title.lowercase()}|${e.dateLabel}|${e.city.lowercase()}"
    private fun connect(url: String): Document = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Kulturadar/2.2").timeout(8000).followRedirects(true).maxBodySize(3_000_000).get()

    companion object {
        private val CZ_DATE = Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(20\\d{2})\\b")
        private val TIME = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b")
        private val PRICE = Regex("(?:Cena(?:\\s+za)?|od)\\s*(\\d[\\d ]*)\\s*Kč", RegexOption.IGNORE_CASE)
        private val CITY_HINT = Regex("(?:,|\\s)([A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ][A-Za-zÁ-ž -]{2,40})(?:\\s|$)")
    }
}
