package app.lockin.system.blocker

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.lockin.core.data.AllowedAppsRepository
import app.lockin.core.data.BlockerPermissions
import app.lockin.core.data.InstalledApps
import app.lockin.core.data.PreferencesRepository
import app.lockin.core.timer.Phase
import app.lockin.core.timer.RunState
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.readoutAt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces the allowed-apps list while a focus phase is running. Another projection of timer
 * state (architecture rule 2): paused sessions, breaks and idle never block anything.
 *
 * Started from Application.onCreate. The timer's foreground service keeps the process alive for
 * the whole session, so this loop lives exactly as long as there is something to enforce.
 */
@Singleton
class AppBlocker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TimerController,
    private val preferences: PreferencesRepository,
    private val allowedApps: AllowedAppsRepository,
    private val installedApps: InstalledApps,
    private val permissions: BlockerPermissions,
    private val detector: ForegroundAppDetector,
    private val overlay: BlockOverlay,
    private val time: TimeSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.awaitReady()
            combine(
                controller.state,
                preferences.preferences
                    .map { BlockSettings(it.blockingEnabled, LockScreenStyle(it.accent, it.themeMode)) }
                    .distinctUntilChanged(),
                allowedApps.allowedPackages,
            ) { state, settings, allowed -> Triple(state, settings, allowed) }
                .collectLatest { (state, settings, allowed) ->
                    if (!settings.enabled || state !is TimerState.Active || state.run !is RunState.Running) {
                        hideOverlay()
                    } else {
                        try {
                            enforce(state, allowed, settings.style)
                        } finally {
                            withContext(kotlinx.coroutines.NonCancellable) { hideOverlay() }
                        }
                    }
                }
        }
    }

    /**
     * [userAllowed] are the apps the user ticked; they are also the shortcuts on the lock screen.
     * Lock In, the dialer, Settings and the system UI are never blocked. The home screen is: leaving
     * Lock In during focus lands on the lock screen, which is how you reach the allowed apps.
     */
    private suspend fun enforce(state: TimerState.Active, userAllowed: Set<String>, style: LockScreenStyle) {
        val allowed = userAllowed + installedApps.alwaysAllowed()
        val power = context.getSystemService(PowerManager::class.java)
        detector.reset()
        var checks = 0
        var hasAccess = permissions.hasUsageAccess()
        while (true) {
            if (checks++ % 10 == 0) hasAccess = permissions.hasUsageAccess()
            val readout = state.readoutAt(time.now())
            val focusing = readout != null && readout.isRunning && readout.phase == Phase.FOCUS
            when {
                !focusing || !hasAccess -> hideOverlay()
                power != null && !power.isInteractive -> Unit
                else -> {
                    val front = runCatching { detector.current() }.onFailure { Log.w(TAG, "usage events", it) }.getOrNull()
                    if (front != null && front !in allowed) {
                        // Just after a tap on the lock screen, the app it opened may not be in front yet.
                        if (!overlay.isHandingOff) {
                            withContext(Dispatchers.Main) { overlay.show(state.subject.name, userAllowed, style) }
                        }
                    } else {
                        hideOverlay()
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    private suspend fun hideOverlay() {
        if (overlay.isShowing) withContext(Dispatchers.Main) { overlay.hide() }
    }

    private data class BlockSettings(val enabled: Boolean, val style: LockScreenStyle)

    private companion object {
        const val TAG = "LockInBlocker"
        const val POLL_MS = 800L
    }
}
