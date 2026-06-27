package fi.gompashi.app

import kotlinx.serialization.Serializable

@Serializable
data class AlkoStore(
    val name: String,
    val lat: Double,
    val lon: Double,
    // 7 entries Mon..Sun; each is [open, close] ("HH:MM") or null when closed that day.
    val hours: List<List<String>?> = emptyList(),
    val hoursKnown: Boolean = true,
    // Date-specific overrides ("yyyy-MM-dd" -> [open, close] or null) for the next ~10 days,
    // taking precedence over the weekly schedule and the holiday list.
    val exceptions: Map<String, List<String>?> = emptyMap(),
)
