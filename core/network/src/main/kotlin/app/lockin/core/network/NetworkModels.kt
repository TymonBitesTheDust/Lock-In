package app.lockin.core.network

data class Account(
    val uid: String,
    val email: String?,
    val displayName: String,
)

data class StudyGroup(
    val id: String,
    val name: String,
    val code: String,
    val ownerId: String,
    val memberIds: List<String>,
)

enum class PresenceState { STUDYING, BREAK, PAUSED, IDLE }

/**
 * What a member publishes. Never written per second: while studying, viewers add
 * `now - runningSinceMs` themselves, so the clock ticks on their screen for free.
 */
data class MemberPresence(
    val uid: String,
    val displayName: String,
    val state: PresenceState,
    val subject: String?,
    /** Wall-clock start of the focus stretch not yet included in [todayMs]/[weekMs]. */
    val runningSinceMs: Long?,
    val todayKey: String,
    /** Stored focus time for [todayKey], excluding the running stretch. */
    val todayMs: Long,
    val weekKey: String,
    val weekMs: Long,
    val updatedAtMs: Long,
) {
    private fun liveMs(nowMs: Long): Long =
        if (state == PresenceState.STUDYING && runningSinceMs != null) (nowMs - runningSinceMs).coerceIn(0, MAX_LIVE_MS) else 0

    fun todayTotal(currentDayKey: String, nowMs: Long): Long = (if (todayKey == currentDayKey) todayMs else 0) + liveMs(nowMs)

    fun weekTotal(currentWeekKey: String, nowMs: Long): Long = (if (weekKey == currentWeekKey) weekMs else 0) + liveMs(nowMs)

    private companion object {
        /** A stale "studying" record (phone died) never adds more than 12 hours. */
        const val MAX_LIVE_MS = 12 * 3_600_000L
    }
}

data class ChatMessage(
    val id: String,
    val uid: String,
    val name: String,
    val text: String,
    val sentAtMs: Long,
)

data class Nudge(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val groupName: String,
    val sentAtMs: Long,
)

/** Thrown with a message that can be shown to the user as is. */
class GroupException(message: String) : Exception(message)
