@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tengigabytes.gymnotus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.IntervalSummary
import io.github.tengigabytes.gymnotus.power.MonitorType
import io.github.tengigabytes.gymnotus.power.ReadingStatus
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import io.github.tengigabytes.gymnotus.sampler.LogStatus
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.RailRow
import io.github.tengigabytes.gymnotus.sampler.Sampler
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import java.util.Locale

// ACCESS_LOCAL_NETWORK is new in Android 17 (API 37); older releases ignore the request and need no such permission.
@SuppressLint("InlinedApi")
private val SETUP_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_LOCAL_NETWORK)

private const val GRANT_COMMAND =
    "adb shell pm grant io.github.tengigabytes.gymnotus android.permission.ACCESS_FINE_POWER_MONITORS"

@Composable
fun ProbeScreen(viewModel: ProbeViewModel) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val history by viewModel.history.collectAsState()
    val deviceMap by viewModel.deviceMap.collectAsState()
    val selection = remember { ChartSelection() }
    val batteryLabel = stringResource(R.string.battery_series)
    var defaultsApplied by remember { mutableStateOf(false) }
    if (!defaultsApplied && state.rails.any { it.reading?.powerMw != null }) {
        LaunchedEffect(Unit) {
            selectDefaults(selection, buildTree(state.rails, TreeGrouping.SUBSYSTEM, state.battery, deviceMap), batteryLabel)
            defaultsApplied = true
        }
    }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.BY_SUBSYSTEM) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri) else viewModel.cancelExport()
    }
    val logLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.startLog(uri)
    }
    // The log runs whether or not its notification may be shown, so the answer is not used.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        logLauncher.launch(viewModel.suggestedLogName())
    }

    val setupPermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        viewModel.setUpFastMode(notificationsAllowed = results[Manifest.permission.POST_NOTIFICATIONS] == true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Summary(
                    state,
                    onRetry = viewModel::retry,
                    onSetUp = {
                        // Asked here, inside the app: a permission prompt over Settings would close the pairing dialog.
                        setupPermissionsLauncher.launch(SETUP_PERMISSIONS)
                    },
                )
            }
            if (state.listState == MonitorListState.READY) {
                item {
                    Controls(
                        state = state,
                        message = message,
                        onInterval = viewModel::setInterval,
                        onReset = viewModel::reset,
                        onExport = { viewModel.prepareExport()?.let(exportLauncher::launch) },
                        onStartLog = {
                            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                logLauncher.launch(viewModel.suggestedLogName())
                            } else {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onStopLog = viewModel::stopLog,
                        onSlowWhenScreenOff = viewModel::setSlowWhenScreenOff,
                    )
                }
                item { ViewModeChips(viewMode, onSelect = { viewMode = it }) }
                val grouping = viewMode.grouping
                if (grouping != null) {
                    treeView(buildTree(state.rails, grouping, state.battery, deviceMap), state.rails, state.battery, grouping, selection, deviceMap) {
                        // In standard mode a minute holds only three readings.
                        TimelineChart(history, selection, defaultWindowMs = if (state.finePermission) 60_000L else 300_000L)
                    }
                } else {
                    // UNKNOWN first: a type this build does not know about is itself a finding.
                    for (type in listOf(MonitorType.UNKNOWN, MonitorType.MEASUREMENT, MonitorType.CONSUMER)) {
                        railSection(type, state.rails.filter { it.info.type == type })
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(state: SamplerState, onRetry: () -> Unit, onSetUp: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Mono("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · API ${Build.VERSION.SDK_INT}")
        when (state.listState) {
            MonitorListState.LOADING -> Mono(stringResource(R.string.list_waiting))
            MonitorListState.EMPTY -> {
                Mono(stringResource(R.string.list_empty))
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            MonitorListState.FAILED -> {
                // The system's own error text, shown as it came.
                Mono(state.listError.orEmpty(), error = true)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            MonitorListState.READY -> {
                val measurement = state.rails.count { it.info.type == MonitorType.MEASUREMENT }
                val consumer = state.rails.count { it.info.type == MonitorType.CONSUMER }
                Mono(stringResource(R.string.summary_monitors, state.rails.size, measurement, consumer))
                Mono(stringResource(R.string.summary_polls, state.polls, state.pollErrors, minMedMax(state.callLatency)))
                state.lastPollError?.let { Mono(stringResource(R.string.last_error, it), error = true) }
                ModeNotice(state.finePermission, onSetUp)
                Mono(stringResource(R.string.overhead_notice))
            }
        }
    }
}

/** Which of the two refresh limits applies, and how to get the faster one. */
@Composable
private fun ModeNotice(finePermission: Boolean, onSetUp: () -> Unit) {
    if (finePermission) {
        Mono(stringResource(R.string.mode_fast))
        return
    }
    val context = LocalContext.current
    Mono(stringResource(R.string.mode_standard))
    Mono(stringResource(R.string.setup_steps))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSetUp) { Text(stringResource(R.string.setup_button)) }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    // Developer options are hidden until enabled in About phone; nothing to open yet.
                }
            },
        ) { Text(stringResource(R.string.developer_options)) }
    }
    Mono(stringResource(R.string.setup_computer))
    SelectionContainer { Mono(GRANT_COMMAND) }
}

