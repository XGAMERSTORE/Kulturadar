package cz.kulturadar.app.data

import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

data class EventLoadResult(
    val events: List<CulturalEvent>,
    val error: String? = null
)

class EventRepository {
    fun demoEvents(): List<CulturalEvent> = emptyList()

    /** Compatibility wrapper for older UI code. */
    fun ticketmaster(apiKey: String, city: String?, keyword: String?): List<CulturalEvent> =
        loadTicketmaster(apiKey, city, keyword).events

    fun loadTicketmaster(apiKey: String, city: String?, keyword: String?): EventLoadResult {
        if (apiKey.isBlank()) return EventLoadResult(emptyList(), "Chybí Ticketmaster API klíč.")

        val params = mutableListOf(
            "apikey=${enc(apiKey.trim())}",
            "countryCode=CZ",
            "size=200",
            "sort=date,asc",
            "locale=cs,*",
            "includeTBA=no",
            "includeTBD=no"
        )
        city?.takeIf { it.isNotBlank() && it != "Všechna města" }?.let { params += "city=${enc(it)}" }
        keyword?.takeIf { it.isNotBlank() }?.let { params += "keyword=${enc(it)}" }

        val conn = (URL("https://app.ticketmaster.com/discovery/v2/events.json?${params.joinToString("&")}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kulturadar/1.7 Android")
        }

        return try {
            val code = conn.responseCode
            if (code == 401 || code == 403) return EventLoadResult(emptyList(), "Ticketmaster API klíč není platný nebo nemá přístup.")
            if (code == 429) return EventLoadResult(emptyList(), "Ticketmaster právě omezuje počet dotazů. Zkus to za chvíli znovu.")
            if (code !in 200..299) return EventLoadResult(emptyList(), "Ticketmaster vrátil chybu $code.")

            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = root.optJSONObject("_embedded")?.optJSONArray("events")
                ?: return EventLoadResult(emptyList(), "Pro tento výběr nejsou dostupné žádné akce.")

            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val dates = e.optJSONObject("dates")
                    val start = dates?.optJSONObject("start")
                    val status = dates?.optJSONObject("status")?.optString("code").orEmpty().ifBlank { null }
                    val venue = e.optJSONObject("_embedded")?.optJSONArray("venues")?.optJSONObject(0)
                    val location = venue?.optJSONObject("location")
                    val classifications = e.optJSONArray("classifications")?.optJSONObject(0)
                    val segment = classifications?.optJSONObject("segment")?.optString("name").orEmpty()
                    val genre = classifications?.optJSONObject("genre")?.optString("name").cleanClassification()
                    val subGenre = classifications?.optJSONObject("subGenre")?.optString("name").cleanClassification()
                    val image = bestImage(e)
                    val prices = e.optJSONArray("priceRanges")?.optJSONObject(0)
                    val priceMin = prices?.optDouble("min")?.takeIf { !it.isNaN() }?.toInt()
                    val priceMax = prices?.optDouble("max")?.takeIf { !it.isNaN() }?.toInt()
                    val currency = prices?.optString("currency")?.takeIf(String::isNotBlank)
                    val sales = e.optJSONObject("sales")?.optJSONObject("public")
                    val title = e.optString("name").trim()
                    if (title.isBlank()) continue

                    val address = venue?.optJSONObject("address")?.optString("line1")?.trim()?.takeIf(String::isNotBlank)
                    val cityName = venue?.optJSONObject("city")?.optString("name").orEmpty().ifBlank { "Česko" }
                    val date = start?.optString("localDate").orEmpty()
                    if (date.isBlank()) continue

                    add(
                        CulturalEvent(
                            id = e.optString("id", "tm-$i"),
                            title = title,
                            subtitle = listOfNotNull(genre, subGenre).distinct().joinToString(" · ").ifBlank { segment.ifBlank { "Akce" } },
                            type = mapType(segment, genre, subGenre),
                            city = cityName,
                            venue = venue?.optString("name").orEmpty().ifBlank { "Místo neuvedeno" },
                            dateLabel = date,
                            timeLabel = start?.optString("localTime").orEmpty().take(5),
                            priceCzk = priceMin,
                            imageUrl = image?.url,
                            description = e.optString("info")
                                .ifBlank { e.optString("pleaseNote") }
                                .ifBlank { e.optString("description") }
                                .ifBlank { "Pořadatel neposkytl delší popis. Otevři oficiální detail pro aktuální informace." },
                            ticketUrl = e.optString("url").takeIf(String::isNotBlank),
                            source = "Ticketmaster",
                            genre = genre ?: subGenre,
                            status = status,
                            latitude = location?.optString("latitude")?.toDoubleOrNull(),
                            longitude = location?.optString("longitude")?.toDoubleOrNull(),
                            imageIsFallback = image?.fallback ?: false,
                            imageAttribution = image?.attribution,
                            address = address,
                            postalCode = venue?.optString("postalCode")?.takeIf(String::isNotBlank),
                            priceMaxCzk = priceMax,
                            currency = currency,
                            salesStart = sales?.optString("startDateTime")?.takeIf(String::isNotBlank),
                            salesEnd = sales?.optString("endDateTime")?.takeIf(String::isNotBlank),
                            timezone = venue?.optString("timezone")?.takeIf(String::isNotBlank)
                        )
                    )
                }
            }.distinctBy { it.id }

            EventLoadResult(parsed, if (parsed.isEmpty()) "Pro tento výběr nejsou dostupné žádné akce." else null)
        } catch (_: java.net.SocketTimeoutException) {
            EventLoadResult(emptyList(), "Načítání trvalo příliš dlouho. Zkontroluj připojení a zkus to znovu.")
        } catch (_: java.net.UnknownHostException) {
            EventLoadResult(emptyList(), "Internet není dostupný.")
        } catch (_: Exception) {
            EventLoadResult(emptyList(), "Akce se nepodařilo načíst. Zkus to znovu.")
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
            var score = width.coerceAtMost(6000)
            if (ratio == "16_9") score += 3000
            if (!fallback) score += 8000
            if (width >= 1024) score += 1500
            if (height >= 576) score += 600
            if (score > bestScore) {
                bestScore = score
                best = ImagePick(url, fallback, image.optString("attribution").takeIf(String::isNotBlank))
            }
        }
        return best
    }

    private fun String?.cleanClassification(): String? = this
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("Undefined", true) && !it.equals("Miscellaneous", true) }

    private fun mapType(segment: String, genre: String?, subGenre: String?): EventType {
        val text = listOf(segment, genre, subGenre).joinToString(" ")
        return when {
            text.contains("Music", true) || text.contains("Hudba", true) -> EventType.CONCERT
            text.contains("Film", true) || text.contains("Cinema", true) -> EventType.CINEMA
            text.contains("Theatre", true) || text.contains("Theater", true) || text.contains("Arts", true) -> EventType.THEATRE
            text.contains("Exhibit", true) || text.contains("Museum", true) || text.contains("Gallery", true) -> EventType.EXHIBITION
            else -> EventType.EVENT
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
