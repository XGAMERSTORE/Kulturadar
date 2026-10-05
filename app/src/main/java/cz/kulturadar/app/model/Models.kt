package cz.kulturadar.app.model

enum class EventType(val title: String) {
    ALL("Vše"), EVENT("Akce"), THEATRE("Divadlo"), CINEMA("Kino"), CONCERT("Koncert"), EXHIBITION("Výstava")
}

enum class Reaction { NONE, LOVED, HATED }

data class CulturalEvent(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: EventType,
    val city: String,
    val venue: String,
    val dateLabel: String,
    val timeLabel: String,
    val priceCzk: Int?,
    val imageUrl: String? = null,
    val description: String,
    val ticketUrl: String? = null,
    val source: String = "Ticketmaster",
    val genre: String? = null,
    val status: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val imageIsFallback: Boolean = false,
    val imageAttribution: String? = null
)

data class EventFilter(
    val type: EventType = EventType.ALL,
    val city: String = "Všechna města",
    val date: String = "Kdykoliv",
    val freeOnly: Boolean = false,
    val maxPrice: Int? = null,
    val search: String = "",
    val onlyWithRealImage: Boolean = false,
    val hideDisliked: Boolean = true
)
