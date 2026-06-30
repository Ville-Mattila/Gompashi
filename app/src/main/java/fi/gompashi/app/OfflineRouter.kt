package fi.gompashi.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.PriorityQueue
import kotlin.math.cos

/** A routable walking graph: node coordinates + adjacency (indices). */
@Serializable
data class RouteGraph(val lat: List<Double>, val lon: List<Double>, val adj: List<List<Int>>)

/**
 * On-device pedestrian routing from an OSM walking network downloaded per region (via Overpass).
 * Computes shortest walking paths with A* so routes work without a network connection.
 */
class OfflineRouter(context: Context) {
    private val dir = File(context.filesDir, "routes").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var graphs: List<RouteGraph>? = null

    private fun file(lat: Double, lon: Double) = File(dir, "route_%.5f_%.5f.json".format(lat, lon))

    /** Fetch the walkable network around (lat,lon), build a graph, store it. Returns bytes (0 on failure). */
    suspend fun downloadAndStore(lat: Double, lon: Double): Long = withContext(Dispatchers.IO) {
        val dLat = ROUTE_KM / 111.32
        val dLon = ROUTE_KM / (111.32 * cos(lat * Math.PI / 180))
        val bbox = "(%.5f,%.5f,%.5f,%.5f)".format(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        val query = "[out:json][timeout:60];way[\"highway\"~\"^($WALK)\$\"]$bbox;out geom;"
        // Best-effort: try several Overpass mirrors, but cap the whole thing so a slow/blocked
        // service can't make the download appear stuck. Tiles already work without the graph.
        withTimeoutOrNull(90_000) {
            for (endpoint in ENDPOINTS) {
                val text = postOverpass(endpoint, query) ?: continue
                val graph = runCatching { buildGraph(text) }.getOrNull() ?: continue
                return@withTimeoutOrNull runCatching {
                    val out = file(lat, lon)
                    out.writeText(json.encodeToString(graph))
                    graphs = null // invalidate
                    out.length()
                }.getOrDefault(0L)
            }
            0L
        } ?: 0L
    }

    private fun postOverpass(endpoint: String, query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000; readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Gompashi/1.x (https://gompashi.vercel.app)")
                outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun deleteRegion(lat: Double, lon: Double) {
        file(lat, lon).delete()
        graphs = null
    }

    private fun buildGraph(text: String): RouteGraph? {
        val ways = JSONObject(text).optJSONArray("elements") ?: return null
        val index = HashMap<Long, Int>()
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val adj = ArrayList<MutableList<Int>>()
        fun node(la: Double, lo: Double): Int {
            // Unique composite key from quantized coords (~1 m): (latE5+9e6)*4e7 + (lonE5+18e6).
            val key = (Math.round(la * 1e5) + 9_000_000L) * 40_000_001L + (Math.round(lo * 1e5) + 18_000_000L)
            index[key]?.let { return it }
            val i = lat.size
            index[key] = i
            lat.add(Math.round(la * 1e5) / 1e5); lon.add(Math.round(lo * 1e5) / 1e5)
            adj.add(ArrayList(2))
            return i
        }
        for (w in 0 until ways.length()) {
            val geom = ways.getJSONObject(w).optJSONArray("geometry") ?: continue
            var prev = -1
            for (p in 0 until geom.length()) {
                val pt = geom.getJSONObject(p)
                val id = node(pt.getDouble("lat"), pt.getDouble("lon"))
                if (prev >= 0 && prev != id) {
                    if (!adj[prev].contains(id)) adj[prev].add(id)
                    if (!adj[id].contains(prev)) adj[id].add(prev)
                }
                prev = id
            }
        }
        if (lat.isEmpty()) return null
        return RouteGraph(lat, lon, adj)
    }

    private fun loadGraphs(): List<RouteGraph> {
        graphs?.let { return it }
        val loaded = dir.listFiles { f -> f.name.startsWith("route_") && f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<RouteGraph>(it.readText()) }.getOrNull() }
            ?: emptyList()
        graphs = loaded
        return loaded
    }

    /** Compute a walking route from a downloaded graph, or null if none covers both ends. */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): FootRoute? =
        withContext(Dispatchers.Default) {
            for (g in loadGraphs()) {
                if (g.lat.isEmpty()) continue
                val s = snap(g, fromLat, fromLon)
                val e = snap(g, toLat, toLon)
                if (s.second > 300.0 || e.second > 300.0) continue
                val path = aStar(g, s.first, e.first) ?: continue
                val pts = ArrayList<RoutePoint>(path.size + 2)
                pts.add(RoutePoint(fromLat, fromLon))
                for (i in path) pts.add(RoutePoint(g.lat[i], g.lon[i]))
                pts.add(RoutePoint(toLat, toLon))
                var dist = s.second + e.second
                for (k in 1 until path.size) dist += haversine(g.lat[path[k - 1]], g.lon[path[k - 1]], g.lat[path[k]], g.lon[path[k]])
                return@withContext FootRoute(dist, dist / 1.35, pts)
            }
            null
        }

    private fun snap(g: RouteGraph, la: Double, lo: Double): Pair<Int, Double> {
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in g.lat.indices) {
            val d = haversine(la, lo, g.lat[i], g.lon[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return best to bestD
    }

    /** A* over the node graph; returns the node-index path or null. */
    private fun aStar(g: RouteGraph, start: Int, goal: Int): IntArray? {
        val n = g.lat.size
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val came = IntArray(n) { -1 }
        val gLat = g.lat[goal]; val gLon = g.lon[goal]
        fun h(i: Int) = haversine(g.lat[i], g.lon[i], gLat, gLon)
        dist[start] = 0.0
        val pq = PriorityQueue<Long>()
        fun enc(pri: Int, node: Int) = (pri.toLong() shl 32) or (node.toLong() and 0xffffffffL)
        pq.add(enc(h(start).toInt(), start))
        while (pq.isNotEmpty()) {
            val top = pq.poll() ?: break
            val u = (top and 0xffffffffL).toInt()
            val pri = (top ushr 32).toInt()
            if (u == goal) break
            if (pri > (dist[u] + h(u)).toInt() + 2) continue // stale
            for (v in g.adj[u]) {
                val nd = dist[u] + haversine(g.lat[u], g.lon[u], g.lat[v], g.lon[v])
                if (nd < dist[v]) {
                    dist[v] = nd; came[v] = u
                    pq.add(enc((nd + h(v)).toInt(), v))
                }
            }
        }
        if (dist[goal] == Double.MAX_VALUE) return null
        val rev = ArrayList<Int>()
        var c = goal
        while (c >= 0) { rev.add(c); c = came[c] }
        return IntArray(rev.size) { rev[rev.size - 1 - it] }
    }

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val p1 = la1 * Math.PI / 180; val p2 = la2 * Math.PI / 180
        val dp = (la2 - la1) * Math.PI / 180; val dl = (lo2 - lo1) * Math.PI / 180
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        const val ROUTE_KM = 2.5
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
        private const val WALK = "footway|path|pedestrian|living_street|residential|steps|unclassified|tertiary|service"
    }
}
