package app.lockin.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    /** Archived subjects disappear from pickers but keep their name and colour in history. */
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtMs: Long,
)

/**
 * One closed stretch of focus. Keyed by (sessionId, segmentIndex) so replaying a snapshot after a
 * crash upserts the same rows instead of duplicating them.
 */
@Entity(
    tableName = "focus_segments",
    primaryKeys = ["sessionId", "segmentIndex"],
    indices = [Index("startWallMs"), Index("endWallMs")],
)
data class FocusSegmentEntity(
    val sessionId: String,
    val segmentIndex: Int,
    val subjectId: Long?,
    val subjectName: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long,
    val endedBy: String,
)

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey val sessionId: String,
    val subjectId: Long?,
    val subjectName: String,
    val mode: String,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    val focusMs: Long,
    val segmentCount: Int,
    val focusRounds: Int,
)

@Entity(tableName = "tasks", indices = [Index("epochDay")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Study day as LocalDate.toEpochDay(). */
    val epochDay: Long,
    val subjectId: Long?,
    val done: Boolean = false,
    val createdAtMs: Long,
    val doneAtMs: Long? = null,
)

@Entity(tableName = "ddays")
data class DDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val epochDay: Long,
    val createdAtMs: Long,
)

@Entity(tableName = "allowed_apps")
data class AllowedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
)

/** Row type for per-day task counts in the planner's week strip. */
data class TaskDayCount(
    val epochDay: Long,
    val total: Int,
    val done: Int,
)
