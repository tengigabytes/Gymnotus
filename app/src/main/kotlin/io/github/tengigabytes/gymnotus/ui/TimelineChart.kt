@file:OptIn(ExperimentalLayoutApi::class)

package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.gymnotus.power.niceScale
import io.github.tengigabytes.gymnotus.sampler.HistoryPoint
import java.util.Locale
import kotlin.math.abs

/**
 * Chart colours: a validated eight-hue categorical palette with its own light and dark steps, on the surface it
 * was validated against (so the chart sits on its own card rather than on the dynamic Material surface).
 * The slot order is what keeps neighbouring series apart for colour-blind readers; do not reorder or extend it.
 */
class ChartColors(
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    val series: List<Color>,
)

private val LIGHT = ChartColors(
    surface = Color(0xFFFCFCFB),
    primary = Color(0xFF0B0B0B),
    secondary = Color(0xFF52514E),
    muted = Color(0xFF898781),
    grid = Color(0xFFE1E0D9),
    axis = Color(0xFFC3C2B7),
    series = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF4A3AA7, 0xFFE34948).map(::Color),
)

private val DARK = ChartColors(
    surface = Color(0xFF1A1A19),
    primary = Color(0xFFFFFFFF),
    secondary = Color(0xFFC3C2B7),
    muted = Color(0xFF898781),
    grid = Color(0xFF2C2C2A),
    axis = Color(0xFF383835),
    series = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map(::Color),
)

@Composable
fun chartColors(): ChartColors = if (isSystemInDarkTheme()) DARK else LIGHT

/**
 * One charted line.
 *
 * @property indices monitor indices whose power is summed into this line (one for a rail, several for a group)
 * @property slot colour slot, fixed for as long as the series stays selected
 */
data class SeriesSpec(val key: String, val label: String, val indices: List<Int>, val slot: Int)

/** Which rows are charted. A series keeps its colour slot while selected; a freed slot goes to the next pick. */
class ChartSelection {
    val series = mutableStateListOf<SeriesSpec>()

    fun slotOf(key: String): Int? = series.firstOrNull { it.key == key }?.slot

    /** Adds or removes a series; adding does nothing once every colour slot is taken. */
    fun toggle(key: String, label: String, indices: List<Int>) {
        if (series.removeAll { it.key == key }) return
        val slot = (0 until MAX_SERIES).firstOrNull { candidate -> series.none { it.slot == candidate } } ?: return
        series.add(SeriesSpec(key, label, indices, slot))
        series.sortBy { it.slot }
    }

    companion object {
        const val MAX_SERIES = 8
    }
}

private val WINDOWS_MS = listOf(60_000L to "1 min", 300_000L to "5 min")

