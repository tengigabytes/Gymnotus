package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.power.BatterySample
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.PowerTree
import io.github.tengigabytes.gymnotus.power.PowerTreeBuilder
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import io.github.tengigabytes.gymnotus.power.TreeInput
import io.github.tengigabytes.gymnotus.sampler.RailRow
import java.util.Locale

fun buildTree(rails: List<RailRow>, grouping: TreeGrouping, battery: BatterySample?): PowerTree = PowerTreeBuilder.build(
    rails.filter { it.info.type == MonitorType.MEASUREMENT }.map {
        TreeInput(it.info.index, it.info.name, it.reading?.powerMw)
    },
    grouping,
    battery?.onExternalPower,
)

/** Grouped MEASUREMENT rails followed by the CONSUMER list, which is shown separately and never summed in. */
fun LazyListScope.treeView(tree: PowerTree, rails: List<RailRow>, battery: BatterySample?) {
    item(key = "tree-root") { TreeRoot(tree, rails, battery) }
    for (group in tree.groups) {
        item(key = "group-${group.name}") {
            TreeRow(
                name = group.name,
                detail = if (group.missing > 0) "${group.missing} without data" else null,
                powerMw = group.powerMw,
                totalMw = tree.baseMw,
                isGroup = true,
            )
        }
        items(group.leaves, key = { "leaf-${it.index}" }) { leaf ->
            TreeRow(
                name = leaf.rail,
                detail = leaf.detail,
                powerMw = leaf.powerMw,
                totalMw = tree.baseMw,
                isGroup = false,
            )
        }
        item(key = "divider-${group.name}") { HorizontalDivider() }
    }
    if (tree.batteryIsRoot) {
        item(key = "unmeasured") {
            TreeRow("Unmeasured", "battery rail − measured rails", tree.unmeasuredMw, tree.baseMw, isGroup = true)
        }
    }
    val consumers = rails.filter { it.info.type == MonitorType.CONSUMER }
    if (consumers.isNotEmpty()) {
        item(key = "consumer-header") {
            Text(
                text = "CONSUMER (${consumers.size}) · modelled, overlaps the rails above",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(consumers, key = { "consumer-${it.info.index}" }) {
            TreeRow(it.info.name, null, it.reading?.powerMw, totalMw = null, isGroup = false)
        }
    }
}

@Composable
private fun TreeRoot(tree: PowerTree, rails: List<RailRow>, battery: BatterySample?) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (tree.batteryIsRoot) "Battery output" else "Σ measured rails", fontWeight = FontWeight.Bold)
            Text(formatPower(tree.baseMw), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        if (tree.batteryIsRoot) {
            Text("${PowerTreeBuilder.BATTERY_RAIL} · measured rails below sum to ${formatPower(tree.railsSumMw)}", style = small, color = muted)
        } else if (tree.hasBatteryRail) {
            val source = if (battery?.onExternalPower == true) "On external power" else "Power source unknown"
            Text(
                "$source: no device total. ${PowerTreeBuilder.BATTERY_RAIL} (${formatPower(tree.batteryRailMw)}) counts battery discharge only.",
                style = small,
                color = muted,
            )
        }
        Text(batteryLine(battery), style = small, color = muted)
        val ageMs = rails.firstOrNull { it.info.type == MonitorType.MEASUREMENT }?.ageMs
        val age = ageMs?.let { String.format(Locale.ROOT, "data age %.1f s", it / 1000.0) } ?: "no data yet"
        val missing = if (tree.missing > 0) " · ${tree.missing} rails without data" else ""
        Text("$age$missing", style = small, color = muted)
        Text("Rails are grouped by name only: topology below the battery is unknown.", style = small, color = muted)
    }
    HorizontalDivider()
}

@Composable
private fun TreeRow(name: String, detail: String?, powerMw: Double?, totalMw: Double?, isGroup: Boolean) {
    val weight = if (isGroup) FontWeight.Bold else FontWeight.Normal
    val style = if (isGroup) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val share = if (powerMw != null && totalMw != null && totalMw > 0) powerMw / totalMw else null
    Column(Modifier.fillMaxWidth().padding(start = if (isGroup) 16.dp else 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (detail != null) "$name · $detail" else name,
                style = style,
                fontWeight = weight,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPower(powerMw) + (share?.let { String.format(Locale.ROOT, " %5.1f%%", it * 100) } ?: ""),
                style = style,
                fontFamily = FontFamily.Monospace,
                fontWeight = weight,
            )
        }
        if (share != null) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth(share.coerceIn(0.0, 1.0).toFloat())
                    .height(if (isGroup) 4.dp else 2.dp)
                    .background(if (share > 0) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
        }
    }
}

/** BatteryManager's own numbers, as an independent cross-check of the battery rail. */
private fun batteryLine(battery: BatterySample?): String {
    if (battery == null) return "BatteryManager: no sample yet"
    val status = when (battery.status) {
        2 -> "charging"
        3 -> "discharging"
        4 -> "not charging"
        5 -> "full"
        else -> "status ${battery.status ?: "?"}"
    }
    val current = battery.currentUa?.let { String.format(Locale.ROOT, "%.1f mA", it / 1000.0) } ?: "? mA"
    val voltage = battery.voltageMv?.let { "$it mV" } ?: "? mV"
    val power = battery.powerMw?.let { String.format(Locale.ROOT, "%.1f mW", it) } ?: "no data"
    return "BatteryManager (instant, raw sign): $current × $voltage = $power · $status · plugged=${battery.plugged ?: "?"}"
}

private fun formatPower(powerMw: Double?) =
    if (powerMw == null) "no data" else String.format(Locale.ROOT, "%.1f mW", powerMw)
