package app.lockin.system.timer.clock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.lockin.core.timer.TimePoint
import app.lockin.core.timer.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTimeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : TimeSource {

    // Constant for the life of a boot, and a process never outlives its boot.
    private val bootCount: Int by lazy {
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, TimePoint.UNKNOWN_BOOT)
        }.getOrDefault(TimePoint.UNKNOWN_BOOT)
    }

    override fun now(): TimePoint = TimePoint(
        elapsedMs = SystemClock.elapsedRealtime(),
        wallMs = System.currentTimeMillis(),
        bootCount = bootCount,
    )
}
