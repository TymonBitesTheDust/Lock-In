package app.lockin.feature.groups

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.component.LockInCard
import app.lockin.core.designsystem.component.SectionHeader
import app.lockin.core.model.Durations
import app.lockin.core.network.PresenceState

@Composable
fun GroupsRoute(viewModel: GroupsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        message?.let { text ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { viewModel.consumeMessage() },
            ) {
                Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LaunchedEffect(text) {
                kotlinx.coroutines.delay(4_000)
                viewModel.consumeMessage()
            }
        }
        val detail = ui.detail
        when {
            !ui.available -> Unavailable()
            ui.loading -> Unit
            ui.account == null -> AuthForm(
                busy = busy,
                onSignIn = viewModel::signIn,
                onSignUp = viewModel::signUp,
                onReset = viewModel::resetPassword,
            )
            detail != null -> {
                BackHandler { viewModel.select(null) }
                GroupDetailScreen(
                    detail = detail,
                    chatLocked = ui.chatLocked,
                    onBack = { viewModel.select(null) },
                    onNudge = viewModel::nudge,
                    onSend = viewModel::send,
                    onLeave = viewModel::leave,
                )
            }
            else -> GroupList(ui, onOpen = viewModel::select, onCreate = viewModel::create, onJoin = viewModel::join, onSignOut = viewModel::signOut)
        }
    }
}

@Composable
private fun Unavailable() {
    Column(Modifier.padding(16.dp)) {
        Text("Groups", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LockInCard {
            Text("Study groups aren't connected in this build yet.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Groups need the app's Firebase project. Everything else (timer, planner, stats, app blocking) works without it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthForm(
    busy: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onReset: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
    ) {
        Text("Groups", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Study with friends: see who's studying right now, compare hours, and nudge each other back to work.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !creating, onClick = { creating = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sign in") }
            SegmentedButton(selected = creating, onClick = { creating = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Create account") }
        }
        Spacer(Modifier.height(16.dp))
        if (creating) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("Name your groups will see") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (creating) "Password (6+ characters)" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (creating) onSignUp(name, email, password) else onSignIn(email, password) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!creating || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (creating) "Create account" else "Sign in") }
        if (!creating) {
            TextButton(onClick = { onReset(email) }, enabled = !busy) { Text("Forgot password?") }
        }
    }
}

@Composable
private fun GroupList(
    ui: GroupsUi,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Groups", style = MaterialTheme.typography.headlineSmall)
                    Text("Signed in as ${ui.account?.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Account") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onSignOut() })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = "create" }, modifier = Modifier.weight(1f)) { Text("Create group") }
                OutlinedButton(onClick = { dialog = "join" }, modifier = Modifier.weight(1f)) { Text("Join with code") }
            }
            Spacer(Modifier.height(16.dp))
            if (ui.groups.isEmpty()) {
                Text(
                    "You're not in a group yet. Create one and share its code, or join a friend's.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(ui.groups, key = { it.id }) { group ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onOpen(group.id) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${group.memberCount} member${if (group.memberCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (group.studyingNow > 0) {
                        StatusDot(PresenceState.STUDYING)
                        Spacer(Modifier.width(6.dp))
                        Text("${group.studyingNow} studying", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    when (dialog) {
        "create" -> TextInputDialog("New study group", "Group name", "Create", capitalize = true, onConfirm = { onCreate(it); dialog = null }, onDismiss = { dialog = null })
        "join" -> TextInputDialog("Join a group", "Invite code", "Join", capitalize = false, onConfirm = { onJoin(it); dialog = null }, onDismiss = { dialog = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailScreen(
    detail: GroupDetail,
    chatLocked: Boolean,
    onBack: () -> Unit,
    onNudge: (String) -> Unit,
    onSend: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to groups") }
            Column(Modifier.weight(1f)) {
                Text(detail.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Code ${detail.code}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Study with me on Lock In! Join \"${detail.name}\" with code ${detail.code}")
                    context.startActivity(Intent.createChooser(share, "Share invite code"))
                },
            ) { Icon(Icons.Filled.Share, contentDescription = "Share invite code") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Leave group") }, onClick = { menu = false; confirmLeave = true })
                }
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            listOf("Members", "Ranking", "Chat").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> MembersTab(detail, onNudge)
            1 -> RankingTab(detail)
            else -> ChatTab(detail, chatLocked, onSend)
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${detail.name}?") },
            text = { Text("You can rejoin later with the code.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MembersTab(detail: GroupDetail, onNudge: (String) -> Unit) {
    val studying = detail.members.count { it.state == PresenceState.STUDYING }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item {
            Text(
                "$studying of ${detail.members.size} studying now",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(detail.members, key = { it.uid }) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(m.state)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m.isMe) "${m.name} (you)" else m.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (m.isMe) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(statusText(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Durations.clock(m.todayMs), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                if (!m.isMe) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onNudge(m.uid) }, enabled = m.canNudge, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("Nudge")
                    }
                }
            }
        }
        item {
            Text(
                "Nudge a friend who isn't studying: their phone shakes with a reminder to lock in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun statusText(m: MemberRow): String = when (m.state) {
    PresenceState.STUDYING -> "Studying" + (m.subject?.let { " · $it" } ?: "")
    PresenceState.BREAK -> "On a break"
    PresenceState.PAUSED -> "Paused"
    PresenceState.IDLE -> "Not studying"
}

@Composable
private fun StatusDot(state: PresenceState) {
    val color = when (state) {
        PresenceState.STUDYING -> Color(0xFF0CA30C)
        PresenceState.BREAK -> Color(0xFFFAB219)
        PresenceState.PAUSED, PresenceState.IDLE -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankingTab(detail: GroupDetail) {
    var week by rememberSaveable { mutableStateOf(false) }
    val rows = if (week) detail.rankingWeek else detail.rankingToday
    val max = rows.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !week, onClick = { week = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Today") }
                SegmentedButton(selected = week, onClick = { week = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("This week") }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(rows, key = { "${it.rank}-${it.name}" }) { r ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${r.rank}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (r.rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                    Text(
                        if (r.isMe) "${r.name} (you)" else r.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (r.isMe) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Durations.short(r.ms), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .padding(start = 28.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(r.ms.toFloat() / max)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        item {
            Text(
                "Rankings use each member's focus time for the current study day and week, live.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ChatTab(detail: GroupDetail, locked: Boolean, onSend: (String) -> Unit) {
    if (locked) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Chat opens on your break", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Messages stay hidden while you focus. ${detail.messages.size} in this group.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(detail.messages.size) {
        if (detail.messages.isNotEmpty()) listState.animateScrollToItem(detail.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (detail.messages.isEmpty()) {
                item { Text("No messages yet. Say hi on your break.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(detail.messages, key = { it.id }) { m -> MessageBubble(m) }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageRow) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start) {
        if (!m.isMine) {
            Text(m.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (m.isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                if (m.time.isNotEmpty()) {
                    Text(m.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    confirm: String,
    capitalize: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = if (capitalize) it.take(40) else it.uppercase().take(6) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.Characters,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
