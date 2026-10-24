package app.lockin.feature.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.component.ColorDot
import app.lockin.core.designsystem.component.EmptyHint
import app.lockin.core.designsystem.component.LocalDatePickerDialog
import app.lockin.core.designsystem.component.LockInCard
import app.lockin.core.designsystem.component.SectionHeader
import app.lockin.core.model.Durations
import app.lockin.core.model.Subject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun PlannerRoute(onSessionStarted: () -> Unit, viewModel: PlannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addingDDay by rememberSaveable { mutableStateOf(false) }
    if (state.loading) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Planner", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (state.selected != state.today) {
                    TextButton(onClick = viewModel::goToToday) { Text("Today") }
                }
            }
            Spacer(Modifier.height(8.dp))
            WeekStrip(state, onSelect = viewModel::select, onShift = viewModel::shiftWeek)
            Spacer(Modifier.height(12.dp))
            Text(
                state.selected.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Studied ${Durations.short(state.dayTotalMs)}" + if (state.liveMs > 0) " (studying now)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("D-Day") {
                TextButton(onClick = { addingDDay = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            if (state.ddays.isEmpty()) {
                EmptyHint("Count down to exams, deadlines and milestones.")
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.ddays, key = { it.id }) { dday -> DDayCard(dday, onDelete = { viewModel.deleteDDay(dday.id) }) }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            val done = state.tasks.count { it.done }
            SectionHeader(if (state.tasks.isEmpty()) "To-do" else "To-do · $done of ${state.tasks.size} done")
            AddTaskRow(state.subjects, onAdd = viewModel::addTask)
        }
        items(state.tasks, key = { it.id }) { task ->
            TaskItem(
                task = task,
                onToggle = { viewModel.setDone(task.id, it) },
                onStart = {
                    viewModel.startTask(task)
                    onSessionStarted()
                },
                onDelete = { viewModel.delete(task.id) },
                onMoveNextDay = { viewModel.moveToNextDay(task.id) },
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Study log")
            LockInCard {
                if (state.shares.isEmpty()) {
                    Text("Nothing logged on this day yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ShareBar(state.shares)
                    Spacer(Modifier.height(12.dp))
                    state.shares.forEach { share ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(share.color)
                            Spacer(Modifier.width(8.dp))
                            Text(share.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Durations.short(share.ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.log.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.log.forEach { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.timeRange,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                    modifier = Modifier.widthIn(min = 104.dp),
                                )
                                ColorDot(entry.color, size = 8.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(entry.subjectName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Durations.short(entry.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addingDDay) {
        AddDDayDialog(
            today = state.today,
            onSave = { title, date ->
                viewModel.addDDay(title, date)
                addingDDay = false
            },
            onDismiss = { addingDDay = false },
        )
    }
}

@Composable
private fun WeekStrip(state: PlannerState, onSelect: (LocalDate) -> Unit, onShift: (Long) -> Unit) {
    val first = state.week.firstOrNull()?.date ?: return
    val last = state.week.last().date
    val rangeFormat = DateTimeFormatter.ofPattern("d MMM")
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week") }
        Text(
            "${first.format(rangeFormat)} – ${last.format(rangeFormat)}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.week.forEach { day ->
            val selected = day.date == state.selected
            val isToday = day.date == state.today
            val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
            val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .then(if (isToday && !selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onSelect(day.date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                )
                Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
                Text(
                    if (day.studiedMs >= 60_000) Durations.short(day.studiedMs).replace(" ", "") else "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                day.tasks?.let { t ->
                    Text(
                        "${t.done}/${t.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DDayCard(dday: DDayRow, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dday.past) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 12.dp).widthIn(min = 120.dp, max = 200.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dday.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirm = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete ${dday.title}", modifier = Modifier.size(16.dp))
                }
            }
            Text(dday.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dday.dateText, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${dday.title}?") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddTaskRow(subjects: List<Subject>, onAdd: (String, Long?) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var subjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val submit = {
        if (title.isNotBlank()) {
            onAdd(title, subjectId)
            title = ""
        }
    }
    Column {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(120) },
            placeholder = { Text("Add a task") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = title.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Add task") }
            },
        )
        if (subjects.isNotEmpty()) {
            Box {
                val current = subjects.firstOrNull { it.id == subjectId }
                AssistChip(
                    onClick = { menuOpen = true },
                    label = { Text(current?.name ?: "No subject") },
                    leadingIcon = { if (current != null) ColorDot(current.color) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("No subject") }, onClick = { subjectId = null; menuOpen = false })
                    subjects.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            leadingIcon = { ColorDot(s.color) },
                            onClick = { subjectId = s.id; menuOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskItem(
    task: TaskRow,
    onToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit,
    onMoveNextDay: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = task.done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (task.subjectName != null && task.subjectColor != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(task.subjectColor, size = 8.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(task.subjectName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!task.done) {
            IconButton(onClick = onStart) { Icon(Icons.Filled.PlayArrow, contentDescription = "Start studying for ${task.title}") }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${task.title}") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Move to next day") }, onClick = { menuOpen = false; onMoveNextDay() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

/** Horizontal share of the day per subject: filled segments with a 2dp surface gap between them. */
@Composable
private fun ShareBar(shares: List<SubjectShare>) {
    val total = shares.sumOf { it.ms }.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shares.forEach { share ->
            Box(
                Modifier
                    .weight((share.ms.toFloat() / total).coerceAtLeast(0.01f))
                    .fillMaxSize()
                    .background(Color(share.color)),
            )
        }
    }
}

@Composable
private fun AddDDayDialog(today: LocalDate, onSave: (String, LocalDate) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.plusDays(30).toEpochDay()) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New D-Day") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = { Text("What's coming up?") },
                    placeholder = { Text("Final exams") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(LocalDate.ofEpochDay(date).format(DateTimeFormatter.ofPattern("EEE d MMMM yyyy")))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, LocalDate.ofEpochDay(date)) }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        LocalDatePickerDialog(
            initial = LocalDate.ofEpochDay(date),
            onPicked = {
                date = it.toEpochDay()
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
