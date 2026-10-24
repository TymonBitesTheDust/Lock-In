package app.lockin.core.timer

/** What the user is studying. `id` is null until subjects are stored in Room. */
data class SubjectRef(
    val id: Long?,
    val name: String,
)

sealed interface TimerMode {

    /** YPT-style count-up timer. Runs until the user pauses or stops. */
    data object Stopwatch : TimerMode

    /** Focus/break cycles. Only focus phases count as study time. */
    data class Pomodoro(
        val focusMs: Long,
        val shortBreakMs: Long,
        val longBreakMs: Long,
        val longBreakEvery: Int = 4,
        val autoStartBreaks: Boolean = true,
        val autoStartFocus: Boolean = false,
    ) : TimerMode {
        init {
            require(focusMs >= MIN_PHASE_MS) { "focusMs must be at least $MIN_PHASE_MS" }
            require(shortBreakMs >= MIN_PHASE_MS) { "shortBreakMs must be at least $MIN_PHASE_MS" }
            require(longBreakMs >= MIN_PHASE_MS) { "longBreakMs must be at least $MIN_PHASE_MS" }
            require(longBreakEvery >= 1) { "longBreakEvery must be at least 1" }
        }

        fun durationOf(phase: Phase): Long = when (phase) {
            Phase.FOCUS -> focusMs
            Phase.SHORT_BREAK -> shortBreakMs
            Phase.LONG_BREAK -> longBreakMs
        }

        /** The break that follows focus round number [round] (1-based). */
        fun breakAfterRound(round: Int): Phase =
            if (round % longBreakEvery == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK

        companion object {
            const val MIN_PHASE_MS = 60_000L

            val Classic = Pomodoro(
                focusMs = 25 * 60_000L,
                shortBreakMs = 5 * 60_000L,
                longBreakMs = 15 * 60_000L,
            )
        }
    }
}

enum class Phase {
    FOCUS,
    SHORT_BREAK,
    LONG_BREAK,
    ;

    val isBreak: Boolean get() = this != FOCUS
}

enum class PauseReason {
    /** The user pressed pause. */
    USER,

    /** A Pomodoro phase finished and auto-start is off for the next one. */
    AWAITING_NEXT_PHASE,

    /** The device rebooted mid-session. Time was credited up to the last checkpoint. */
    INTERRUPTED,
}

sealed interface RunState {
    /** Counting since [since]. Nothing is accumulated while running; it is derived on read. */
    data class Running(val since: TimePoint) : RunState

    data class Paused(val reason: PauseReason, val atWallMs: Long) : RunState
}

sealed interface TimerState {

    data object Idle : TimerState

    /**
     * A session in progress. Note what is *not* here: no tick counter, no "seconds so far".
     * The live total is always `focusBankedMs + (now - since)`, so a dead process, a sleeping
     * CPU or a late alarm cannot lose or double-count time.
     */
    data class Active(
        val sessionId: String,
        val subject: SubjectRef,
        val mode: TimerMode,
        val startedAtWallMs: Long,
        val phase: Phase,
        /** Focus rounds that ended, either completed or skipped. Always 0 for stopwatch sessions. */
        val focusRounds: Int,
        val run: RunState,
        /** Time spent in the current phase across segments that have already closed. */
        val phaseBankedMs: Long,
        /** Focus time across segments that have already closed. */
        val focusBankedMs: Long,
        /** Index the next focus segment will get. Segments are keyed by (sessionId, index). */
        val nextSegmentIndex: Int,
        /** Last instant this session was observed by a live process. Used after a reboot. */
        val lastAlive: TimePoint,
    ) : TimerState
}

enum class SegmentEnd { PAUSED, PHASE_COMPLETE, SKIPPED, STOPPED, INTERRUPTED }

/**
 * A closed, contiguous stretch of focus. This is the row analytics are built from
 * (heatmaps, per-subject totals, leaderboards).
 *
 * [durationMs] comes from the monotonic clock and is authoritative. [endWallMs] is defined as
 * `startWallMs + durationMs`, so a wall-clock change mid-segment cannot stretch or shrink it.
 */
data class FocusSegment(
    val sessionId: String,
    val index: Int,
    val subject: SubjectRef,
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long,
    val endedBy: SegmentEnd,
)

data class SessionSummary(
    val sessionId: String,
    val subject: SubjectRef,
    val mode: TimerMode,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    val focusMs: Long,
    val segmentCount: Int,
    val focusRounds: Int,
)
