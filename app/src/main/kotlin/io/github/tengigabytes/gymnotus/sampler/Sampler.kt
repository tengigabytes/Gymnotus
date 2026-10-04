// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.sampler

import android.content.Context
import androidx.core.content.edit
import android.net.Uri
import android.os.Build
import android.os.PowerMonitor
import android.os.SystemClock
import android.view.Display
import io.github.tengigabytes.gymnotus.power.BatteryReader
import io.github.tengigabytes.gymnotus.power.BatterySample
import io.github.tengigabytes.gymnotus.power.ContextReader
import io.github.tengigabytes.gymnotus.power.ExportMeta
import io.github.tengigabytes.gymnotus.power.ExportStats
import io.github.tengigabytes.gymnotus.power.IntervalStats
import io.github.tengigabytes.gymnotus.power.IntervalSummary
import io.github.tengigabytes.gymnotus.power.MonitorInfo
import io.github.tengigabytes.gymnotus.power.PollRecord
import io.github.tengigabytes.gymnotus.power.PowerMonitorSource
import io.github.tengigabytes.gymnotus.power.RailReading
import io.github.tengigabytes.gymnotus.power.ReadingStatus
import io.github.tengigabytes.gymnotus.power.RailStats
import io.github.tengigabytes.gymnotus.power.RailTracker
import io.github.tengigabytes.gymnotus.power.toInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** @property ageMs elapsedRealtime at the result callback minus the rail's own timestamp */
data class RailRow(
    val info: MonitorInfo,
    val reading: RailReading?,
    val ageMs: Long?,
    val stats: RailStats,
)

enum class MonitorListState { LOADING, READY, EMPTY, FAILED }

/**
 * @property startedElapsedMs elapsedRealtime when the log was opened
 * @property polls polls handed to the writer so far
 * @property error set when writing failed; the log is then no longer being written
 */
data class LogStatus(val fileName: String, val startedElapsedMs: Long, val polls: Long, val error: String? = null)

/**
 * One refresh of the rails, kept for the chart.
 *
 * @property powerMw by monitor index; NaN where the rail had no new value in this poll
 */
class HistoryPoint(val elapsedMs: Long, val powerMw: FloatArray)

data class SamplerState(
    val listState: MonitorListState = MonitorListState.LOADING,
    val listError: String? = null,
    val rails: List<RailRow> = emptyList(),
    val battery: BatterySample? = null,
    val finePermission: Boolean = false,
    val intervalMs: Int = Sampler.FINE_INTERVAL_MS,
    val polls: Long = 0,
    val pollErrors: Long = 0,
    val lastPollError: String? = null,
    val callLatency: IntervalSummary = IntervalSummary(0, null, null, null, null),
    val bufferSpanMs: Long = 0,
    val slowWhenScreenOff: Boolean = true,
    val log: LogStatus? = null,
)

class ExportSnapshot(
    val meta: ExportMeta,
    val monitors: List<MonitorInfo>,
    val polls: List<PollRecord>,
    val stats: ExportStats,
)

/**
 * Polls every supported power monitor at a selectable interval, keeps the last [BUFFER_SPAN_MS] of raw readings,
 * accumulates per-rail update statistics and feeds the log, if one is open.
 *
 * One instance per process: the screen and the logging service both hold it, and it polls while either does.
 * All state is confined to the main thread; only file writes leave it.
 */
class Sampler(private val context: Context) {
    enum class Holder { UI, LOG }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = PowerMonitorSource(context)
    private val batteryReader = BatteryReader(context)
    private val contextReader = ContextReader(context)

    private val _state = MutableStateFlow(SamplerState())
    val state: StateFlow<SamplerState> = _state.asStateFlow()

    private var monitors: List<PowerMonitor> = emptyList()
    private var infos: List<MonitorInfo> = emptyList()
    private var trackers: List<RailTracker> = emptyList()
    private val callLatency = IntervalStats()
    private val buffer = ArrayDeque<PollRecord>()

