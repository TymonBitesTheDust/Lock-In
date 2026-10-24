package app.lockin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import app.lockin.core.designsystem.component.LockInIcons
import app.lockin.feature.planner.PlannerRoute
import app.lockin.feature.settings.SettingsRoute
import app.lockin.feature.stats.StatsRoute
import app.lockin.feature.subjects.SubjectsRoute
import app.lockin.feature.timer.TimerRoute

/** Bottom-level destinations. */
enum class Tab(val label: String) { TIMER("Timer"), PLANNER("Planner"), STATS("Stats"), SETTINGS("Settings") }

/** Full-screen destinations pushed above the tabs. */
enum class Pushed { SUBJECTS, BLOCKER }

/**
 * App-owned navigation: the "back stack" is two pieces of saveable state (current tab, optional
 * pushed screen) and Back pops them in order. Small enough not to need a navigation library yet.
 */
@Composable
fun LockInApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.TIMER) }
    var pushed by rememberSaveable { mutableStateOf<Pushed?>(null) }

    BackHandler(enabled = pushed != null) { pushed = null }
    BackHandler(enabled = pushed == null && tab != Tab.TIMER) { tab = Tab.TIMER }

    when (pushed) {
        Pushed.SUBJECTS -> {
            SubjectsRoute(onBack = { pushed = null })
            return
        }
        Pushed.BLOCKER -> {
            Placeholder("Focus protection")
            return
        }
        null -> Unit
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { TabIcon(item) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.TIMER -> TimerRoute()
                Tab.PLANNER -> PlannerRoute(onSessionStarted = { tab = Tab.TIMER })
                Tab.STATS -> StatsRoute()
                Tab.SETTINGS -> SettingsRoute(
                    onOpenSubjects = { pushed = Pushed.SUBJECTS },
                    onOpenBlocker = { pushed = Pushed.BLOCKER },
                )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab) {
    val painter: Painter? = when (tab) {
        Tab.TIMER -> LockInIcons.timer
        Tab.PLANNER -> LockInIcons.planner
        Tab.STATS -> LockInIcons.stats
        Tab.SETTINGS -> null
    }
    if (painter != null) Icon(painter, contentDescription = null) else Icon(Icons.Filled.Settings, contentDescription = null)
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
