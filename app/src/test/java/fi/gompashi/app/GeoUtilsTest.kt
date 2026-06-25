package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun distance_helsinki_to_tampere_is_about_160km() {
        // Helsinki (60.1699, 24.9384) -> Tampere (61.4978, 23.7610)
        val meters = GeoUtils.distanceMeters(60.1699, 24.9384, 61.4978, 23.7610)
        // ~160 km; allow 5 km tolerance
        assertEquals(160_000.0, meters, 5_000.0)
    }

    @Test
    fun bearing_due_north_is_zero() {
        val deg = GeoUtils.bearingTo(60.0, 24.0, 61.0, 24.0)
        assertEquals(0.0, deg, 1.0)
    }

    @Test
    fun bearing_due_east_is_ninety() {
        val deg = GeoUtils.bearingTo(60.0, 24.0, 60.0, 25.0)
        assertEquals(90.0, deg, 1.0)
    }

    @Test
    fun bearing_is_normalized_to_0_360() {
        // due west should be ~270, not negative
        val deg = GeoUtils.bearingTo(60.0, 24.0, 60.0, 23.0)
        assertEquals(270.0, deg, 1.0)
    }

    @Test
    fun smallest_delta_takes_short_way_across_seam() {
        assertEquals(20.0, GeoUtils.smallestAngleDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoUtils.smallestAngleDelta(10.0, 350.0), 1e-9)
    }

    @Test
    fun smallest_delta_handles_unbounded_continuous_angle() {
        // continuous angle 725.3 (== 5.3 mod 360) toward 10 -> +4.7
        assertEquals(4.7, GeoUtils.smallestAngleDelta(725.3, 10.0), 1e-9)
    }

    @Test
    fun smallest_delta_zero_when_equal() {
        assertEquals(0.0, GeoUtils.smallestAngleDelta(123.0, 123.0), 1e-9)
    }
}
