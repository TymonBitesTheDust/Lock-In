package app.lockin.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        SubjectEntity::class,
        FocusSegmentEntity::class,
        StudySessionEntity::class,
        TaskEntity::class,
        DDayEntity::class,
        AllowedAppEntity::class,
    ],
    version = 1,
    // Turn on with a schemas/ directory before the first schema change ships, to test migrations.
    exportSchema = false,
)
abstract class LockInDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun studyRecordDao(): StudyRecordDao
    abstract fun taskDao(): TaskDao
    abstract fun ddayDao(): DDayDao
    abstract fun allowedAppDao(): AllowedAppDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LockInDatabase =
        Room.databaseBuilder(context, LockInDatabase::class.java, "lockin.db").build()

    @Provides fun subjectDao(db: LockInDatabase): SubjectDao = db.subjectDao()

    @Provides fun studyRecordDao(db: LockInDatabase): StudyRecordDao = db.studyRecordDao()

    @Provides fun taskDao(db: LockInDatabase): TaskDao = db.taskDao()

    @Provides fun ddayDao(db: LockInDatabase): DDayDao = db.ddayDao()

    @Provides fun allowedAppDao(db: LockInDatabase): AllowedAppDao = db.allowedAppDao()
}
