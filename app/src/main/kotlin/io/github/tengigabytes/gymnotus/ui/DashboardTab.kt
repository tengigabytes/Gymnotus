package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.BatterySample
import io.github.tengigabytes.gymnotus.power.DeviceMap
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.PowerTreeBuilder
import io.github.tengigabytes.gymnotus.power.parseRailName
import io.github.tengigabytes.gymnotus.sampler.HistoryPoint
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.RailRow
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import java.util.Locale

private const val SPARKLINE_WINDOW_MS = 60_000L

/**
 * Every monitor as a tile that never moves: tiles keep the order the system lists the monitors in, every tile
 * has the same layout, and figures are right-aligned in tabular digits, so only the digits change.
 *
 * Rails show power only. Voltage and current per rail are not available to an app (nor to the adb shell); the
 * battery is the one place where all three exist.
 */
@Composable
fun DashboardTab(state: SamplerState, history: List<HistoryPoint>, deviceMap: DeviceMap?, onRetry: () -> Unit) {
    if (state.listState != MonitorListState.READY) {
        ListStatus(state, onRetry)
        return
    }
    val batteryRail = deviceMap?.batteryRail ?: PowerTreeBuilder.BATTERY_RAIL
    val batteryRailMw = state.rails.firstOrNull { parseRailName(it.info.name).rail == batteryRail }?.reading?.powerMw
    val recent = remember(history) {
        val end = history.lastOrNull()?.elapsedMs ?: 0L
        history.filter { it.elapsedMs >= end - SPARKLINE_WINDOW_MS }
    }
    val measured = state.rails.filter { it.info.type == MonitorType.MEASUREMENT }
    val modelled = state.rails.filter { it.info.type != MonitorType.MEASUREMENT }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "battery", span = { GridItemSpan(maxLineSpan) }) { BatteryCard(state.battery, batteryRail, batteryRailMw) }
        item(key = "rails-title", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionTitle(stringResource(R.string.dashboard_rails, measured.size))
                Text(stringResource(R.string.dashboard_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(measured, key = { it.info.index }) { RailTile(it, history, recent) }
        if (modelled.isNotEmpty()) {
            item(key = "modelled-title", span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(stringResource(R.string.consumer_header, modelled.size))
            }
            items(modelled, key = { it.info.index }) { RailTile(it, history, recent) }
        }
    }
}

/** The battery as BatteryManager reports it, plus the battery rail for comparison. */
@Composable
private fun BatteryCard(battery: BatterySample?, batteryRail: String, batteryRailMw: Double?) {
    val dash = "—"
    fun number(format: String, value: Double?) = value?.let { String.format(Locale.ROOT, format, it) } ?: dash
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.field_voltage), number("%.3f V", battery?.voltageMv?.let { it / 1000.0 }), Modifier.weight(1f))
                Stat(stringResource(R.string.field_current), number("%+.1f mA", battery?.currentUa?.let { it / 1000.0 }), Modifier.weight(1f))
                Stat(stringResource(R.string.field_power), number("%+.1f mW", battery?.powerMw), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.field_status), batteryStatus(battery?.status) ?: dash, Modifier.weight(1f))
                Stat(stringResource(R.string.field_source), pluggedName(battery?.plugged) ?: dash, Modifier.weight(1f))
                Stat(stringResource(R.string.field_temperature), number("%.1f °C", battery?.temperatureDeciC?.let { it / 10.0 }), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.field_charge), number("%,.0f mAh", battery?.chargeCounterUah?.let { it / 1000.0 }), Modifier.weight(1f))
                Stat(stringResource(R.string.field_battery_rail, batteryRail), number("%.1f mW", batteryRailMw), Modifier.weight(2f))
            }
            Text(stringResource(R.string.battery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

@Composable
private fun batteryStatus(status: Int?): String? = when (status) {
    null -> null
    2 -> stringResource(R.string.status_charging)
    3 -> stringResource(R.string.status_discharging)
    4 -> stringResource(R.string.status_not_charging)
    5 -> stringResource(R.string.status_full)
    else -> stringResource(R.string.status_other, status.toString())
}

@Composable
private fun pluggedName(plugged: Int?): String? = when (plugged) {
    null -> null
    0 -> stringResource(R.string.plugged_battery)
    1 -> stringResource(R.string.plugged_ac)
    2 -> stringResource(R.string.plugged_usb)
    4 -> stringResource(R.string.plugged_wireless)
    8 -> stringResource(R.string.plugged_dock)
    else -> plugged.toString()
}

/** One monitor: its name, its power now, the last minute as a sparkline, and the range over the kept history. */
@Composable
private fun RailTile(row: RailRow, history: List<HistoryPoint>, recent: List<HistoryPoint>) {
    val index = row.info.index
    val name = parseRailName(row.info.name)
    val label = name.subsystem?.let { subsystem -> name.variant?.let { "$subsystem($it)" } ?: subsystem }
    val range = remember(history, index) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (point in history) {
            val value = point.powerMw.getOrNull(index) ?: continue
            if (!value.isNaN()) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        if (min <= max) min to max else null
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(name.rail, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Always a line, even when empty, so tiles with and without a label are the same height.
            Text(label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.reading?.powerMw?.let { stringResource(R.string.power_mw, String.format(Locale.ROOT, "%,.1f", it)) } ?: stringResource(R.string.no_data),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Sparkline(recent, index)
            Text(
                range?.let { (min, max) -> stringResource(R.string.tile_range, String.format(Locale.ROOT, "%,.1f", min), String.format(Locale.ROOT, "%,.1f", max)) }
                    ?: stringResource(R.string.no_data),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = muted,
                maxLines = 1,
            )
        }
    }
}

/** A single unlabelled line on a zero-based scale of its own: shape only, the figures are beside it. */
@Composable
private fun Sparkline(points: List<HistoryPoint>, index: Int) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 2.dp)) {
        val start = points.firstOrNull()?.elapsedMs ?: return@Canvas
        val peak = points.maxOf { it.powerMw.getOrNull(index)?.takeIf { value -> !value.isNaN() } ?: 0f }
        if (peak <= 0f) return@Canvas
        val path = Path()
        var penDown = false
        for (point in points) {
            val value = point.powerMw.getOrNull(index)
            if (value == null || value.isNaN()) {
                penDown = false
                continue
            }
            // The x scale is the full window, so a tile that has only just started fills from the left.
            val x = size.width * (point.elapsedMs - start).toFloat() / SPARKLINE_WINDOW_MS
            val y = size.height * (1f - value / peak)
            if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
            penDown = true
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color.copy(alpha = 0.25f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}
