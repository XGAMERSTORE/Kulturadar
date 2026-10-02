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
        CulturalEvent("demo-5", "Městský festival", "Hudba · jídlo · program", EventType.EVENT, "Frýdek-Místek", "Centrum", "Ne 11. 10.", "14:00", 250, description = "Ukázková celodenní akce."),
        CulturalEvent("demo-6", "Velká večerní scéna", "Činohra · hlavní sál", EventType.THEATRE, "Praha", "Městské divadlo", "Dnes", "19:30", 690, description = "Ukázková pražská divadelní událost."),
        CulturalEvent("demo-7", "Půlnoční projekce", "Speciální filmový večer", EventType.CINEMA, "Praha", "Kino centrum", "Zítra", "22:00", 240, description = "Ukázková filmová projekce v Praze."),
        CulturalEvent("demo-8", "Hudba na nábřeží", "Open air koncert", EventType.CONCERT, "Praha", "Nábřeží", "So 10. 10.", "19:00", 0, description = "Ukázková venkovní hudební akce zdarma."),
        CulturalEvent("demo-9", "Večer současného divadla", "Drama · premiéra", EventType.THEATRE, "Brno", "Divadelní scéna", "Pá 9. 10.", "19:00", 490, description = "Ukázková divadelní akce v Brně."),
        CulturalEvent("demo-10", "Filmová noc", "Maraton filmů", EventType.CINEMA, "Brno", "Kino", "So 10. 10.", "21:00", 320, description = "Ukázkový filmový maraton."),
        CulturalEvent("demo-11", "Indie koncert", "Klubová noc", EventType.CONCERT, "Brno", "Hudební klub", "Ne 11. 10.", "20:00", 450, description = "Ukázkový klubový koncert."),
        CulturalEvent("demo-12", "Divadelní klasika", "Večerní představení", EventType.THEATRE, "Plzeň", "Velké divadlo", "Pá 9. 10.", "19:00", 520, description = "Ukázkové představení v Plzni."),
        CulturalEvent("demo-13", "Kino pod širým nebem", "Venkovní projekce", EventType.CINEMA, "Plzeň", "Náměstí", "So 10. 10.", "20:30", 120, description = "Ukázková venkovní projekce."),
        CulturalEvent("demo-14", "Festival světla", "Instalace · hudba · město", EventType.EVENT, "Olomouc", "Centrum", "Pá 9. 10.", "18:00", 0, description = "Ukázková městská kulturní akce."),
        CulturalEvent("demo-15", "Komorní představení", "Malá scéna", EventType.THEATRE, "Olomouc", "Divadlo", "Ne 11. 10.", "18:30", 390, description = "Ukázkové komorní divadlo."),
        CulturalEvent("demo-16", "Noční galerie", "Výstava · komentovaná prohlídka", EventType.EXHIBITION, "Liberec", "Galerie", "So 10. 10.", "19:00", 150, description = "Ukázková večerní výstava."),
        CulturalEvent("demo-17", "Koncert v centru", "Živá hudba", EventType.CONCERT, "Liberec", "Kulturní centrum", "Ne 11. 10.", "19:30", 480, description = "Ukázkový koncert v Liberci."),
        CulturalEvent("demo-18", "Divadlo po setmění", "Drama", EventType.THEATRE, "Hradec Králové", "Klicperova scéna", "Pá 9. 10.", "19:00", 430, description = "Ukázková divadelní událost."),
        CulturalEvent("demo-19", "Městská filmová noc", "Kino · speciální program", EventType.CINEMA, "Hradec Králové", "Kino", "So 10. 10.", "20:00", 210, description = "Ukázkový filmový večer."),
        CulturalEvent("demo-20", "Hudební večer", "Koncert", EventType.CONCERT, "Pardubice", "Kulturní dům", "Pá 9. 10.", "20:00", 550, description = "Ukázkový koncert v Pardubicích."),
        CulturalEvent("demo-21", "Divadelní víkend", "Činohra", EventType.THEATRE, "Pardubice", "Divadlo", "Ne 11. 10.", "17:00", 410, description = "Ukázkové nedělní divadlo."),
        CulturalEvent("demo-22", "Design a město", "Výstava", EventType.EXHIBITION, "Zlín", "Galerie", "Dnes", "17:30", 190, description = "Ukázková výstava ve Zlíně."),
        CulturalEvent("demo-23", "Večer na scéně", "Divadlo", EventType.THEATRE, "Zlín", "Městské divadlo", "Zítra", "19:00", 450, description = "Ukázkové večerní představení."),
        CulturalEvent("demo-24", "Jihočeská filmová noc", "Kino", EventType.CINEMA, "České Budějovice", "Kino centrum", "Pá 9. 10.", "20:15", 220, description = "Ukázková filmová událost."),
        CulturalEvent("demo-25", "Hudba na náměstí", "Koncert zdarma", EventType.CONCERT, "České Budějovice", "Náměstí", "So 10. 10.", "18:00", 0, description = "Ukázkový venkovní koncert zdarma."),
        CulturalEvent("demo-26", "Galerie živě", "Výstava · performance", EventType.EXHIBITION, "Karlovy Vary", "Galerie", "Pá 9. 10.", "18:30", 170, description = "Ukázkový kulturní večer."),
        CulturalEvent("demo-27", "Festival v centru", "Hudba · divadlo · jídlo", EventType.EVENT, "Jihlava", "Centrum města", "So 10. 10.", "13:00", 0, description = "Ukázkový městský festival zdarma."),
        CulturalEvent("demo-28", "Večerní činohra", "Divadlo", EventType.THEATRE, "Ústí nad Labem", "Divadlo", "Ne 11. 10.", "19:00", 380, description = "Ukázková činohra."),
        CulturalEvent("demo-29", "Koncert pod věží", "Open air", EventType.CONCERT, "Tábor", "Historické centrum", "So 10. 10.", "20:00", 280, description = "Ukázkový open air koncert."),
        CulturalEvent("demo-30", "Filmový večer", "Kino", EventType.CINEMA, "Mladá Boleslav", "Kino", "Zítra", "19:45", 190, description = "Ukázková filmová projekce.")
    )

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
