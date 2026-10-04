package io.github.tengigabytes.gymnotus.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerMonitor
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tengigabytes.gymnotus.power.BatteryReader
import io.github.tengigabytes.gymnotus.power.BatterySample
import io.github.tengigabytes.gymnotus.power.CsvExport
import io.github.tengigabytes.gymnotus.power.ExportMeta
import io.github.tengigabytes.gymnotus.power.IntervalStats
import io.github.tengigabytes.gymnotus.power.IntervalSummary
import io.github.tengigabytes.gymnotus.power.MonitorInfo
import io.github.tengigabytes.gymnotus.power.PollRecord
import io.github.tengigabytes.gymnotus.power.PowerMonitorSource
import io.github.tengigabytes.gymnotus.power.RailReading
import io.github.tengigabytes.gymnotus.power.RailStats
import io.github.tengigabytes.gymnotus.power.RailTracker
import io.github.tengigabytes.gymnotus.power.toInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** @property ageMs elapsedRealtime at the result callback minus the rail's own timestamp */
data class RailRow(
    val info: MonitorInfo,
    val reading: RailReading?,
    val ageMs: Long?,
    val stats: RailStats,
)

enum class MonitorListState { LOADING, READY, EMPTY, FAILED }

data class ProbeUiState(
    val listState: MonitorListState = MonitorListState.LOADING,
    val listError: String? = null,
    val rails: List<RailRow> = emptyList(),
    val battery: BatterySample? = null,
    val finePermission: Boolean = false,
    val intervalMs: Int = ProbeViewModel.DEFAULT_INTERVAL_MS,
    val polls: Long = 0,
    val pollErrors: Long = 0,
    val lastPollError: String? = null,
    val callLatency: IntervalSummary = IntervalSummary(0, null, null, null, null),
    val bufferSpanMs: Long = 0,
    val bufferPolls: Int = 0,
    val exportMessage: String? = null,
)

/**
 * Phase 0 probe: polls every supported power monitor at a selectable interval, keeps the last
 * [BUFFER_SPAN_MS] of raw readings for export, and accumulates per-rail update statistics.
 *
 * All state is confined to the main thread; only the CSV write leaves it.
 */
