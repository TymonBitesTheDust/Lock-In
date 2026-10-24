package app.lockin.core.timer

import app.lockin.core.timer.TimerState.Active

/**
 * Flat, versioned encoding of [TimerState] for key-value storage (Preferences DataStore).
 *
 * The persisted format is kept separate from the domain model on purpose: domain classes can be
 * refactored freely, and a format change is an explicit [VERSION] bump with a migration.
 * Values are only Int, Long, String or Boolean so any key-value store can hold them.
 */
object TimerSnapshotCodec {

    const val VERSION = 1

    fun encode(state: TimerState): Map<String, Any> = when (state) {
        TimerState.Idle -> mapOf(K_VERSION to VERSION, K_KIND to KIND_IDLE)
        is Active -> buildMap {
            put(K_VERSION, VERSION)
            put(K_KIND, KIND_ACTIVE)
            put(K_SESSION_ID, state.sessionId)
            state.subject.id?.let { put(K_SUBJECT_ID, it) }
            put(K_SUBJECT_NAME, state.subject.name)
            when (val mode = state.mode) {
                TimerMode.Stopwatch -> put(K_MODE, MODE_STOPWATCH)
                is TimerMode.Pomodoro -> {
                    put(K_MODE, MODE_POMODORO)
                    put(K_FOCUS_MS, mode.focusMs)
                    put(K_SHORT_BREAK_MS, mode.shortBreakMs)
                    put(K_LONG_BREAK_MS, mode.longBreakMs)
                    put(K_LONG_BREAK_EVERY, mode.longBreakEvery)
                    put(K_AUTO_BREAKS, mode.autoStartBreaks)
                    put(K_AUTO_FOCUS, mode.autoStartFocus)
                }
            }
            put(K_STARTED_WALL, state.startedAtWallMs)
            put(K_PHASE, state.phase.name)
            put(K_ROUNDS, state.focusRounds)
            put(K_PHASE_BANKED, state.phaseBankedMs)
            put(K_FOCUS_BANKED, state.focusBankedMs)
            put(K_NEXT_SEGMENT, state.nextSegmentIndex)
            when (val run = state.run) {
                is RunState.Running -> {
                    put(K_RUN, RUN_RUNNING)
                    putPoint(P_SINCE, run.since)
                }
                is RunState.Paused -> {
                    put(K_RUN, RUN_PAUSED)
                    put(K_PAUSE_REASON, run.reason.name)
                    put(K_PAUSED_WALL, run.atWallMs)
                }
            }
            putPoint(P_LAST_ALIVE, state.lastAlive)
        }
    }

    /**
     * Returns [TimerState.Idle] for an empty store (first launch), the decoded state, or null if
     * the data is unreadable or from an unknown version. Callers decide how to treat null.
     */
    fun decode(values: Map<String, Any?>): TimerState? {
        if (values.isEmpty()) return TimerState.Idle
        return try {
            decodeOrThrow(values)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun decodeOrThrow(v: Map<String, Any?>): TimerState {
        require(v.int(K_VERSION) == VERSION) { "Unsupported snapshot version ${v[K_VERSION]}" }
        return when (val kind = v.string(K_KIND)) {
            KIND_IDLE -> TimerState.Idle
            KIND_ACTIVE -> Active(
                sessionId = v.string(K_SESSION_ID),
                subject = SubjectRef(id = v.longOrNull(K_SUBJECT_ID), name = v.string(K_SUBJECT_NAME)),
                mode = when (val mode = v.string(K_MODE)) {
                    MODE_STOPWATCH -> TimerMode.Stopwatch
                    MODE_POMODORO -> TimerMode.Pomodoro(
                        focusMs = v.long(K_FOCUS_MS),
                        shortBreakMs = v.long(K_SHORT_BREAK_MS),
                        longBreakMs = v.long(K_LONG_BREAK_MS),
                        longBreakEvery = v.int(K_LONG_BREAK_EVERY),
                        autoStartBreaks = v.boolean(K_AUTO_BREAKS),
                        autoStartFocus = v.boolean(K_AUTO_FOCUS),
                    )
                    else -> error("Unknown mode $mode")
                },
                startedAtWallMs = v.long(K_STARTED_WALL),
                phase = Phase.valueOf(v.string(K_PHASE)),
                focusRounds = v.int(K_ROUNDS),
                run = when (val run = v.string(K_RUN)) {
                    RUN_RUNNING -> RunState.Running(since = v.point(P_SINCE))
                    RUN_PAUSED -> RunState.Paused(
                        reason = PauseReason.valueOf(v.string(K_PAUSE_REASON)),
                        atWallMs = v.long(K_PAUSED_WALL),
                    )
                    else -> error("Unknown run state $run")
                },
                phaseBankedMs = v.long(K_PHASE_BANKED),
                focusBankedMs = v.long(K_FOCUS_BANKED),
                nextSegmentIndex = v.int(K_NEXT_SEGMENT),
                lastAlive = v.point(P_LAST_ALIVE),
            )
            else -> error("Unknown kind $kind")
        }
    }

    private fun MutableMap<String, Any>.putPoint(prefix: String, point: TimePoint) {
        put("$prefix.elapsed", point.elapsedMs)
        put("$prefix.wall", point.wallMs)
        put("$prefix.boot", point.bootCount)
    }

    private fun Map<String, Any?>.point(prefix: String) = TimePoint(
        elapsedMs = long("$prefix.elapsed"),
        wallMs = long("$prefix.wall"),
        bootCount = int("$prefix.boot"),
    )

    private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: error("Missing $key")
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: error("Missing $key")
    private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: error("Missing $key")
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: error("Missing $key")

    private const val K_VERSION = "v"
    private const val K_KIND = "kind"
    private const val K_SESSION_ID = "session.id"
    private const val K_SUBJECT_ID = "subject.id"
    private const val K_SUBJECT_NAME = "subject.name"
    private const val K_MODE = "mode"
    private const val K_FOCUS_MS = "mode.focusMs"
    private const val K_SHORT_BREAK_MS = "mode.shortBreakMs"
    private const val K_LONG_BREAK_MS = "mode.longBreakMs"
    private const val K_LONG_BREAK_EVERY = "mode.longBreakEvery"
    private const val K_AUTO_BREAKS = "mode.autoStartBreaks"
    private const val K_AUTO_FOCUS = "mode.autoStartFocus"
    private const val K_STARTED_WALL = "startedAtWallMs"
    private const val K_PHASE = "phase"
    private const val K_ROUNDS = "focusRounds"
    private const val K_PHASE_BANKED = "phaseBankedMs"
    private const val K_FOCUS_BANKED = "focusBankedMs"
    private const val K_NEXT_SEGMENT = "nextSegmentIndex"
    private const val K_RUN = "run"
    private const val K_PAUSE_REASON = "run.pauseReason"
    private const val K_PAUSED_WALL = "run.pausedAtWallMs"
    private const val P_SINCE = "run.since"
    private const val P_LAST_ALIVE = "lastAlive"

    private const val KIND_IDLE = "idle"
    private const val KIND_ACTIVE = "active"
    private const val MODE_STOPWATCH = "stopwatch"
    private const val MODE_POMODORO = "pomodoro"
    private const val RUN_RUNNING = "running"
    private const val RUN_PAUSED = "paused"
}
