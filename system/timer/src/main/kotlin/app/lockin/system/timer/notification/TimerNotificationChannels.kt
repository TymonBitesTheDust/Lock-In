package app.lockin.system.timer.notification

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import app.lockin.system.timer.R

object TimerNotificationChannels {

    /** The ongoing timer. Default importance so it sits with normal notifications, but silent. */
    const val ONGOING = "timer_ongoing"

    /** Phase changes and interruptions. Plays the channel sound; the system plays it, not our process,
     *  so Android 17's background-audio hardening does not apply. */
    const val ALERTS = "timer_alerts"

    fun ensure(context: Context) {
        val ongoing = NotificationChannelCompat.Builder(ONGOING, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.timer_channel_ongoing))
            .setDescription(context.getString(R.string.timer_channel_ongoing_description))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        val alerts = NotificationChannelCompat.Builder(ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.timer_channel_alerts))
            .setDescription(context.getString(R.string.timer_channel_alerts_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(ongoing, alerts))
    }
}
