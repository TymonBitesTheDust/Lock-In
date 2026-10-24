package app.lockin.system.timer.receiver

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs suspending work from `onReceive` while keeping the broadcast (and its wakelock, for alarm
 * broadcasts) alive until the work finishes. Keep the work short: the system allows ~10 seconds.
 */
internal fun BroadcastReceiver.launchAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
