package app.lockin

import android.app.Application
import app.lockin.core.timer.TimerController
import app.lockin.system.blocker.AppBlocker
import app.lockin.system.presence.NudgeListener
import app.lockin.system.presence.PresencePublisher
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

    /** Watches timer state and enforces the allowed-apps list during focus phases. */
    @Inject lateinit var appBlocker: AppBlocker

    /** Live status for study groups, and nudges from friends (no-ops until Firebase is configured). */
    @Inject lateinit var presencePublisher: PresencePublisher

    @Inject lateinit var nudgeListener: NudgeListener

    override fun onCreate() {
        super.onCreate()
        appBlocker.start()
        presencePublisher.start()
        nudgeListener.start()
    }
}
