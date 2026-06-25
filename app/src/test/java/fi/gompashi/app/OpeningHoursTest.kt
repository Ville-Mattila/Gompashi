package fi.gompashi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OpeningHoursTest {

    private val allDay = List(7) { listOf("09:00", "21:00") } // every day 09–21
    private val noClosed = emptySet<String>()

    @Test
    fun open_now_targets_today_close() {
        val now = LocalDateTime.of(2026, 6, 22, 12, 0)
        val st = OpeningHours.status(now, allDay, noClosed)!!
        assertTrue(st.open)
        assertEquals(LocalDateTime.of(2026, 6, 22, 21, 0), st.target)
    }

    @Test
    fun before_open_targets_today_open() {
        val now = LocalDateTime.of(2026, 6, 22, 8, 0)
        val st = OpeningHours.status(now, allDay, noClosed)!!
        assertFalse(st.open)
        assertEquals(LocalDateTime.of(2026, 6, 22, 9, 0), st.target)
    }

    @Test
    fun after_close_targets_next_day_open() {
        val now = LocalDateTime.of(2026, 6, 22, 22, 0)
        val st = OpeningHours.status(now, allDay, noClosed)!!
        assertFalse(st.open)
        assertEquals(LocalDateTime.of(2026, 6, 23, 9, 0), st.target)
    }

    @Test
    fun holiday_today_skips_to_next_open_day() {
        val now = LocalDateTime.of(2026, 6, 22, 12, 0)
        val st = OpeningHours.status(now, allDay, setOf("2026-06-22"))!!
        assertFalse(st.open)
        assertEquals(LocalDateTime.of(2026, 6, 23, 9, 0), st.target)
    }

    @Test
    fun closed_day_in_schedule_is_skipped() {
        // Sunday (index 6) closed; Saturday 2026-06-27 after close -> next open Monday 2026-06-29
        val hours = List(6) { listOf("09:00", "18:00") } + listOf<List<String>?>(null)
        val now = LocalDateTime.of(2026, 6, 27, 19, 0) // Saturday evening
        val st = OpeningHours.status(now, hours, noClosed)!!
        assertFalse(st.open)
        assertEquals(LocalDateTime.of(2026, 6, 29, 9, 0), st.target) // Monday
    }

    @Test
    fun countdown_text_formats_open_and_closed() {
        val now = LocalDateTime.of(2026, 6, 22, 12, 0, 0)
        val open = OpenStatus(true, LocalDateTime.of(2026, 6, 22, 14, 34, 11))
        assertEquals("Auki vielä 2:34:11", OpeningHours.countdownText(open, now))
        val closed = OpenStatus(false, LocalDateTime.of(2026, 6, 23, 9, 0, 0))
        assertEquals("Aukeaa 21:00:00", OpeningHours.countdownText(closed, now))
    }

    @Test
    fun no_schedule_returns_null() {
        assertNull(OpeningHours.status(LocalDateTime.of(2026, 6, 22, 12, 0), List(7) { null }, noClosed))
    }
}
