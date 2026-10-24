package app.lockin.system.timer.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.lockin.core.timer.TimerForegroundHost
import app.lockin.core.timer.TimerState
import app.lockin.system.timer.notification.TimerNotificationFactory
import app.lockin.system.timer.receiver.TimerSystemEventReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "A session is active" ⇒ "the service is running". The controller calls this after every commit;
 * it is cheap when nothing needs to change.
 */
@Singleton
class ServiceForegroundHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: TimerNotificationFactory,
) : TimerForegroundHost {

    @Volatile
    var isServiceRunning: Boolean = false
        private set

    @Volatile
    private var systemEventsEnabled: Boolean? = null

    internal fun onServiceCreated() {
        isServiceRunning = true
    }

    internal fun onServiceDestroyed() {
        isServiceRunning = false
    }

    override fun ensureForeground(state: TimerState.Active, force: Boolean) {
        setSystemEventsEnabled(true)
        if (isServiceRunning && !force) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, StudyTimerService::class.java))
            notifications.cancelDetached()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
            // Typical cause: the process was restarted cold by an inexact alarm or WorkManager.
            // The session keeps counting; the next user interaction re-promotes the service.
            Log.w(TAG, "Foreground service start not allowed right now", e)
            notifications.postDetached(state)
        }
    }

    override fun release() {
        setSystemEventsEnabled(false)
        notifications.cancelDetached()
    }

    private fun setSystemEventsEnabled(enabled: Boolean) {
        if (systemEventsEnabled == enabled) return
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, TimerSystemEventReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        systemEventsEnabled = enabled
    }

    private companion object {
        const val TAG = "LockInTimerHost"
    }
}
