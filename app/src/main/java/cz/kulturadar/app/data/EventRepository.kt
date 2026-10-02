package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class EventRepository {
    fun demoEvents(): List<CulturalEvent> = listOf(
        CulturalEvent("demo-1", "Noční premiéra", "Velký sál · premiéra", EventType.THEATRE, "Ostrava", "Divadlo", "Dnes", "19:00", 420, description = "Ukázková divadelní událost pro první spuštění Kulturadaru."),
        CulturalEvent("demo-2", "Film pod hvězdami", "Letní kino", EventType.CINEMA, "Ostrava", "Kino", "Zítra", "20:30", 180, description = "Ukázková filmová projekce. Živá data lze napojit přes samostatné zdroje."),
        CulturalEvent("demo-3", "Temný večer", "Koncert · klub", EventType.CONCERT, "Ostrava", "Klub", "Pá 9. 10.", "20:00", 590, description = "Ukázkový koncert v katalogu."),
        CulturalEvent("demo-4", "Galerie po setmění", "Komentovaná prohlídka", EventType.EXHIBITION, "Opava", "Galerie", "So 10. 10.", "18:00", 0, description = "Ukázková bezplatná kulturní akce."),
        CulturalEvent("demo-5", "Městský festival", "Hudba · jídlo · program", EventType.EVENT, "Frýdek-Místek", "Centrum", "Ne 11. 10.", "14:00", 250, description = "Ukázková celodenní akce.")
    )

    fun ticketmaster(apiKey: String, city: String?, keyword: String?): List<CulturalEvent> {
        if (apiKey.isBlank()) return emptyList()
        val params = mutableListOf("apikey=${enc(apiKey)}", "countryCode=CZ", "size=40", "sort=date,asc")
        city?.takeIf { it.isNotBlank() && it != "Všechna města" }?.let { params += "city=${enc(it)}" }
        keyword?.takeIf { it.isNotBlank() }?.let { params += "keyword=${enc(it)}" }
        val conn = (URL("https://app.ticketmaster.com/discovery/v2/events.json?${params.joinToString("&")}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = root.optJSONObject("_embedded")?.optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val dates = e.optJSONObject("dates")?.optJSONObject("start")
                    val venue = e.optJSONObject("_embedded")?.optJSONArray("venues")?.optJSONObject(0)
                    val images = e.optJSONArray("images")
                    var bestImage: String? = null
                    var bestWidth = 0
                    if (images != null) for (j in 0 until images.length()) {
                        val image = images.optJSONObject(j) ?: continue
                        val width = image.optInt("width", 0)
                        if (width > bestWidth) { bestWidth = width; bestImage = image.optString("url").takeIf(String::isNotBlank) }
                    }
                    val segment = e.optJSONArray("classifications")?.optJSONObject(0)?.optJSONObject("segment")?.optString("name").orEmpty()
                    add(CulturalEvent(
                        id = e.optString("id", "tm-$i"),
                        title = e.optString("name", "Událost"),
                        subtitle = segment.ifBlank { "Akce" },
                        type = mapType(segment),
                        city = venue?.optJSONObject("city")?.optString("name").orEmpty().ifBlank { "Česko" },
                        venue = venue?.optString("name").orEmpty().ifBlank { "Místo neuvedeno" },
                        dateLabel = dates?.optString("localDate").orEmpty().ifBlank { "Datum neuvedeno" },
                        timeLabel = dates?.optString("localTime").orEmpty().take(5).ifBlank { "" },
                        priceCzk = e.optJSONArray("priceRanges")?.optJSONObject(0)?.optDouble("min")?.toInt(),
                        imageUrl = bestImage,
                        description = e.optString("info").ifBlank { e.optString("pleaseNote") }.ifBlank { "Podrobnosti jsou dostupné u pořadatele." },
                        ticketUrl = e.optString("url").takeIf(String::isNotBlank),
                        source = "Ticketmaster"
                    ))
                }
            }
        } catch (_: Exception) { emptyList() } finally { conn.disconnect() }
    }

    private fun mapType(segment: String): EventType = when {
        segment.contains("Music", true) -> EventType.CONCERT
        segment.contains("Arts", true) || segment.contains("Theatre", true) -> EventType.THEATRE
        segment.contains("Film", true) -> EventType.CINEMA
        else -> EventType.EVENT
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
