package fi.gompashi.app

import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads and caches CARTO "dark" raster map tiles (OpenStreetMap data) for the route map's
 * dim base layer. Tiles are fetched off the main thread; [tiles] is a snapshot-backed map so
 * the canvas recomposes as each tile arrives. Failures are remembered to avoid refetch storms.
 */
class TileStore(private val scope: CoroutineScope) {
    val tiles = mutableStateMapOf<String, ImageBitmap>()
    private val inFlight = HashSet<String>()

    /** Returns the tile if loaded, else null and kicks off a background load. */
    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        tiles[key]?.let { return it }
        if (inFlight.add(key)) {
            scope.launch {
                val bmp = download(z, x, y)
                if (bmp != null) tiles[key] = bmp
            }
        }
        return null
    }

    private suspend fun download(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Gompashi/1.x (https://gompashi.vercel.app)")
            }
            if (conn.responseCode != 200) return@withContext null
            BitmapFactory.decodeStream(conn.inputStream)?.asImageBitmap()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
