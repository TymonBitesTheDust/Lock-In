package app.lockin.feature.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.component.ColorDot
import app.lockin.core.designsystem.component.SectionHeader
import app.lockin.core.designsystem.component.SubjectEditorDialog
import app.lockin.core.designsystem.theme.LockInTextStyles
import app.lockin.core.designsystem.theme.LockInTheme
import app.lockin.core.model.Durations
import app.lockin.core.model.SubjectPalette
import app.lockin.core.timer.PauseReason
import app.lockin.core.timer.Phase

@Composable
fun TimerRoute(viewModel: TimerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Asked when a session starts, where the reason is obvious. Denying it only hides the notification.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var addingSubject by rememberSaveable { mutableStateOf(false) }

    TimerScreen(
        state = state,
        onIntent = { intent ->
            if (intent is TimerIntent.StartSubject &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.onIntent(intent)
        },
        onAddSubject = { addingSubject = true },
    )

    if (addingSubject) {
        SubjectEditorDialog(
            title = "New subject",
            initialName = "",
            initialColor = viewModel.nextSubjectColor(),
            palette = SubjectPalette.colors,
            onSave = { name, color ->
                viewModel.onIntent(TimerIntent.AddSubject(name, color))
                addingSubject = false
            },
            onDismiss = { addingSubject = false },
        )
    }
}

@Composable
fun TimerScreen(
    state: TimerHomeState,
    onIntent: (TimerIntent) -> Unit,
    onAddSubject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.loading -> Unit
            state.session != null -> ActiveContent(state, state.session, onIntent)
            else -> IdleContent(state, onIntent, onAddSubject)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleContent(state: TimerHomeState, onIntent: (TimerIntent) -> Unit, onAddSubject: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        item {
            Header(state)
            Spacer(Modifier.height(20.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.preferPomodoro,
                    onClick = { onIntent(TimerIntent.SetPomodoro(false)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Stopwatch") }
                SegmentedButton(
                    selected = state.preferPomodoro,
                    onClick = { onIntent(TimerIntent.SetPomodoro(true)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Pomodoro ${state.pomodoroLabel}") }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("Subjects") {
                TextButton(onClick = onAddSubject) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
        items(state.subjects, key = { it.id ?: -1L }) { row ->
            SubjectRowItem(row, onStart = { onIntent(TimerIntent.StartSubject(row.id)) })
        }
        if (!state.hasSubjects) {
            item {
                Text(
                    text = "Add your subjects to see where your time goes. Until then, sessions count as General.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.timer_idle_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun Header(state: TimerHomeState) {
    Column(Modifier.fillMaxWidth()) {
        state.dday?.let { dday ->
            AssistChip(
                onClick = {},
                label = { Text("${dday.title} · ${dday.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Text("Today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = Durations.clock(state.todayMs),
            style = LockInTextStyles.clock,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SubjectRowItem(row: SubjectRow, onStart: () -> Unit) {
    val highlight = if (row.isActive) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(highlight)
            .clickable(onClick = onStart)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(row.color, size = 12.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Durations.clock(row.todayMs),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = onStart) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Start ${row.name}")
        }
    }
}

@Composable
private fun ActiveContent(home: TimerHomeState, state: TimerUiState.Active, onIntent: (TimerIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            home.sessionColor?.let {
                ColorDot(it, size = 10.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = state.subjectName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = phaseLabel(state),
            style = MaterialTheme.typography.labelLarge,
            color = if (state.phase == Phase.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.clock,
            style = LockInTextStyles.clock,
            color = if (state.isRunning) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            // Announced on change only when paused or between phases, not every second.
            modifier = Modifier.semantics { if (!state.isRunning) liveRegion = LiveRegionMode.Polite },
        )
        if (state.isPomodoro) {
            Text(
                text = stringResource(R.string.timer_session_focus, state.sessionFocus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Today ${Durations.clock(home.todayMs)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        statusMessage(state)?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (state.phase.isBreak && state.isRunning) {
            Spacer(Modifier.height(8.dp))
            Text("Break time: apps are unlocked and group chat is open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isRunning) {
                FilledTonalButton(onClick = { onIntent(TimerIntent.Pause) }) { Text(stringResource(R.string.timer_pause)) }
            } else {
                Button(onClick = { onIntent(TimerIntent.Resume) }) { Text(stringResource(R.string.timer_resume)) }
            }
            if (state.isPomodoro) {
                OutlinedButton(onClick = { onIntent(TimerIntent.Skip) }) { Text(stringResource(R.string.timer_skip)) }
            }
            TextButton(onClick = { onIntent(TimerIntent.Stop) }) { Text(stringResource(R.string.timer_stop)) }
        }
    }
}

@Composable
private fun phaseLabel(state: TimerUiState.Active): String = when (state.phase) {
    Phase.FOCUS -> if (state.isPomodoro) stringResource(R.string.timer_focus_round, state.round) else stringResource(R.string.timer_focus)
    Phase.SHORT_BREAK -> stringResource(R.string.timer_short_break)
    Phase.LONG_BREAK -> stringResource(R.string.timer_long_break)
}

@Composable
private fun statusMessage(state: TimerUiState.Active): String? = when (state.pauseReason) {
    null -> null
    PauseReason.USER -> stringResource(R.string.timer_status_paused)
    PauseReason.AWAITING_NEXT_PHASE ->
        if (state.phase == Phase.FOCUS) stringResource(R.string.timer_status_break_over) else stringResource(R.string.timer_status_round_done)
    PauseReason.INTERRUPTED -> stringResource(R.string.timer_status_interrupted)
}

@Preview
@Composable
private fun IdlePreview() = LockInTheme(darkTheme = true) {
    TimerScreen(
        state = TimerHomeState(
            loading = false,
            todayMs = 3 * 3_600_000L + 12 * 60_000L,
            subjects = listOf(
                SubjectRow(1, "Maths", SubjectPalette.colors[0], 5_400_000, isActive = false, isRunning = false),
                SubjectRow(2, "Physics", SubjectPalette.colors[1], 2_700_000, isActive = false, isRunning = false),
            ),
            hasSubjects = true,
            dday = DDayChip("Finals", "D-12"),
        ),
        onIntent = {},
        onAddSubject = {},
    )
}
