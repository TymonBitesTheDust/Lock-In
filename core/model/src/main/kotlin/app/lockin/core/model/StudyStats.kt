package app.lockin.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class SubjectTotal(
    val subjectId: Long?,
    val name: String,
    val ms: Long,
)

/** Pure aggregation over focus segments. Segments that cross a boundary are split, never rounded. */
object StudyStats {

    /** Milliseconds of [segment] inside `[startMs, endMs)`. */
    fun overlap(segment: StudySegment, startMs: Long, endMs: Long): Long =
        (minOf(segment.endWallMs, endMs) - maxOf(segment.startWallMs, startMs)).coerceAtLeast(0)

    fun total(segments: List<StudySegment>, startMs: Long, endMs: Long): Long =
        segments.sumOf { overlap(it, startMs, endMs) }

    /** Total per study day for every day in `[from, to]`, including days with nothing. */
    fun perDay(segments: List<StudySegment>, clock: StudyDayClock, from: LocalDate, to: LocalDate): LinkedHashMap<LocalDate, Long> {
        val result = LinkedHashMap<LocalDate, Long>()
        var day = from
        while (!day.isAfter(to)) {
            result[day] = 0L
            day = day.plusDays(1)
        }
        for (segment in segments) {
            var d = clock.dayOf(segment.startWallMs)
            while (true) {
                val start = clock.startOf(d)
                if (start >= segment.endWallMs) break
                val part = overlap(segment, start, clock.endOf(d))
                val current = result[d]
                if (part > 0 && current != null) result[d] = current + part
                d = d.plusDays(1)
            }
        }
        return result
    }

    /** Totals per subject inside the range, largest first. Subjects are keyed by id, falling back to name. */
    fun perSubject(segments: List<StudySegment>, startMs: Long, endMs: Long): List<SubjectTotal> =
        segments
            .groupBy { it.subjectId?.let { id -> "id:$id" } ?: "name:${it.subjectName}" }
            .map { (_, list) ->
                SubjectTotal(
                    subjectId = list.first().subjectId,
                    name = list.maxBy { it.startWallMs }.subjectName,
                    ms = list.sumOf { overlap(it, startMs, endMs) },
                )
            }
            .filter { it.ms > 0 }
            .sortedByDescending { it.ms }

    /** Time per local clock hour (0..23) inside the range. */
    fun perHour(segments: List<StudySegment>, startMs: Long, endMs: Long, zone: ZoneId): LongArray {
        val hours = LongArray(24)
        for (segment in segments) {
            var cursor = maxOf(segment.startWallMs, startMs)
            val end = minOf(segment.endWallMs, endMs)
            while (cursor < end) {
                val local = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHour = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val stop = minOf(nextHour, end)
                hours[local.hour] += stop - cursor
                cursor = stop
            }
        }
        return hours
    }

    /**
     * Consecutive days with at least [minMs] of focus, ending today, or ending yesterday when
     * today has nothing yet (the streak is not broken until the day is over).
     */
    fun currentStreak(perDay: Map<LocalDate, Long>, today: LocalDate, minMs: Long = MIN_STREAK_MS): Int {
        var day = if ((perDay[today] ?: 0) >= minMs) today else today.minusDays(1)
        var streak = 0
        while ((perDay[day] ?: 0) >= minMs) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    fun bestStreak(perDay: Map<LocalDate, Long>, minMs: Long = MIN_STREAK_MS): Int {
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in perDay.keys.sorted()) {
            val studied = (perDay[day] ?: 0) >= minMs
            run = when {
                !studied -> 0
                previous != null && previous.plusDays(1) == day -> run + 1
                else -> 1
            }
            best = maxOf(best, run)
            previous = day
        }
        return best
    }

    fun weekStart(day: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(firstDay))

    /** A day counts towards a streak from one minute of focus. */
    const val MIN_STREAK_MS = 60_000L
}

object Durations {
    /** `3h 05m`, `42m`, `0m`. Compact form for lists, charts and leaderboards. */
    fun short(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm".format(minutes)
    }

    /** `3:05:09`, always with hours. For totals that tick. */
    fun clock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1_000
        return "%d:%02d:%02d".format(totalSeconds / 3_600, (totalSeconds % 3_600) / 60, totalSeconds % 60)
    }
}
