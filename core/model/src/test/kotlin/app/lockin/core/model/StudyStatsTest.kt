package app.lockin.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StudyStatsTest {

    private val zone = ZoneId.of("Australia/Sydney")
    private val clock = StudyDayClock(zone, startHour = 5)

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun segment(start: String, minutes: Long, subjectId: Long? = 1, name: String = "Maths", index: Int = 0) =
        StudySegment("s", index, subjectId, name, at(start), minutes * 60_000)

    @Test
    fun `a session after midnight belongs to the previous study day`() {
        assertEquals(LocalDate.parse("2026-10-23"), clock.dayOf(at("2026-10-24T01:30:00")))
        assertEquals(LocalDate.parse("2026-10-24"), clock.dayOf(at("2026-10-24T05:00:00")))
    }

    @Test
    fun `segments crossing the day boundary are split exactly`() {
        // 04:30 to 05:30: 30 minutes on the 23rd's study day, 30 on the 24th's.
        val totals = StudyStats.perDay(
            listOf(segment("2026-10-24T04:30:00", 60)),
            clock,
            LocalDate.parse("2026-10-23"),
            LocalDate.parse("2026-10-24"),
        )
        assertEquals(30 * 60_000L, totals[LocalDate.parse("2026-10-23")])
        assertEquals(30 * 60_000L, totals[LocalDate.parse("2026-10-24")])
    }

    @Test
    fun `days without study are present with zero`() {
        val totals = StudyStats.perDay(emptyList(), clock, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"))
        assertEquals(7, totals.size)
        assertEquals(0L, totals.values.sum())
    }

    @Test
    fun `per subject groups by id and keeps the latest name`() {
        val result = StudyStats.perSubject(
            listOf(
                segment("2026-10-24T09:00:00", 30, subjectId = 1, name = "Math"),
                segment("2026-10-24T10:00:00", 20, subjectId = 1, name = "Maths", index = 1),
                segment("2026-10-24T11:00:00", 40, subjectId = 2, name = "Physics", index = 2),
            ),
            at("2026-10-24T05:00:00"),
            at("2026-10-25T05:00:00"),
        )
        assertEquals(listOf("Maths" to 50 * 60_000L, "Physics" to 40 * 60_000L), result.map { it.name to it.ms })
    }

    @Test
    fun `per hour splits at the hour`() {
        val hours = StudyStats.perHour(listOf(segment("2026-10-24T09:45:00", 30)), 0, Long.MAX_VALUE, zone)
        assertEquals(15 * 60_000L, hours[9])
        assertEquals(15 * 60_000L, hours[10])
    }

    @Test
    fun `streak survives an empty today and breaks on a gap`() {
        val today = LocalDate.parse("2026-10-24")
        val perDay = mapOf(
            today.minusDays(4) to 3_600_000L,
            // gap on day -3
            today.minusDays(2) to 3_600_000L,
            today.minusDays(1) to 120_000L,
            today to 0L,
        )
        assertEquals(2, StudyStats.currentStreak(perDay, today))
        assertEquals(2, StudyStats.bestStreak(perDay))
    }

    @Test
    fun `dday labels`() {
        val today = LocalDate.parse("2026-10-24")
        assertContentEquals(
            listOf("D-12", "D-Day", "D+3"),
            listOf(
                DDay(1, "Finals", today.plusDays(12)).label(today),
                DDay(2, "Essay", today).label(today),
                DDay(3, "Quiz", today.minusDays(3)).label(today),
            ),
        )
    }

    @Test
    fun `duration formats`() {
        assertEquals("3h 05m", Durations.short(3 * 3_600_000L + 5 * 60_000L + 59_000))
        assertEquals("42m", Durations.short(42 * 60_000L))
        assertEquals("1:02:03", Durations.clock(3_723_000))
    }
}
