package fi.gompashi.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** A downloaded offline region (a box of tiles around a point). */
@Serializable
data class MapRegion(val lat: Double, val lon: Double, val tiles: Int, val bytes: Long)

/**
 * Loads CARTO "dark" raster tiles (OSM data) for the route map. Tiles are cached on disk so
 * downloaded regions render offline; [tiles] is snapshot-backed so the canvas recomposes as
 * tiles arrive. Also tracks connectivity (to cap the zoom to downloaded tiles when offline) and
 * drives region downloads with progress.
 */
class TileStore(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "tiles").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("gompashi_tiles", Context.MODE_PRIVATE)

    val tiles = mutableStateMapOf<String, ImageBitmap>()
    private val inFlight = HashSet<String>()

    val regions = mutableStateListOf<MapRegion>().apply { addAll(loadRegions()) }
    var downloading by mutableStateOf(false); private set
    var progressDone by mutableStateOf(0); private set
    var progressTotal by mutableStateOf(0); private set

    var online by mutableStateOf(true); private set

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        online = cm.activeNetwork != null
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { online = true }
                override fun onLost(network: Network) { online = cm.activeNetwork != null }
            })
        }
    }

    private fun file(z: Int, x: Int, y: Int) = File(dir, "${z}_${x}_${y}.png")

    /** Returns the tile if cached (disk/memory), else null and kicks off a load. Hits the network
     *  only when online; offline it serves whatever was downloaded. */
    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        tiles[key]?.let { return it }
        if (inFlight.add(key)) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val f = file(z, x, y)
                    if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap()
                    else if (online) downloadToFile(z, x, y, f)?.asImageBitmap()
                    else null
                }
                if (bmp != null) tiles[key] = bmp else inFlight.remove(key)
            }
        }
        return null
    }

    private fun downloadToFile(z: Int, x: Int, y: Int, f: File): android.graphics.Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 10_000
                setRequestProperty("User-Agent", "Gompashi/1.x (https://gompashi.vercel.app)")
            }
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            f.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Download all tiles around (lat, lon) for offline use. Safe to call repeatedly. */
    fun downloadRegion(lat: Double, lon: Double) {
        if (downloading) return
        scope.launch {
            downloading = true
            val list = regionTiles(lat, lon)
            progressDone = 0; progressTotal = list.size
            var bytes = 0L
            withContext(Dispatchers.IO) {
                for (t in list) {
                    val f = file(t[0], t[1], t[2])
                    if (f.exists()) bytes += f.length()
                    else downloadToFile(t[0], t[1], t[2], f)?.let { bytes += f.length() }
                    progressDone++
                }
            }
            regions.add(MapRegion(lat, lon, list.size, bytes))
            saveRegions()
            downloading = false
        }
    }

    fun deleteRegion(index: Int) {
        val r = regions.getOrNull(index) ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (t in regionTiles(r.lat, r.lon)) file(t[0], t[1], t[2]).delete()
            }
            tiles.clear()
            if (index in regions.indices) regions.removeAt(index)
            saveRegions()
        }
    }

    private fun loadRegions(): List<MapRegion> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<MapRegion>>(it) }.getOrNull() } ?: emptyList()

    private fun saveRegions() {
        prefs.edit().putString(KEY, json.encodeToString(regions.toList())).apply()
    }

    companion object {
        const val MIN_ZOOM = 11
        const val MAX_ZOOM = 14 // also the offline render cap; closer zooms overzoom these
        const val RADIUS_KM = 25.0

        private fun mercY(lat: Double) = (1 - ln(tan(lat * Math.PI / 180) + 1 / cos(lat * Math.PI / 180)) / Math.PI) / 2

        /** [z,x,y] triples covering a ±RADIUS_KM box around (lat,lon), zoom MIN..MAX. */
        fun regionTiles(lat: Double, lon: Double): List<IntArray> {
            val dLat = RADIUS_KM / 111.32
            val dLon = RADIUS_KM / (111.32 * cos(lat * Math.PI / 180))
            val out = ArrayList<IntArray>()
            for (z in MIN_ZOOM..MAX_ZOOM) {
                val n = 1 shl z
                fun tx(lo: Double) = (((lo + 180) / 360 * n).toInt()).coerceIn(0, n - 1)
                fun ty(la: Double) = ((mercY(la) * n).toInt()).coerceIn(0, n - 1)
                val x0 = tx(lon - dLon); val x1 = tx(lon + dLon)
                val y0 = ty(lat + dLat); val y1 = ty(lat - dLat) // north = smaller y
                for (x in x0..x1) for (y in y0..y1) out.add(intArrayOf(z, x, y))
            }
            return out
        }

        private const val KEY = "regions"
    }
}