    private val history = ArrayDeque<HistoryPoint>()
    private val _history = MutableStateFlow<List<HistoryPoint>>(emptyList())

    /** Power per rail at each refresh over the last [BUFFER_SPAN_MS]. */
    val historyFlow: StateFlow<List<HistoryPoint>> = _history.asStateFlow()

    private val prefs = context.getSharedPreferences("sampler", Context.MODE_PRIVATE)

    // The user's choice if there is one. Otherwise it follows the mode: without the fine permission the system
    // refreshes every 20 s, so fast polling would only return repeats.
    private var intervalMs = prefs.getInt(PREF_INTERVAL_MS, if (source.hasFinePermission) FINE_INTERVAL_MS else COARSE_INTERVAL_MS)
    private var seq = 0L
    private var pollErrors = 0L
    private var lastPollError: String? = null

    // Bumped by reset(); a poll that was in flight across a reset is discarded.
    private var generation = 0

    private var slowWhenScreenOff = prefs.getBoolean(PREF_SLOW_WHEN_SCREEN_OFF, true)
    private var lastScreenState: Int? = null

    private val holders = mutableSetOf<Holder>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pollJob: Job? = null
    private var log: LogSession? = null

    init {
        _state.update { it.copy(intervalMs = intervalMs, finePermission = source.hasFinePermission, slowWhenScreenOff = slowWhenScreenOff) }
    }

