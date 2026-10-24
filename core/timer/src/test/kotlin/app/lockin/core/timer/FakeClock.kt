package app.lockin.core.timer

/** A controllable device clock: sleep, wall-clock jumps and reboots on demand. */
class FakeClock(
    private var elapsedMs: Long = 1_000_000L,
    private var wallMs: Long = 1_760_000_000_000L,
    private var bootCount: Int = 7,
) : TimeSource {

    override fun now(): TimePoint = TimePoint(elapsedMs, wallMs, bootCount)

    /** Real time passes (screen on or off; elapsedRealtime counts deep sleep). */
    fun advance(ms: Long) {
        elapsedMs += ms
        wallMs += ms
    }

    fun advanceMinutes(minutes: Int) = advance(minutes * MINUTE)

    /** The user changes the system time. Elapsed time is unaffected. */
    fun jumpWallClock(deltaMs: Long) {
        wallMs += deltaMs
    }

    /** Device is off for [offForMs], then boots; the monotonic clock restarts near zero. */
    fun reboot(offForMs: Long, uptimeAfterBootMs: Long = 30_000L, countBoots: Boolean = true) {
        wallMs += offForMs + uptimeAfterBootMs
        elapsedMs = uptimeAfterBootMs
        if (countBoots) bootCount += 1 else bootCount = TimePoint.UNKNOWN_BOOT
    }

    fun forgetBootCount() {
        bootCount = TimePoint.UNKNOWN_BOOT
    }

    companion object {
        const val MINUTE = 60_000L
    }
}
