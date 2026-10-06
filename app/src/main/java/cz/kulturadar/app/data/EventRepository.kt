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
    private val goOut = GoOutRepository()
    private val publicSources = PublicEventSourcesRepository()

    /**
     * Real cached bootstrap cards from the previous Kulturadar approach.
     * They are only used if all live public sources fail, so a fresh install is not an empty shell.
     */
    fun demoEvents(): List<CulturalEvent> = bootstrapEvents()

    fun loadAllSources(apiKey: String, city: String?, keyword: String?): EventLoadResult {
        val goOutResult = goOut.load(city, keyword)
        val publicResult = publicSources.load(city, keyword)
        val ticketResult = if (apiKey.isNotBlank()) loadTicketmaster(apiKey, city, keyword) else EventLoadResult(emptyList())

        val merged = (goOutResult.events + publicResult.events + ticketResult.events)
            .distinctBy { canonicalKey(it) }
            .sortedBy { it.dateLabel + it.timeLabel }

        if (merged.isNotEmpty()) return EventLoadResult(merged)

        val fallback = bootstrapEvents()
            .filter { city.isNullOrBlank() || city == "Všechna města" || it.city.equals(city, true) }
            .filter { keyword.isNullOrBlank() || listOf(it.title, it.subtitle, it.venue, it.city).any { v -> v.contains(keyword, true) } }

        return EventLoadResult(
            fallback,
            when {
                fallback.isNotEmpty() -> "Živý zdroj teď neodpověděl. Zobrazuji poslední ověřený GoOut výběr, dokud se data neobnoví."
                goOutResult.error != null -> goOutResult.error
                ticketResult.error != null -> ticketResult.error
                else -> "Pro tento výběr nejsou dostupné žádné akce."
            }
        )
    }

    /** Compatibility wrapper for older UI code. */
    fun ticketmaster(apiKey: String, city: String?, keyword: String?): List<CulturalEvent> =
        loadTicketmaster(apiKey, city, keyword).events

    fun loadTicketmaster(apiKey: String, city: String?, keyword: String?): EventLoadResult {
        if (apiKey.isBlank()) return EventLoadResult(emptyList())

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
            setRequestProperty("User-Agent", "Kulturadar/1.8 Android")
        }

        return try {
            val code = conn.responseCode
            if (code == 401 || code == 403) return EventLoadResult(emptyList(), "Ticketmaster API klíč není platný.")
            if (code == 429) return EventLoadResult(emptyList(), "Ticketmaster právě omezuje počet dotazů.")
            if (code !in 200..299) return EventLoadResult(emptyList(), "Ticketmaster vrátil chybu $code.")

            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = root.optJSONObject("_embedded")?.optJSONArray("events")
                ?: return EventLoadResult(emptyList())

            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val dates = e.optJSONObject("dates")
                    val start = dates?.optJSONObject("start")
                    val date = start?.optString("localDate").orEmpty()
                    if (date.isBlank()) continue

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

                    add(
                        CulturalEvent(
                            id = "tm-${e.optString("id", i.toString())}",
                            title = title,
                            subtitle = listOfNotNull(genre, subGenre).distinct().joinToString(" · ").ifBlank { segment.ifBlank { "Akce" } },
                            type = mapType(segment, genre, subGenre),
                            city = venue?.optJSONObject("city")?.optString("name").orEmpty().ifBlank { "Česko" },
                            venue = venue?.optString("name").orEmpty().ifBlank { "Místo neuvedeno" },
                            dateLabel = date,
                            timeLabel = start?.optString("localTime").orEmpty().take(5),
                            priceCzk = priceMin,
                            imageUrl = image?.url,
                            description = e.optString("info")
                                .ifBlank { e.optString("pleaseNote") }
                                .ifBlank { e.optString("description") }
                                .ifBlank { "Podrobnosti jsou dostupné u pořadatele." },
                            ticketUrl = e.optString("url").takeIf(String::isNotBlank),
                            source = "Ticketmaster",
                            genre = genre ?: subGenre,
                            status = status,
                            latitude = location?.optString("latitude")?.toDoubleOrNull(),
                            longitude = location?.optString("longitude")?.toDoubleOrNull(),
                            imageIsFallback = image?.fallback ?: false,
                            imageAttribution = image?.attribution,
                            address = venue?.optJSONObject("address")?.optString("line1")?.takeIf(String::isNotBlank),
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

            EventLoadResult(parsed)
        } catch (_: java.net.SocketTimeoutException) {
            EventLoadResult(emptyList(), "Ticketmaster neodpověděl včas.")
        } catch (_: java.net.UnknownHostException) {
            EventLoadResult(emptyList(), "Internet není dostupný.")
        } catch (_: Exception) {
            EventLoadResult(emptyList(), "Ticketmaster se nepodařilo načíst.")
        } finally {
            conn.disconnect()
        }
    }

    private fun bootstrapEvents(): List<CulturalEvent> = listOf(
        goOutSnapshot("katarzia-robin", "Katarzia & Robin Duo", "Koncert", EventType.CONCERT, "Ostrava", "Barrák", "2026-10-07", "20:00", null, "https://goout.net/cs/katarzia-and-robin-duo/szrably/"),
        goOutSnapshot("dub-pistols", "Dub Pistols", "Elektronika · big beat", EventType.CONCERT, "Ostrava", "Barrák", "2026-10-11", "20:00", 490, "https://goout.net/cs/dub-pistols/szdhosx/"),
        goOutSnapshot("maly-princ", "Malý princ, Recitál", "Divadlo · hudba · slovo", EventType.THEATRE, "Ostrava", "Multifunkční aula Gong", "2026-10-19", "19:00", 890, "https://goout.net/cs/maly-princ-recital/szowkgy/"),
        goOutSnapshot("pro-pain", "Pro-Pain + Sloth + Madrain", "Metal · rock", EventType.CONCERT, "Ostrava", "Barrák", "2026-11-08", "18:30", 590, "https://goout.net/en/pro-pain%2Bsloth%2Bmadrain/szhrjky/"),
        goOutSnapshot("sps-fialky", "SPS + The Fialky – God Save the Punk II. 2026", "Punk · rock", EventType.CONCERT, "Ostrava", "Barrák", "2026-11-13", "20:00", 390, "https://goout.net/cs/sps%2Bthe-fialky-god-save-the-punk-ii-2026/szkmtky/"),
        goOutSnapshot("strihavka", "Kamil Střihavka: 40 let na scéně", "Rock · pop", EventType.CONCERT, "Ostrava", "Barrák", "2026-11-26", "19:30", 790, "https://goout.net/en/kamil-strihavka-40-let-na-scene/szcaqiy/")
    )

    private fun goOutSnapshot(
        id: String,
        title: String,
        subtitle: String,
        type: EventType,
        city: String,
        venue: String,
        date: String,
        time: String,
        price: Int?,
        url: String
    ) = CulturalEvent(
        id = "snapshot-$id",
        title = title,
        subtitle = subtitle,
        type = type,
        city = city,
        venue = venue,
        dateLabel = date,
        timeLabel = time,
        priceCzk = price,
        imageUrl = "https://api.microlink.io/?url=${enc(url)}&embed=image.url",
        description = "Ověřená akce z GoOut. Při připojení Kulturadar načte živou verzi a aktuální detaily.",
        ticketUrl = url,
        source = "GoOut · offline výběr",
        genre = subtitle
    )

    private fun canonicalKey(event: CulturalEvent): String =
        "${event.title.lowercase().replace(Regex("\\s+"), " ").trim()}|${event.dateLabel}|${event.city.lowercase()}"

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
