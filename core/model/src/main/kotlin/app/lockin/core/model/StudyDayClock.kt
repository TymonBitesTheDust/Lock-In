package app.lockin.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A study day runs from [startHour]:00 local time to the same hour the next day (default 05:00),
 * so a late-night session counts for the evening it belongs to. "Daily reset" is this function,
 * not a job (architecture rule 5).
 */
data class StudyDayClock(
    val zone: ZoneId,
    val startHour: Int,
) {
    init {
        require(startHour in 0..23) { "startHour must be 0..23" }
    }

    fun dayOf(wallMs: Long): LocalDate =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDateTime().minusHours(startHour.toLong()).toLocalDate()

    /** First instant of [day]. Uses local time, so DST shifts the instant, not the hour. */
    fun startOf(day: LocalDate): Long = day.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()

    fun endOf(day: LocalDate): Long = startOf(day.plusDays(1))
}
