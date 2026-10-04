// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.ui

import android.os.Build
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.BatterySample
import io.github.tengigabytes.gymnotus.power.DeviceMap
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.PowerTree
import io.github.tengigabytes.gymnotus.power.PowerTreeBuilder
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import io.github.tengigabytes.gymnotus.power.TreeInput
import io.github.tengigabytes.gymnotus.sampler.RailRow
import java.util.Locale

// By default groups and rails are put in the order the system lists the monitors, not by power: readings change
// twice a second, and a list that re-sorts itself that often cannot be read. Sorting by power is the user's call.
fun buildTree(rails: List<RailRow>, grouping: TreeGrouping, battery: BatterySample?, map: DeviceMap?, sortByPower: Boolean = false): PowerTree {
    val tree = PowerTreeBuilder.build(
        rails.filter { it.info.type == MonitorType.MEASUREMENT }.map { TreeInput(it.info.index, it.info.name, it.reading?.powerMw) },
        grouping,
        battery?.onExternalPower,
        map,
    )
    if (sortByPower) return tree
    return tree.copy(
        groups = tree.groups
            .map { group -> group.copy(leaves = group.leaves.sortedBy { it.index }) }
            .sortedBy { group -> group.leaves.minOf { it.index } },
    )
}

/**
 * Grouped MEASUREMENT rails followed by the CONSUMER list, which is shown separately and never summed in.
 * Rows are tappable: a tap adds the group or rail to [selection], i.e. to the chart, or takes it out again.
 */
fun LazyListScope.treeRows(tree: PowerTree, rails: List<RailRow>, grouping: TreeGrouping, selection: ChartSelection) {
    for (group in tree.groups) {
        val groupKey = groupKey(grouping, group.name)
        item(key = "group-${group.name}") {
            TreeRow(
                name = group.name,
                detail = if (group.missing > 0) stringResource(R.string.group_missing, group.missing) else null,
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
                text = stringResource(R.string.consumer_header, consumers.size),
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

/** Filled with the series colour while the row is charted, an empty ring otherwise. */
@Composable
fun SeriesDot(slot: Int?) {
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
                text = formatPower(powerMw) + formatShare(share),
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
        Text(stringResource(R.string.unmeasured), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            text = formatPower(powerMw) + formatShare(share),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Says where the grouping comes from, and which rails have no monitor and so can only show up as unmeasured. */
@Composable
fun mapLine(map: DeviceMap?): String {
    if (map == null) return stringResource(R.string.map_none, Build.DEVICE)
    val line = stringResource(R.string.map_line, map.description)
    if (map.unmonitored.isEmpty()) return line
    val bucks = map.unmonitored.filter { it.kind != "ldo" }.joinToString { it.rail }
    val ldos = map.unmonitored.count { it.kind == "ldo" }
    val unmonitored = if (ldos > 0) {
        stringResource(R.string.map_unmonitored_ldos, bucks, ldos)
    } else {
        stringResource(R.string.map_unmonitored, bucks)
    }
    // Chinese sentences end in a full-width stop and take no space after it.
    return if (line.endsWith("。")) line + unmonitored else "$line $unmonitored"
}

fun railKey(index: Int) = "r:$index"

fun groupKey(grouping: TreeGrouping, name: String) = "g:$grouping:$name"

/** What the chart shows before the user picks anything: the battery rail and the three largest subsystems. */
fun selectDefaults(selection: ChartSelection, tree: PowerTree, batteryLabel: String) {
    tree.batteryRailIndex?.let { selection.toggle(railKey(it), batteryLabel, listOf(it)) }
    for (group in tree.groups.sortedByDescending { it.powerMw ?: Double.NEGATIVE_INFINITY }.take(3)) {
        selection.toggle(groupKey(TreeGrouping.SUBSYSTEM, group.name), group.name, group.leaves.map { it.index })
    }
}

@Composable
fun formatPower(powerMw: Double?): String =
    if (powerMw == null) stringResource(R.string.no_data) else LocalPowerUnit.current.text(powerMw)

private fun formatShare(share: Double?) = share?.let { String.format(Locale.ROOT, " %5.1f%%", it * 100) } ?: ""
