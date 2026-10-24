package app.lockin.core.timer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerSnapshotCodecTest {

    private val point = TimePoint(elapsedMs = 123_456, wallMs = 1_760_000_000_000, bootCount = 9)

    private val running = TimerState.Active(
        sessionId = "abc",
        subject = SubjectRef(id = 42, name = "Organic chemistry"),
        mode = TimerMode.Pomodoro.Classic.copy(autoStartFocus = true),
        startedAtWallMs = point.wallMs - 600_000,
        phase = Phase.LONG_BREAK,
        focusRounds = 4,
        run = RunState.Running(since = point),
        phaseBankedMs = 1_000,
        focusBankedMs = 6_000_000,
        nextSegmentIndex = 5,
        lastAlive = point.shiftedBy(60_000),
    )

    @Test
    fun `round-trips every state shape`() {
        val paused = running.copy(
            subject = SubjectRef(id = null, name = "General"),
            mode = TimerMode.Stopwatch,
            phase = Phase.FOCUS,
            run = RunState.Paused(PauseReason.INTERRUPTED, atWallMs = point.wallMs),
        )
        for (state in listOf(TimerState.Idle, running, paused)) {
            assertEquals(state, TimerSnapshotCodec.decode(TimerSnapshotCodec.encode(state)))
        }
    }

    @Test
    fun `values are limited to key-value friendly types`() {
        val types = TimerSnapshotCodec.encode(running).values.map { it::class }.toSet()
        assertTrue(types.all { it in setOf(Int::class, Long::class, String::class, Boolean::class) }, "got $types")
    }

    @Test
    fun `empty store decodes to idle`() {
        assertEquals(TimerState.Idle, TimerSnapshotCodec.decode(emptyMap()))
    }

    @Test
    fun `unreadable or unknown data decodes to null`() {
        val encoded = TimerSnapshotCodec.encode(running)
        assertNull(TimerSnapshotCodec.decode(encoded + ("v" to 999)))
        assertNull(TimerSnapshotCodec.decode(encoded - "run.since.elapsed"))
        assertNull(TimerSnapshotCodec.decode(encoded + ("phase" to "NAP")))
        assertNull(TimerSnapshotCodec.decode(encoded + ("mode.focusMs" to 5L)), "invalid Pomodoro config")
    }

    @Test
    fun `ints stored as longs are accepted`() {
        // Some stores widen numbers; decoding must not depend on the exact numeric type.
        val widened = TimerSnapshotCodec.encode(running).mapValues { (_, v) -> if (v is Int) v.toLong() else v }
        assertEquals(running, TimerSnapshotCodec.decode(widened))
    }
}
