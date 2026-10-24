package app.lockin.core.timer

import kotlin.math.abs

/**
 * One reading of the three clocks the timer cares about, taken at the same instant.
 *
 * - [elapsedMs] is monotonic and keeps counting through deep sleep (`SystemClock.elapsedRealtime()`).
 *   **Every duration in Lock In is computed from this clock**, so changing the system time, timezone
 *   or DST can never add or remove study time.
 * - [wallMs] is the wall clock (`System.currentTimeMillis()`). It is only used to place segments on a
 *   calendar (heatmaps, "today" totals), never to measure how long something took.
 * - [bootCount] identifies the boot that [elapsedMs] belongs to (`Settings.Global.BOOT_COUNT`).
 *   Elapsed readings from different boots cannot be compared.
 */
data class TimePoint(
    val elapsedMs: Long,
    val wallMs: Long,
    val bootCount: Int,
) {
    /** The same boot, [deltaMs] later on both clocks. */
    fun shiftedBy(deltaMs: Long): TimePoint =
        copy(elapsedMs = elapsedMs + deltaMs, wallMs = wallMs + deltaMs)

    /**
     * True when [earlier] was taken during the current boot, so `elapsedMs - earlier.elapsedMs`
     * is a real duration.
     *
     * Uses the boot counter when both readings have one. Otherwise falls back to checking that
     * the two clocks advanced together; a large disagreement means the monotonic clock restarted.
     * The fallback also trips if the user moves the wall clock by more than the tolerance, which
     * is the safe direction to fail: the session pauses at its last checkpoint instead of
     * crediting time that may not have happened.
     */
    fun isSameBootAs(earlier: TimePoint): Boolean {
        if (elapsedMs < earlier.elapsedMs) return false
        if (bootCount != UNKNOWN_BOOT && earlier.bootCount != UNKNOWN_BOOT) {
            return bootCount == earlier.bootCount
        }
        val drift = (wallMs - earlier.wallMs) - (elapsedMs - earlier.elapsedMs)
        return abs(drift) <= CLOCK_DRIFT_TOLERANCE_MS
    }

    companion object {
        const val UNKNOWN_BOOT = -1
        const val CLOCK_DRIFT_TOLERANCE_MS = 5 * 60 * 1000L
    }
}

/** Reads all three clocks at once. The Android implementation lives in `:system:timer`. */
fun interface TimeSource {
    fun now(): TimePoint
}
