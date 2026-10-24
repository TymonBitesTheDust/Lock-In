package app.lockin.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Subject(
    val id: Long,
    val name: String,
    /** ARGB. */
    val color: Int,
)

data class StudyTask(
    val id: Long,
    val title: String,
    val date: LocalDate,
    val subjectId: Long?,
    val done: Boolean,
)

/** An exam, deadline or milestone the user is counting down to. */
data class DDay(
    val id: Long,
    val title: String,
    val date: LocalDate,
) {
    /** Positive before the date, 0 on the day, negative after. */
    fun daysFrom(today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

    /** `D-12`, `D-Day` or `D+3`, the convention exam countdowns use. */
    fun label(today: LocalDate): String {
        val days = daysFrom(today)
        return when {
            days > 0 -> "D-$days"
            days == 0L -> "D-Day"
            else -> "D+${-days}"
        }
    }
}

/** A closed stretch of focus as stored in the database. */
data class StudySegment(
    val sessionId: String,
    val index: Int,
    val subjectId: Long?,
    val subjectName: String,
    val startWallMs: Long,
    val durationMs: Long,
) {
    /** Defined from the duration (monotonic clock), never from a second wall-clock reading. */
    val endWallMs: Long get() = startWallMs + durationMs
}

/** An app the user allows during focus. */
data class AllowedApp(
    val packageName: String,
    val label: String,
)

data class StudyPreferences(
    val pomodoroFocusMin: Int = 25,
    val shortBreakMin: Int = 5,
    val longBreakMin: Int = 15,
    val longBreakEvery: Int = 4,
    val autoStartBreaks: Boolean = true,
    val autoStartFocus: Boolean = false,
    /** Local hour a study day starts at (0..23). 5 means 05:00 to 05:00. */
    val dayStartHour: Int = 5,
    /** Block apps outside the allowed list during focus phases. */
    val blockingEnabled: Boolean = false,
    /** Which mode the subject play buttons start. */
    val preferPomodoro: Boolean = false,
)

object SubjectPalette {
    /** Calm, distinguishable on the dark theme, readable as chart fills. */
    val colors: List<Int> = listOf(
        0xFF9DC3FF, // blue
        0xFFA9CDB8, // sage
        0xFFF2B58C, // apricot
        0xFFD7A8F0, // lilac
        0xFFF4D27A, // sand
        0xFF8FD6D9, // teal
        0xFFF29CB0, // rose
        0xFFB8C47E, // olive
        0xFFC9B8A6, // taupe
        0xFF9FA8F5, // periwinkle
    ).map { it.toInt() }

    fun next(used: Collection<Int>): Int = colors.firstOrNull { it !in used } ?: colors[used.size % colors.size]
}
