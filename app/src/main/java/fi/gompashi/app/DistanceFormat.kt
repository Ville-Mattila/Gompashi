package fi.gompashi.app

import java.util.Locale
import kotlin.math.roundToInt

object DistanceFormat {
    /** Human-friendly distance. <1 km -> meters rounded to 10; otherwise km with one decimal. */
    fun format(meters: Double): String {
        if (meters < 1000.0) {
            val rounded = (meters / 10.0).roundToInt() * 10
            return "$rounded m"
        }
        val km = meters / 1000.0
        return String.format(Locale.US, "%.1f km", km)
    }
}
