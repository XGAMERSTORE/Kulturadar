package cz.kulturadar.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class EventWeather(
    val date: String,
    val minC: Int?,
    val maxC: Int?,
    val precipitationProbability: Int?,
    val weatherCode: Int?,
    val label: String
)

class WeatherRepository {
    fun forecast(latitude: Double, longitude: Double, dateIso: String): EventWeather? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=16"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kulturadar/1.7 Android")
        }

        return try {
            if (conn.responseCode !in 200..299) return null
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val daily = root.optJSONObject("daily") ?: return null
            val times = daily.optJSONArray("time") ?: return null
            var index = -1
            for (i in 0 until times.length()) {
                if (times.optString(i) == dateIso) { index = i; break }
            }
            if (index < 0) return null

            val code = daily.optJSONArray("weather_code")?.optInt(index)
            val max = daily.optJSONArray("temperature_2m_max")?.optDouble(index)?.takeIf { !it.isNaN() }?.toInt()
            val min = daily.optJSONArray("temperature_2m_min")?.optDouble(index)?.takeIf { !it.isNaN() }?.toInt()
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(index)
            EventWeather(dateIso, min, max, rain, code, weatherLabel(code))
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun weatherLabel(code: Int?): String = when (code) {
        0 -> "Jasno"
        1 -> "Převážně jasno"
        2 -> "Polojasno"
        3 -> "Zataženo"
        45, 48 -> "Mlha"
        51, 53, 55, 56, 57 -> "Mrholení"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Déšť"
        71, 73, 75, 77, 85, 86 -> "Sněžení"
        95, 96, 99 -> "Bouřky"
        else -> "Počasí"
    }
}
