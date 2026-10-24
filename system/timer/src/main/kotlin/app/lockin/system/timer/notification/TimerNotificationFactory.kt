package app.lockin.system.timer.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lockin.core.timer.PauseReason
import app.lockin.core.timer.Phase
import app.lockin.core.timer.TimePoint
import app.lockin.core.timer.TimerFormat
import app.lockin.core.timer.TimerMode
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.readoutAt
import app.lockin.system.timer.R
import app.lockin.system.timer.receiver.TimerActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds every timer notification. The ongoing one never needs per-second updates: while running,
 * the system draws the clock itself from `when` + `setUsesChronometer`, so the app is not woken
 * to tick. It is re-posted only when the displayed *structure* changes (pause, phase, subject).
 *
 * Live Updates (Android 16+): the notification meets the promotion rules (ongoing, has a title,
 * standard style, not colorized, channel above MIN) and requests promotion, so it can appear as a
 * status-bar chip showing the running clock. Whether the system promotes it is up to the platform
 * and the user's settings; nothing breaks if it doesn't.
 */
@Singleton
class TimerNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        TimerNotificationChannels.ensure(context)
    }

    fun build(state: TimerState.Active, now: TimePoint): Notification {
        val readout = requireNotNull(state.readoutAt(now)) { "An active session always has a readout" }
        val builder = baseBuilder(TimerNotificationChannels.ONGOING)
            .setContentTitle(context.getString(R.string.timer_title, state.subject.name, phaseLabel(readout.phase)))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)

        val isPomodoro = state.mode is TimerMode.Pomodoro
        if (readout.isRunning) {
            builder.setShowWhen(true).setUsesChronometer(true)
            if (readout.isCountdown) {
                builder.setChronometerCountDown(true).setWhen(now.wallMs + readout.displayMs)
            } else {
                builder.setChronometerCountDown(false).setWhen(now.wallMs - readout.displayMs)
            }
            builder.setContentText(
                when {
                    !isPomodoro -> context.getString(R.string.timer_text_studying)
                    readout.phase == Phase.FOCUS -> context.getString(R.string.timer_text_round, readout.focusRounds + 1)
                    else -> context.getString(R.string.timer_text_next_round, readout.focusRounds + 1)
                },
            )
            builder.addAction(action(R.drawable.ic_timer_pause, R.string.timer_action_pause, TimerActionReceiver.ACTION_PAUSE))
            if (isPomodoro) {
                builder.addAction(action(R.drawable.ic_timer_skip, R.string.timer_action_skip, TimerActionReceiver.ACTION_SKIP))
            }
        } else {
            // No chronometer while paused. Short critical text takes precedence in the Live Update chip.
            builder.setShowWhen(false).setUsesChronometer(false)
                .setShortCriticalText(context.getString(R.string.timer_chip_paused))
            val focused = TimerFormat.clock(readout.sessionFocusMs)
            builder.setContentText(
                when (readout.pauseReason) {
                    PauseReason.AWAITING_NEXT_PHASE ->
                        if (readout.phase == Phase.FOCUS) {
                            context.getString(R.string.timer_text_awaiting_focus)
                        } else {
                            context.getString(R.string.timer_text_awaiting_break)
                        }
                    PauseReason.INTERRUPTED -> context.getString(R.string.timer_text_interrupted, focused)
                    PauseReason.USER, null -> context.getString(R.string.timer_text_paused, focused)
                },
            )
            builder.addAction(action(R.drawable.ic_timer_play, R.string.timer_action_resume, TimerActionReceiver.ACTION_RESUME))
        }
        builder.addAction(action(R.drawable.ic_timer_stop, R.string.timer_action_stop, TimerActionReceiver.ACTION_STOP))
        return builder.build()
    }

    /** Shown for the instant between a cold service start and the snapshot being restored. */
    fun buildRestoring(): Notification = baseBuilder(TimerNotificationChannels.ONGOING)
        .setContentTitle(context.getString(R.string.timer_text_restoring))
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    @SuppressLint("MissingPermission") // Checked by canPost().
    fun notifyOngoing(state: TimerState.Active, now: TimePoint) {
        if (!canPost()) return
        NotificationManagerCompat.from(context).notify(ONGOING_ID, build(state, now))
    }

    /**
     * Fallback when the service may not start (background-start restrictions after the system
     * restarted us cold). The session keeps counting regardless; this tells the user so, and
     * tapping it brings the app forward, which re-promotes the service.
     */
    @SuppressLint("MissingPermission") // Checked by canPost().
    fun postDetached(state: TimerState.Active) {
        if (!canPost()) return
        val notification = baseBuilder(TimerNotificationChannels.ONGOING)
            .setContentTitle(state.subject.name)
            .setContentText(context.getString(R.string.timer_text_detached))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DETACHED_ID, notification)
    }

    fun cancelDetached() {
        NotificationManagerCompat.from(context).cancel(DETACHED_ID)
    }

    @SuppressLint("MissingPermission") // Checked by canPost().
    fun postAlert(title: String, text: String) {
        if (!canPost()) return
        val notification = baseBuilder(TimerNotificationChannels.ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_ID, notification)
    }

    fun phaseLabel(phase: Phase): String = context.getString(
        when (phase) {
            Phase.FOCUS -> R.string.timer_phase_focus
            Phase.SHORT_BREAK -> R.string.timer_phase_short_break
            Phase.LONG_BREAK -> R.string.timer_phase_long_break
        },
    )

    fun formatWallTime(wallMs: Long): String = DateFormat.getTimeFormat(context).format(Date(wallMs))

    private fun baseBuilder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_lockin)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openAppIntent())

    private fun action(@DrawableRes icon: Int, @StringRes label: Int, action: String): NotificationCompat.Action {
        val intent = Intent(context, TimerActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(icon, context.getString(label), pending).build()
    }

    /** Uses the launcher intent so this module does not depend on the app's Activity class. */
    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ONGOING_ID = 41
        const val DETACHED_ID = 42
        const val ALERT_ID = 43
        private const val REQUEST_OPEN_APP = 2001
        private const val ALERT_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
