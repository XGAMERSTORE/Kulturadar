package cz.kulturadar.app.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.*

data class CzechPlace(
    val name: String,
    val detail: String,
    val latitude: Double,
    val longitude: Double
)

class PlaceRepository {
    fun search(query: String): List<CzechPlace> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=cz&addressdetails=1&limit=10&q=$encoded")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "cs")
            setRequestProperty("User-Agent", "Kulturadar/1.9 Android (place search)")
        }
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val lat = item.optString("lat").toDoubleOrNull() ?: continue
                    val lon = item.optString("lon").toDoubleOrNull() ?: continue
                    val address = item.optJSONObject("address")
                    val name = listOf("village", "town", "city", "municipality", "hamlet", "suburb")
                        .firstNotNullOfOrNull { key -> address?.optString(key)?.takeIf { it.isNotBlank() } }
                        ?: item.optString("name").takeIf { it.isNotBlank() }
                        ?: continue
                    val district = address?.optString("county").orEmpty()
                    val region = address?.optString("state").orEmpty()
                    val detail = listOf(district, region).filter { it.isNotBlank() && !it.equals(name, true) }.distinct().joinToString(" · ")
                    add(CzechPlace(name, detail, lat, lon))
                }
            }.distinctBy { "${it.name.lowercase()}|${"%.4f".format(Locale.US, it.latitude)}|${"%.4f".format(Locale.US, it.longitude)}" }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0088
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
