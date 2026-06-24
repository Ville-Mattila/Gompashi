package fi.gompashi.app

data class RankedStore(
    val store: AlkoStore,
    val distanceMeters: Double,
    val bearingDeg: Double,
)

object NearestStoreFinder {
    /** Returns stores sorted by ascending distance from (lat, lon), with distance and bearing. */
    fun rank(lat: Double, lon: Double, stores: List<AlkoStore>): List<RankedStore> =
        stores
            .map { s ->
                RankedStore(
                    store = s,
                    distanceMeters = GeoUtils.distanceMeters(lat, lon, s.lat, s.lon),
                    bearingDeg = GeoUtils.bearingTo(lat, lon, s.lat, s.lon),
                )
            }
            .sortedBy { it.distanceMeters }
}
