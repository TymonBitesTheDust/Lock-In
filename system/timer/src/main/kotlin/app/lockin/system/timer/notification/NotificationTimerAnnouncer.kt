package app.lockin.system.timer.notification

import android.content.Context
import app.lockin.core.timer.Phase
import app.lockin.core.timer.TimerAnnouncer
import app.lockin.core.timer.TimerEffect
import app.lockin.core.timer.TimerState
import app.lockin.system.timer.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationTimerAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: TimerNotificationFactory,
) : TimerAnnouncer {

    override fun phaseChanged(change: TimerEffect.PhaseChanged, state: TimerState) {
        val active = state as? TimerState.Active ?: return
        val (title, text) = when {
            change.to == Phase.FOCUS && change.nextStartsAutomatically ->
                R.string.timer_alert_break_over to context.getString(R.string.timer_alert_focus_started, active.subject.name)
            change.to == Phase.FOCUS ->
                R.string.timer_alert_break_over to context.getString(R.string.timer_alert_focus_waiting)
            change.nextStartsAutomatically ->
                R.string.timer_alert_round_done to context.getString(
                    R.string.timer_alert_break_started,
                    notifications.phaseLabel(change.to),
                )
            else ->
                R.string.timer_alert_round_done to context.getString(R.string.timer_alert_break_waiting)
        }
        notifications.postAlert(context.getString(title), text)
    }

    override fun interrupted(event: TimerEffect.Interrupted, state: TimerState) {
        notifications.postAlert(
            title = context.getString(R.string.timer_alert_interrupted_title),
            text = context.getString(
                R.string.timer_alert_interrupted_text,
                notifications.formatWallTime(event.creditedUntilWallMs),
            ),
        )
    }
}