    fun acquire(holder: Holder) {
        holders += holder
        if (pollJob?.isActive == true) {
            // The loop may be in a long screen-off wait; the screen coming back should not have to sit that out.
            wake.trySend(Unit)
            return
        }
        pollJob = scope.launch {
            if (_state.value.listState != MonitorListState.READY && !loadMonitors()) return@launch
            // The gap since the last poll before a pause must not show up as one long update interval.
            trackers.forEach { it.dropBaseline() }
            var next = SystemClock.elapsedRealtime()
            while (isActive) {
                pollOnce()
                next += effectiveIntervalMs()
                val now = SystemClock.elapsedRealtime()
                if (next < now) next = now
                if (withTimeoutOrNull(next - now) { wake.receive() } != null) next = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Applies from the next poll on; allowed during a log, where each row carries the interval in effect. */
    fun setSlowWhenScreenOff(enabled: Boolean) {
        slowWhenScreenOff = enabled
        prefs.edit { putBoolean(PREF_SLOW_WHEN_SCREEN_OFF, enabled) }
        _state.update { it.copy(slowWhenScreenOff = enabled) }
    }

    /** Call after the fine permission may have been granted: switches the default interval to the fast one. */
    fun onFinePermissionChanged() {
        val fine = source.hasFinePermission
        _state.update { it.copy(finePermission = fine) }
        if (fine && intervalMs == COARSE_INTERVAL_MS) setInterval(FINE_INTERVAL_MS)
    }

    /**
     * With the screen off and this app not showing, every poll is a wake-up that adds to the standby power being
     * measured, so the interval is stretched. Rail energy is cumulative: only time resolution is lost.
     */
    private fun effectiveIntervalMs(): Int {
        val screenOff = lastScreenState != null && lastScreenState != Display.STATE_ON
        return if (slowWhenScreenOff && screenOff && Holder.UI !in holders) maxOf(intervalMs, SCREEN_OFF_INTERVAL_MS) else intervalMs
    }

    fun release(holder: Holder) {
        holders -= holder
        if (holders.isEmpty()) stopPolling()
    }

    fun retry() {
        stopPolling()
        _state.value = SamplerState(
            intervalMs = intervalMs,
            finePermission = source.hasFinePermission,
            slowWhenScreenOff = slowWhenScreenOff,
        )
        if (holders.isNotEmpty()) acquire(holders.first())
    }

    /** Ignored while a log is open, so that one log has one interval and one run of poll numbers. */
    fun setInterval(ms: Int) {
        if (ms == intervalMs || log != null) return
        intervalMs = ms
        prefs.edit { putInt(PREF_INTERVAL_MS, ms) }
        reset()
    }

    /** Clears statistics and the export buffer so both describe a single polling interval. Ignored while logging. */
    fun reset() {
        if (log != null) return
        generation++
        trackers.forEach { it.reset() }
        callLatency.clear()
        buffer.clear()
        history.clear()
        _history.value = emptyList()
        seq = 0
        pollErrors = 0
        lastPollError = null
        publish(lastRecord = null)
    }

    /** The current buffer with its metadata, or null if nothing has been read yet. */
    fun snapshot(): ExportSnapshot? {
        if (buffer.isEmpty()) return null
        return ExportSnapshot(meta("buffer"), infos, buffer.toList(), stats())
    }

    fun suggestedFileName(kind: String): String {
        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "gymnotus_${kind}_${Build.DEVICE}_${intervalMs}ms_$stamp.csv"
    }

    /** Starts writing every poll to [uri]. Returns false if the monitor list is not ready or a log is already open. */
    fun startLog(uri: Uri, fileName: String): Boolean {
        if (log != null || _state.value.listState != MonitorListState.READY) return false
        log = LogSession(context, scope, uri, meta("log"), infos) { error ->
            _state.update { it.copy(log = it.log?.copy(error = error)) }
        }
        _state.update { it.copy(log = LogStatus(fileName, SystemClock.elapsedRealtime(), polls = 0)) }
        return true
    }

    fun stopLog() {
        log?.close(stats())
        log = null
        _state.update { it.copy(log = null) }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun meta(kind: String) = ExportMeta(
        kind = kind,
        writtenAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        fingerprint = Build.FINGERPRINT,
        sdkInt = Build.VERSION.SDK_INT,
        pollIntervalMs = intervalMs,
        finePowerMonitors = source.hasFinePermission,
    )

    private fun stats() = ExportStats(seq, pollErrors, callLatency.summary(), trackers.map { it.stats() })

    private suspend fun loadMonitors(): Boolean {
        val loaded = try {
            withTimeout(LIST_TIMEOUT_MS) { source.supportedMonitors() }
        } catch (e: TimeoutCancellationException) {
            return failList("getSupportedPowerMonitors: no callback within $LIST_TIMEOUT_MS ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
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
        val requestMs = SystemClock.elapsedRealtime()
        val wallMs = System.currentTimeMillis()
        val battery = batteryReader.sample()
        val deviceContext = contextReader.sample(appVisible = Holder.UI in holders)
        lastScreenState = deviceContext.screenState
        val pollInterval = effectiveIntervalMs()
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
        val record = PollRecord(++seq, requestMs, responseMs, wallMs, pollInterval, readings, error, battery, deviceContext)
        buffer.addLast(record)
        while (record.requestElapsedMs - buffer.first().requestElapsedMs > BUFFER_SPAN_MS) buffer.removeFirst()
        log?.offer(record)
        if (readings.any { it.status == ReadingStatus.OK }) {
            history.addLast(
                HistoryPoint(responseMs, FloatArray(readings.size) { i -> readings[i].takeIf { it.status == ReadingStatus.OK }?.powerMw?.toFloat() ?: Float.NaN }),
            )
            while (responseMs - history.first().elapsedMs > BUFFER_SPAN_MS) history.removeFirst()
            _history.value = history.toList()
        }
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
                log = if (lastRecord != null && log != null) it.log?.let { l -> l.copy(polls = l.polls + 1) } else it.log,
            )
        }
    }

    companion object {
        const val FINE_INTERVAL_MS = 250
        const val COARSE_INTERVAL_MS = 1000
        const val SCREEN_OFF_INTERVAL_MS = 5000
        val INTERVAL_CHOICES_MS = listOf(100, 250, 500, 1000)

        // Long enough for the battery charge counter to move by many of its steps.
        const val BUFFER_SPAN_MS = 300_000L
        private const val PREF_INTERVAL_MS = "interval_ms"
        private const val PREF_SLOW_WHEN_SCREEN_OFF = "slow_when_screen_off"
        private const val LIST_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 5_000L
    }
}
