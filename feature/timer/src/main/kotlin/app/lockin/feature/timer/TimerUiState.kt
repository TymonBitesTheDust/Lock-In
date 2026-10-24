package app.lockin.feature.timer

import app.lockin.core.timer.PauseReason
import app.lockin.core.timer.Phase
import app.lockin.core.timer.TimerFormat
import app.lockin.core.timer.TimerMode
import app.lockin.core.timer.TimerReadout
import app.lockin.core.timer.TimerState

sealed interface TimerUiState {

    data object Loading : TimerUiState

    data object Idle : TimerUiState

    data class Active(
        val subjectName: String,
        val phase: Phase,
        val clock: String,
        val isRunning: Boolean,
        val pauseReason: PauseReason?,
        val isPomodoro: Boolean,
        /** Session focus total; shown under the countdown in Pomodoro mode. */
        val sessionFocus: String,
        val round: Int,
    ) : TimerUiState

    companion object {
        fun from(state: TimerState, readout: TimerReadout?): TimerUiState = when {
            state !is TimerState.Active || readout == null -> Idle
            else -> Active(
                subjectName = state.subject.name,
                phase = readout.phase,
                clock = TimerFormat.clock(readout),
                isRunning = readout.isRunning,
                pauseReason = readout.pauseReason,
                isPomodoro = state.mode is TimerMode.Pomodoro,
                sessionFocus = TimerFormat.clock(readout.sessionFocusMs),
                round = readout.focusRounds + if (readout.phase == Phase.FOCUS) 1 else 0,
            )
        }
    }
}

sealed interface TimerIntent {
    /** Null starts the built-in "General" subject. */
    data class StartSubject(val subjectId: Long?) : TimerIntent
    data object Pause : TimerIntent
    data object Resume : TimerIntent
    data object Skip : TimerIntent
    data object Stop : TimerIntent
    data class SetPomodoro(val enabled: Boolean) : TimerIntent
    data class AddSubject(val name: String, val color: Int) : TimerIntent
}
