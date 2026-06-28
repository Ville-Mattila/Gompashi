package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrideEstimatorTest {

    @Test
    fun uses_default_stride_before_calibration() {
        val e = StrideEstimator(default = 0.75)
        assertFalse(e.calibrated)
        // 75 m / 0.75 m per step = 100 steps
        assertEquals(100, e.stepsFor(75.0))
    }

    @Test
    fun calibrates_from_distance_and_steps() {
        val e = StrideEstimator()
        // 20 m over 20 steps -> 1.0 m/step (within range), reached once >= 15 steps.
        e.onSegment(20.0, 20)
        assertTrue(e.calibrated)
        assertEquals(1.0, e.stride, 1e-9)
        assertEquals(100, e.stepsFor(100.0))
    }

    @Test
    fun accumulates_short_segments_until_threshold() {
        val e = StrideEstimator()
        e.onSegment(5.0, 8)   // not enough steps yet
        assertFalse(e.calibrated)
        e.onSegment(5.0, 8)   // now 16 steps, 10 m -> 0.625 m/step
        assertTrue(e.calibrated)
        assertEquals(0.625, e.stride, 1e-9)
    }

    @Test
    fun clamps_implausible_stride() {
        val e = StrideEstimator()
        // 100 m over 20 steps = 5 m/step -> clamped to max 1.05
        e.onSegment(100.0, 20)
        assertEquals(1.05, e.stride, 1e-9)
    }

    @Test
    fun ignores_nonpositive_input() {
        val e = StrideEstimator(default = 0.8)
        e.onSegment(0.0, 10)
        e.onSegment(10.0, 0)
        assertFalse(e.calibrated)
        assertEquals(0.8, e.stride, 1e-9)
    }

    @Test
    fun zero_distance_is_zero_steps() {
        assertEquals(0, StrideEstimator().stepsFor(0.0))
    }
}
