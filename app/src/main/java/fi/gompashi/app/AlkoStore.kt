package fi.gompashi.app

import kotlinx.serialization.Serializable

@Serializable
data class AlkoStore(
    val name: String,
    val lat: Double,
    val lon: Double,
)
