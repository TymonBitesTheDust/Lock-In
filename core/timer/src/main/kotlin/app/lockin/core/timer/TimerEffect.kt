package app.lockin.core.timer

/**
 * Things the engine cannot express as state.
 *
 * Alarms, the foreground service and the notification are deliberately *not* effects: they are
 * projections of [TimerState] that the controller re-derives after every transition. That keeps
 * them correct even when a transition happens during process restore or after a missed alarm.
 */
sealed interface TimerEffect {

    /** Persist this segment. Sinks must treat (sessionId, index) as an idempotent key. */
    data class RecordSegment(val segment: FocusSegment) : TimerEffect

    data class SessionFinished(val summary: SessionSummary) : TimerEffect

    /**
     * A Pomodoro boundary was crossed without the user asking. If several boundaries passed
     * while the device slept, only the last one is reported.
     */
    data class PhaseChanged(
        val from: Phase,
        val to: Phase,
        val nextStartsAutomatically: Boolean,
    ) : TimerEffect

    /** The device rebooted mid-session; time was credited up to [creditedUntilWallMs]. */
    data class Interrupted(val creditedUntilWallMs: Long) : TimerEffect

    /** The command did not apply to the current state. Useful for logging and UI feedback. */
    data class Ignored(val command: TimerCommand, val reason: String) : TimerEffect
}

data class Transition(
    val state: TimerState,
    val effects: List<TimerEffect> = emptyList(),
)
