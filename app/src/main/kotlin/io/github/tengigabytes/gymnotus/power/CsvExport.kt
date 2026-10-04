// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

import java.util.Locale

enum class MonitorType { MEASUREMENT, CONSUMER, UNKNOWN }

/** @property index position in the list returned by getSupportedPowerMonitors */
data class MonitorInfo(val index: Int, val name: String, val type: MonitorType, val rawType: Int)

/**
 * One call to getPowerMonitorReadings.
 *
 * @property requestElapsedMs elapsedRealtime just before the call
 * @property responseElapsedMs elapsedRealtime inside the result callback
 * @property readings one entry per monitor, in [MonitorInfo.index] order; empty when the call failed
 * @property error set when the call failed or timed out
 * @property battery BatteryManager state taken just before the call
 * @property context device state taken just before the call
 */
data class PollRecord(
    val seq: Long,
    val requestElapsedMs: Long,
    val responseElapsedMs: Long,
    val wallClockMs: Long,
    val intervalMs: Int,
    val readings: List<RailReading>,
    val error: String? = null,
    val battery: BatterySample? = null,
    val context: DeviceContext? = null,
)

/**
 * Raw BatteryManager values; null means not reported.
 *
 * @property currentUa BATTERY_PROPERTY_CURRENT_NOW, instantaneous; unit and sign are as the device reports them
 * @property voltageMv EXTRA_VOLTAGE of the last battery broadcast
 * @property status BatteryManager.BATTERY_STATUS_*: 2 charging, 3 discharging, 4 not charging, 5 full
 * @property plugged BatteryManager.BATTERY_PLUGGED_* bit mask: 0 on battery, 1 AC, 2 USB, 4 wireless, 8 dock
 * @property chargeCounterUah BATTERY_PROPERTY_CHARGE_COUNTER: remaining charge in µAh, an integrated value that
 * does not depend on when it is sampled
 * @property temperatureDeciC EXTRA_TEMPERATURE of the last battery broadcast, tenths of a degree Celsius
 */
data class BatterySample(
    val currentUa: Int?,
    val voltageMv: Int?,
    val status: Int?,
    val plugged: Int?,
    val chargeCounterUah: Int?,
    val temperatureDeciC: Int?,
) {
    val onExternalPower: Boolean? get() = plugged?.let { it != 0 }

    /** Current × voltage assuming µA and mV, sign as reported. An instantaneous value, unlike the rail powers. */
    val powerMw: Double? get() = if (currentUa != null && voltageMv != null) currentUa.toDouble() * voltageMv / 1e6 else null
}

/**
 * Raw device state that explains a power reading; null means not reported.
 *
 * @property screenState android.view.Display.STATE_*: 1 off, 2 on, 3 doze, 4 doze suspend
 * @property brightness Settings.System.SCREEN_BRIGHTNESS as stored; the range is device specific
 * @property thermalStatus PowerManager.THERMAL_STATUS_*: 0 none … 6 shutdown
 * @property appVisible whether this app's own screen was showing, i.e. adding its drawing to the measurement
 */
data class DeviceContext(val screenState: Int?, val brightness: Int?, val thermalStatus: Int?, val appVisible: Boolean?)

/** @property kind `buffer` for a snapshot of the rolling buffer, `log` for a file written while sampling */
data class ExportMeta(
    val kind: String,
    val writtenAt: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val sdkInt: Int,
    val pollIntervalMs: Int,
    val finePowerMonitors: Boolean,
)

/** Statistics since the last reset; they are written after the rows because a log only knows them at the end. */
data class ExportStats(
    val polls: Long,
    val pollErrors: Long,
    val callLatency: IntervalSummary,
    val rails: List<RailStats>,
)

/**
 * CSV layout shared by buffer exports and logs: `#` metadata (device, rail list), the table in long format with
 * one row per rail per poll, then `#` statistics. pandas: `read_csv(path, comment="#")`.
 * Missing values are empty cells, never 0.
 */
object CsvExport {
    const val COLUMNS = "poll_seq,request_elapsed_ms,response_elapsed_ms,wall_clock_ms,poll_interval_ms," +
        "monitor,type,status,energy_uws,timestamp_ms,dt_ms,de_uws,power_mw," +
        "batt_current_ua,batt_voltage_mv,batt_status,batt_plugged,batt_charge_counter_uah,batt_temp_dc," +
        "screen_state,brightness,thermal_status,app_visible,note"

    fun write(out: Appendable, meta: ExportMeta, monitors: List<MonitorInfo>, polls: List<PollRecord>, stats: ExportStats) {
        writeHeader(out, meta, monitors)
        polls.forEach { writePoll(out, monitors, it) }
        writeStats(out, monitors, stats)
    }

