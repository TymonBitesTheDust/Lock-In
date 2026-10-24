package app.lockin.system.timer.di

import android.util.Log
import app.lockin.core.timer.DefaultTimerController
import app.lockin.core.timer.PhaseWakeUpScheduler
import app.lockin.core.timer.StudyRecordSink
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerAnnouncer
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerForegroundHost
import app.lockin.core.timer.TimerSnapshotStore
import app.lockin.system.timer.alarm.AlarmPhaseWakeUpScheduler
import app.lockin.system.timer.clock.SystemTimeSource
import app.lockin.system.timer.notification.NotificationTimerAnnouncer
import app.lockin.system.timer.persistence.DataStoreTimerSnapshotStore
import app.lockin.system.timer.record.LoggingStudyRecordSink
import app.lockin.system.timer.service.ServiceForegroundHost
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the process. Moves to :core:common when a second module needs it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object TimerProvidesModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun timerController(
        time: TimeSource,
        store: TimerSnapshotStore,
        records: StudyRecordSink,
        wakeUps: PhaseWakeUpScheduler,
        host: TimerForegroundHost,
        announcer: TimerAnnouncer,
        @ApplicationScope scope: CoroutineScope,
    ): TimerController = DefaultTimerController(
        time = time,
        store = store,
        records = records,
        wakeUps = wakeUps,
        host = host,
        announcer = announcer,
        scope = scope,
        onError = { Log.e("LockInTimer", "Timer controller error", it) },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TimerBindsModule {

    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource

    @Binds
    abstract fun snapshotStore(impl: DataStoreTimerSnapshotStore): TimerSnapshotStore

    // Replaced by the Room-backed sink when :core:database lands (Room schema step).
    @Binds
    abstract fun recordSink(impl: LoggingStudyRecordSink): StudyRecordSink

    @Binds
    abstract fun wakeUpScheduler(impl: AlarmPhaseWakeUpScheduler): PhaseWakeUpScheduler

    @Binds
    abstract fun foregroundHost(impl: ServiceForegroundHost): TimerForegroundHost

    @Binds
    abstract fun announcer(impl: NotificationTimerAnnouncer): TimerAnnouncer
}
