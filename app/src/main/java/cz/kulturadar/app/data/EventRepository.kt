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
        goOutEvent(
            id = "goout-katarzia-robin",
            title = "Katarzia & Robin Duo",
            subtitle = "Koncert",
            type = EventType.CONCERT,
            venue = "Barrák",
            date = "St 7. 10.",
            time = "20:00",
            price = null,
            url = "https://goout.net/cs/katarzia-and-robin-duo/szrably/",
            description = "Katarzia & Robin Duo vystoupí v ostravském Barráku."
        ),
        goOutEvent(
            id = "goout-dub-pistols",
            title = "Dub Pistols",
            subtitle = "Elektronika · big beat",
            type = EventType.CONCERT,
            venue = "Barrák",
            date = "Ne 11. 10.",
            time = "20:00",
            price = 490,
            url = "https://goout.net/cs/dub-pistols/szdhosx/",
            description = "Legendy dubu a breakbeatu z Velké Británie přijíždějí do Ostravy."
        ),
        goOutEvent(
            id = "goout-maly-princ",
            title = "Malý princ, Recitál",
            subtitle = "Divadlo · hudba · slovo",
            type = EventType.THEATRE,
            venue = "Multifunkční aula Gong",
            date = "Po 19. 10.",
            time = "19:00",
            price = 890,
            url = "https://goout.net/cs/maly-princ-recital/szowkgy/",
            description = "Originální spojení hudby a slova s Janem Cinou a Unique Orchestra."
        ),
        goOutEvent(
            id = "goout-pro-pain",
            title = "Pro-Pain + Sloth + Madrain",
            subtitle = "Metal · rock",
            type = EventType.CONCERT,
            venue = "Barrák",
            date = "Ne 8. 11.",
            time = "18:30",
            price = 590,
            url = "https://goout.net/en/pro-pain%2Bsloth%2Bmadrain/szhrjky/",
            description = "Pro-Pain, Sloth a Madrain vystoupí v ostravském Barráku."
        ),
        goOutEvent(
            id = "goout-sps-fialky",
            title = "SPS + The Fialky – God Save the Punk II. 2026",
            subtitle = "Punk · rock",
            type = EventType.CONCERT,
            venue = "Barrák",
            date = "Pá 13. 11.",
            time = "20:00",
            price = 390,
            url = "https://goout.net/cs/sps%2Bthe-fialky-god-save-the-punk-ii-2026/szkmtky/",
            description = "Společné turné SPS a The Fialky se zastaví v Ostravě."
        ),
        goOutEvent(
            id = "goout-strihavka",
            title = "Kamil Střihavka: 40 let na scéně",
            subtitle = "Rock · pop",
            type = EventType.CONCERT,
            venue = "Barrák",
            date = "Čt 26. 11.",
            time = "19:30",
            price = 790,
            url = "https://goout.net/en/kamil-strihavka-40-let-na-scene/szcaqiy/",
            description = "Výroční koncert Kamila Střihavky s kapelou The Leaders! a speciálními hosty."
        )
    )

    private fun goOutEvent(
        id: String,
        title: String,
        subtitle: String,
        type: EventType,
        venue: String,
        date: String,
        time: String,
        price: Int?,
        url: String,
        description: String
    ) = CulturalEvent(
        id = id,
        title = title,
        subtitle = subtitle,
        type = type,
        city = "Ostrava",
        venue = venue,
        dateLabel = date,
        timeLabel = time,
        priceCzk = price,
        imageUrl = previewImage(url),
        description = description,
        ticketUrl = url,
        source = "GoOut"
    )

    private fun previewImage(pageUrl: String): String =
        "https://api.microlink.io/?url=${enc(pageUrl)}&embed=image.url"

    fun ticketmaster(apiKey: String, city: String?, keyword: String?): List<CulturalEvent> {
        if (apiKey.isBlank()) return emptyList()
        val params = mutableListOf("apikey=${enc(apiKey)}", "countryCode=CZ", "size=200", "sort=date,asc")
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
                        if (width > bestWidth) {
                            bestWidth = width
                            bestImage = image.optString("url").takeIf(String::isNotBlank)
                        }
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
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun mapType(segment: String): EventType = when {
        segment.contains("Music", true) -> EventType.CONCERT
        segment.contains("Arts", true) || segment.contains("Theatre", true) -> EventType.THEATRE
        segment.contains("Film", true) -> EventType.CINEMA
        else -> EventType.EVENT
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
