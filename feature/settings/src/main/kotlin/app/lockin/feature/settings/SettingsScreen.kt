package app.lockin.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lockin.core.model.AccentColor
import app.lockin.core.model.ThemeMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.PreferencesRepository
import app.lockin.core.designsystem.component.LockInCard
import app.lockin.core.designsystem.component.SectionHeader
import app.lockin.core.model.StudyPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {
    val prefs: StateFlow<StudyPreferences?> =
        preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (StudyPreferences) -> StudyPreferences) = viewModelScope.launch { preferences.update(transform) }
}

@Composable
fun SettingsRoute(
    onOpenSubjects: () -> Unit,
    onOpenBlocker: () -> Unit,
    accountSection: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val p = prefs ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        accountSection()

        LockInCard {
            NavRow("Subjects", "Add, rename, recolour or remove subjects", onOpenSubjects)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            NavRow(
                "Focus protection",
                if (p.blockingEnabled) "On: other apps are blocked during focus" else "Off: choose which apps you can use while studying",
                onOpenBlocker,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Appearance")
        LockInCard {
            AppearanceSection(
                mode = p.themeMode,
                accent = p.accent,
                onMode = { mode -> viewModel.update { it.copy(themeMode = mode) } },
                onAccent = { accent -> viewModel.update { it.copy(accent = accent) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Pomodoro")
        LockInCard {
            Stepper("Focus", "${p.pomodoroFocusMin} min", { viewModel.update { it.copy(pomodoroFocusMin = (it.pomodoroFocusMin - 5).coerceAtLeast(5)) } }) {
                viewModel.update { it.copy(pomodoroFocusMin = (it.pomodoroFocusMin + 5).coerceAtMost(180)) }
            }
            Stepper("Short break", "${p.shortBreakMin} min", { viewModel.update { it.copy(shortBreakMin = (it.shortBreakMin - 1).coerceAtLeast(1)) } }) {
                viewModel.update { it.copy(shortBreakMin = (it.shortBreakMin + 1).coerceAtMost(60)) }
            }
            Stepper("Long break", "${p.longBreakMin} min", { viewModel.update { it.copy(longBreakMin = (it.longBreakMin - 5).coerceAtLeast(5)) } }) {
                viewModel.update { it.copy(longBreakMin = (it.longBreakMin + 5).coerceAtMost(90)) }
            }
            Stepper("Long break every", "${p.longBreakEvery} rounds", { viewModel.update { it.copy(longBreakEvery = (it.longBreakEvery - 1).coerceAtLeast(1)) } }) {
                viewModel.update { it.copy(longBreakEvery = (it.longBreakEvery + 1).coerceAtMost(12)) }
            }
            SwitchRow("Start breaks automatically", p.autoStartBreaks) { on -> viewModel.update { it.copy(autoStartBreaks = on) } }
            SwitchRow("Start focus automatically after a break", p.autoStartFocus) { on -> viewModel.update { it.copy(autoStartFocus = on) } }
            Text(
                "Changes apply to the next Pomodoro you start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Study day")
        LockInCard {
            Stepper(
                "New day starts at",
                "%02d:00".format(p.dayStartHour),
                { viewModel.update { it.copy(dayStartHour = (it.dayStartHour + 23) % 24) } },
            ) { viewModel.update { it.copy(dayStartHour = (it.dayStartHour + 1) % 24) } }
            Text(
                "Late-night study before this hour counts for the previous day, in totals, stats and the planner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    mode: ThemeMode,
    accent: AccentColor,
    onMode: (ThemeMode) -> Unit,
    onAccent: (AccentColor) -> Unit,
) {
    Text("Theme", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { i, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
            ) { Text(option.label) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Colour · ${accent.label}", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        AccentColor.entries.forEach { option ->
            val selected = option == accent
            // Each swatch gets an equal share of the row as its touch target, so six fit on narrow phones.
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onAccent(option) })
                    .semantics { contentDescription = option.label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color(option.swatch)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Used across Lock In and on the lock screen you see during focus.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        FilledTonalIconButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
