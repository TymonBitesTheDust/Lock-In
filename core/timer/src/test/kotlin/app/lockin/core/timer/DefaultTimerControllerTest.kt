package app.lockin.core.timer

import app.lockin.core.timer.FakeClock.Companion.MINUTE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTimerControllerTest {

    private val clock = FakeClock()
    private val log = mutableListOf<String>()

    private val store = object : TimerSnapshotStore {
        var saved: TimerState? = TimerState.Idle
        override suspend fun read() = saved
        override suspend fun write(state: TimerState) {
            saved = state
            log += "snapshot:${state::class.simpleName}"
        }
    }

    private val records = object : StudyRecordSink {
        val segments = mutableListOf<FocusSegment>()
        val sessions = mutableListOf<SessionSummary>()
        override suspend fun recordSegment(segment: FocusSegment) {
            segments += segment
            log += "segment:${segment.index}"
        }
        override suspend fun finishSession(summary: SessionSummary) {
            sessions += summary
            log += "session"
        }
    }

    private val wakeUps = object : PhaseWakeUpScheduler {
        var scheduledAt: Long? = null
        var syncCount = 0
        var fire: (() -> Unit)? = null
        override fun sync(atElapsedMs: Long?, onWakeUp: () -> Unit) {
            scheduledAt = atElapsedMs
            syncCount++
            fire = onWakeUp
        }
    }

    private val host = object : TimerForegroundHost {
        var foreground = false
        var forcedStarts = 0
        override fun ensureForeground(state: TimerState.Active, force: Boolean) {
            foreground = true
            if (force) forcedStarts++
        }
        override fun release() {
            foreground = false
        }
    }

    private val announcer = object : TimerAnnouncer {
        val phases = mutableListOf<TimerEffect.PhaseChanged>()
        val interruptions = mutableListOf<TimerEffect.Interrupted>()
        override fun phaseChanged(change: TimerEffect.PhaseChanged, state: TimerState) {
            phases += change
        }
        override fun interrupted(event: TimerEffect.Interrupted, state: TimerState) {
            interruptions += event
        }
    }

    private fun controller(scope: CoroutineScope) =
        DefaultTimerController(clock, store, records, wakeUps, host, announcer, scope, onError = { throw it })

    private fun withController(block: suspend (DefaultTimerController) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = controller(scope)
            controller.awaitReady()
            block(controller)
        } finally {
            scope.cancel()
        }
    }

    private val pomodoro = TimerMode.Pomodoro.Classic

    @Test
    fun `start promotes the service and schedules the first boundary`() = withController { c ->
        c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), pomodoro))
        assertTrue(host.foreground)
        assertEquals(1, host.forcedStarts, "Idle -> Active always (re)requests the service")
        assertEquals(clock.now().elapsedMs + 25 * MINUTE, wakeUps.scheduledAt)
        assertIs<TimerState.Active>(store.saved)
    }

    @Test
    fun `durable records are written before the snapshot`() = withController { c ->
        c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), TimerMode.Stopwatch))
        clock.advanceMinutes(10)
        log.clear()
        c.dispatch(TimerCommand.Stop)
        assertEquals(listOf("segment:0", "session", "snapshot:Idle"), log)
        assertNull(wakeUps.scheduledAt)
        assertTrue(!host.foreground)
    }

    @Test
    fun `heartbeats persist but do not reschedule alarms`() = withController { c ->
        c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), pomodoro))
        val syncs = wakeUps.syncCount
        repeat(5) {
            clock.advanceMinutes(1)
            c.dispatch(TimerCommand.Heartbeat)
        }
        assertEquals(syncs, wakeUps.syncCount)
        assertEquals(clock.now(), (store.saved as TimerState.Active).lastAlive)
    }

    @Test
    fun `an alarm firing late still lands the phase change exactly`() = withController { c ->
        c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), pomodoro))
        clock.advanceMinutes(29) // the alarm was due at 25 but fired 4 minutes late
        assertNotNull(wakeUps.fire).invoke()

        val s = c.state.value as TimerState.Active
        assertEquals(Phase.SHORT_BREAK, s.phase)
        assertEquals(25 * MINUTE, records.segments.single().durationMs)
        assertEquals(Phase.SHORT_BREAK, announcer.phases.single().to)
        assertEquals((s.run as RunState.Running).since.elapsedMs + 5 * MINUTE, wakeUps.scheduledAt)
    }

    @Test
    fun `process death mid-session is restored and re-projected`() {
        // First process: start a session, then "die" (the scope is cancelled, nothing else runs).
        withController { c -> c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), pomodoro)) }
        host.foreground = false
        wakeUps.scheduledAt = null

        clock.advanceMinutes(12)

        // Second process: a new controller restores from the snapshot alone.
        withController { c ->
            val s = c.state.value as TimerState.Active
            assertEquals("s", s.sessionId)
            assertEquals(12 * MINUTE, s.readoutAt(clock.now())!!.sessionFocusMs)
            assertTrue(host.foreground, "restore re-requests the service")
            assertNotNull(wakeUps.scheduledAt, "restore re-schedules the boundary")
        }
    }

    @Test
    fun `reboot mid-session is announced once`() {
        withController { c ->
            c.dispatch(TimerCommand.Start("s", SubjectRef(null, "Maths"), TimerMode.Stopwatch))
            clock.advanceMinutes(40)
            c.dispatch(TimerCommand.Heartbeat)
        }
        clock.reboot(offForMs = 10 * MINUTE)

        withController { c ->
            val s = c.state.value as TimerState.Active
            assertEquals(PauseReason.INTERRUPTED, (s.run as RunState.Paused).reason)
            assertEquals(1, announcer.interruptions.size)
            assertEquals(40 * MINUTE, records.segments.single().durationMs)
            c.resyncSystemState()
            assertEquals(1, announcer.interruptions.size, "resync does not repeat the announcement")
        }
    }

    @Test
    fun `unreadable snapshot starts idle`() {
        store.saved = null
        withController { c ->
            assertEquals(TimerState.Idle, c.state.value)
            assertTrue(!host.foreground)
        }
    }
}
