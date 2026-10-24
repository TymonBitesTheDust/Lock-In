package app.lockin.feature.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.DDayRepository
import app.lockin.core.data.DayTaskCount
import app.lockin.core.data.LiveTotals
import app.lockin.core.data.SessionLauncher
import app.lockin.core.data.StudyLogRepository
import app.lockin.core.data.SubjectRepository
import app.lockin.core.data.TaskRepository
import app.lockin.core.model.DDay
import app.lockin.core.model.StudyDayClock
import app.lockin.core.model.StudySegment
import app.lockin.core.model.StudyStats
import app.lockin.core.model.StudyTask
import app.lockin.core.model.Subject
import app.lockin.core.timer.TimePoint
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.unrecordedFocusMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class WeekDay(val date: LocalDate, val studiedMs: Long, val tasks: DayTaskCount?)

data class TaskRow(
    val id: Long,
    val title: String,
    val done: Boolean,
    val subjectId: Long?,
    val subjectName: String?,
    val subjectColor: Int?,
)

data class DDayRow(val id: Long, val title: String, val dateText: String, val label: String, val past: Boolean)

data class SubjectShare(val name: String, val color: Int, val ms: Long)

data class LogEntry(val timeRange: String, val subjectName: String, val color: Int, val durationMs: Long)

data class PlannerState(
    val loading: Boolean = true,
    val selected: LocalDate = LocalDate.MIN,
    val today: LocalDate = LocalDate.MIN,
    val week: List<WeekDay> = emptyList(),
    val tasks: List<TaskRow> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val ddays: List<DDayRow> = emptyList(),
    val dayTotalMs: Long = 0,
    val liveMs: Long = 0,
    val shares: List<SubjectShare> = emptyList(),
    val log: List<LogEntry> = emptyList(),
)

