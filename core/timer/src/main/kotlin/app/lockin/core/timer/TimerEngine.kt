package app.lockin.core.timer

import app.lockin.core.timer.TimerState.Active

/**
 * The timer's functional core: `(state, command, now) -> (state, effects)`.
 *
 * Pure and deterministic. It never reads a clock, never sleeps and never touches Android, so the
 * hard cases (late alarms, hours of deep sleep, reboots, wall-clock jumps) are plain unit tests.
 *
 * Every call first replays whatever happened between the last observation and [now]:
 * 1. If the device rebooted, the session is replayed up to its last heartbeat and paused there.
 * 2. Any Pomodoro boundaries that passed are crossed at their *exact* instants.
 * Only then is the command applied. This is why an alarm that fires four minutes late, or never,
 * cannot change the outcome; it only changes when the user hears about it.
 */
object TimerEngine {

    /** Upper bound on boundaries crossed in one catch-up (about a week of 1-minute phases). */
    const val MAX_CATCH_UP_PHASES = 20_000

    fun reduce(state: TimerState, command: TimerCommand, now: TimePoint): Transition {
        val effects = mutableListOf<TimerEffect>()
        var next = recoverIfRebooted(state, now, effects)
        next = advance(next, now, effects)
        next = apply(next, command, now, effects)
        if (next is Active) next = next.copy(lastAlive = now)
        return Transition(next, effects)
    }

    /** [state] as it is at [now], without effects. Used for display and for scheduling. */
    fun project(state: TimerState, now: TimePoint): TimerState {
        val discard = mutableListOf<TimerEffect>()
        return advance(recoverIfRebooted(state, now, discard), now, discard)
    }

    // region Catch-up

    private fun recoverIfRebooted(
        state: TimerState,
        now: TimePoint,
        effects: MutableList<TimerEffect>,
    ): TimerState {
        if (state !is Active) return state
        val running = state.run as? RunState.Running ?: return state
        if (now.isSameBootAs(running.since)) return state

        // `since` belongs to a previous boot, so `now - since` means nothing. Replay the session
        // up to the last instant a live process saw it, then pause there.
        val lastAlive = state.lastAlive.takeIf { it.isSameBootAs(running.since) } ?: running.since
        val replayEffects = mutableListOf<TimerEffect>()
        var replayed = advance(state, lastAlive, replayEffects)
        if (replayed.run is RunState.Running) {
            replayed = closeSegment(
                state = replayed,
                at = lastAlive,
                endedBy = SegmentEnd.INTERRUPTED,
                effects = replayEffects,
                then = RunState.Paused(PauseReason.INTERRUPTED, lastAlive.wallMs),
            )
        }
        // Keep the durable records from the replay; drop phase announcements that are now stale.
        effects += replayEffects.filterIsInstance<TimerEffect.RecordSegment>()
        effects += TimerEffect.Interrupted(creditedUntilWallMs = lastAlive.wallMs)
        return replayed
    }

    private fun advance(state: TimerState, now: TimePoint, effects: MutableList<TimerEffect>): TimerState =
        if (state is Active) advance(state, now, effects) else state

    private fun advance(state: Active, now: TimePoint, effects: MutableList<TimerEffect>): Active {
        val mode = state.mode as? TimerMode.Pomodoro ?: return state
        var current = state
        var lastChange: TimerEffect.PhaseChanged? = null
        var crossed = 0
        while (crossed < MAX_CATCH_UP_PHASES) {
            val running = current.run as? RunState.Running ?: break
            val remaining = (mode.durationOf(current.phase) - current.phaseBankedMs).coerceAtLeast(0)
            val boundary = running.since.shiftedBy(remaining)
            if (now.elapsedMs < boundary.elapsedMs) break

            val closed = closeSegment(current, boundary, SegmentEnd.PHASE_COMPLETE, effects, then = running)
            val (entered, change) = enterNextPhase(closed, mode, at = boundary, forceStart = false)
            current = entered
            lastChange = change
            crossed++
        }
        lastChange?.let { effects += it }
        return current
    }

    // endregion

    // region Commands

