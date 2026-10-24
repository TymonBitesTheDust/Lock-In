package app.lockin.feature.subjects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.SubjectRepository
import app.lockin.core.designsystem.component.ColorDot
import app.lockin.core.designsystem.component.EmptyHint
import app.lockin.core.designsystem.component.SubjectEditorDialog
import app.lockin.core.model.Subject
import app.lockin.core.model.SubjectPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubjectsViewModel @Inject constructor(
    private val repository: SubjectRepository,
) : ViewModel() {
    val subjects: StateFlow<List<Subject>> =
        repository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, color: Int) = viewModelScope.launch { repository.add(name, color) }

    fun update(id: Long, name: String, color: Int) = viewModelScope.launch { repository.update(id, name, color) }

    fun remove(id: Long) = viewModelScope.launch { repository.archive(id) }
}

/** null = closed, -1 = new subject, otherwise the id being edited. */
private const val NEW_SUBJECT = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectsRoute(onBack: () -> Unit, viewModel: SubjectsViewModel = hiltViewModel()) {
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subjects") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW_SUBJECT },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add subject") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (subjects.isEmpty()) {
                item { EmptyHint("No subjects yet. Add the courses or topics you study, like Maths or IELTS Reading.") }
            }
            items(subjects, key = { it.id }) { subject ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = subject.id }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(subject.color, size = 14.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(subject.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${subject.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    when (val id = editing) {
        null -> Unit
        NEW_SUBJECT -> SubjectEditorDialog(
            title = "New subject",
            initialName = "",
            initialColor = SubjectPalette.next(subjects.map { it.color }),
            palette = SubjectPalette.colors,
            onSave = { name, color -> viewModel.add(name, color); editing = null },
            onDismiss = { editing = null },
        )
        else -> subjects.firstOrNull { it.id == id }?.let { subject ->
            SubjectEditorDialog(
                title = "Edit subject",
                initialName = subject.name,
                initialColor = subject.color,
                palette = SubjectPalette.colors,
                onSave = { name, color -> viewModel.update(subject.id, name, color); editing = null },
                onDismiss = { editing = null },
                onRemove = { viewModel.remove(subject.id); editing = null },
            )
        }
    }
}