@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val ddayRepository: DDayRepository,
    private val launcher: SessionLauncher,
    studyLog: StudyLogRepository,
    subjectRepository: SubjectRepository,
    controller: TimerController,
    private val time: TimeSource,
) : ViewModel() {

    /** Null follows "today", so the planner moves on by itself after the day rolls over. */
    private val selected = MutableStateFlow<LocalDate?>(null)

    private data class Context(val clock: StudyDayClock, val today: LocalDate, val selected: LocalDate)

    /** Re-reads the live session every 30 s while visible; totals here don't need to tick per second. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Pair<TimerState, TimePoint>> = flow {
        controller.awaitReady()
        emitAll(controller.state)
    }.flatMapLatest { state ->
        flow {
            while (true) {
                emit(state to time.now())
                if (state !is TimerState.Active) break
                delay(30_000)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PlannerState> = combine(studyLog.clock, studyLog.today, selected) { clock, today, sel ->
        Context(clock, today, sel ?: today)
    }
        .distinctUntilChanged()
        .flatMapLatest { ctx ->
            val weekStart = StudyStats.weekStart(ctx.selected)
            val weekEnd = weekStart.plusDays(6)
            combine(
                tasks.tasksFor(ctx.selected),
                tasks.countsBetween(weekStart, weekEnd),
                studyLog.segments(ctx.clock.startOf(weekStart), ctx.clock.endOf(weekEnd)),
                combine(subjectRepository.subjects, subjectRepository.allById, ddayRepository.ddays) { a, b, c -> Triple(a, b, c) },
                live,
            ) { dayTasks, counts, segments, (subjects, allById, ddays), (timerState, now) ->
                build(ctx, weekStart, dayTasks, counts, segments, subjects, allById, ddays, timerState, now)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerState())

    fun select(date: LocalDate) {
        selected.value = date
    }

    fun shiftWeek(weeks: Long) {
        val current = state.value.selected.takeIf { it != LocalDate.MIN } ?: return
        selected.value = current.plusWeeks(weeks)
    }

    fun goToToday() {
        selected.value = null
    }

    fun addTask(title: String, subjectId: Long?) {
        val day = state.value.selected.takeIf { it != LocalDate.MIN } ?: return
        if (title.isBlank()) return
        viewModelScope.launch { tasks.add(title, day, subjectId) }
    }

    fun setDone(id: Long, done: Boolean) = viewModelScope.launch { tasks.setDone(id, done) }

    fun delete(id: Long) = viewModelScope.launch { tasks.delete(id) }

    fun moveToNextDay(id: Long) = viewModelScope.launch {
        val day = state.value.selected
        if (day != LocalDate.MIN) tasks.moveTo(id, day.plusDays(1))
    }

    fun startTask(task: TaskRow) = viewModelScope.launch { launcher.startOrResume(task.subjectId) }

    fun addDDay(title: String, date: LocalDate) = viewModelScope.launch { ddayRepository.add(title, date) }

    fun deleteDDay(id: Long) = viewModelScope.launch { ddayRepository.delete(id) }

    private fun build(
        ctx: Context,
        weekStart: LocalDate,
        dayTasks: List<StudyTask>,
        counts: Map<LocalDate, DayTaskCount>,
        segments: List<StudySegment>,
        subjects: List<Subject>,
        allById: Map<Long, Subject>,
        ddays: List<DDay>,
        timerState: TimerState,
        now: TimePoint,
    ): PlannerState {
        val clock = ctx.clock
        val perDay = StudyStats.perDay(segments, clock, weekStart, weekStart.plusDays(6))
        val dayStart = clock.startOf(ctx.selected)
        val dayEnd = clock.endOf(ctx.selected)
        val totals = LiveTotals.forDay(segments, dayStart, dayEnd, timerState, now)
        val liveToday = if (ctx.selected == ctx.today) totals.liveMs else 0L
        val liveNow = timerState.unrecordedFocusMs(now)

        fun colorOf(id: Long?) = id?.let { allById[it]?.color } ?: SessionLauncher.GENERAL_COLOR
        fun nameOf(id: Long?, fallback: String) = id?.let { allById[it]?.name } ?: fallback

        val activeSubjectName = (timerState as? TimerState.Active)?.subject?.name ?: SessionLauncher.GENERAL
        val shares = totals.bySubjectId
            .filterValues { it > 0 }
            .map { (id, ms) ->
                val fallback = segments.lastOrNull { it.subjectId == id }?.subjectName ?: activeSubjectName
                SubjectShare(nameOf(id, fallback), colorOf(id), ms)
            }
            .sortedByDescending { it.ms }

        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        val log = segments
            .filter { StudyStats.overlap(it, dayStart, dayEnd) > 0 }
            .map { segment ->
                val start = Instant.ofEpochMilli(segment.startWallMs).atZone(clock.zone).format(timeFormat)
                val end = Instant.ofEpochMilli(segment.endWallMs).atZone(clock.zone).format(timeFormat)
                LogEntry("$start–$end", nameOf(segment.subjectId, segment.subjectName), colorOf(segment.subjectId), segment.durationMs)
            }

        val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
        return PlannerState(
            loading = false,
            selected = ctx.selected,
            today = ctx.today,
            week = (0..6L).map { offset ->
                val date = weekStart.plusDays(offset)
                WeekDay(date, (perDay[date] ?: 0L) + if (date == ctx.today) liveNow else 0L, counts[date])
            },
            tasks = dayTasks.map { task ->
                TaskRow(
                    id = task.id,
                    title = task.title,
                    done = task.done,
                    subjectId = task.subjectId,
                    subjectName = task.subjectId?.let { allById[it]?.name },
                    subjectColor = task.subjectId?.let { allById[it]?.color },
                )
            },
            subjects = subjects,
            ddays = ddays.map { d ->
                DDayRow(d.id, d.title, d.date.format(dateFormat), d.label(ctx.today), past = d.daysFrom(ctx.today) < 0)
            },
            dayTotalMs = totals.totalMs,
            liveMs = liveToday,
            shares = shares,
            log = log,
        )
    }
}
