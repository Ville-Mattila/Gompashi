package fi.gompashi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Local persistence for user-added stores and a custom needle image. */
class LocalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("gompashi", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val needleFile = File(context.filesDir, "needle.png")

    fun loadCustomStores(): List<AlkoStore> =
        prefs.getString(KEY_STORES, null)?.let { s ->
            runCatching { json.decodeFromString<List<AlkoStore>>(s) }.getOrDefault(emptyList())
        } ?: emptyList()

    fun saveCustomStores(list: List<AlkoStore>) {
        prefs.edit().putString(KEY_STORES, json.encodeToString(list)).apply()
    }

    /** Copies the picked image into internal storage, downscaled to keep memory sane. */
    fun saveNeedleFromUri(uri: Uri): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return false
        needleFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    fun loadNeedle(): ImageBitmap? =
        if (needleFile.exists()) {
            runCatching { BitmapFactory.decodeFile(needleFile.path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }

    fun deleteNeedle() {
        needleFile.delete()
    }

    private companion object {
        const val KEY_STORES = "customStores"
        const val MAX_DIM = 1080
    }
}
