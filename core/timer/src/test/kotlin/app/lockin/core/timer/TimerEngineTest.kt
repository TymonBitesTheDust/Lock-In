package app.lockin.core.timer

import app.lockin.core.timer.FakeClock.Companion.MINUTE
import app.lockin.core.timer.TimerEffect.Ignored
import app.lockin.core.timer.TimerEffect.Interrupted
import app.lockin.core.timer.TimerEffect.PhaseChanged
import app.lockin.core.timer.TimerEffect.RecordSegment
import app.lockin.core.timer.TimerEffect.SessionFinished
import app.lockin.core.timer.TimerState.Active
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerEngineTest {

    private val clock = FakeClock()
    private val physics = SubjectRef(id = 3, name = "Physics")
    private var state: TimerState = TimerState.Idle
    private val effects = mutableListOf<TimerEffect>()

    private fun send(command: TimerCommand): Transition {
        val t = TimerEngine.reduce(state, command, clock.now())
        state = t.state
        effects += t.effects
        return t
    }

    private fun start(mode: TimerMode = TimerMode.Stopwatch) =
        send(TimerCommand.Start(sessionId = "s-1", subject = physics, mode = mode))

    private fun readout() = state.readoutAt(clock.now())!!
    private fun active() = state as Active
    private fun segments() = effects.filterIsInstance<RecordSegment>().map { it.segment }

    private val shortPomodoro = TimerMode.Pomodoro(
        focusMs = 25 * MINUTE,
        shortBreakMs = 5 * MINUTE,
        longBreakMs = 15 * MINUTE,
        longBreakEvery = 4,
        autoStartBreaks = true,
        autoStartFocus = true,
    )

    // region Stopwatch

    @Test
    fun `stopwatch counts only running time and segments add up to the total`() {
        start()
        clock.advanceMinutes(10)
        assertEquals(10 * MINUTE, readout().sessionFocusMs)

        send(TimerCommand.Pause)
        clock.advanceMinutes(5)
        assertEquals(10 * MINUTE, readout().sessionFocusMs, "paused time must not count")

        send(TimerCommand.Resume)
        clock.advanceMinutes(3)
        assertEquals(13 * MINUTE, readout().sessionFocusMs)

        send(TimerCommand.Stop)
        assertEquals(TimerState.Idle, state)
        val summary = effects.filterIsInstance<SessionFinished>().single().summary
        assertEquals(13 * MINUTE, summary.focusMs)
        assertEquals(2, summary.segmentCount)
        assertEquals(listOf(SegmentEnd.PAUSED, SegmentEnd.STOPPED), segments().map { it.endedBy })
        assertEquals(summary.focusMs, segments().sumOf { it.durationMs })
        assertEquals(listOf(0, 1), segments().map { it.index })
    }

    @Test
    fun `changing the wall clock never changes study time`() {
        start()
        val startWall = clock.now().wallMs
        clock.advanceMinutes(10)
        clock.jumpWallClock(+2 * 60 * MINUTE) // user sets the clock two hours ahead
        clock.advanceMinutes(5)
        assertEquals(15 * MINUTE, readout().sessionFocusMs)

        send(TimerCommand.Stop)
        val segment = segments().single()
        assertEquals(15 * MINUTE, segment.durationMs)
        assertEquals(startWall, segment.startWallMs)
        assertEquals(startWall + 15 * MINUTE, segment.endWallMs, "end is start + duration, not the jumped clock")
    }

    @Test
    fun `heartbeats do not create segments or change totals`() {
        start()
        repeat(30) {
            clock.advanceMinutes(1)
            send(TimerCommand.Heartbeat)
        }
        assertTrue(segments().isEmpty())
        assertEquals(30 * MINUTE, readout().sessionFocusMs)
        assertEquals(clock.now(), active().lastAlive)
    }

    // endregion

    // region Pomodoro

    @Test
    fun `late wake-up crosses every boundary at its exact instant`() {
        start(shortPomodoro)
        // Nothing observes the timer for 61 minutes: focus 0-25, break 25-30, focus 30-55, break 55-60, focus 60-
        clock.advanceMinutes(61)
        send(TimerCommand.Reconcile)

        val s = active()
        assertEquals(Phase.FOCUS, s.phase)
        assertEquals(2, s.focusRounds)
        assertEquals(1 * MINUTE, readout().phaseElapsedMs)
        assertEquals(51 * MINUTE, readout().sessionFocusMs)
        assertEquals(listOf(25 * MINUTE, 25 * MINUTE), segments().map { it.durationMs })
        assertTrue(segments().all { it.endedBy == SegmentEnd.PHASE_COMPLETE })

        val announced = effects.filterIsInstance<PhaseChanged>()
        assertEquals(1, announced.size, "only the latest boundary is announced")
        assertEquals(PhaseChanged(Phase.SHORT_BREAK, Phase.FOCUS, nextStartsAutomatically = true), announced.single())
    }

    @Test
    fun `reconcile is idempotent`() {
        start(shortPomodoro)
        clock.advanceMinutes(26)
        val first = send(TimerCommand.Reconcile)
        val second = send(TimerCommand.Reconcile)
        assertEquals(first.state, second.state)
        assertTrue(second.effects.isEmpty())
    }

    @Test
    fun `without auto-start focus the session waits after a break`() {
        start(shortPomodoro.copy(autoStartFocus = false))
        clock.advanceMinutes(90)
        send(TimerCommand.Reconcile)

        val s = active()
        assertEquals(Phase.FOCUS, s.phase)
        assertEquals(RunState.Paused(PauseReason.AWAITING_NEXT_PHASE, s.lastAlive.wallMs - 60 * MINUTE), s.run)
        assertEquals(25 * MINUTE, readout().sessionFocusMs, "waiting time is not study time")

        send(TimerCommand.Resume)
        clock.advanceMinutes(5)
        assertEquals(30 * MINUTE, readout().sessionFocusMs)
    }

    @Test
    fun `every fourth round is followed by a long break`() {
        start(shortPomodoro)
        // 3 x (25 + 5) = 90 minutes, then the 4th focus ends at 115
        clock.advanceMinutes(116)
        send(TimerCommand.Reconcile)
        assertEquals(Phase.LONG_BREAK, active().phase)
        assertEquals(4, active().focusRounds)
        assertEquals(100 * MINUTE, readout().sessionFocusMs)
        assertEquals(14 * MINUTE, readout().phaseRemainingMs)
    }

    @Test
    fun `skip ends the phase now and starts the next one`() {
        start(shortPomodoro.copy(autoStartBreaks = false))
        clock.advanceMinutes(10)
        send(TimerCommand.SkipPhase)

        assertEquals(Phase.SHORT_BREAK, active().phase)
        assertIs<RunState.Running>(active().run, "skipping starts the next phase even when auto-start is off")
        assertEquals(SegmentEnd.SKIPPED, segments().single().endedBy)
        assertEquals(10 * MINUTE, segments().single().durationMs)
    }

    @Test
    fun `pausing mid-phase keeps the remaining time`() {
        start(shortPomodoro)
        clock.advanceMinutes(20)
        send(TimerCommand.Pause)
        clock.advanceMinutes(60)
        send(TimerCommand.Reconcile)
        assertEquals(Phase.FOCUS, active().phase, "no boundary crossed while paused")
        assertEquals(5 * MINUTE, readout().phaseRemainingMs)

        send(TimerCommand.Resume)
        assertEquals(clock.now().elapsedMs + 5 * MINUTE, state.nextBoundaryElapsedMs())
    }

    @Test
    fun `readout crosses a boundary even before the alarm is handled`() {
        start(shortPomodoro)
        clock.advanceMinutes(26)
        // No Reconcile sent: the stored state still says FOCUS, but displays must not.
        assertEquals(Phase.FOCUS, active().phase)
        assertEquals(Phase.SHORT_BREAK, readout().phase)
        assertEquals(4 * MINUTE, readout().phaseRemainingMs)
    }

    // endregion

    // region Reboots

    @Test
    fun `reboot credits time up to the last heartbeat and pauses`() {
        start()
        clock.advanceMinutes(10)
        send(TimerCommand.Heartbeat)
        val lastAliveWall = clock.now().wallMs
        clock.advanceMinutes(3) // studied 3 more minutes, then the phone died without a heartbeat
        clock.reboot(offForMs = 20 * MINUTE)

        send(TimerCommand.Reconcile)

        val s = active()
        assertEquals(RunState.Paused(PauseReason.INTERRUPTED, lastAliveWall), s.run)
        assertEquals(10 * MINUTE, readout().sessionFocusMs)
        assertEquals(SegmentEnd.INTERRUPTED, segments().single().endedBy)
        assertEquals(Interrupted(creditedUntilWallMs = lastAliveWall), effects.filterIsInstance<Interrupted>().single())

        send(TimerCommand.Resume)
        clock.advanceMinutes(2)
        assertEquals(12 * MINUTE, readout().sessionFocusMs, "resuming in the new boot works normally")
    }

    @Test
    fun `reboot is detected without a boot counter when the clocks disagree`() {
        clock.forgetBootCount()
        start()
        clock.advanceMinutes(5)
        send(TimerCommand.Heartbeat)
        // Long uptime after reboot: elapsed is larger than before, so only clock drift reveals it.
        clock.reboot(offForMs = 8 * 60 * MINUTE, uptimeAfterBootMs = 3 * 60 * 60 * 1_000L, countBoots = false)

        send(TimerCommand.Reconcile)
        assertEquals(PauseReason.INTERRUPTED, (active().run as RunState.Paused).reason)
        assertEquals(5 * MINUTE, readout().sessionFocusMs)
    }

    @Test
    fun `pomodoro boundaries before the last heartbeat are replayed on reboot`() {
        start(shortPomodoro)
        clock.advanceMinutes(27) // focus done at 25, break running
        send(TimerCommand.Heartbeat)
        clock.reboot(offForMs = 60 * MINUTE)

        send(TimerCommand.Reconcile)
        val s = active()
        assertEquals(Phase.SHORT_BREAK, s.phase)
        assertEquals(PauseReason.INTERRUPTED, (s.run as RunState.Paused).reason)
        assertEquals(2 * MINUTE, s.phaseBankedMs)
        assertEquals(25 * MINUTE, readout().sessionFocusMs)
        assertTrue(effects.none { it is PhaseChanged && it.to == Phase.FOCUS }, "no stale announcements after reboot")
    }

    @Test
    fun `paused sessions survive a reboot untouched`() {
        start()
        clock.advanceMinutes(10)
        send(TimerCommand.Pause)
        val paused = state
        clock.reboot(offForMs = MINUTE)
        val t = send(TimerCommand.Reconcile)
        assertEquals(paused.ignoringHeartbeat(), t.state.ignoringHeartbeat())
        assertTrue(t.effects.isEmpty())
    }

    // endregion

    // region Invalid commands

    @Test
    fun `commands that do not apply are ignored with a reason`() {
        assertIs<Ignored>(send(TimerCommand.Pause).effects.single())
        assertIs<Ignored>(send(TimerCommand.Stop).effects.single())
        assertTrue(send(TimerCommand.Heartbeat).effects.isEmpty(), "background commands are silent when idle")

        start()
        assertIs<Ignored>(start().effects.single())
        assertIs<Ignored>(send(TimerCommand.Resume).effects.single())
        assertIs<Ignored>(send(TimerCommand.SkipPhase).effects.single(), "stopwatch has no phases")
        assertEquals("s-1", active().sessionId)
    }

    @Test
    fun `pausing immediately after resuming records no empty segment`() {
        start()
        send(TimerCommand.Pause)
        assertTrue(segments().isEmpty())
        assertEquals(0, active().nextSegmentIndex)
    }

    // endregion

    // region Readout

    @Test
    fun `display ticks land exactly on second boundaries`() {
        start()
        clock.advance(10_250)
        assertEquals(750, readout().millisUntilDisplayChanges())

        val countdown = TimerReadout(Phase.FOCUS, true, null, 0, 0, phaseRemainingMs = 1_499, focusRounds = 0)
        assertEquals(499, countdown.millisUntilDisplayChanges())
        assertEquals("00:02", TimerFormat.clock(countdown))

        val exact = countdown.copy(phaseRemainingMs = 2_000)
        assertEquals(1_000, exact.millisUntilDisplayChanges())

        send(TimerCommand.Pause)
        assertEquals(Long.MAX_VALUE, readout().millisUntilDisplayChanges())
    }

    @Test
    fun `clock formatting`() {
        assertEquals("00:00", TimerFormat.clock(0))
        assertEquals("09:59", TimerFormat.clock(599_999))
        assertEquals("10:00", TimerFormat.clock(599_001, roundUp = true))
        assertEquals("1:00:00", TimerFormat.clock(3_600_000))
        assertEquals("12:34:56", TimerFormat.clock((12 * 3600 + 34 * 60 + 56) * 1_000L))
    }

    @Test
    fun `idle has no readout and no boundary`() {
        assertNull(TimerState.Idle.readoutAt(clock.now()))
        assertNull(TimerState.Idle.nextBoundaryElapsedMs())
        start()
        assertNull(state.nextBoundaryElapsedMs(), "stopwatch schedules nothing")
    }

    // endregion
}