/** Power of the selected series over the last minute or five, with a tap-and-drag cursor that reads out values. */
@Composable
fun TimelineChart(history: List<HistoryPoint>, selection: ChartSelection, defaultWindowMs: Long) {
    val colors = chartColors()
    val density = LocalDensity.current
    var windowMs by rememberSaveable { mutableStateOf(defaultWindowMs) }
    var cursorX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }

    val series = selection.series
    val endMs = history.lastOrNull()?.elapsedMs
    val visible = if (endMs == null) emptyList() else history.filter { it.elapsedMs >= endMs - windowMs }
    val lines = series.map { spec -> FloatArray(visible.size) { i -> sumOf(visible[i], spec.indices) } }
    val scale = niceScale(lines.maxOfOrNull { line -> line.filterNot { it.isNaN() }.maxOrNull() ?: 0f }?.toDouble() ?: 0.0)

    val leftPx = with(density) { 48.dp.toPx() }
    val rightPx = with(density) { 8.dp.toPx() }
    val plotWidth = (widthPx - leftPx - rightPx).coerceAtLeast(1f)
    fun xOf(elapsedMs: Long) = leftPx + plotWidth * (1f - (endMs!! - elapsedMs).toFloat() / windowMs)
    val cursorIndex = cursorX?.let { x -> visible.indices.minByOrNull { abs(xOf(visible[it].elapsedMs) - x) } }

    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Power over time (mW)", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((ms, label) in WINDOWS_MS) {
                    FilterChip(selected = windowMs == ms, onClick = { windowMs = ms }, label = { Text(label) })
                }
            }
        }
        when {
            series.isEmpty() -> Note("Tap a row below to chart it (up to ${ChartSelection.MAX_SERIES}).", colors)
            visible.size < 2 -> Note("Collecting readings…", colors)
            else -> {
                val measurer = rememberTextMeasurer()
                val tickStyle = TextStyle(fontSize = 11.sp, color = colors.muted)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .onSizeChanged { widthPx = it.width }
                        .pointerInput(Unit) { detectTapGestures { cursorX = if (cursorX == null) it.x else null } }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(onDragStart = { cursorX = it.x }) { change, _ -> cursorX = change.position.x }
                        },
                ) {
                    val topPx = 8.dp.toPx()
                    val plotHeight = size.height - topPx - 20.dp.toPx()
                    fun yOf(value: Double) = topPx + plotHeight * (1f - (value / scale.top).toFloat())

                    for (tick in scale.ticks) {
                        val y = yOf(tick)
                        drawLine(if (tick == 0.0) colors.axis else colors.grid, Offset(leftPx, y), Offset(leftPx + plotWidth, y), 1.dp.toPx())
                        val label = measurer.measure(String.format(Locale.ROOT, "%,.0f", tick), tickStyle)
                        drawText(label, topLeft = Offset(leftPx - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
                    }
                    val xLabels = listOf(0f to "−${windowMs / 1000} s", 0.5f to "−${windowMs / 2000} s", 1f to "now")
                    for ((fraction, text) in xLabels) {
                        val label = measurer.measure(text, tickStyle)
                        val x = (leftPx + plotWidth * fraction - label.size.width * fraction).coerceAtLeast(leftPx)
                        drawText(label, topLeft = Offset(x, topPx + plotHeight + 4.dp.toPx()))
                    }

                    lines.forEachIndexed { s, values ->
                        val path = Path()
                        var penDown = false
                        values.forEachIndexed { i, value ->
                            if (value.isNaN()) {
                                penDown = false
                            } else {
                                val x = xOf(visible[i].elapsedMs)
                                val y = yOf(value.toDouble())
                                if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                                penDown = true
                            }
                        }
                        drawPath(path, colors.series[series[s].slot], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }

                    if (cursorIndex != null) {
                        val x = xOf(visible[cursorIndex].elapsedMs)
                        drawLine(colors.axis, Offset(x, topPx), Offset(x, topPx + plotHeight), 1.dp.toPx())
                        lines.forEachIndexed { s, values ->
                            val value = values[cursorIndex]
                            if (!value.isNaN()) {
                                val centre = Offset(x, yOf(value.toDouble()))
                                // The surface-coloured ring keeps markers legible where lines cross.
                                drawCircle(colors.surface, 6.dp.toPx(), centre)
                                drawCircle(colors.series[series[s].slot], 4.dp.toPx(), centre)
                            }
                        }
                    }
                }
                val at = cursorIndex?.let { String.format(Locale.ROOT, "%.1f s ago", (endMs!! - visible[it].elapsedMs) / 1000.0) } ?: "latest"
                Text("Values: $at · tap or drag the chart to read a moment", color = colors.secondary, fontSize = 12.sp)
            }
        }
        // Identity is never colour alone: every series is named here, with its value in text ink.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            series.forEachIndexed { s, spec ->
                val values = lines[s]
                val value = if (cursorIndex != null) values.getOrNull(cursorIndex) else values.lastOrNull { !it.isNaN() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors.series[spec.slot]))
                    Text(spec.label, color = colors.primary, fontSize = 12.sp)
                    Text(
                        if (value == null || value.isNaN()) "—" else String.format(Locale.ROOT, "%,.1f", value),
                        color = colors.secondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, colors: ChartColors) {
    Text(text, color = colors.secondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 24.dp))
}

/** Sum of the given monitors at one point, skipping those without a new value; NaN if none has one. */
private fun sumOf(point: HistoryPoint, indices: List<Int>): Float {
    var sum = 0f
    var any = false
    for (index in indices) {
        val value = point.powerMw.getOrNull(index) ?: continue
        if (!value.isNaN()) {
            sum += value
            any = true
        }
    }
    return if (any) sum else Float.NaN
}
