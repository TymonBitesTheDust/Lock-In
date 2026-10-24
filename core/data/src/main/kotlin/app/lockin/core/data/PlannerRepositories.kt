package app.lockin.core.data

import app.lockin.core.database.DDayDao
import app.lockin.core.database.DDayEntity
import app.lockin.core.database.TaskDao
import app.lockin.core.database.TaskEntity
import app.lockin.core.model.DDay
import app.lockin.core.model.StudyTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class DayTaskCount(val total: Int, val done: Int)

@Singleton
class TaskRepository @Inject constructor(
    private val dao: TaskDao,
) {
    fun tasksFor(day: LocalDate): Flow<List<StudyTask>> =
        dao.observeForDay(day.toEpochDay()).map { list -> list.map { it.toModel() } }

    fun countsBetween(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayTaskCount>> =
        dao.observeCounts(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.associate { LocalDate.ofEpochDay(it.epochDay) to DayTaskCount(it.total, it.done) }
        }

    suspend fun add(title: String, day: LocalDate, subjectId: Long?): Long = dao.insert(
        TaskEntity(title = title.trim(), epochDay = day.toEpochDay(), subjectId = subjectId, createdAtMs = System.currentTimeMillis()),
    )

    suspend fun setDone(id: Long, done: Boolean) = dao.setDone(id, done, if (done) System.currentTimeMillis() else null)

    suspend fun edit(id: Long, title: String, subjectId: Long?) = dao.edit(id, title.trim(), subjectId)

    suspend fun moveTo(id: Long, day: LocalDate) = dao.move(id, day.toEpochDay())

    suspend fun delete(id: Long) = dao.delete(id)

    private fun TaskEntity.toModel() = StudyTask(
        id = id,
        title = title,
        date = LocalDate.ofEpochDay(epochDay),
        subjectId = subjectId,
        done = done,
    )
}

@Singleton
class DDayRepository @Inject constructor(
    private val dao: DDayDao,
) {
    /** Ordered by date. */
    val ddays: Flow<List<DDay>> = dao.observeAll().map { list ->
        list.map { DDay(id = it.id, title = it.title, date = LocalDate.ofEpochDay(it.epochDay)) }
    }

    suspend fun add(title: String, date: LocalDate): Long =
        dao.insert(DDayEntity(title = title.trim(), epochDay = date.toEpochDay(), createdAtMs = System.currentTimeMillis()))

    suspend fun delete(id: Long) = dao.delete(id)
}
