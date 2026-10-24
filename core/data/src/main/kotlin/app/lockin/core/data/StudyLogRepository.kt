package app.lockin.core.data

import app.lockin.core.database.FocusSegmentEntity
import app.lockin.core.database.StudyRecordDao
import app.lockin.core.database.StudySessionEntity
import app.lockin.core.model.StudyDayClock
import app.lockin.core.model.StudySegment
import app.lockin.core.timer.FocusSegment
import app.lockin.core.timer.SessionSummary
import app.lockin.core.timer.StudyRecordSink
import app.lockin.core.timer.TimePoint
import app.lockin.core.timer.TimerMode
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.unrecordedFocusMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Reads finished study time. Totals are always computed from segments by window (rule 5). */
@Singleton
class StudyLogRepository @Inject constructor(
    private val dao: StudyRecordDao,
    private val preferences: PreferencesRepository,
) {
    val clock: Flow<StudyDayClock> = preferences.preferences
        .map { StudyDayClock(ZoneId.systemDefault(), it.dayStartHour) }
        .distinctUntilChanged()

    /** The current study day; re-emits when the day rolls over while someone is collecting. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val today: Flow<LocalDate> = clock
        .flatMapLatest { c ->
            flow {
                while (true) {
                    val now = System.currentTimeMillis()
                    val day = c.dayOf(now)
                    emit(day)
                    delay((c.endOf(day) - now).coerceIn(1_000, 60_000))
                }
            }
        }
        .distinctUntilChanged()

    fun segments(fromMs: Long, toMs: Long): Flow<List<StudySegment>> =
        dao.observeSegments(fromMs, toMs).map { list -> list.map { it.toModel() } }

    suspend fun segmentsOnce(fromMs: Long, toMs: Long): List<StudySegment> = dao.segments(fromMs, toMs).map { it.toModel() }

    private fun FocusSegmentEntity.toModel() = StudySegment(
        sessionId = sessionId,
        index = segmentIndex,
        subjectId = subjectId,
        subjectName = subjectName,
        startWallMs = startWallMs,
        durationMs = durationMs,
    )
}

/** Replaces the logging sink: segments and sessions are upserted on their natural keys. */
@Singleton
class RoomStudyRecordSink @Inject constructor(
    private val dao: StudyRecordDao,
) : StudyRecordSink {

    override suspend fun recordSegment(segment: FocusSegment) {
        dao.upsertSegment(
            FocusSegmentEntity(
                sessionId = segment.sessionId,
                segmentIndex = segment.index,
                subjectId = segment.subject.id,
                subjectName = segment.subject.name,
                startWallMs = segment.startWallMs,
                endWallMs = segment.endWallMs,
                durationMs = segment.durationMs,
                endedBy = segment.endedBy.name,
            ),
        )
    }

    override suspend fun finishSession(summary: SessionSummary) {
        dao.upsertSession(
            StudySessionEntity(
                sessionId = summary.sessionId,
                subjectId = summary.subject.id,
                subjectName = summary.subject.name,
                mode = if (summary.mode is TimerMode.Pomodoro) "pomodoro" else "stopwatch",
                startedAtWallMs = summary.startedAtWallMs,
                endedAtWallMs = summary.endedAtWallMs,
                focusMs = summary.focusMs,
                segmentCount = summary.segmentCount,
                focusRounds = summary.focusRounds,
            ),
        )
    }
}

/** Totals for one study day, including the part of a live session not yet stored. */
data class DayTotals(
    val totalMs: Long,
    val bySubjectId: Map<Long?, Long>,
    /** Focus time currently running and not yet stored (0 when idle or paused). */
    val liveMs: Long,
)

object LiveTotals {
    fun forDay(
        segments: List<StudySegment>,
        dayStartMs: Long,
        dayEndMs: Long,
        state: TimerState,
        now: TimePoint,
    ): DayTotals {
        val bySubject = HashMap<Long?, Long>()
        var total = 0L
        for (segment in segments) {
            val part = app.lockin.core.model.StudyStats.overlap(segment, dayStartMs, dayEndMs)
            if (part <= 0) continue
            bySubject[segment.subjectId] = (bySubject[segment.subjectId] ?: 0) + part
            total += part
        }
        var live = 0L
        if (state is TimerState.Active && now.wallMs in dayStartMs until dayEndMs) {
            live = state.unrecordedFocusMs(now)
            if (live > 0) {
                bySubject[state.subject.id] = (bySubject[state.subject.id] ?: 0) + live
                total += live
            }
        }
        return DayTotals(totalMs = total, bySubjectId = bySubject, liveMs = live)
    }
}
