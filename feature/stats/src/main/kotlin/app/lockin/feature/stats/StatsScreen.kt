package app.lockin.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lockin.core.designsystem.component.BarChart
import app.lockin.core.designsystem.component.ColorDot
import app.lockin.core.designsystem.component.LockInCard
import app.lockin.core.designsystem.component.SectionHeader
import app.lockin.core.designsystem.component.StudyHeatmap
import app.lockin.core.model.Durations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsRoute(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Stats", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StatsRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = state.range == r,
                    onClick = { viewModel.setRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = StatsRange.entries.size),
                ) { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = { viewModel.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous") }
            Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.shift(1) }, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tiles.forEach { tile ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tile.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(tile.value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LockInCard {
            Text(
                if (state.range == StatsRange.DAY) "By hour" else "By day",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            BarChart(
                values = state.bars,
                labels = state.barLabels,
                formatValue = Durations::short,
                emphasisIndex = state.emphasisIndex,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("By subject")
        LockInCard {
            if (state.subjects.isEmpty()) {
                Text("No study time in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val max = state.subjects.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
            state.subjects.forEach { s ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(s.color)
                        Spacer(Modifier.width(8.dp))
                        Text(s.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Durations.short(s.ms)} · ${(s.share * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(s.ms.toFloat() / max)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(s.color)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("Consistency")
        LockInCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Current streak", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(days(state.currentStreak), style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("Best streak (${StatsViewModel.HEATMAP_WEEKS} weeks)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(days(state.bestStreak), style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            StudyHeatmap(
                totals = state.heatmap,
                endDay = state.today,
                formatValue = Durations::short,
                weeks = StatsViewModel.HEATMAP_WEEKS,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"
