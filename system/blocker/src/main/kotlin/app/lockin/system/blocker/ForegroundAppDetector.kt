package app.lockin.system.blocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which app is in front, from the usage-events stream (needs usage access). Reads only the events
 * since the previous poll, so each call is cheap. No AccessibilityService: Play restricts those to
 * accessibility tools, and polling only runs while a focus phase is on screen.
 */
@Singleton
class ForegroundAppDetector @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private var lastPackage: String? = null
    private var lastQueryEnd = 0L

    fun current(): String? {
        val manager = usageStats ?: return null
        val now = System.currentTimeMillis()
        val from = if (lastQueryEnd == 0L) now - LOOKBACK_MS else lastQueryEnd - OVERLAP_MS
        val events = manager.queryEvents(from, now) ?: return lastPackage
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION") // Same value as ACTIVITY_RESUMED (API 29+); works on every API level.
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPackage = event.packageName
        }
        lastQueryEnd = now
        return lastPackage
    }

    fun reset() {
        lastPackage = null
        lastQueryEnd = 0L
    }

    private companion object {
        const val LOOKBACK_MS = 60 * 60_000L
        const val OVERLAP_MS = 2_000L
    }
}
