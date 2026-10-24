package app.lockin.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.SessionLauncher
import app.lockin.core.data.StudyLogRepository
import app.lockin.core.data.SubjectRepository
import app.lockin.core.model.Durations
import app.lockin.core.model.StudyDayClock
import app.lockin.core.model.StudySegment
import app.lockin.core.model.StudyStats
import app.lockin.core.model.Subject
import app.lockin.core.model.SubjectTotal
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.unrecordedFocusMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

enum class StatsRange { DAY, WEEK, MONTH }

data class SubjectStat(val name: String, val color: Int, val ms: Long, val share: Float)

data class StatTile(val label: String, val value: String)

data class StatsState(
    val loading: Boolean = true,
    val range: StatsRange = StatsRange.WEEK,
    val title: String = "",
    val canGoForward: Boolean = false,
    val tiles: List<StatTile> = emptyList(),
    val bars: List<Long> = emptyList(),
    val barLabels: List<String> = emptyList(),
    val emphasisIndex: Int? = null,
    val subjects: List<SubjectStat> = emptyList(),
    val heatmap: Map<LocalDate, Long> = emptyMap(),
    val today: LocalDate = LocalDate.MIN,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val studyLog: StudyLogRepository,
    subjectRepository: SubjectRepository,
    controller: TimerController,
    private val time: TimeSource,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.WEEK)

    /** Null means "the current period". */
    private val anchor = MutableStateFlow<LocalDate?>(null)

    private data class Query(val clock: StudyDayClock, val today: LocalDate, val range: StatsRange, val anchor: LocalDate)

    private val timerState: Flow<TimerState> = flow {
        controller.awaitReady()
        emitAll(controller.state)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<StatsState> = combine(studyLog.clock, studyLog.today, range, anchor) { clock, today, r, a ->
        Query(clock, today, r, a ?: today)
    }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            val (from, to) = bounds(q.range, q.anchor)
            val heatFrom = StudyStats.weekStart(q.today).minusWeeks(HEATMAP_WEEKS - 1L)
            val queryFrom = if (from.isBefore(heatFrom)) from else heatFrom
            val queryTo = if (to.isAfter(q.today)) to else q.today
            combine(
                studyLog.segments(q.clock.startOf(queryFrom), q.clock.endOf(queryTo)),
                subjectRepository.allById,
                timerState,
            ) { segments, subjects, timer -> build(q, from, to, heatFrom, segments, subjects, timer) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    fun setRange(value: StatsRange) {
        range.value = value
        anchor.value = null
    }

    fun shift(steps: Long) {
        val current = state.value
        if (current.loading) return
        val base = anchor.value ?: current.today
        val next = when (range.value) {
            StatsRange.DAY -> base.plusDays(steps)
            StatsRange.WEEK -> base.plusWeeks(steps)
            StatsRange.MONTH -> base.plusMonths(steps)
        }
        anchor.value = if (next.isAfter(current.today)) null else next
    }

    private fun bounds(range: StatsRange, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        StatsRange.DAY -> anchor to anchor
        StatsRange.WEEK -> StudyStats.weekStart(anchor).let { it to it.plusDays(6) }
        StatsRange.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusDays(it.lengthOfMonth() - 1L) }
    }

    private fun build(
        q: Query,
        from: LocalDate,
        to: LocalDate,
        heatFrom: LocalDate,
        stored: List<StudySegment>,
        subjects: Map<Long, Subject>,
        timer: TimerState,
    ): StatsState {
        val now = time.now()
        // The running stretch counts as a provisional segment so stats agree with the timer screen.
        val live = (timer as? TimerState.Active)?.let { active ->
            val ms = active.unrecordedFocusMs(now)
            if (ms <= 0) null else StudySegment("live", -1, active.subject.id, active.subject.name, now.wallMs - ms, ms)
        }
        val segments = if (live != null) stored + live else stored
        val clock = q.clock
        val startMs = clock.startOf(from)
        val endMs = clock.endOf(to)
        val perDay = StudyStats.perDay(segments, clock, from, to)
        val total = StudyStats.total(segments, startMs, endMs)

        val bars: List<Long>
        val labels: List<String>
        var emphasis: Int? = null
        val tiles: List<StatTile>
        when (q.range) {
            StatsRange.DAY -> {
                val hours = StudyStats.perHour(segments, startMs, endMs, clock.zone)
                // Rotate so the chart starts at the study day's first hour.
                bars = (0 until 24).map { hours[(clock.startHour + it) % 24] }
                labels = (0 until 24).map { i -> if (i % 6 == 0) ((clock.startHour + i) % 24).toString() else "" }
                val inDay = segments.filter { StudyStats.overlap(it, startMs, endMs) > 0 }
                tiles = listOf(
                    StatTile("Total", Durations.short(total)),
                    StatTile("Sessions", inDay.map { it.sessionId }.distinct().size.toString()),
                    StatTile("Longest stretch", Durations.short(inDay.maxOfOrNull { it.durationMs } ?: 0)),
                )
            }
            StatsRange.WEEK, StatsRange.MONTH -> {
                val days = perDay.keys.toList()
                bars = perDay.values.toList()
                labels = days.map { d ->
                    if (q.range == StatsRange.WEEK) {
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    } else if (d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) {
                        d.dayOfMonth.toString()
                    } else {
                        ""
                    }
                }
                emphasis = days.indexOf(q.today).takeIf { it >= 0 }
                val elapsed = days.count { !it.isAfter(q.today) }.coerceAtLeast(1)
                val best = perDay.maxByOrNull { it.value }?.takeIf { it.value > 0 }
                tiles = listOf(
                    StatTile("Total", Durations.short(total)),
                    StatTile("Daily average", Durations.short(total / elapsed)),
                    StatTile(
                        "Best day",
                        best?.let { "${Durations.short(it.value)} · ${it.key.format(DateTimeFormatter.ofPattern("EEE d"))}" } ?: "–",
                    ),
                )
            }
        }

        val bySubject: List<SubjectTotal> = StudyStats.perSubject(segments, startMs, endMs)
        val subjectTotal = bySubject.sumOf { it.ms }.coerceAtLeast(1)
        val subjectStats = bySubject.map { t ->
            val subject = t.subjectId?.let { subjects[it] }
            SubjectStat(subject?.name ?: t.name, subject?.color ?: SessionLauncher.GENERAL_COLOR, t.ms, t.ms.toFloat() / subjectTotal)
        }

        val heat = StudyStats.perDay(segments, clock, heatFrom, q.today)
        val containsToday = !q.today.isBefore(from) && !q.today.isAfter(to)
        val title = when (q.range) {
            StatsRange.DAY -> if (from == q.today) "Today" else from.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
            StatsRange.WEEK -> if (containsToday) "This week" else "${from.format(DateTimeFormatter.ofPattern("d MMM"))} – ${to.format(DateTimeFormatter.ofPattern("d MMM"))}"
            StatsRange.MONTH -> if (containsToday) "This month" else from.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        }
        return StatsState(
            loading = false,
            range = q.range,
            title = title,
            canGoForward = to.isBefore(q.today),
            tiles = tiles,
            bars = bars,
            barLabels = labels,
            emphasisIndex = emphasis,
            subjects = subjectStats,
            heatmap = heat,
            today = q.today,
            currentStreak = StudyStats.currentStreak(heat, q.today),
            bestStreak = StudyStats.bestStreak(heat),
        )
    }

    companion object {
        const val HEATMAP_WEEKS = 18
    }
}
