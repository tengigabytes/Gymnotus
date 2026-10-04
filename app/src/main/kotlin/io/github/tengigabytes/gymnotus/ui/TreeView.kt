package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
/** Rows are tappable: a tap adds the group or rail to [selection], i.e. to the chart, or takes it out again. */
fun LazyListScope.treeView(
    tree: PowerTree,
    rails: List<RailRow>,
    battery: BatterySample?,
    grouping: TreeGrouping,
    selection: ChartSelection,
    chart: @Composable () -> Unit,
) {
    item(key = "tree-root") { TreeRoot(tree, rails, battery, selection) }
    item(key = "chart") { chart() }
    for (group in tree.groups) {
        val groupKey = groupKey(grouping, group.name)
        item(key = "group-${group.name}") {
            TreeRow(
                name = group.name,
                detail = if (group.missing > 0) "${group.missing} without data" else null,
                powerMw = group.powerMw,
                totalMw = tree.baseMw,
                isGroup = true,
                slot = selection.slotOf(groupKey),
                onClick = { selection.toggle(groupKey, group.name, group.leaves.map { it.index }) },
            )
        }
        items(group.leaves, key = { "leaf-${it.index}" }) { leaf ->
            TreeRow(
                name = leaf.rail,
                detail = leaf.detail,
                powerMw = leaf.powerMw,
                totalMw = tree.baseMw,
                isGroup = false,
                slot = selection.slotOf(railKey(leaf.index)),
                onClick = { selection.toggle(railKey(leaf.index), leaf.rail, listOf(leaf.index)) },
            )
        }
        item(key = "divider-${group.name}") { HorizontalDivider() }
    }
    if (tree.batteryIsRoot) {
        item(key = "unmeasured") {
            // Not a rail, so it has no history to chart.
            UnmeasuredRow(tree.unmeasuredMw, tree.baseMw)
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
            TreeRow(
                name = it.info.name,
                detail = null,
                powerMw = it.reading?.powerMw,
                totalMw = null,
                isGroup = false,
                slot = selection.slotOf(railKey(it.info.index)),
                onClick = { selection.toggle(railKey(it.info.index), it.info.name, listOf(it.info.index)) },
            )
        }
    }
}

@Composable
private fun TreeRoot(tree: PowerTree, rails: List<RailRow>, battery: BatterySample?, selection: ChartSelection) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (tree.batteryIsRoot) "Battery output" else "Σ measured rails", fontWeight = FontWeight.Bold)
            Text(formatPower(tree.baseMw), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        val batteryIndex = tree.batteryRailIndex
        if (batteryIndex != null) {
            // The battery rail is not a row of the tree, so it gets its own switch for the chart.
            Row(
                Modifier.clickable { selection.toggle(railKey(batteryIndex), BATTERY_SERIES_LABEL, listOf(batteryIndex)) },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeriesDot(selection.slotOf(railKey(batteryIndex)))
                Text("Chart the battery rail", style = small, color = muted)
            }
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

/** Filled with the series colour while the row is charted, an empty ring otherwise. */
@Composable
private fun SeriesDot(slot: Int?) {
    val shape = Modifier.size(10.dp).clip(CircleShape)
    if (slot != null) {
        Box(shape.background(chartColors().series[slot]))
    } else {
        Box(shape.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

@Composable
private fun TreeRow(
    name: String,
    detail: String?,
    powerMw: Double?,
    totalMw: Double?,
    isGroup: Boolean,
    slot: Int?,
    onClick: () -> Unit,
) {
    val weight = if (isGroup) FontWeight.Bold else FontWeight.Normal
    val style = if (isGroup) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val share = if (powerMw != null && totalMw != null && totalMw > 0) powerMw / totalMw else null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (isGroup) 16.dp else 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SeriesDot(slot)
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

@Composable
private fun UnmeasuredRow(powerMw: Double?, totalMw: Double?) {
    val share = if (powerMw != null && totalMw != null && totalMw > 0) powerMw / totalMw else null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Unmeasured · battery rail − measured rails", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            text = formatPower(powerMw) + (share?.let { String.format(Locale.ROOT, " %5.1f%%", it * 100) } ?: ""),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

fun railKey(index: Int) = "r:$index"

fun groupKey(grouping: TreeGrouping, name: String) = "g:$grouping:$name"

/** What the chart shows before the user picks anything: the battery rail and the three largest subsystems. */
fun selectDefaults(selection: ChartSelection, tree: PowerTree) {
    tree.batteryRailIndex?.let { selection.toggle(railKey(it), BATTERY_SERIES_LABEL, listOf(it)) }
    for (group in tree.groups.take(3)) {
        selection.toggle(groupKey(TreeGrouping.SUBSYSTEM, group.name), group.name, group.leaves.map { it.index })
    }
}

const val BATTERY_SERIES_LABEL = "Battery rail"

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
