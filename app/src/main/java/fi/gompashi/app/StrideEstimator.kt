package fi.gompashi.app

import kotlin.math.roundToInt

/**
 * Estimates the user's stride length by correlating distance actually walked (from GPS
 * segments) with steps counted over the same intervals, then converts a remaining
 * straight-line distance into an approximate number of steps.
 *
 * Pure logic, no Android dependencies, so it is unit-testable. Until enough steps have
 * accumulated it falls back to [default] metres per step; afterwards it adapts with a
 * light exponential moving average and stays within a plausible human range.
 */
class StrideEstimator(private val default: Double = 0.75) {

    var stride: Double = default
        private set
    var calibrated: Boolean = false
        private set

    private var windowDistance = 0.0
    private var windowSteps = 0

    /** Feed one matched segment: metres travelled and steps taken during the same interval. */
    fun onSegment(meters: Double, steps: Int) {
        if (meters <= 0.0 || steps <= 0) return
        windowDistance += meters
        windowSteps += steps
        if (windowSteps >= MIN_STEPS) {
            val sample = (windowDistance / windowSteps).coerceIn(MIN_STRIDE, MAX_STRIDE)
            stride = if (!calibrated) sample else stride * (1 - ALPHA) + sample * ALPHA
            calibrated = true
            windowDistance = 0.0
            windowSteps = 0
        }
    }

    /** Approximate steps for a remaining distance in metres. */
    fun stepsFor(meters: Double): Int = if (meters <= 0.0) 0 else (meters / stride).roundToInt()

    private companion object {
        const val MIN_STEPS = 15       // collect at least this many steps before trusting a sample
        const val MIN_STRIDE = 0.45    // metres/step lower bound (short steps)
        const val MAX_STRIDE = 1.05    // metres/step upper bound (long strides / brisk walk)
        const val ALPHA = 0.3          // EMA weight for new samples
    }
}