    fun writeHeader(out: Appendable, meta: ExportMeta, monitors: List<MonitorInfo>) {
        out.line("# gymnotus export")
        out.line("# format_version,5")
        out.line("# kind,${meta.kind}")
        out.line("# written_at,${meta.writtenAt}")
        out.line("# device,${cell(meta.manufacturer)},${cell(meta.model)},${cell(meta.device)}")
        out.line("# fingerprint,${cell(meta.fingerprint)}")
        out.line("# sdk_int,${meta.sdkInt}")
        out.line("# fine_power_monitors_permission,${meta.finePowerMonitors}")
        out.line("# poll_interval_ms,${meta.pollIntervalMs}")
        out.line("# batt_status,BatteryManager.BATTERY_STATUS_*: 2 charging; 3 discharging; 4 not charging; 5 full")
        out.line("# batt_plugged,BatteryManager.BATTERY_PLUGGED_* bit mask: 0 on battery; 1 AC; 2 USB; 4 wireless; 8 dock")
        out.line("# batt_temp_dc,tenths of a degree Celsius")
        out.line("# screen_state,android.view.Display.STATE_*: 1 off; 2 on; 3 doze; 4 doze suspend")
        out.line("# brightness,Settings.System.SCREEN_BRIGHTNESS as stored (device-specific range)")
        out.line("# thermal_status,PowerManager.THERMAL_STATUS_*: 0 none; 1 light; 2 moderate; 3 severe; 4 critical; 5 emergency; 6 shutdown")
        out.line("# app_visible,1 while the Gymnotus screen itself was showing; 0 while logging in the background")
        out.line("# monitor_count,${monitors.size}")
        out.line("# monitor_columns,index,name,type,raw_type")
        monitors.forEach { out.line("# monitor,${it.index},${cell(it.name)},${it.type},${it.rawType}") }
        out.line(COLUMNS)
    }

    fun writePoll(out: Appendable, monitors: List<MonitorInfo>, poll: PollRecord) {
        val prefix = "${poll.seq},${poll.requestElapsedMs},${poll.responseElapsedMs},${poll.wallClockMs}," +
            "${poll.intervalMs},"
        val b = poll.battery
        val c = poll.context
        val state = "${b?.currentUa.orEmpty()},${b?.voltageMv.orEmpty()},${b?.status.orEmpty()}," +
            "${b?.plugged.orEmpty()},${b?.chargeCounterUah.orEmpty()},${b?.temperatureDeciC.orEmpty()}," +
            "${c?.screenState.orEmpty()},${c?.brightness.orEmpty()},${c?.thermalStatus.orEmpty()}," +
            (c?.appVisible?.let { if (it) "1" else "0" } ?: "")
        if (poll.error != null) {
            out.line("$prefix,,POLL_ERROR,,,,,,$state,${cell(poll.error)}")
            return
        }
        poll.readings.forEachIndexed { i, r ->
            val m = monitors[i]
            out.line(
                "$prefix${cell(m.name)},${m.type},${r.status},${r.energyUws.orEmpty()},${r.timestampMs}," +
                    "${r.dtMs.orEmpty()},${r.deUws.orEmpty()},${r.powerMw.orEmpty()},$state,",
            )
        }
    }

    fun writeStats(out: Appendable, monitors: List<MonitorInfo>, stats: ExportStats) {
        out.line("# stats_scope,since last reset (may cover more than the rows above)")
        out.line("# polls,${stats.polls}")
        out.line("# poll_errors,${stats.pollErrors}")
        out.line("# call_latency_ms,${intervalCells(stats.callLatency)}")
        out.line(
            "# monitor_stats_columns,index,name,polls,unavailable,repeats,resets,stale_energy_changes," +
                "interval_n,interval_min_ms,interval_median_ms,interval_max_ms,interval_mean_ms",
        )
        monitors.forEachIndexed { i, m ->
            val s = stats.rails[i]
            out.line(
                "# monitor_stats,${m.index},${cell(m.name)},${s.polls},${s.unavailable}," +
                    "${s.repeats},${s.resets},${s.staleEnergyChanges},${intervalCells(s.updateIntervals)}",
            )
        }
    }

    private fun intervalCells(s: IntervalSummary) =
        "${s.count},${s.minMs.orEmpty()},${s.medianMs.orEmpty()},${s.maxMs.orEmpty()},${s.meanMs.orEmpty()}"

    private fun Long?.orEmpty() = this?.toString() ?: ""

    private fun Int?.orEmpty() = this?.toString() ?: ""

    private fun Double?.orEmpty() = if (this == null) "" else String.format(Locale.ROOT, "%.4f", this)

    private fun cell(value: String) =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun Appendable.line(text: String) {
        append(text).append('\n')
    }
}