@Composable
private fun Controls(
    state: SamplerState,
    message: String?,
    onInterval: (Int) -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onStartLog: () -> Unit,
    onStopLog: () -> Unit,
    onSlowWhenScreenOff: (Boolean) -> Unit,
) {
    val log = state.log
    Column(Modifier.padding(horizontal = 16.dp)) {
        // One log has one interval and one run of poll numbers, so these are locked while it is open.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ms in Sampler.INTERVAL_CHOICES_MS) {
                FilterChip(
                    selected = state.intervalMs == ms,
                    enabled = log == null,
                    onClick = { onInterval(ms) },
                    label = { Text(stringResource(R.string.interval_ms, ms)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReset, enabled = log == null) { Text(stringResource(R.string.reset)) }
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.export_buffer)) }
            val filled = String.format(Locale.ROOT, "%.0f", state.bufferSpanMs / 1000.0)
            Mono(stringResource(R.string.buffer_span, filled, (Sampler.BUFFER_SPAN_MS / 1000).toInt()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (log == null) {
                Button(onClick = onStartLog) { Text(stringResource(R.string.start_log)) }
                Mono(stringResource(R.string.log_hint))
            } else {
                Button(onClick = onStopLog) { Text(stringResource(R.string.stop_log)) }
                LogLine(log)
            }
        }
        FilterChip(
            selected = state.slowWhenScreenOff,
            onClick = { onSlowWhenScreenOff(!state.slowWhenScreenOff) },
            label = { Text(stringResource(R.string.slow_screen_off, Sampler.SCREEN_OFF_INTERVAL_MS / 1000)) },
        )
        message?.let { Mono(it) }
    }
}

@Composable
private fun LogLine(log: LogStatus) {
    if (log.error != null) {
        Mono(log.error, error = true)
        return
    }
    val seconds = (SystemClock.elapsedRealtime() - log.startedElapsedMs) / 1000
    val elapsed = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    Mono(stringResource(R.string.log_status, log.fileName, log.polls, elapsed))
}

/** @property grouping tree grouping, or null for the flat list of raw readings and statistics */
private enum class ViewMode(@StringRes val label: Int, val grouping: TreeGrouping?) {
    BY_SUBSYSTEM(R.string.view_subsystem, TreeGrouping.SUBSYSTEM),
    BY_SOURCE(R.string.view_source, TreeGrouping.SOURCE),
    RAW(R.string.view_raw, null),
}

@Composable
private fun ViewModeChips(selected: ViewMode, onSelect: (ViewMode) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in ViewMode.entries) {
            FilterChip(selected = mode == selected, onClick = { onSelect(mode) }, label = { Text(stringResource(mode.label)) })
        }
    }
}

private fun LazyListScope.railSection(type: MonitorType, rows: List<RailRow>) {
    if (rows.isEmpty()) return
    item(key = "header-$type") {
        Text(
            // The type is the API's own constant name, kept as it is.
            text = stringResource(R.string.raw_section, type.name, rows.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.info.index }) { RailItem(it) }
}

@Composable
private fun RailItem(row: RailRow) {
    val reading = row.reading
    val stats = row.stats
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.info.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                text = when {
                    reading == null -> "—"
                    reading.status == ReadingStatus.UNAVAILABLE -> stringResource(R.string.no_data)
                    // FIRST or RESET: no power yet; the status name is the CSV's own vocabulary.
                    reading.powerMw == null -> reading.status.name
                    reading.status == ReadingStatus.STALE -> stringResource(R.string.raw_stale, twoDecimals(reading.powerMw))
                    else -> stringResource(R.string.power_mw, twoDecimals(reading.powerMw))
                },
                fontFamily = FontFamily.Monospace,
                color = if (reading?.status == ReadingStatus.OK) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (reading != null) {
            val energy = reading.energyUws?.let { stringResource(R.string.raw_energy_uj, String.format(Locale.ROOT, "%,d", it)) }
                ?: stringResource(R.string.raw_energy_unavailable)
            Mono(stringResource(R.string.raw_reading, energy, String.format(Locale.ROOT, "%,d", reading.timestampMs), row.ageMs ?: 0L))
        }
        Mono(stringResource(R.string.raw_intervals, stats.updateIntervals.count, minMedMax(stats.updateIntervals)))
        Mono(stringResource(R.string.raw_counts, stats.repeats, stats.polls, percent(stats.repeats, stats.polls), stats.unavailable, stats.resets))
        if (stats.staleEnergyChanges > 0) Mono(stringResource(R.string.raw_stale_energy, stats.staleEnergyChanges), error = true)
    }
    HorizontalDivider()
}

@Composable
private fun Mono(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun minMedMax(summary: IntervalSummary): String =
    if (summary.count == 0L) {
        stringResource(R.string.min_med_max_none)
    } else {
        stringResource(R.string.min_med_max, summary.minMs ?: 0L, summary.medianMs ?: 0L, summary.maxMs ?: 0L)
    }

private fun twoDecimals(value: Double) = String.format(Locale.ROOT, "%.2f", value)

private fun percent(part: Long, total: Long) =
    if (total == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total)
