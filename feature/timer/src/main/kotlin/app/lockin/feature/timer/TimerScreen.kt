package app.lockin.feature.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.theme.LockInTextStyles
import app.lockin.core.designsystem.theme.LockInTheme
import app.lockin.core.timer.PauseReason
import app.lockin.core.timer.Phase

@Composable
fun TimerRoute(viewModel: TimerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Asked when a session starts, where the reason is obvious. Denying it only hides the notification.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    TimerScreen(
        state = state,
        onIntent = { intent ->
            val starting = intent == TimerIntent.StartStopwatch || intent == TimerIntent.StartPomodoro
            if (starting &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.onIntent(intent)
        },
    )
}

@Composable
fun TimerScreen(state: TimerUiState, onIntent: (TimerIntent) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                TimerUiState.Loading -> Unit
                TimerUiState.Idle -> IdleContent(onIntent)
                is TimerUiState.Active -> ActiveContent(state, onIntent)
            }
        }
    }
}

@Composable
private fun IdleContent(onIntent: (TimerIntent) -> Unit) {
    Text(
        text = stringResource(R.string.timer_idle_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.timer_idle_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(40.dp))
    Button(onClick = { onIntent(TimerIntent.StartStopwatch) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.timer_start_stopwatch))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { onIntent(TimerIntent.StartPomodoro) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.timer_start_pomodoro))
    }
}

@Composable
private fun ActiveContent(state: TimerUiState.Active, onIntent: (TimerIntent) -> Unit) {
    Text(
        text = state.subjectName,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    statusMessage(state)?.let {
        Spacer(Modifier.height(12.dp))
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun IdlePreview() = LockInTheme(darkTheme = true) { TimerScreen(TimerUiState.Idle, onIntent = {}) }

@Preview
@Composable
private fun RunningPomodoroPreview() = LockInTheme(darkTheme = true) {
    TimerScreen(
        state = TimerUiState.Active(
            subjectName = "Physics",
            phase = Phase.FOCUS,
            clock = "18:42",
            isRunning = true,
            pauseReason = null,
            isPomodoro = true,
            sessionFocus = "1:31:18",
            round = 4,
        ),
        onIntent = {},
    )
}

@Preview
@Composable
private fun InterruptedPreview() = LockInTheme(darkTheme = true) {
    TimerScreen(
        state = TimerUiState.Active(
            subjectName = "General",
            phase = Phase.FOCUS,
            clock = "42:10",
            isRunning = false,
            pauseReason = PauseReason.INTERRUPTED,
            isPomodoro = false,
            sessionFocus = "42:10",
            round = 1,
        ),
        onIntent = {},
    )
}
