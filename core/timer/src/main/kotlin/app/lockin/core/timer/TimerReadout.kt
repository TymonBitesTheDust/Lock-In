package app.lockin.core.timer

import app.lockin.core.timer.TimerState.Active

/** Everything a screen, notification or widget needs to render the timer at one instant. */
data class TimerReadout(
    val phase: Phase,
    val isRunning: Boolean,
    val pauseReason: PauseReason?,
    /** Focus time in this session, including the segment that is running right now. */
    val sessionFocusMs: Long,
    val phaseElapsedMs: Long,
    /** Pomodoro only. */
    val phaseRemainingMs: Long?,
    val focusRounds: Int,
) {
    val isCountdown: Boolean get() = phaseRemainingMs != null

    /** The number on the big clock: time left in the phase (Pomodoro) or session focus (stopwatch). */
    val displayMs: Long get() = phaseRemainingMs ?: sessionFocusMs

    /**
     * How long until the displayed second changes. UI tickers sleep exactly this long, so the
     * clock never skips or repeats a second and nothing ticks while the value cannot change.
     * Countdowns display rounded up (25:00 stays on screen for the first second); count-ups round down.
     */
    fun millisUntilDisplayChanges(): Long {
        if (!isRunning) return Long.MAX_VALUE
        val intoSecond = displayMs % 1_000
        val wait = if (isCountdown) intoSecond.takeIf { it > 0 } ?: 1_000 else 1_000 - intoSecond
        return wait.coerceIn(1, 1_000)
    }
}

/** Reads the session at [now], crossing any Pomodoro boundaries that have passed. Null when idle. */
fun TimerState.readoutAt(now: TimePoint): TimerReadout? {
    val s = TimerEngine.project(this, now) as? Active ?: return null
    val running = s.run as? RunState.Running
    val runningForMs = running?.let { (now.elapsedMs - it.since.elapsedMs).coerceAtLeast(0) } ?: 0
    val phaseElapsedMs = s.phaseBankedMs + runningForMs
    val sessionFocusMs = s.focusBankedMs + if (s.phase == Phase.FOCUS) runningForMs else 0
    val remainingMs = (s.mode as? TimerMode.Pomodoro)?.let { (it.durationOf(s.phase) - phaseElapsedMs).coerceAtLeast(0) }
    return TimerReadout(
        phase = s.phase,
        isRunning = running != null,
        pauseReason = (s.run as? RunState.Paused)?.reason,
        sessionFocusMs = sessionFocusMs,
        phaseElapsedMs = phaseElapsedMs,
        phaseRemainingMs = remainingMs,
        focusRounds = s.focusRounds,
    )
}

/**
 * Elapsed-realtime instant of the next Pomodoro boundary, or null if nothing is scheduled.
 * The controller keeps exactly one wake-up alarm in sync with this value.
 */
fun TimerState.nextBoundaryElapsedMs(): Long? {
    val s = this as? Active ?: return null
    val mode = s.mode as? TimerMode.Pomodoro ?: return null
    val running = s.run as? RunState.Running ?: return null
    return running.since.elapsedMs + (mode.durationOf(s.phase) - s.phaseBankedMs).coerceAtLeast(0)
}

/**
 * The state with its heartbeat stripped. Heartbeats change [Active.lastAlive] every minute;
 * renderers compare on this so a heartbeat never triggers a notification update.
 */
fun TimerState.ignoringHeartbeat(): TimerState =
    if (this is Active) copy(lastAlive = HEARTBEAT_PLACEHOLDER) else this

private val HEARTBEAT_PLACEHOLDER = TimePoint(elapsedMs = 0, wallMs = 0, bootCount = TimePoint.UNKNOWN_BOOT)

object TimerFormat {
    /** `12:34` under an hour, `1:02:03` from an hour up. */
    fun clock(ms: Long, roundUp: Boolean = false): String {
        val safeMs = ms.coerceAtLeast(0)
        val totalSeconds = if (roundUp) (safeMs + 999) / 1_000 else safeMs / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun clock(readout: TimerReadout): String = clock(readout.displayMs, roundUp = readout.isCountdown)
}
