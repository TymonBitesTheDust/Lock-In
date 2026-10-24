package app.lockin.system.timer.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.lockin.core.timer.PhaseWakeUpScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one wake-up at the next Pomodoro boundary. The engine crosses boundaries at their exact
 * instant no matter when we wake, so this only decides *how promptly the user hears about it*.
 *
 * | Exact-alarm grant | Primary                                                 | Backstop if the process dies |
 * |-------------------|---------------------------------------------------------|------------------------------|
 * | yes (or API < 31) | `setExactAndAllowWhileIdle(PendingIntent)`              | (same alarm)                 |
 * | no, API 37+       | `setExactAndAllowWhileIdle(OnAlarmListener)`, in Doze  | inexact `setAndAllowWhileIdle` |
 * | no, API < 37      | `setExact(OnAlarmListener)`, deferred by Doze           | inexact `setAndAllowWhileIdle` |
 *
 * Listener alarms need no exact-alarm permission (they die with the process, which the foreground
 * service keeps alive during a session). Both paths deliver [TimerCommand.Reconcile], which is
 * idempotent, so a double delivery is harmless.
 */
@Singleton
class AlarmPhaseWakeUpScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhaseWakeUpScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: AlarmManager.OnAlarmListener? = null

    @Synchronized
    override fun sync(atElapsedMs: Long?, onWakeUp: () -> Unit) {
        cancelAll()
        if (atElapsedMs == null) return

        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, backstopIntent())
            return
        }

        val alarmListener = AlarmManager.OnAlarmListener { onWakeUp() }
        listener = alarmListener
        if (Build.VERSION.SDK_INT >= API_37) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    atElapsedMs,
                    LISTENER_TAG,
                    ContextCompat.getMainExecutor(context),
                    alarmListener,
                )
            } catch (e: SecurityException) {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, LISTENER_TAG, alarmListener, mainHandler)
            }
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, LISTENER_TAG, alarmListener, mainHandler)
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, backstopIntent())
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun cancelAll() {
        alarmManager.cancel(backstopIntent())
        listener?.let { alarmManager.cancel(it) }
        listener = null
    }

    private fun backstopIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_WAKE_UP,
        Intent(context, TimerWakeUpReceiver::class.java).setAction(TimerWakeUpReceiver.ACTION_WAKE_UP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** Android 17. Adds the listener variant of setExactAndAllowWhileIdle that fires during Doze. */
        const val API_37 = 37
        const val LISTENER_TAG = "lockin:phase-boundary"
        const val REQUEST_WAKE_UP = 1001
    }
}
