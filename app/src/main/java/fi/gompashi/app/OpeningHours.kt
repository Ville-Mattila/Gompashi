package fi.gompashi.app

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Result of evaluating a store's schedule: whether it is open now and the next transition. */
data class OpenStatus(val open: Boolean, val target: LocalDateTime)

object OpeningHours {

    /**
     * Given the current time, a 7-day schedule (Mon..Sun; each [open,close] or null), per-date
     * exceptions ("yyyy-MM-dd" -> [open,close] or null), and the set of Alko-closed dates
     * (ISO "yyyy-MM-dd"), returns whether the store is open now and when it next closes (if open)
     * or opens (if closed). Null if no opening found in 2 weeks.
     *
     * Precedence per day: exception (if the date is present) > closed date > weekly schedule.
     */
    fun status(
        now: LocalDateTime,
        hours: List<List<String>?>,
        closedDates: Set<String>,
        exceptions: Map<String, List<String>?> = emptyMap(),
    ): OpenStatus? {
        for (offset in 0L..13L) {
            val day = now.toLocalDate().plusDays(offset)
            val key = day.toString()
            val dow = day.dayOfWeek.value - 1 // Mon=0 .. Sun=6
            val sched = when {
                exceptions.containsKey(key) -> exceptions[key]
                closedDates.contains(key) || dow !in hours.indices -> null
                else -> hours[dow]
            }
            if (sched == null || sched.size < 2) continue
            val open = day.atTime(LocalTime.parse(sched[0]))
            val close = day.atTime(LocalTime.parse(sched[1]))
            if (offset == 0L) {
                if (now.isBefore(open)) return OpenStatus(false, open)
                if (now.isBefore(close)) return OpenStatus(true, close)
                continue // already closed for today
            }
            return OpenStatus(false, open)
        }
        return null
    }

    /** Human countdown text, e.g. "Auki vielä 2:34:11" or "Aukeaa 1 pv 9:12:05". */
    fun countdownText(status: OpenStatus?, now: LocalDateTime): String {
        if (status == null) return ""
        val secs = Duration.between(now, status.target).seconds.coerceAtLeast(0)
        val d = secs / 86400
        val hms = "%d:%02d:%02d".format((secs % 86400) / 3600, (secs % 3600) / 60, secs % 60)
        val t = if (d > 0) "$d pv $hms" else hms
        return (if (status.open) "Auki vielä " else "Aukeaa ") + t
    }
}
