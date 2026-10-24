package app.lockin.core.data

import app.lockin.core.database.StudyRecordDao
import app.lockin.core.database.SubjectDao
import app.lockin.core.database.SubjectEntity
import app.lockin.core.model.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubjectRepository @Inject constructor(
    private val dao: SubjectDao,
    private val records: StudyRecordDao,
) {
    /** Subjects the user can pick, in their order. */
    val subjects: Flow<List<Subject>> = dao.observeActive().map { list -> list.map { it.toModel() } }

    /** Every subject ever created (archived too), for colouring history and stats. */
    val allById: Flow<Map<Long, Subject>> = dao.observeAll().map { list -> list.associate { it.id to it.toModel() } }

    suspend fun add(name: String, color: Int): Long = dao.insert(
        SubjectEntity(
            name = name.trim(),
            color = color,
            sortOrder = dao.maxSortOrder() + 1,
            createdAtMs = System.currentTimeMillis(),
        ),
    )

    /** Renaming also renames past segments, so history and stats show the new name. */
    suspend fun update(id: Long, name: String, color: Int) {
        val existing = dao.get(id) ?: return
        val trimmed = name.trim()
        dao.update(existing.copy(name = trimmed, color = color))
        if (trimmed != existing.name) records.renameSubjectInHistory(id, trimmed)
    }

    /** Hides the subject from pickers. Its study history stays. */
    suspend fun archive(id: Long) = dao.archive(id)

    private fun SubjectEntity.toModel() = Subject(id = id, name = name, color = color)
}
