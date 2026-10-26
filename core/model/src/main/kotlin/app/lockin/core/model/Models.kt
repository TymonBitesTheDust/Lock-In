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
    /** Light, dark or follow the phone. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Accent colour for the whole app, the lock screen included. */
    val accent: AccentColor = AccentColor.BLUE,
)

object SubjectPalette {
    /**
     * Categorical hues in a fixed order, validated on the dark surface (#0F1216): lightness band,
     * chroma floor, colour-blind separation of neighbours (worst ΔE 8.4) and 3:1 contrast all pass.
     * New subjects take the next unused slot; colours follow the subject, never its rank.
     */
    val colors: List<Int> = listOf(
        0xFF3987E5, // blue
        0xFFD95926, // orange
        0xFF199E70, // aqua
        0xFFC98500, // yellow
        0xFFD55181, // magenta
        0xFF008300, // green
        0xFF9085E9, // violet
        0xFFE66767, // red
    ).map { it.toInt() }

    fun next(used: Collection<Int>): Int = colors.firstOrNull { it !in used } ?: colors[used.size % colors.size]
}