    private fun apply(
        state: TimerState,
        command: TimerCommand,
        now: TimePoint,
        effects: MutableList<TimerEffect>,
    ): TimerState = when (command) {
        is TimerCommand.Start -> when (state) {
            TimerState.Idle -> Active(
                sessionId = command.sessionId,
                subject = command.subject,
                mode = command.mode,
                startedAtWallMs = now.wallMs,
                phase = Phase.FOCUS,
                focusRounds = 0,
                run = RunState.Running(since = now),
                phaseBankedMs = 0,
                focusBankedMs = 0,
                nextSegmentIndex = 0,
                lastAlive = now,
            )
            is Active -> state.ignore(command, "A session is already active", effects)
        }

        TimerCommand.Pause -> state.whenActive(command, effects) { s ->
            if (s.run is RunState.Running) {
                closeSegment(s, now, SegmentEnd.PAUSED, effects, then = RunState.Paused(PauseReason.USER, now.wallMs))
            } else {
                s.ignore(command, "Already paused", effects)
            }
        }

        TimerCommand.Resume -> state.whenActive(command, effects) { s ->
            if (s.run is RunState.Paused) s.copy(run = RunState.Running(since = now)) else s.ignore(command, "Already running", effects)
        }

        TimerCommand.SkipPhase -> state.whenActive(command, effects) { s ->
            val mode = s.mode as? TimerMode.Pomodoro
            if (mode == null) {
                s.ignore(command, "Stopwatch sessions have no phases", effects)
            } else {
                val closed = closeSegment(s, now, SegmentEnd.SKIPPED, effects, then = s.run)
                enterNextPhase(closed, mode, at = now, forceStart = true).first
            }
        }

        TimerCommand.Stop -> state.whenActive(command, effects) { s ->
            val closed = closeSegment(s, now, SegmentEnd.STOPPED, effects, then = RunState.Paused(PauseReason.USER, now.wallMs))
            effects += TimerEffect.SessionFinished(
                SessionSummary(
                    sessionId = closed.sessionId,
                    subject = closed.subject,
                    mode = closed.mode,
                    startedAtWallMs = closed.startedAtWallMs,
                    endedAtWallMs = now.wallMs,
                    focusMs = closed.focusBankedMs,
                    segmentCount = closed.nextSegmentIndex,
                    focusRounds = closed.focusRounds,
                ),
            )
            TimerState.Idle
        }

        // Catch-up already happened above; these only exist to trigger it (and refresh lastAlive).
        TimerCommand.Reconcile, TimerCommand.Heartbeat -> state
    }

    // endregion

    // region Building blocks

    /**
     * Closes the running segment at [at] (if running) and moves to [then].
     * Focus time is banked and, if non-empty, emitted as a [FocusSegment].
     */
    private fun closeSegment(
        state: Active,
        at: TimePoint,
        endedBy: SegmentEnd,
        effects: MutableList<TimerEffect>,
        then: RunState,
    ): Active {
        val running = state.run as? RunState.Running ?: return state.copy(run = then)
        val durationMs = (at.elapsedMs - running.since.elapsedMs).coerceAtLeast(0)
        var next = state.copy(run = then, phaseBankedMs = state.phaseBankedMs + durationMs)
        if (state.phase == Phase.FOCUS) {
            next = next.copy(focusBankedMs = state.focusBankedMs + durationMs)
            if (durationMs > 0) {
                effects += TimerEffect.RecordSegment(
                    FocusSegment(
                        sessionId = state.sessionId,
                        index = state.nextSegmentIndex,
                        subject = state.subject,
                        startWallMs = running.since.wallMs,
                        endWallMs = running.since.wallMs + durationMs,
                        durationMs = durationMs,
                        endedBy = endedBy,
                    ),
                )
                next = next.copy(nextSegmentIndex = state.nextSegmentIndex + 1)
            }
        }
        return next
    }

    private fun enterNextPhase(
        state: Active,
        mode: TimerMode.Pomodoro,
        at: TimePoint,
        forceStart: Boolean,
    ): Pair<Active, TimerEffect.PhaseChanged> {
        val leavingFocus = state.phase == Phase.FOCUS
        val rounds = if (leavingFocus) state.focusRounds + 1 else state.focusRounds
        val nextPhase = if (leavingFocus) mode.breakAfterRound(rounds) else Phase.FOCUS
        val autoStart = forceStart || if (leavingFocus) mode.autoStartBreaks else mode.autoStartFocus
        val run = if (autoStart) {
            // Starts exactly at the boundary, not "when we noticed", so late wake-ups lose nothing.
            RunState.Running(since = at)
        } else {
            RunState.Paused(PauseReason.AWAITING_NEXT_PHASE, at.wallMs)
        }
        val next = state.copy(phase = nextPhase, focusRounds = rounds, phaseBankedMs = 0, run = run)
        return next to TimerEffect.PhaseChanged(from = state.phase, to = nextPhase, nextStartsAutomatically = autoStart)
    }

    private inline fun TimerState.whenActive(
        command: TimerCommand,
        effects: MutableList<TimerEffect>,
        block: (Active) -> TimerState,
    ): TimerState = when (this) {
        TimerState.Idle -> ignore(command, "No active session", effects)
        is Active -> block(this)
    }

    private fun <S : TimerState> S.ignore(command: TimerCommand, reason: String, effects: MutableList<TimerEffect>): S {
        effects += TimerEffect.Ignored(command, reason)
        return this
    }

    // endregion
}
