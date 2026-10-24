package app.lockin.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.StudyLogRepository
import app.lockin.core.model.StudyStats
import app.lockin.core.network.Account
import app.lockin.core.network.AuthRepository
import app.lockin.core.network.ChatMessage
import app.lockin.core.network.GroupException
import app.lockin.core.network.GroupRepository
import app.lockin.core.network.MemberPresence
import app.lockin.core.network.PresenceState
import app.lockin.core.network.StudyGroup
import app.lockin.core.timer.Phase
import app.lockin.core.timer.TimeSource
import app.lockin.core.timer.TimerController
import app.lockin.core.timer.TimerState
import app.lockin.core.timer.readoutAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class GroupCard(val id: String, val name: String, val memberCount: Int, val studyingNow: Int)

data class MemberRow(
    val uid: String,
    val name: String,
    val state: PresenceState,
    val subject: String?,
    val todayMs: Long,
    val isMe: Boolean,
    val canNudge: Boolean,
)

data class RankRow(val rank: Int, val name: String, val ms: Long, val isMe: Boolean)

data class MessageRow(val id: String, val name: String, val text: String, val time: String, val isMine: Boolean)

data class GroupDetail(
    val id: String,
    val name: String,
    val code: String,
    val members: List<MemberRow>,
    val rankingToday: List<RankRow>,
    val rankingWeek: List<RankRow>,
    val messages: List<MessageRow>,
)

