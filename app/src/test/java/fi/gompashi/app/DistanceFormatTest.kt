package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceFormatTest {
    @Test
    fun under_1km_shows_meters() {
        assertEquals("850 m", DistanceFormat.format(850.0))
    }

    @Test
    fun rounds_meters_to_nearest_ten() {
        assertEquals("120 m", DistanceFormat.format(123.0))
    }

    @Test
    fun at_or_above_1km_shows_one_decimal_km() {
        assertEquals("1.2 km", DistanceFormat.format(1234.0))
    }
}
