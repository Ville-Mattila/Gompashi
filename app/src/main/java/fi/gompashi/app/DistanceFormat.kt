package fi.gompashi.app

import java.util.Locale
import kotlin.math.roundToInt

object DistanceFormat {
    /**
     * Human-friendly distance. Under 100 m it shows single-metre precision (the final
     * sprint); 100 m..1 km rounds to 10 m to tame GPS jitter; above that, km with one decimal.
     */
    fun format(meters: Double): String {
        if (meters < 1000.0) {
            val m = meters.roundToInt()
            val rounded = if (m < 100) m else (m / 10.0).roundToInt() * 10
            return "$rounded m"
        }
        val km = meters / 1000.0
        return String.format(Locale.US, "%.1f km", km)
    }
}
