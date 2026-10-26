package app.lockin.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.lockin.core.designsystem.theme.LocalHeatmapColors
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Rounds a duration up to a readable axis maximum (15m, 30m, 1h, 2h, ...). */
fun niceDurationCeiling(ms: Long): Long {
    val minute = 60_000L
    val hour = 60 * minute
    val steps = listOf(15 * minute, 30 * minute, hour, 2 * hour, 3 * hour, 4 * hour, 6 * hour, 8 * hour, 10 * hour, 12 * hour, 16 * hour, 24 * hour)
    if (ms <= 0) return hour
    return steps.firstOrNull { it >= ms } ?: ((ms + hour - 1) / hour) * hour
}

/**
 * A single-series bar chart (one colour, so no legend). Bars have 4dp rounded data ends anchored
 * to the baseline and a gap between them; tapping a bar shows its value. Labels may be blank to
 * thin out a dense axis.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    emphasisIndex: Int? = null,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val emphasisStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val maxValue = niceDurationCeiling(values.maxOrNull() ?: 0L)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(values) {
                detectTapGestures { offset ->
                    if (values.isNotEmpty()) {
                        val slot = size.width.toFloat() / values.size
                        val index = (offset.x / slot).toInt().coerceIn(0, values.lastIndex)
                        selected = if (selected == index) null else index
                    }
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val labelHeight = 18.dp.toPx()
        val topPad = 22.dp.toPx()
        val chartBottom = size.height - labelHeight
        val chartHeight = chartBottom - topPad
        val slot = size.width / values.size
        val gap = maxOf(2.dp.toPx(), slot * 0.28f)
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = minOf(4.dp.toPx(), barWidth / 2)

        drawLine(
            color = axisColor,
            start = Offset(0f, topPad),
            end = Offset(size.width, topPad),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        val maxLayout = measurer.measure(formatValue(maxValue), labelStyle)
        drawText(maxLayout, topLeft = Offset(0f, topPad - maxLayout.size.height - 2.dp.toPx()))
        drawLine(axisColor, Offset(0f, chartBottom), Offset(size.width, chartBottom), strokeWidth = 1.dp.toPx())

        fun barHeight(v: Long) = if (maxValue > 0) chartHeight * (v.toFloat() / maxValue) else 0f

        values.forEachIndexed { i, v ->
            val left = i * slot + gap / 2
            val h = barHeight(v)
            if (h > 0.5f) {
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = chartBottom - h,
                            right = left + barWidth,
                            bottom = chartBottom,
                            topLeftCornerRadius = CornerRadius(radius),
                            topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                }
                drawPath(path, barColor.copy(alpha = alpha))
            }
            val label = labels.getOrNull(i).orEmpty()
            if (label.isNotEmpty()) {
                val layout = measurer.measure(label, if (i == emphasisIndex) emphasisStyle else labelStyle)
                drawText(layout, topLeft = Offset(left + barWidth / 2 - layout.size.width / 2f, chartBottom + 3.dp.toPx()))
            }
        }

        selected?.let { i ->
            val layout = measurer.measure(formatValue(values[i]), valueStyle)
            val x = (i * slot + slot / 2 - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            val y = (chartBottom - barHeight(values[i]) - layout.size.height - 4.dp.toPx()).coerceAtLeast(0f)
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}

/**
 * Consistency heatmap: one column per week (Monday first), one cell per study day, sequential
 * single-hue scale. Fixed thresholds (none, under 1h, 1-2h, 2-4h, 4h+) so colours mean the same
 * thing month to month. Tap a cell to read its exact total.
 */
@Composable
fun StudyHeatmap(
    totals: Map<LocalDate, Long>,
    endDay: LocalDate,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier,
    weeks: Int = 18,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(endDay) { mutableStateOf<LocalDate?>(null) }
    val levels = heatmapLevels()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ringColor = MaterialTheme.colorScheme.onSurface
    val firstDay = endDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelColumn = 18.dp
            val cell = (maxWidth - labelColumn) / weeks
            val monthRow = 16.dp
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(monthRow + cell * 7)
                    .pointerInput(endDay, weeks) {
                        detectTapGestures { offset ->
                            val cellPx = (size.width - labelColumn.toPx()) / weeks
                            val col = ((offset.x - labelColumn.toPx()) / cellPx).toInt()
                            val row = ((offset.y - monthRow.toPx()) / cellPx).toInt()
                            if (col in 0 until weeks && row in 0..6) {
                                val day = firstDay.plusDays(col * 7L + row)
                                selected = if (day.isAfter(endDay) || day == selected) null else day
                            }
                        }
                    },
            ) {
                val left = labelColumn.toPx()
                val top = monthRow.toPx()
                val cellPx = (size.width - left) / weeks
                val gap = 3.dp.toPx()
                val side = cellPx - gap
                val radius = CornerRadius(2.dp.toPx())

                var lastLabelX = -1000f
                for (w in 0 until weeks) {
                    val columnStart = firstDay.plusWeeks(w.toLong())
                    val previous = columnStart.minusWeeks(1)
                    val x = left + w * cellPx
                    if ((w == 0 || columnStart.month != previous.month) && x - lastLabelX > 30.dp.toPx()) {
                        val name = columnStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        drawText(measurer.measure(name, labelStyle), topLeft = Offset(x, 0f))
                        lastLabelX = x
                    }
                }
                listOf(0 to "M", 2 to "W", 4 to "F").forEach { (row, text) ->
                    val layout = measurer.measure(text, labelStyle)
                    drawText(layout, topLeft = Offset(0f, top + row * cellPx + (side - layout.size.height) / 2))
                }
                for (w in 0 until weeks) {
                    for (d in 0..6) {
                        val day = firstDay.plusDays(w * 7L + d)
                        if (day.isAfter(endDay)) continue
                        val topLeft = Offset(left + w * cellPx, top + d * cellPx)
                        drawRoundRect(levels[heatLevel(totals[day] ?: 0)], topLeft, Size(side, side), radius)
                        if (day == selected) {
                            drawRoundRect(ringColor, topLeft, Size(side, side), radius, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val day = selected
            Text(
                text = if (day == null) "Tap a day for its total" else "${day.format(dayFormat)} · ${formatValue(totals[day] ?: 0)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            levels.forEach { color ->
                Box(
                    Modifier
                        .padding(horizontal = 1.5.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("4h+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun heatLevel(ms: Long): Int {
    val hour = 3_600_000L
    return when {
        ms <= 0 -> 0
        ms < hour -> 1
        ms < 2 * hour -> 2
        ms < 4 * hour -> 3
        else -> 4
    }
}

/** Empty cell plus four steps of the accent's ramp, ordered so "more" always reads as stronger. */
@Composable
private fun heatmapLevels(): List<Color> {
    val levels = LocalHeatmapColors.current.levels
    if (levels.size == 5) return levels
    // Outside LockInTheme (previews): the original blue ramp.
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        listOf(empty, Color(0xFF184F95), Color(0xFF256ABF), Color(0xFF3987E5), Color(0xFF86B6EF))
    } else {
        listOf(empty, Color(0xFF9EC5F4), Color(0xFF6DA7EC), Color(0xFF3987E5), Color(0xFF1C5CAB))
    }
}
