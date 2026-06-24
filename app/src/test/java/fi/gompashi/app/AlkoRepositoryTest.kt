package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AlkoRepositoryTest {

    @Test
    fun parses_stores_from_json() {
        val json = """
            [
              {"name":"Alko Helsinki Kamppi","lat":60.1685,"lon":24.9311},
              {"name":"Alko Tampere Koskikeskus","lat":61.4970,"lon":23.7680}
            ]
        """.trimIndent()

        val stores = AlkoRepository.parseStores(json)

        assertEquals(2, stores.size)
        assertEquals("Alko Helsinki Kamppi", stores[0].name)
        assertEquals(60.1685, stores[0].lat, 0.0001)
        assertEquals(24.9311, stores[0].lon, 0.0001)
    }

    @Test
    fun parses_empty_array() {
        assertEquals(0, AlkoRepository.parseStores("[]").size)
    }
}
