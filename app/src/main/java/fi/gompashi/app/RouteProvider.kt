package fi.gompashi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A single point of a walking route. */
data class RoutePoint(val lat: Double, val lon: Double)

/** A foot route: total walking distance/time and the polyline to draw. */
data class FootRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<RoutePoint>,
)

/**
 * Fetches a pedestrian route from the keyless FOSSGIS OSRM service (OSM data, fair-use).
 * Returns null on any error so callers fall back to the straight line.
 */
class RouteProvider {
    suspend fun fetch(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): FootRoute? = withContext(Dispatchers.IO) {
        val url = "$BASE$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Gompashi/1.x (https://gompashi.vercel.app)")
            }
            if (conn.responseCode != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parse(text)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(text: String): FootRoute? {
        val root = JSONObject(text)
        if (root.optString("code") != "Ok") return null
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r = routes.getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<RoutePoint>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            points.add(RoutePoint(c.getDouble(1), c.getDouble(0))) // GeoJSON is [lon, lat]
        }
        return FootRoute(r.getDouble("distance"), r.optDouble("duration", 0.0), points)
    }

    private companion object {
        const val BASE = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
    }
}
