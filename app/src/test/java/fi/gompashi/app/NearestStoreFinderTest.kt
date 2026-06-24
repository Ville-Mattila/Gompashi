package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearestStoreFinderTest {

    private val stores = listOf(
        AlkoStore("Far", 61.4970, 23.7680),     // Tampere
        AlkoStore("Near", 60.1685, 24.9311),    // Helsinki Kamppi
        AlkoStore("Mid", 60.4518, 22.2666),     // Turku
    )

    @Test
    fun ranks_stores_by_ascending_distance() {
        // user near Helsinki
        val ranked = NearestStoreFinder.rank(60.1699, 24.9384, stores)
        assertEquals("Near", ranked[0].store.name)
        // distances are non-decreasing
        for (i in 1 until ranked.size) {
            assertTrue(ranked[i].distanceMeters >= ranked[i - 1].distanceMeters)
        }
    }

    @Test
    fun second_nearest_is_index_one() {
        val ranked = NearestStoreFinder.rank(60.1699, 24.9384, stores)
        assertEquals("Mid", ranked[1].store.name)
    }

    @Test
    fun empty_input_yields_empty_list() {
        assertEquals(0, NearestStoreFinder.rank(60.0, 24.0, emptyList()).size)
    }
}
