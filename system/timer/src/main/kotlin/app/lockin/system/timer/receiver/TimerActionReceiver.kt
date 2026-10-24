package app.lockin.system.timer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lockin.core.timer.TimerCommand
import app.lockin.core.timer.TimerController
import app.lockin.system.timer.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Notification (and later widget) buttons. They talk to the controller, not the service: the
 * service only mirrors state, so a button works even if the service is being restarted.
 * A notification tap also grants the app a short window to start a foreground service.
 */
@AndroidEntryPoint
class TimerActionReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TimerController

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val command = when (intent.action) {
            ACTION_PAUSE -> TimerCommand.Pause
            ACTION_RESUME -> TimerCommand.Resume
            ACTION_SKIP -> TimerCommand.SkipPhase
            ACTION_STOP -> TimerCommand.Stop
            else -> return
        }
        launchAsync(scope) { controller.dispatch(command) }
    }

    companion object {
        const val ACTION_PAUSE = "app.lockin.timer.action.PAUSE"
        const val ACTION_RESUME = "app.lockin.timer.action.RESUME"
        const val ACTION_SKIP = "app.lockin.timer.action.SKIP"
        const val ACTION_STOP = "app.lockin.timer.action.STOP"
    }
}
