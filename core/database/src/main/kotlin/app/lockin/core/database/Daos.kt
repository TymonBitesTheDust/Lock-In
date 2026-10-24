package app.lockin.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<SubjectEntity>>

    /** Including archived ones, for colouring history. */
    @Query("SELECT * FROM subjects")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun get(id: Long): SubjectEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM subjects")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(subject: SubjectEntity): Long

    @Update
    suspend fun update(subject: SubjectEntity)

    @Query("UPDATE subjects SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}

@Dao
interface StudyRecordDao {
    @Upsert
    suspend fun upsertSegment(segment: FocusSegmentEntity)

    @Upsert
    suspend fun upsertSession(session: StudySessionEntity)

    /** Segments overlapping `[fromMs, toMs)`. */
    @Query("SELECT * FROM focus_segments WHERE endWallMs > :fromMs AND startWallMs < :toMs ORDER BY startWallMs")
    fun observeSegments(fromMs: Long, toMs: Long): Flow<List<FocusSegmentEntity>>

    @Query("SELECT * FROM focus_segments WHERE endWallMs > :fromMs AND startWallMs < :toMs ORDER BY startWallMs")
    suspend fun segments(fromMs: Long, toMs: Long): List<FocusSegmentEntity>

    @Query("UPDATE focus_segments SET subjectName = :name WHERE subjectId = :subjectId")
    suspend fun renameSubjectInHistory(subjectId: Long, name: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE epochDay = :epochDay ORDER BY done, id")
    fun observeForDay(epochDay: Long): Flow<List<TaskEntity>>

    @Query(
        "SELECT epochDay, COUNT(*) AS total, SUM(CASE WHEN done THEN 1 ELSE 0 END) AS done " +
            "FROM tasks WHERE epochDay BETWEEN :fromDay AND :toDay GROUP BY epochDay",
    )
    fun observeCounts(fromDay: Long, toDay: Long): Flow<List<TaskDayCount>>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET done = :done, doneAtMs = :atMs WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, atMs: Long?)

    @Query("UPDATE tasks SET title = :title, subjectId = :subjectId WHERE id = :id")
    suspend fun edit(id: Long, title: String, subjectId: Long?)

    @Query("UPDATE tasks SET epochDay = :epochDay WHERE id = :id")
    suspend fun move(id: Long, epochDay: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DDayDao {
    @Query("SELECT * FROM ddays ORDER BY epochDay, id")
    fun observeAll(): Flow<List<DDayEntity>>

    @Insert
    suspend fun insert(dday: DDayEntity): Long

    @Query("DELETE FROM ddays WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AllowedAppDao {
    @Query("SELECT * FROM allowed_apps ORDER BY label")
    fun observeAll(): Flow<List<AllowedAppEntity>>

    @Upsert
    suspend fun upsert(app: AllowedAppEntity)

    @Query("DELETE FROM allowed_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
