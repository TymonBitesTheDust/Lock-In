package app.lockin.system.presence

import android.util.Log
import app.lockin.core.data.StudyLogRepository
import app.lockin.core.model.StudyStats
import app.lockin.core.network.Account
import app.lockin.core.network.AuthRepository
import app.lockin.core.network.GroupRepository
import app.lockin.core.network.MemberPresence
import app.lockin.core.network.PresenceState
import app.lockin.core.timer.Phase
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.readoutAt
import app.lockin.core.timer.unrecordedFocusMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes this user's live status for their study groups: a projection of timer state and stored
 * totals (rule 2), written only when something a viewer would see changes, never per second.
 */
@Singleton
class PresencePublisher @Inject constructor(
    private val auth: AuthRepository,
    private val groups: GroupRepository,
    private val controller: TimerController,
    private val studyLog: StudyLogRepository,
    private val time: TimeSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (started || !auth.isAvailable) return
        started = true
        scope.launch {
            controller.awaitReady()
            auth.account
                .flatMapLatest { account -> if (account == null) emptyFlow() else snapshots(account) }
                .distinctUntilChanged { a, b -> a.copy(updatedAtMs = 0) == b.copy(updatedAtMs = 0) }
                .debounce(1_000)
                .collect { presence ->
                    runCatching { groups.publishPresence(presence) }.onFailure { Log.w(TAG, "publish failed", it) }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun snapshots(account: Account): Flow<MemberPresence> =
        combine(studyLog.clock, studyLog.today) { clock, today -> clock to today }
            .distinctUntilChanged()
            .flatMapLatest { (clock, today) ->
                val weekStart = StudyStats.weekStart(today)
                combine(studyLog.segments(clock.startOf(weekStart), clock.endOf(today)), controller.state) { segments, state ->
                    val now = time.now()
                    val readout = state.readoutAt(now)
                    val active = state as? TimerState.Active
                    val presenceState = when {
                        active == null || readout == null -> PresenceState.IDLE
                        !readout.isRunning -> PresenceState.PAUSED
                        readout.phase == Phase.FOCUS -> PresenceState.STUDYING
                        else -> PresenceState.BREAK
                    }
                    val runningSince = if (presenceState == PresenceState.STUDYING) {
                        // Rounded so tiny clock jitter between emissions doesn't cause a rewrite.
                        ((now.wallMs - state.unrecordedFocusMs(now)) / 1_000) * 1_000
                    } else {
                        null
                    }
                    MemberPresence(
                        uid = account.uid,
                        displayName = account.displayName,
                        state = presenceState,
                        subject = active?.subject?.name,
                        runningSinceMs = runningSince,
                        todayKey = today.toString(),
                        todayMs = StudyStats.total(segments, clock.startOf(today), clock.endOf(today)),
                        weekKey = weekStart.toString(),
                        weekMs = StudyStats.total(segments, clock.startOf(weekStart), clock.endOf(today)),
                        updatedAtMs = now.wallMs,
                    )
                }
            }

    private companion object {
        const val TAG = "LockInPresence"
    }
}
