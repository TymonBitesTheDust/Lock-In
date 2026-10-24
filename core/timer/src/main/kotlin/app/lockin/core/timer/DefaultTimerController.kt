package app.lockin.core.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The imperative shell around [TimerEngine]. Single writer of timer state for the whole process.
 *
 * Commit order for every transition (write-ahead, then publish, then project):
 * 1. Durable records: segments and session summaries go to [StudyRecordSink].
 * 2. Durable snapshot: the new state goes to [TimerSnapshotStore].
 * 3. Publish: [state] emits.
 * 4. Projections: the wake-up alarm and the foreground service are re-derived from state.
 * 5. Announcements: phase-change and interruption alerts.
 *
 * A crash between any two steps is safe: on restart the old snapshot is replayed, which re-emits
 * the same segments under the same (sessionId, index) keys, and projections are rebuilt.
 */
class DefaultTimerController(
    private val time: TimeSource,
    private val store: TimerSnapshotStore,
    private val records: StudyRecordSink,
    private val wakeUps: PhaseWakeUpScheduler,
    private val host: TimerForegroundHost,
    private val announcer: TimerAnnouncer,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) : TimerController {

    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow<TimerState>(TimerState.Idle)
    override val state: StateFlow<TimerState> = _state.asStateFlow()

    // Guarded by [mutex].
    private var syncedBoundary: Long? = null
    private var boundarySynced = false

    init {
        scope.launch {
            try {
                mutex.withLock {
                    val persisted = runCatching { store.read() }.onFailure(onError).getOrNull() ?: TimerState.Idle
                    commit(TimerEngine.reduce(persisted, TimerCommand.Reconcile, time.now()), forceProjections = true)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError(t)
            } finally {
                ready.complete(Unit)
            }
        }
    }

    override suspend fun awaitReady() = ready.await()

    override suspend fun dispatch(command: TimerCommand): Transition {
        ready.await()
        return mutex.withLock {
            val transition = TimerEngine.reduce(_state.value, command, time.now())
            commit(transition, forceProjections = false)
            transition
        }
    }

    override suspend fun resyncSystemState() {
        ready.await()
        mutex.withLock {
            commit(TimerEngine.reduce(_state.value, TimerCommand.Reconcile, time.now()), forceProjections = true)
        }
    }

    private suspend fun commit(transition: Transition, forceProjections: Boolean) {
        val previous = _state.value
        val next = transition.state

        for (effect in transition.effects) {
            when (effect) {
                is TimerEffect.RecordSegment -> records.recordSegment(effect.segment)
                is TimerEffect.SessionFinished -> records.finishSession(effect.summary)
                else -> Unit
            }
        }
        if (next != previous || forceProjections) store.write(next)
        _state.value = next

        project(previous, next, forceProjections)

        for (effect in transition.effects) {
            when (effect) {
                is TimerEffect.PhaseChanged -> announcer.phaseChanged(effect, next)
                is TimerEffect.Interrupted -> announcer.interrupted(effect, next)
                else -> Unit
            }
        }
    }

    private fun project(previous: TimerState, next: TimerState, force: Boolean) {
        val boundary = next.nextBoundaryElapsedMs()
        if (force || !boundarySynced || boundary != syncedBoundary) {
            wakeUps.sync(boundary, ::onWakeUp)
            syncedBoundary = boundary
            boundarySynced = true
        }
        when (next) {
            is TimerState.Active -> host.ensureForeground(next, force = force || previous !is TimerState.Active)
            TimerState.Idle -> if (force || previous is TimerState.Active) host.release()
        }
    }

    private fun onWakeUp() {
        scope.launch {
            try {
                dispatch(TimerCommand.Reconcile)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onError(t)
            }
        }
    }
}
