package app.lockin.feature.blocker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.component.LockInCard
import app.lockin.core.designsystem.component.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockerRoute(onBack: () -> Unit, viewModel: BlockerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }
    val open: (Intent) -> Unit = { intent ->
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus protection") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                LockInCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Block apps during focus", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "While a focus round runs, leaving Lock In shows a lock screen with the apps you tick below. " +
                            "Tap one to open it. Breaks and paused sessions are never blocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.enabled && (!state.usageAccess || !state.overlay)) {
                item {
                    Spacer(Modifier.height(12.dp))
                    SectionHeader("Two permissions needed")
                    PermissionCard(
                        title = "Usage access",
                        body = "Lets Lock In see which app is open during focus. This stays on your phone; nothing is stored or sent.",
                        granted = state.usageAccess,
                        onGrant = { open(viewModel.usageAccessIntent()) },
                    )
                    Spacer(Modifier.height(8.dp))
                    PermissionCard(
                        title = "Display over other apps",
                        body = "Lets Lock In show the lock screen over other apps.",
                        granted = state.overlay,
                        onGrant = { open(viewModel.overlayIntent()) },
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Apps you can use while studying (${state.allowedCount})")
                Text(
                    "Phone calls, Settings and Lock In always stay available. Your home screen is covered during focus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (state.loading) Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    loadIcon = { px -> viewModel.icon(app.packageName, px) },
                    onToggle = { viewModel.setAllowed(app, it) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    LockInCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = if (granted) "Granted" else "Not granted",
                tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onGrant) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, loadIcon: suspend (Int) -> ImageBitmap?, onToggle: (Boolean) -> Unit) {
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) { value = loadIcon(px) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !app.alwaysAllowed) { onToggle(!app.allowed) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.alwaysAllowed) {
                Text("Always allowed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = app.allowed || app.alwaysAllowed, onCheckedChange = onToggle, enabled = !app.alwaysAllowed)
    }
}
