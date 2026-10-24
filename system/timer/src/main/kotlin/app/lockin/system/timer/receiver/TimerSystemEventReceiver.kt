package app.lockin.system.timer.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lockin.core.timer.TimerController
import app.lockin.system.timer.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Enabled only while a session is active (see [app.lockin.system.timer.service.ServiceForegroundHost]),
 * so an idle Lock In is never woken at boot.
 *
 * - BOOT_COMPLETED: alarms were wiped. Restore detects the reboot, credits time to the last
 *   heartbeat, pauses the session and re-posts the notification. specialUse services may be
 *   started from BOOT_COMPLETED (Android 15 only blocks dataSync, camera, mediaPlayback,
 *   phoneCall, mediaProjection and microphone).
 * - MY_PACKAGE_REPLACED: the update killed the process mid-session.
 * - SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED: switch alarm strategy.
 */
@AndroidEntryPoint
class TimerSystemEventReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TimerController

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> launchAsync(scope) { controller.resyncSystemState() }
        }
    }
}
