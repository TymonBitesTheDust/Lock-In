package app.lockin.feature.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.DDayRepository
import app.lockin.core.data.LiveTotals
import app.lockin.core.data.PreferencesRepository
import app.lockin.core.data.StudyLogRepository
import app.lockin.core.data.SubjectRepository
import app.lockin.core.data.pomodoroMode
import app.lockin.core.model.DDay
import app.lockin.core.model.StudyPreferences
import app.lockin.core.model.StudySegment
import app.lockin.core.model.Subject
import app.lockin.core.model.SubjectPalette
import app.lockin.core.timer.RunState
import app.lockin.core.timer.SubjectRef
import app.lockin.core.timer.TimePoint
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class SubjectRow(
    /** Null is the built-in "General" subject. */
    val id: Long?,
    val name: String,
    val color: Int,
    val todayMs: Long,
    val isActive: Boolean,
    val isRunning: Boolean,
)

data class DDayChip(val title: String, val label: String)

data class TimerHomeState(
    val loading: Boolean = true,
    val session: TimerUiState.Active? = null,
    val sessionColor: Int? = null,
    val todayMs: Long = 0,
    val subjects: List<SubjectRow> = emptyList(),
    val hasSubjects: Boolean = false,
    val preferPomodoro: Boolean = false,
    val pomodoroLabel: String = "25 / 5",
    val dday: DDayChip? = null,
)

/**
 * MVI: intents in, one immutable [TimerHomeState] out. The ticker lives here, not in the service,
 * and only runs while the screen is collecting (WhileSubscribed), aligned to the next second change.
 */
@HiltViewModel
class TimerViewModel @Inject constructor(
    private val controller: TimerController,
    private val time: TimeSource,
    private val subjectRepository: SubjectRepository,
    private val studyLog: StudyLogRepository,
    private val preferences: PreferencesRepository,
    ddayRepository: DDayRepository,
) : ViewModel() {

    private data class DayWindow(val day: LocalDate, val startMs: Long, val endMs: Long, val segments: List<StudySegment>)

    private data class Inputs(
        val state: TimerState,
        val subjects: List<Subject>,
        val prefs: StudyPreferences,
        val ddays: List<DDay>,
        val window: DayWindow,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val window: Flow<DayWindow> = combine(studyLog.clock, studyLog.today) { clock, day -> clock to day }
        .distinctUntilChanged()
        .flatMapLatest { (clock, day) ->
            val start = clock.startOf(day)
            val end = clock.endOf(day)
            studyLog.segments(start, end).map { DayWindow(day, start, end, it) }
        }

    private val timerState: Flow<TimerState> = flow {
        controller.awaitReady()
        emitAll(controller.state)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TimerHomeState> = combine(
        timerState,
        subjectRepository.subjects,
        preferences.preferences,
        ddayRepository.ddays,
        window,
    ) { state, subjects, prefs, ddays, window -> Inputs(state, subjects, prefs, ddays, window) }
        .flatMapLatest { inputs -> ticks(inputs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimerHomeState())

    init {
        // Re-promotes the service if it could not start while we were in the background.
        viewModelScope.launch { controller.resyncSystemState() }
    }

    fun onIntent(intent: TimerIntent) {
        viewModelScope.launch {
            when (intent) {
                is TimerIntent.StartSubject -> start(intent.subjectId)
                TimerIntent.Pause -> controller.dispatch(TimerCommand.Pause)
                TimerIntent.Resume -> controller.dispatch(TimerCommand.Resume)
                TimerIntent.Skip -> controller.dispatch(TimerCommand.SkipPhase)
                TimerIntent.Stop -> controller.dispatch(TimerCommand.Stop)
                is TimerIntent.SetPomodoro -> preferences.update { it.copy(preferPomodoro = intent.enabled) }
                is TimerIntent.AddSubject -> subjectRepository.add(intent.name, intent.color)
            }
        }
    }

    fun nextSubjectColor(): Int = SubjectPalette.next(uiState.value.subjects.filter { it.id != null }.map { it.color })

    /**
     * Starts [subjectId] in the preferred mode. Tapping the subject that is already active resumes
     * it if paused; tapping another one ends the current session first (its time is kept).
     */
    private suspend fun start(subjectId: Long?) {
        val current = controller.state.value
        if (current is TimerState.Active) {
            if (current.subject.id == subjectId) {
                if (current.run is RunState.Paused) controller.dispatch(TimerCommand.Resume)
                return
            }
            controller.dispatch(TimerCommand.Stop)
        }
        val prefs = preferences.current()
        val name = subjectId?.let { id -> subjectRepository.subjects.first().firstOrNull { it.id == id }?.name } ?: GENERAL
        val mode = if (prefs.preferPomodoro) prefs.pomodoroMode() else TimerMode.Stopwatch
        controller.dispatch(TimerCommand.Start(UUID.randomUUID().toString(), SubjectRef(subjectId, name), mode))
    }

    private fun ticks(inputs: Inputs): Flow<TimerHomeState> = flow {
        while (true) {
            val now = time.now()
            emit(build(inputs, now))
            val readout = inputs.state.readoutAt(now)
            if (readout == null || !readout.isRunning) break
            delay(readout.millisUntilDisplayChanges())
        }
    }

    private fun build(inputs: Inputs, now: TimePoint): TimerHomeState {
        val window = inputs.window
        val totals = LiveTotals.forDay(window.segments, window.startMs, window.endMs, inputs.state, now)
        val active = inputs.state as? TimerState.Active
        val readout = inputs.state.readoutAt(now)
        val running = readout?.isRunning == true
        val rows = buildList {
            inputs.subjects.forEach { s ->
                val isActive = active?.subject?.id == s.id
                add(SubjectRow(s.id, s.name, s.color, totals.bySubjectId[s.id] ?: 0, isActive, isActive && running))
            }
            val generalMs = totals.bySubjectId[null] ?: 0
            val generalActive = active != null && active.subject.id == null
            if (inputs.subjects.isEmpty() || generalMs > 0 || generalActive) {
                add(SubjectRow(null, GENERAL, GENERAL_COLOR, generalMs, generalActive, generalActive && running))
            }
        }
        val upcoming = inputs.ddays.firstOrNull { it.daysFrom(window.day) >= 0 }
        return TimerHomeState(
            loading = false,
            session = TimerUiState.from(inputs.state, readout) as? TimerUiState.Active,
            sessionColor = active?.let { a -> inputs.subjects.firstOrNull { it.id == a.subject.id }?.color ?: GENERAL_COLOR },
            todayMs = totals.totalMs,
            subjects = rows,
            hasSubjects = inputs.subjects.isNotEmpty(),
            preferPomodoro = inputs.prefs.preferPomodoro,
            pomodoroLabel = "${inputs.prefs.pomodoroFocusMin} / ${inputs.prefs.shortBreakMin}",
            dday = upcoming?.let { DDayChip(it.title, it.label(window.day)) },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val GENERAL = "General"
        val GENERAL_COLOR = 0xFF8A9099.toInt()
    }
}
