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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val selection = remember { ChartSelection() }
    var defaultsApplied by remember { mutableStateOf(false) }
    if (!defaultsApplied && state.rails.any { it.reading?.powerMw != null }) {
        LaunchedEffect(Unit) {
            selectDefaults(selection, buildTree(state.rails, TreeGrouping.SUBSYSTEM, state.battery))
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

    Scaffold(topBar = { TopAppBar(title = { Text("Gymnotus") }) }) { padding ->
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
                    treeView(buildTree(state.rails, grouping, state.battery), state.rails, state.battery, grouping, selection) {
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
            MonitorListState.LOADING -> Mono("getSupportedPowerMonitors: waiting…")
            MonitorListState.EMPTY -> {
                Mono("getSupportedPowerMonitors returned an empty list: no power monitors on this device.")
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            MonitorListState.FAILED -> {
                Mono(state.listError.orEmpty(), error = true)
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            MonitorListState.READY -> {
                val measurement = state.rails.count { it.info.type == MonitorType.MEASUREMENT }
                val consumer = state.rails.count { it.info.type == MonitorType.CONSUMER }
                Mono("monitors ${state.rails.size}: MEASUREMENT $measurement, CONSUMER $consumer")
                Mono("polls ${state.polls} · errors ${state.pollErrors} · call latency ${state.callLatency.minMedMax()}")
                state.lastPollError?.let { Mono("last error: $it", error = true) }
                ModeNotice(state.finePermission, onSetUp)
                Mono("Gymnotus adds load itself: its polling and screen updates show up in the CPU and display rails.")
            }
        }
    }
}

/** Which of the two refresh limits applies, and how to get the faster one. */
@Composable
private fun ModeNotice(finePermission: Boolean, onSetUp: () -> Unit) {
    if (finePermission) {
        Mono("Fast mode: readings refresh about every 0.5 s (fine permission granted).")
        return
    }
    val context = LocalContext.current
    Mono("Standard mode: the system refreshes readings every 20 s. A one-time setup makes that about 0.5 s:")
    Mono("1. Connect to Wi-Fi. 2. Tap Set up fast mode and allow notifications and nearby devices. 3. In Developer options, turn on Wireless debugging and tap \"Pair device with pairing code\". 4. Pull down the notifications and type the code into the Gymnotus one.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSetUp) { Text("Set up fast mode") }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    // Developer options are hidden until enabled in About phone; nothing to open yet.
                }
            },
        ) { Text("Developer options") }
    }
    Mono("Or from a computer:")
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
                    label = { Text("$ms ms") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReset, enabled = log == null) { Text("Reset") }
            OutlinedButton(onClick = onExport) { Text("Export buffer") }
            Mono(String.format(Locale.ROOT, "%.0f / %d s", state.bufferSpanMs / 1000.0, Sampler.BUFFER_SPAN_MS / 1000))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (log == null) {
                Button(onClick = onStartLog) { Text("Start log") }
                Mono("Records every poll to a CSV file, also in the background.")
            } else {
                Button(onClick = onStopLog) { Text("Stop log") }
                LogLine(log)
            }
        }
        FilterChip(
            selected = state.slowWhenScreenOff,
            onClick = { onSlowWhenScreenOff(!state.slowWhenScreenOff) },
            label = { Text("Poll every ${Sampler.SCREEN_OFF_INTERVAL_MS / 1000} s while the screen is off") },
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
    Mono(String.format(Locale.ROOT, "%s · %d polls · %d:%02d", log.fileName, log.polls, seconds / 60, seconds % 60))
}

/** @property grouping tree grouping, or null for the flat list of raw readings and statistics */
private enum class ViewMode(val label: String, val grouping: TreeGrouping?) {
    BY_SUBSYSTEM("By subsystem", TreeGrouping.SUBSYSTEM),
    BY_SOURCE("By source", TreeGrouping.SOURCE),
    RAW("Raw", null),
}

@Composable
private fun ViewModeChips(selected: ViewMode, onSelect: (ViewMode) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in ViewMode.entries) {
            FilterChip(selected = mode == selected, onClick = { onSelect(mode) }, label = { Text(mode.label) })
        }
    }
}

private fun LazyListScope.railSection(type: MonitorType, rows: List<RailRow>) {
    if (rows.isEmpty()) return
    item(key = "header-$type") {
        Text(
            text = "$type (${rows.size})",
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
                    reading.status == ReadingStatus.UNAVAILABLE -> "no data"
                    reading.powerMw == null -> reading.status.name
                    reading.status == ReadingStatus.STALE -> String.format(Locale.ROOT, "%.2f mW (stale)", reading.powerMw)
                    else -> String.format(Locale.ROOT, "%.2f mW", reading.powerMw)
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
            val energy = reading.energyUws?.let { String.format(Locale.ROOT, "%,d µJ", it) } ?: "unavailable"
            Mono(String.format(Locale.ROOT, "E %s · t %,d ms · age %d ms", energy, reading.timestampMs, row.ageMs))
        }
        Mono("Δt n=${stats.updateIntervals.count} ${stats.updateIntervals.minMedMax()}")
        Mono(
            "repeat ${stats.repeats}/${stats.polls} (${percent(stats.repeats, stats.polls)})" +
                " · unavail ${stats.unavailable} · reset ${stats.resets}" +
                if (stats.staleEnergyChanges > 0) " · E changed at same t: ${stats.staleEnergyChanges}" else "",
        )
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

private fun IntervalSummary.minMedMax() =
    if (count == 0L) "min/med/max —" else "min/med/max $minMs/$medianMs/$maxMs ms"

private fun percent(part: Long, total: Long) =
    if (total == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total)
