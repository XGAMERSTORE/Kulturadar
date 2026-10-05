package cz.kulturadar.app.data

import android.content.Context
import cz.kulturadar.app.model.CulturalEvent
import cz.kulturadar.app.model.EventType
import org.json.JSONArray
import org.json.JSONObject

class EventCache(context: Context) {
    private val prefs = context.getSharedPreferences("event_cache", Context.MODE_PRIVATE)

    fun save(events: List<CulturalEvent>) {
        val array = JSONArray()
        events.take(200).forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("type", e.type.name)
                put("city", e.city)
                put("venue", e.venue)
                put("dateLabel", e.dateLabel)
                put("timeLabel", e.timeLabel)
                putNullable("priceCzk", e.priceCzk)
                putNullable("imageUrl", e.imageUrl)
                put("description", e.description)
                putNullable("ticketUrl", e.ticketUrl)
                put("source", e.source)
                putNullable("genre", e.genre)
                putNullable("status", e.status)
                putNullable("latitude", e.latitude)
                putNullable("longitude", e.longitude)
                put("imageIsFallback", e.imageIsFallback)
                putNullable("imageAttribution", e.imageAttribution)
                putNullable("address", e.address)
                putNullable("postalCode", e.postalCode)
                putNullable("priceMaxCzk", e.priceMaxCzk)
                putNullable("currency", e.currency)
                putNullable("salesStart", e.salesStart)
                putNullable("salesEnd", e.salesEnd)
                putNullable("timezone", e.timezone)
            })
        }
        prefs.edit()
            .putString("events", array.toString())
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    fun load(): List<CulturalEvent> {
        val raw = prefs.getString("events", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        CulturalEvent(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            subtitle = o.optString("subtitle"),
                            type = runCatching { EventType.valueOf(o.optString("type")) }.getOrDefault(EventType.EVENT),
                            city = o.optString("city"),
                            venue = o.optString("venue"),
                            dateLabel = o.optString("dateLabel"),
                            timeLabel = o.optString("timeLabel"),
                            priceCzk = o.optIntOrNull("priceCzk"),
                            imageUrl = o.optStringOrNull("imageUrl"),
                            description = o.optString("description"),
                            ticketUrl = o.optStringOrNull("ticketUrl"),
                            source = o.optString("source", "Ticketmaster"),
                            genre = o.optStringOrNull("genre"),
                            status = o.optStringOrNull("status"),
                            latitude = o.optDoubleOrNull("latitude"),
                            longitude = o.optDoubleOrNull("longitude"),
                            imageIsFallback = o.optBoolean("imageIsFallback", false),
                            imageAttribution = o.optStringOrNull("imageAttribution"),
                            address = o.optStringOrNull("address"),
                            postalCode = o.optStringOrNull("postalCode"),
                            priceMaxCzk = o.optIntOrNull("priceMaxCzk"),
                            currency = o.optStringOrNull("currency"),
                            salesStart = o.optStringOrNull("salesStart"),
                            salesEnd = o.optStringOrNull("salesEnd"),
                            timezone = o.optStringOrNull("timezone")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun lastUpdated(): Long = prefs.getLong("updated_at", 0L)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
}
