package fi.gompashi.app

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object AlkoRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pure parse function — testable without Android. */
    fun parseStores(jsonText: String): List<AlkoStore> =
        json.decodeFromString(jsonText)

    /** Loads and parses the bundled asset. */
    fun loadFromAssets(context: Context, fileName: String = "alko_stores.json"): List<AlkoStore> {
        val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
        return parseStores(text)
    }

    /** Loads the bundled Alko-closed dates (ISO "yyyy-MM-dd") as a set. */
    fun loadClosedDates(context: Context, fileName: String = "closed_dates.json"): Set<String> {
        val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
        return json.decodeFromString<List<String>>(text).toSet()
    }
}
