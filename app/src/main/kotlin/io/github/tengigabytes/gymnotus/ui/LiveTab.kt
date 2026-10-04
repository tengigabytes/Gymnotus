package io.github.tengigabytes.gymnotus.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.DeviceMap
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.PowerTree
import io.github.tengigabytes.gymnotus.power.SankeyBuilder
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import io.github.tengigabytes.gymnotus.power.TreeInput
import io.github.tengigabytes.gymnotus.sampler.HistoryPoint
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import java.util.Locale

/** The picture above the list: where the power goes now, or how it changed over the last minutes. */
private enum class Visual(@StringRes val label: Int) {
    FLOW(R.string.view_flow),
    TREND(R.string.view_trend),
}

private val VISUAL_HEIGHT = 300.dp

// Eight labelled rows is what fits beside a 300 dp diagram without the labels crowding the bars.
private const val MAX_FLOW_SUBSYSTEMS = 8

/** Mean power per monitor index over the kept history; NaN where a monitor never had a value. */
private fun averagePower(history: List<HistoryPoint>): FloatArray {
    val size = history.firstOrNull()?.powerMw?.size ?: return FloatArray(0)
    val sums = DoubleArray(size)
    val counts = IntArray(size)
    for (point in history) {
        for (i in 0 until minOf(size, point.powerMw.size)) {
            val value = point.powerMw[i]
            if (!value.isNaN()) {
                sums[i] += value
                counts[i]++
            }
        }
    }
    return FloatArray(size) { if (counts[it] == 0) Float.NaN else (sums[it] / counts[it]).toFloat() }
}

/**
 * The everyday page. The headline number and the picture stay put; only the list of rails scrolls, so the
 * picture is still in view while rows are picked for the chart.
 */
@Composable
fun LiveTab(
    state: SamplerState,
    history: List<HistoryPoint>,
    selection: ChartSelection,
    deviceMap: DeviceMap?,
    grouping: TreeGrouping,
    onGrouping: (TreeGrouping) -> Unit,
    onShowSetup: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.listState != MonitorListState.READY) {
        ListStatus(state, onRetry)
        return
    }
    var visual by rememberSaveable { mutableStateOf(Visual.FLOW) }
    val tree = buildTree(state.rails, grouping, state.battery, deviceMap)
    Column(Modifier.fillMaxSize()) {
        Headline(tree, state, selection)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in Visual.entries) {
                FilterChip(selected = visual == entry, onClick = { visual = entry }, label = { Text(stringResource(entry.label)) })
            }
        }
        when (visual) {
            Visual.FLOW -> {
                // Which subsystems get their own node is decided on their recent average, not the latest reading,
                // so they do not hop in and out of "other" from one refresh to the next.
                val average = remember(history) { averagePower(history) }
                val model = SankeyBuilder.build(
                    state.rails.filter { it.info.type == MonitorType.MEASUREMENT }.map { TreeInput(it.info.index, it.info.name, it.reading?.powerMw) },
                    state.battery?.onExternalPower,
                    deviceMap,
                    maxSubsystems = MAX_FLOW_SUBSYSTEMS,
                    rankMw = { average.getOrNull(it.index)?.takeIf { mean -> !mean.isNaN() }?.toDouble() ?: it.powerMw ?: 0.0 },
                )
                if (model != null) SankeyDiagram(model, selection, VISUAL_HEIGHT)
            }
            // The chart's own header, caption and legend take about 130 dp of the same space.
            // In standard mode a minute holds only three readings.
            Visual.TREND -> TimelineChart(history, selection, defaultWindowMs = if (state.finePermission) 60_000L else 300_000L, plotHeight = 170.dp)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (!state.finePermission) item(key = "setup-card") { SetupCard(onShowSetup) }
            item(key = "grouping") { GroupingChips(grouping, onGrouping) }
            treeRows(tree, state.rails, grouping, selection)
        }
    }
}

/** The one number this app is for: what the device draws right now, or the best stand-in when that is unknown. */
@Composable
private fun Headline(tree: PowerTree, state: SamplerState, selection: ChartSelection) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (tree.batteryIsRoot) R.string.tree_root_battery else R.string.tree_root_sum),
                style = MaterialTheme.typography.labelLarge,
                color = muted,
            )
            Text(
                tree.baseMw?.let { stringResource(R.string.power_mw, String.format(Locale.ROOT, "%,.0f", it)) }
                    ?: stringResource(R.string.no_data),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            val detail = when {
                tree.batteryIsRoot -> stringResource(R.string.hero_detail_battery, formatPower(tree.railsSumMw), formatPower(tree.unmeasuredMw))
                !tree.hasBatteryRail -> null
                state.battery?.onExternalPower == true -> stringResource(R.string.hero_detail_external, formatPower(tree.batteryRailMw))
                else -> stringResource(R.string.hero_detail_unknown, formatPower(tree.batteryRailMw))
            }
            if (detail != null) Text(detail, style = small, color = muted)
            val batteryIndex = tree.batteryRailIndex
            if (batteryIndex != null) {
                // The battery rail is not a row of the list, so its switch for the chart lives here.
                val label = stringResource(R.string.battery_series)
                Row(
                    Modifier.clickable { selection.toggle(railKey(batteryIndex), label, listOf(batteryIndex)) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeriesDot(selection.slotOf(railKey(batteryIndex)))
                    Text(stringResource(R.string.tree_chart_battery), style = small, color = muted)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    stringResource(if (state.finePermission) R.string.badge_fast else R.string.badge_standard),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            val ageMs = state.rails.firstOrNull { it.info.type == MonitorType.MEASUREMENT }?.ageMs
            if (ageMs != null) {
                Text(stringResource(R.string.data_age, String.format(Locale.ROOT, "%.1f", ageMs / 1000.0)), style = small, color = muted)
            }
        }
    }
}

@Composable
private fun GroupingChips(selected: TreeGrouping, onSelect: (TreeGrouping) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == TreeGrouping.SUBSYSTEM,
                onClick = { onSelect(TreeGrouping.SUBSYSTEM) },
                label = { Text(stringResource(R.string.view_subsystem)) },
            )
            FilterChip(
                selected = selected == TreeGrouping.SOURCE,
                onClick = { onSelect(TreeGrouping.SOURCE) },
                label = { Text(stringResource(R.string.view_source)) },
            )
        }
        Text(stringResource(R.string.live_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown only in standard mode: the 20 s limit is the first thing a new user runs into. */
@Composable
private fun SetupCard(onShowSetup: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.setup_card_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.setup_card_body), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onShowSetup, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.setup_card_action)) }
        }
    }
}
