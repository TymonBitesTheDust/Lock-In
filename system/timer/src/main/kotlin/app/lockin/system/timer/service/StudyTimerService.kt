package app.lockin.system.timer.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.lockin.core.timer.RunState
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerCommand
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.ignoringHeartbeat
import app.lockin.system.timer.di.ApplicationScope
import app.lockin.system.timer.notification.TimerNotificationFactory
import app.lockin.system.timer.receiver.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process in the foreground while a session is active and mirrors timer state into the
 * ongoing notification. Owns **no** timer state and does **no** time-keeping: if this service is
 * killed and never comes back, the session's time is still exact (see `TimerEngine`).
 *
 * Its jobs:
 * 1. Answer every `startForegroundService()` with `startForeground()` immediately, before any suspension.
 * 2. Re-render the notification when the displayed structure changes (never per second).
 * 3. Send heartbeats while running (each minute, on screen-off and on shutdown) so a reboot loses
 *    at most the time since the last one.
 * 4. Stop itself when the session ends.
 *
 * Later sprints attach the app blocker and presence publisher to this same lifecycle.
 */
@AndroidEntryPoint
class StudyTimerService : LifecycleService() {

    @Inject lateinit var controller: TimerController
    @Inject lateinit var notifications: TimerNotificationFactory
    @Inject lateinit var time: TimeSource
    @Inject lateinit var host: ServiceForegroundHost

    @Inject @field:ApplicationScope
    lateinit var appScope: CoroutineScope

    private var observing = false
    private val renderRequests = MutableStateFlow(0)

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Persist "alive until now" right before the likeliest moments of losing the process.
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN ->
                    launchAsync(appScope) { controller.dispatch(TimerCommand.Heartbeat) }
                // The chronometer is anchored to wall time, so redraw it when the wall clock moves.
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                    renderRequests.update { it + 1 }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        host.onServiceCreated()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // System broadcasts are still delivered to non-exported receivers.
        ContextCompat.registerReceiver(this, systemEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // A null intent is a START_STICKY restart after the process was killed. The controller may
        // still be restoring, in which case we show a placeholder and correct it moments later.
        val current = controller.state.value
        val notification = if (current is TimerState.Active) {
            notifications.build(current, time.now())
        } else {
            notifications.buildRestoring()
        }
        if (!enterForeground(notification)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            observeState()
        }
        return START_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, TimerNotificationFactory.ONGOING_ID, notification, foregroundServiceType())
        notifications.cancelDetached()
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException, or a SecurityException if the manifest type is wrong.
        Log.w(TAG, "Could not enter the foreground", e)
        (controller.state.value as? TimerState.Active)?.let(notifications::postDetached)
        false
    }

    private fun observeState() {
        lifecycleScope.launch {
            controller.awaitReady()
            controller.state
                .distinctUntilChanged { old, new -> old.ignoringHeartbeat() == new.ignoringHeartbeat() }
                .combine(renderRequests) { state, _ -> state }
                .collect { state ->
                    when (state) {
                        is TimerState.Active -> notifications.notifyOngoing(state, time.now())
                        TimerState.Idle -> stopTimerService()
                    }
                }
        }
        lifecycleScope.launch {
            controller.awaitReady()
            controller.state
                .map { (it as? TimerState.Active)?.run is RunState.Running }
                .distinctUntilChanged()
                .collectLatest { running ->
                    if (running) {
                        while (true) {
                            delay(HEARTBEAT_INTERVAL_MS)
                            controller.dispatch(TimerCommand.Heartbeat)
                        }
                    }
                }
        }
    }

    /** API 35+. specialUse has no time limit today; if one is ever applied, degrade instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (controller.state.value as? TimerState.Active)?.let(notifications::postDetached)
        stopTimerService()
    }

    override fun onDestroy() {
        unregisterReceiver(systemEvents)
        host.onServiceDestroyed()
        super.onDestroy()
    }

    private fun stopTimerService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private companion object {
        const val TAG = "StudyTimerService"

        /** Bounds what a reboot can lose while the screen is on. Screen-off/shutdown heartbeats cover the rest. */
        const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}