class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val source = PowerMonitorSource(app)
    private val batteryReader = BatteryReader(app)

    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()

    private var monitors: List<PowerMonitor> = emptyList()
    private var infos: List<MonitorInfo> = emptyList()
    private var trackers: List<RailTracker> = emptyList()
    private val callLatency = IntervalStats()
    private val buffer = ArrayDeque<PollRecord>()

    private var intervalMs = DEFAULT_INTERVAL_MS
    private var seq = 0L
    private var pollErrors = 0L
    private var lastPollError: String? = null

    // Bumped by reset(); a poll that was in flight across a reset is discarded.
    private var generation = 0

    private var pollJob: Job? = null
    private var pendingExport: ExportSnapshot? = null

    private class ExportSnapshot(
        val meta: ExportMeta,
        val monitors: List<MonitorInfo>,
        val stats: List<RailStats>,
        val polls: List<PollRecord>,
    )

    /** Called while the activity is visible; polling stops in [stop] so pauses do not distort the statistics. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            if (_state.value.listState != MonitorListState.READY && !loadMonitors()) return@launch
            // The gap since the last poll before a pause must not show up as one long update interval.
            trackers.forEach { it.dropBaseline() }
            var next = SystemClock.elapsedRealtime()
            while (isActive) {
                pollOnce()
                next += intervalMs
                val now = SystemClock.elapsedRealtime()
                if (next < now) next = now
                delay(next - now)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun retry() {
        stop()
        _state.value = ProbeUiState(intervalMs = intervalMs)
        start()
    }

    fun setInterval(ms: Int) {
        if (ms == intervalMs) return
        intervalMs = ms
        reset()
    }

    /** Clears statistics and the export buffer so both describe a single polling interval. */
    fun reset() {
        generation++
        trackers.forEach { it.reset() }
        callLatency.clear()
        buffer.clear()
        seq = 0
        pollErrors = 0
        lastPollError = null
        publish(lastRecord = null)
    }

    /** Freezes the current buffer for export; returns the suggested file name, or null if there is nothing yet. */
    fun prepareExport(): String? {
        if (buffer.isEmpty()) {
            _state.update { it.copy(exportMessage = "Nothing to export yet") }
            return null
        }
        val now = OffsetDateTime.now()
        pendingExport = ExportSnapshot(
            meta = ExportMeta(
                exportedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                fingerprint = Build.FINGERPRINT,
                sdkInt = Build.VERSION.SDK_INT,
                pollIntervalMs = intervalMs,
                polls = seq,
                pollErrors = pollErrors,
                callLatency = callLatency.summary(),
                finePowerMonitors = source.hasFinePermission,
            ),
            monitors = infos,
            stats = trackers.map { it.stats() },
            polls = buffer.toList(),
        )
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "gymnotus_${Build.DEVICE}_${intervalMs}ms_$stamp.csv"
    }

    fun cancelExport() {
        pendingExport = null
    }

    fun export(uri: Uri) {
        val snapshot = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: error("Cannot open $uri for writing")
                    stream.bufferedWriter().use {
                        CsvExport.write(it, snapshot.meta, snapshot.monitors, snapshot.stats, snapshot.polls)
                    }
                }
                "Exported ${snapshot.polls.size} polls x ${snapshot.monitors.size} monitors"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Export failed: $e"
            }
            _state.update { it.copy(exportMessage = message) }
        }
    }

    private suspend fun loadMonitors(): Boolean {
        val loaded = try {
            withTimeout(LIST_TIMEOUT_MS) { source.supportedMonitors() }
        } catch (e: TimeoutCancellationException) {
            return failList("getSupportedPowerMonitors: no callback within $LIST_TIMEOUT_MS ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Shown verbatim: a SecurityException here answers the "is a permission needed" question.
            return failList("getSupportedPowerMonitors: $e")
        }
        monitors = loaded
        infos = loaded.mapIndexed { i, pm -> pm.toInfo(i) }
        trackers = loaded.map { RailTracker() }
        if (loaded.isEmpty()) {
            _state.update { it.copy(listState = MonitorListState.EMPTY) }
            return false
        }
        _state.update { it.copy(listState = MonitorListState.READY) }
        publish(lastRecord = null)
        return true
    }

    private fun failList(message: String): Boolean {
        _state.update { it.copy(listState = MonitorListState.FAILED, listError = message) }
        return false
    }

    private suspend fun pollOnce() {
        val pollGeneration = generation
        val pollInterval = intervalMs
        val requestMs = SystemClock.elapsedRealtime()
        val wallMs = System.currentTimeMillis()
        val battery = batteryReader.sample()
        var readings: List<RailReading> = emptyList()
        var responseMs = requestMs
        var error: String? = null
        try {
            val timed = withTimeout(READ_TIMEOUT_MS) { source.read(monitors) }
            if (pollGeneration != generation) return
            responseMs = timed.callbackElapsedMs
            callLatency.add(responseMs - requestMs)
            readings = monitors.mapIndexed { i, pm ->
                trackers[i].onReading(timed.readings.getConsumedEnergy(pm), timed.readings.getTimestampMillis(pm))
            }
        } catch (e: TimeoutCancellationException) {
            error = "getPowerMonitorReadings: no callback within $READ_TIMEOUT_MS ms"
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            error = "getPowerMonitorReadings: $e"
        }
        if (pollGeneration != generation) return
        if (error != null) {
            responseMs = SystemClock.elapsedRealtime()
            pollErrors++
            lastPollError = error
        }
        val record = PollRecord(++seq, requestMs, responseMs, wallMs, pollInterval, readings, error, battery)
        buffer.addLast(record)
        while (record.requestElapsedMs - buffer.first().requestElapsedMs > BUFFER_SPAN_MS) buffer.removeFirst()
        publish(record)
    }

    private fun publish(lastRecord: PollRecord?) {
        // A failed poll carries no readings: keep showing the previous ones rather than blanking the list.
        val previous = _state.value.rails
        val keepPrevious = lastRecord?.error != null && previous.size == infos.size
        val rails = infos.mapIndexed { i, info ->
            val reading = if (keepPrevious) previous[i].reading else lastRecord?.readings?.getOrNull(i)
            val ageMs = if (keepPrevious) previous[i].ageMs else reading?.let { lastRecord!!.responseElapsedMs - it.timestampMs }
            RailRow(info, reading, ageMs, trackers[i].stats())
        }
        _state.update {
            it.copy(
                rails = rails,
                battery = lastRecord?.battery ?: it.battery,
                finePermission = source.hasFinePermission,
                intervalMs = intervalMs,
                polls = seq,
                pollErrors = pollErrors,
                lastPollError = lastPollError,
                callLatency = callLatency.summary(),
                bufferSpanMs = if (buffer.isEmpty()) 0 else buffer.last().requestElapsedMs - buffer.first().requestElapsedMs,
                bufferPolls = buffer.size,
            )
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 250
        val INTERVAL_CHOICES_MS = listOf(100, 250, 500, 1000)
        // Long enough for the battery charge counter to move by many of its steps.
        const val BUFFER_SPAN_MS = 300_000L
        private const val LIST_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 5_000L
    }
}
