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
)
