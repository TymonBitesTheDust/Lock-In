package app.lockin.system.timer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lockin.core.timer.TimerCommand
import app.lockin.core.timer.TimerController
import app.lockin.system.timer.di.ApplicationScope
import app.lockin.system.timer.receiver.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Delivery point for the PendingIntent wake-up. Also restores the session if the process was dead. */
@AndroidEntryPoint
class TimerWakeUpReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TimerController

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE_UP) return
        launchAsync(scope) { controller.dispatch(TimerCommand.Reconcile) }
    }

    companion object {
        const val ACTION_WAKE_UP = "app.lockin.timer.action.WAKE_UP"
    }
}
