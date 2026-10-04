package io.github.tengigabytes.gymnotus.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.DeviceMap
import io.github.tengigabytes.gymnotus.power.IntervalSummary
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.ReadingStatus
import io.github.tengigabytes.gymnotus.power.parseRailName
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.RailRow
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import java.util.Locale

private const val DASH = "—"

/**
 * Everything needed to judge or tune the measurement itself: sources, mode, polling, cross-checks, raw readings.
 *
 * Every changing value sits alone on a line, right-aligned in tabular digits ([Field]). Lines never wrap and
 * never change length, so a refresh changes digits in place and nothing shifts.
 */
@Composable
fun DetailsTab(
    state: SamplerState,
    deviceMap: DeviceMap?,
    onReset: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "device") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(stringResource(R.string.section_device))
                Mono("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · API ${Build.VERSION.SDK_INT}")
            }
        }
        if (state.listState != MonitorListState.READY) {
            item(key = "status") { ListStatus(state, onRetry) }
            return@LazyColumn
        }
        item(key = "sources") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val measurement = state.rails.count { it.info.type == MonitorType.MEASUREMENT }
                val consumer = state.rails.count { it.info.type == MonitorType.CONSUMER }
                Mono(stringResource(R.string.summary_monitors, state.rails.size, measurement, consumer))
                Mono(mapLine(deviceMap))
            }
        }
        item(key = "polling") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(stringResource(R.string.section_polling))
                Field(stringResource(R.string.field_interval), stringResource(R.string.interval_ms, state.intervalMs))
                // Clears the statistics below and the buffer; locked while a log is open.
                OutlinedButton(onClick = onReset, enabled = state.log == null) { Text(stringResource(R.string.reset)) }
                Field(stringResource(R.string.field_polls), integer(state.polls))
                Field(stringResource(R.string.field_errors), integer(state.pollErrors), error = state.pollErrors > 0)
                Field(stringResource(R.string.field_latency), minMedMax(state.callLatency))
                // The system's own error text, shown as it came.
                state.lastPollError?.let { Mono(stringResource(R.string.last_error, it), error = true) }
                Mono(stringResource(R.string.overhead_notice))
            }
        }
        item(key = "crosscheck") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(stringResource(R.string.section_crosscheck))
                CrossChecks(state.rails, deviceMap)
            }
        }
        item(key = "raw-title") {
            Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle(stringResource(R.string.section_raw)) }
        }
        // UNKNOWN first: a type this build does not know about is itself a finding.
        for (type in listOf(MonitorType.UNKNOWN, MonitorType.MEASUREMENT, MonitorType.CONSUMER)) {
            railSection(type, state.rails.filter { it.info.type == type })
        }
    }
}

@Composable
private fun CrossChecks(rails: List<RailRow>, map: DeviceMap?) {
    val checks = map?.crossChecks.orEmpty()
    if (checks.isEmpty()) {
        Mono(stringResource(R.string.crosscheck_none))
        return
    }
    Text(stringResource(R.string.crosscheck_hint), style = MaterialTheme.typography.bodyMedium)
    for (check in checks) {
        val consumer = rails.firstOrNull { it.info.type == MonitorType.CONSUMER && it.info.name == check.consumer }?.reading?.powerMw
        val parts = check.rails.map { name -> rails.firstOrNull { parseRailName(it.info.name).rail == name }?.reading?.powerMw }
        // A sum only when every rail has data: a partial sum would look like a mismatch.
        val sum = if (parts.all { it != null }) parts.sumOf { it!! } else null
        Column(Modifier.padding(top = 6.dp)) {
            Text(check.consumer, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Mono(check.rails.joinToString(" + "))
            Field(stringResource(R.string.crosscheck_system), milliwatts(consumer))
            Field(stringResource(R.string.crosscheck_sum), milliwatts(sum))
            Field(
                stringResource(R.string.crosscheck_diff),
                if (consumer != null && sum != null) String.format(Locale.ROOT, "%+.1f mW", consumer - sum) else DASH,
            )
        }
    }
}

private fun LazyListScope.railSection(type: MonitorType, rows: List<RailRow>) {
    if (rows.isEmpty()) return
    item(key = "header-$type") {
        Text(
            // The type is the API's own constant name, kept as it is.
            text = stringResource(R.string.raw_section, type.name, rows.size),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.info.index }) { RailItem(it) }
}

@Composable
private fun RailItem(row: RailRow) {
    val reading = row.reading
    val stats = row.stats
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.info.name,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    reading == null -> DASH
                    reading.status == ReadingStatus.UNAVAILABLE -> stringResource(R.string.no_data)
                    // FIRST or RESET: no power yet; the status name is the CSV's own vocabulary.
                    reading.powerMw == null -> reading.status.name
                    else -> stringResource(R.string.power_mw, String.format(Locale.ROOT, "%,.2f", reading.powerMw))
                },
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        // The same lines for every rail and every state, so a refresh changes digits only.
        // The status has a line of its own (OK, STALE, …): as a suffix on the power it would shift the number.
        Field(stringResource(R.string.field_reading_status), reading?.status?.name ?: DASH)
        Field(stringResource(R.string.field_energy), reading?.energyUws?.let { String.format(Locale.ROOT, "%,d µJ", it) } ?: DASH)
        Field(stringResource(R.string.field_timestamp), reading?.let { String.format(Locale.ROOT, "%,d ms", it.timestampMs) } ?: DASH)
        Field(stringResource(R.string.field_age), row.ageMs?.let { String.format(Locale.ROOT, "%,d ms", it) } ?: DASH)
        Field(stringResource(R.string.field_intervals, stats.updateIntervals.count), minMedMax(stats.updateIntervals))
        Field(stringResource(R.string.field_repeats), "${integer(stats.repeats)} / ${integer(stats.polls)}")
        Field(stringResource(R.string.field_unavailable_resets), "${integer(stats.unavailable)} / ${integer(stats.resets)}")
        if (stats.staleEnergyChanges > 0) Field(stringResource(R.string.raw_stale_energy), integer(stats.staleEnergyChanges), error = true)
    }
    HorizontalDivider()
}

private fun minMedMax(summary: IntervalSummary) =
    if (summary.count == 0L) DASH else "${summary.minMs} / ${summary.medianMs} / ${summary.maxMs} ms"

private fun integer(value: Long) = String.format(Locale.ROOT, "%,d", value)

private fun milliwatts(value: Double?) = value?.let { String.format(Locale.ROOT, "%,.1f mW", it) } ?: DASH
