package app.lockin.core.data

import app.lockin.core.timer.RunState
import app.lockin.core.timer.SubjectRef
import app.lockin.core.timer.TimerCommand
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerMode
import app.lockin.core.timer.TimerState
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One place that decides how "start studying X" behaves, for the timer screen and the planner. */
@Singleton
class SessionLauncher @Inject constructor(
    private val controller: TimerController,
    private val preferences: PreferencesRepository,
    private val subjects: SubjectRepository,
) {
    /**
     * Starts [subjectId] (null = General) in the preferred mode. If that subject is already the
     * active session, resumes it when paused; if another subject is active, that session ends
     * first (its time is kept) and the new one starts.
     */
    suspend fun startOrResume(subjectId: Long?) {
        val current = controller.state.value
        if (current is TimerState.Active) {
            if (current.subject.id == subjectId) {
                if (current.run is RunState.Paused) controller.dispatch(TimerCommand.Resume)
                return
            }
            controller.dispatch(TimerCommand.Stop)
        }
        val prefs = preferences.current()
        val name = subjectId?.let { id -> subjects.subjects.first().firstOrNull { it.id == id }?.name } ?: GENERAL
        val mode = if (prefs.preferPomodoro) prefs.pomodoroMode() else TimerMode.Stopwatch
        controller.dispatch(TimerCommand.Start(UUID.randomUUID().toString(), SubjectRef(subjectId, name), mode))
    }

    companion object {
        const val GENERAL = "General"
        val GENERAL_COLOR: Int = 0xFF8A9099.toInt()
    }
}
