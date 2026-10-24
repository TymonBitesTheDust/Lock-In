package app.lockin

import android.app.Application
import app.lockin.core.timer.TimerController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LockInApplication : Application() {

    /**
     * Injected eagerly on purpose. Whatever starts the process (launcher, notification button,
     * alarm, boot, WorkManager), the controller is created, restores the persisted session and
     * re-derives its alarms and foreground service before anything else asks.
     */
    @Inject lateinit var timerController: TimerController
}
