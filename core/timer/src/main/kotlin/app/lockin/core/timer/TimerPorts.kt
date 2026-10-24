package app.lockin.core.timer

import kotlinx.coroutines.flow.StateFlow

/**
 * The timer as the rest of the app sees it. Feature modules depend on this interface only;
 * the implementation and its Android adapters are bound in `:system:timer`.
 */
interface TimerController {

    /** Always the latest committed state. Never ticks: derive live numbers with [readoutAt]. */
    val state: StateFlow<TimerState>

    /** Suspends until the persisted session has been restored after process start. */
    suspend fun awaitReady()

    /** Applies [command] atomically and returns what happened (including [TimerEffect.Ignored]). */
    suspend fun dispatch(command: TimerCommand): Transition

    /**
     * Re-derives alarms and the foreground service from current state. Call after anything that
     * may have invalidated them: boot, app update, exact-alarm permission change, or the UI coming
     * to the foreground after the service could not be started from the background.
     */
    suspend fun resyncSystemState()
}

// Ports: what the controller needs from the platform. Each has one Android adapter in :system:timer
// and one fake in tests.

/** Durable copy of the live session (DataStore). Written after every transition, before it is published. */
interface TimerSnapshotStore {
    /** Null means "present but unreadable"; the controller then starts idle. */
    suspend fun read(): TimerState?

    suspend fun write(state: TimerState)
}

/** Where finished focus time goes (Room). Implementations must upsert on (sessionId, index). */
interface StudyRecordSink {
    suspend fun recordSegment(segment: FocusSegment)

    suspend fun finishSession(summary: SessionSummary)
}

/** Keeps exactly one wake-up scheduled for the next Pomodoro boundary (or none for null). */
interface PhaseWakeUpScheduler {
    fun sync(atElapsedMs: Long?, onWakeUp: () -> Unit)
}

/** Keeps the foreground service (and its notification) alive while a session is active. */
interface TimerForegroundHost {
    /** [force] re-requests the service even if it is believed to be running. */
    fun ensureForeground(state: TimerState.Active, force: Boolean)

    fun release()
}

/** User-facing alerts for things that happened without the user asking. */
interface TimerAnnouncer {
    fun phaseChanged(change: TimerEffect.PhaseChanged, state: TimerState)

    fun interrupted(event: TimerEffect.Interrupted, state: TimerState)
}
