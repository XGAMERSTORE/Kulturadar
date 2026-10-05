package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class EventRepository {
    /**
     * No fake catalogue in production. If live data are unavailable, UI shows a clear empty/error state.
     */
    fun demoEvents(): List<CulturalEvent> = emptyList()

    fun ticketmaster(apiKey: String, city: String?, keyword: String?): List<CulturalEvent> {
        if (apiKey.isBlank()) return emptyList()

        val params = mutableListOf(
            "apikey=${enc(apiKey)}",
            "countryCode=CZ",
            "size=200",
            "sort=date,asc",
            "locale=cs,*"
        )
        city?.takeIf { it.isNotBlank() && it != "Všechna města" }?.let { params += "city=${enc(it)}" }
        keyword?.takeIf { it.isNotBlank() }?.let { params += "keyword=${enc(it)}" }

        val conn = (URL("https://app.ticketmaster.com/discovery/v2/events.json?${params.joinToString("&")}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kulturadar/1.6 Android")
        }

        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = root.optJSONObject("_embedded")?.optJSONArray("events") ?: return emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val dates = e.optJSONObject("dates")?.optJSONObject("start")
                    val status = e.optJSONObject("dates")?.optJSONObject("status")?.optString("code").orEmpty().ifBlank { null }
                    val venue = e.optJSONObject("_embedded")?.optJSONArray("venues")?.optJSONObject(0)
                    val location = venue?.optJSONObject("location")
                    val classifications = e.optJSONArray("classifications")?.optJSONObject(0)
                    val segment = classifications?.optJSONObject("segment")?.optString("name").orEmpty()
                    val genre = classifications?.optJSONObject("genre")?.optString("name").orEmpty().takeIf { it.isNotBlank() && !it.equals("Undefined", true) }
                    val subGenre = classifications?.optJSONObject("subGenre")?.optString("name").orEmpty().takeIf { it.isNotBlank() && !it.equals("Undefined", true) }
                    val image = bestImage(e)
                    val price = e.optJSONArray("priceRanges")?.optJSONObject(0)?.optDouble("min")?.takeIf { !it.isNaN() }?.toInt()
                    val title = e.optString("name", "Událost").trim()
                    if (title.isBlank()) continue

                    add(
                        CulturalEvent(
                            id = e.optString("id", "tm-$i"),
                            title = title,
                            subtitle = listOfNotNull(genre, subGenre).distinct().joinToString(" · ").ifBlank { segment.ifBlank { "Akce" } },
                            type = mapType(segment, genre, subGenre),
                            city = venue?.optJSONObject("city")?.optString("name").orEmpty().ifBlank { "Česko" },
                            venue = venue?.optString("name").orEmpty().ifBlank { "Místo neuvedeno" },
                            dateLabel = dates?.optString("localDate").orEmpty().ifBlank { "Datum neuvedeno" },
                            timeLabel = dates?.optString("localTime").orEmpty().take(5),
                            priceCzk = price,
                            imageUrl = image?.url,
                            description = e.optString("info").ifBlank { e.optString("pleaseNote") }.ifBlank { e.optString("description") }.ifBlank { "Podrobnosti jsou dostupné u pořadatele." },
                            ticketUrl = e.optString("url").takeIf(String::isNotBlank),
                            source = "Ticketmaster",
                            genre = genre ?: subGenre,
                            status = status,
                            latitude = location?.optString("latitude")?.toDoubleOrNull(),
                            longitude = location?.optString("longitude")?.toDoubleOrNull(),
                            imageIsFallback = image?.fallback ?: false,
                            imageAttribution = image?.attribution
                        )
                    )
                }
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private data class ImagePick(val url: String, val fallback: Boolean, val attribution: String?)

    private fun bestImage(event: JSONObject): ImagePick? {
        val images = event.optJSONArray("images") ?: return null
        var best: ImagePick? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            val url = image.optString("url").takeIf(String::isNotBlank) ?: continue
            val width = image.optInt("width", 0)
            val height = image.optInt("height", 0)
            val ratio = image.optString("ratio")
            val fallback = image.optBoolean("fallback", false)
            var score = width.coerceAtMost(5000)
            if (ratio == "16_9") score += 2500
            if (!fallback) score += 5000
            if (width >= 1024) score += 1200
            if (height >= 576) score += 500
            if (score > bestScore) {
                bestScore = score
                best = ImagePick(url, fallback, image.optString("attribution").takeIf(String::isNotBlank))
            }
        }
        return best
    }

    private fun mapType(segment: String, genre: String?, subGenre: String?): EventType {
        val text = listOf(segment, genre, subGenre).joinToString(" ")
        return when {
            text.contains("Music", true) || text.contains("Hudba", true) -> EventType.CONCERT
            text.contains("Film", true) || text.contains("Cinema", true) -> EventType.CINEMA
            text.contains("Theatre", true) || text.contains("Theater", true) || text.contains("Arts", true) -> EventType.THEATRE
            text.contains("Exhibit", true) || text.contains("Museum", true) -> EventType.EXHIBITION
            else -> EventType.EVENT
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
