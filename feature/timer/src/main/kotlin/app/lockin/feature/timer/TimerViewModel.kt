package app.lockin.feature.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.timer.SubjectRef
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerCommand
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerMode
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.readoutAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * MVI: intents in, one immutable [TimerUiState] out. The ticker lives here, not in the service,
 * and only runs while the screen is collecting (WhileSubscribed), aligned to the next second change.
 */
@HiltViewModel
class TimerViewModel @Inject constructor(
    private val controller: TimerController,
    private val time: TimeSource,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TimerUiState> = flow {
        controller.awaitReady()
        emit(Unit)
    }
        .flatMapLatest { controller.state }
        .flatMapLatest { state -> ticks(state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimerUiState.Loading)

    init {
        // Re-promotes the service if it could not start while we were in the background.
        viewModelScope.launch { controller.resyncSystemState() }
    }

    fun onIntent(intent: TimerIntent) {
        val command = when (intent) {
            TimerIntent.StartStopwatch -> start(TimerMode.Stopwatch)
            TimerIntent.StartPomodoro -> start(TimerMode.Pomodoro.Classic)
            TimerIntent.Pause -> TimerCommand.Pause
            TimerIntent.Resume -> TimerCommand.Resume
            TimerIntent.Skip -> TimerCommand.SkipPhase
            TimerIntent.Stop -> TimerCommand.Stop
        }
        viewModelScope.launch { controller.dispatch(command) }
    }

    private fun ticks(state: TimerState): Flow<TimerUiState> = flow {
        while (true) {
            val readout = state.readoutAt(time.now())
            emit(TimerUiState.from(state, readout))
            if (readout == null || !readout.isRunning) break
            delay(readout.millisUntilDisplayChanges())
        }
    }

    // Subjects arrive with the Room schema; until then every session is "General".
    private fun start(mode: TimerMode) = TimerCommand.Start(
        sessionId = UUID.randomUUID().toString(),
        subject = SubjectRef(id = null, name = "General"),
        mode = mode,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
