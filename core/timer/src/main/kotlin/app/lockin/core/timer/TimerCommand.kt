package app.lockin.core.timer

sealed interface TimerCommand {

    data class Start(
        val sessionId: String,
        val subject: SubjectRef,
        val mode: TimerMode,
    ) : TimerCommand {
        init {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        }
    }

    data object Pause : TimerCommand

    data object Resume : TimerCommand

    /** Ends the session and emits a [TimerEffect.SessionFinished]. */
    data object Stop : TimerCommand

    /** Pomodoro only: end the current phase now and start the next one. */
    data object SkipPhase : TimerCommand

    /**
     * "Look at the clock and catch up." Sent when an alarm fires, when the process starts and
     * after system events. Idempotent: sending it twice in a row changes nothing the second time.
     */
    data object Reconcile : TimerCommand

    /** Records that the process is alive right now. Sent periodically, on screen-off and on shutdown. */
    data object Heartbeat : TimerCommand
}