data class GroupsUi(
    val available: Boolean = false,
    val loading: Boolean = true,
    val account: Account? = null,
    val groups: List<GroupCard> = emptyList(),
    val detail: GroupDetail? = null,
    /** True during a running focus phase: chat is locked to protect focus. */
    val chatLocked: Boolean = false,
)

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val repository: GroupRepository,
    private val controller: TimerController,
    private val time: TimeSource,
    studyLog: StudyLogRepository,
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val lastNudged = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)

    /** One-off feedback (errors, confirmations) for a snackbar. */
    val message: StateFlow<String?> = _message

    private val timerState: Flow<TimerState> = flow {
        controller.awaitReady()
        emitAll(controller.state)
    }

    private data class Keys(val dayKey: String, val weekKey: String)

    private val keys: Flow<Keys> = studyLog.today.map { Keys(it.toString(), StudyStats.weekStart(it).toString()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val groups: Flow<List<StudyGroup>> = auth.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else repository.myGroups(account.uid)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val presence: Flow<List<MemberPresence>> = groups.flatMapLatest { list ->
        repository.presence(list.flatMap { it.memberIds }.distinct())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messages: Flow<List<ChatMessage>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.messages(id)
    }

    /** Ticks every second so members' clocks run live; each tick is a pure recomputation. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val ui: StateFlow<GroupsUi> = if (!auth.isAvailable) {
        MutableStateFlow(GroupsUi(available = false, loading = false))
    } else {
        combine(
            combine(auth.account, groups, selectedId) { a, g, s -> Triple(a, g, s) },
            combine(presence, messages, lastNudged) { p, m, n -> Triple(p, m, n) },
            timerState,
            keys,
            ticker,
        ) { (account, groupList, selected), (members, chat, nudged), timer, k, nowMs ->
            build(account, groupList, selected, members, chat, nudged, timer, k, nowMs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUi(available = true))
    }

    fun select(groupId: String?) {
        selectedId.value = groupId
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun signIn(email: String, password: String) = launchAction { auth.signIn(email, password) }

    fun signUp(name: String, email: String, password: String) = launchAction {
        if (name.isBlank()) throw GroupException("Add a name your group will see.")
        auth.signUp(name, email, password)
    }

    fun resetPassword(email: String) = launchAction {
        if (email.isBlank()) throw GroupException("Enter your email first.")
        auth.sendPasswordReset(email)
        _message.value = "Password reset email sent."
    }

    fun signOut() {
        selectedId.value = null
        auth.signOut()
    }

    fun create(name: String) = launchAction {
        val account = auth.currentAccount() ?: return@launchAction
        val group = repository.create(name, account)
        selectedId.value = group.id
        _message.value = "Group created. Share code ${group.code} with friends."
    }

    fun join(code: String) = launchAction {
        val account = auth.currentAccount() ?: return@launchAction
        selectedId.value = repository.join(code, account)
    }

    fun leave() = launchAction {
        val account = auth.currentAccount() ?: return@launchAction
        val id = selectedId.value ?: return@launchAction
        repository.leave(id, account.uid)
        selectedId.value = null
    }

    fun nudge(uid: String) = launchAction {
        val account = auth.currentAccount() ?: return@launchAction
        val group = ui.value.detail ?: return@launchAction
        repository.nudge(uid, account, group.name)
        lastNudged.value = lastNudged.value + (uid to System.currentTimeMillis())
        _message.value = "Nudge sent."
    }

    fun send(text: String) = launchAction {
        val account = auth.currentAccount() ?: return@launchAction
        val id = selectedId.value ?: return@launchAction
        if (ui.value.chatLocked) throw GroupException("Chat opens on your break.")
        repository.send(id, account, text)
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: GroupException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Something went wrong. Check your connection and try again."
            } finally {
                _busy.value = false
            }
        }
    }

    private fun build(
        account: Account?,
        groupList: List<StudyGroup>,
        selected: String?,
        members: List<MemberPresence>,
        chat: List<ChatMessage>,
        nudged: Map<String, Long>,
        timer: TimerState,
        keys: Keys,
        nowMs: Long,
    ): GroupsUi {
        val readout = timer.readoutAt(time.now())
        val chatLocked = readout != null && readout.isRunning && readout.phase == Phase.FOCUS
        val byUid = members.associateBy { it.uid }
        val cards = groupList.map { g ->
            GroupCard(g.id, g.name, g.memberIds.size, g.memberIds.count { byUid[it]?.state == PresenceState.STUDYING })
        }
        val group = groupList.firstOrNull { it.id == selected }
        val detail = group?.let { g ->
            val rows = g.memberIds.map { uid ->
                val p = byUid[uid]
                val state = p?.state ?: PresenceState.IDLE
                val isMe = uid == account?.uid
                MemberRow(
                    uid = uid,
                    name = p?.displayName ?: "Member",
                    state = state,
                    subject = p?.subject,
                    todayMs = p?.todayTotal(keys.dayKey, nowMs) ?: 0L,
                    isMe = isMe,
                    canNudge = !isMe && state != PresenceState.STUDYING && nowMs - (nudged[uid] ?: 0L) > NUDGE_COOLDOWN_MS,
                )
            }.sortedWith(compareBy<MemberRow> { it.state.ordinal }.thenByDescending { it.todayMs })

            fun rank(values: List<Pair<MemberRow, Long>>): List<RankRow> =
                values.sortedByDescending { it.second }.mapIndexed { i, (m, ms) -> RankRow(i + 1, m.name, ms, m.isMe) }

            val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
            val zone = ZoneId.systemDefault()
            GroupDetail(
                id = g.id,
                name = g.name,
                code = g.code,
                members = rows,
                rankingToday = rank(rows.map { it to it.todayMs }),
                rankingWeek = rank(rows.map { m -> m to (byUid[m.uid]?.weekTotal(keys.weekKey, nowMs) ?: 0L) }),
                messages = chat.map { m ->
                    MessageRow(
                        id = m.id,
                        name = m.name,
                        text = m.text,
                        time = if (m.sentAtMs > 0) Instant.ofEpochMilli(m.sentAtMs).atZone(zone).format(timeFormat) else "",
                        isMine = m.uid == account?.uid,
                    )
                },
            )
        }
        return GroupsUi(
            available = true,
            loading = false,
            account = account,
            groups = cards,
            detail = detail,
            chatLocked = chatLocked,
        )
    }

    private companion object {
        const val NUDGE_COOLDOWN_MS = 5 * 60_000L
    }
}
