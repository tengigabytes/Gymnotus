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
 */
data class BatterySample(
    val currentUa: Int?,
    val voltageMv: Int?,
    val status: Int?,
    val plugged: Int?,
    val chargeCounterUah: Int?,
) {
    val onExternalPower: Boolean? get() = plugged?.let { it != 0 }

    /** Current × voltage assuming µA and mV, sign as reported. An instantaneous value, unlike the rail powers. */
    val powerMw: Double? get() = if (currentUa != null && voltageMv != null) currentUa.toDouble() * voltageMv / 1e6 else null
}

data class ExportMeta(
    val exportedAt: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val sdkInt: Int,
    val pollIntervalMs: Int,
    val polls: Long,
    val pollErrors: Long,
    val callLatency: IntervalSummary,
    val finePowerMonitors: Boolean,
)

/**
 * Phase 0 export: one file holding the rail list and the raw readings.
 *
 * Metadata lines start with `#` (pandas: `read_csv(path, comment="#")`); the table is long format, one row per
 * rail per poll. Missing values are empty cells, never 0.
 */
object CsvExport {
    const val COLUMNS = "poll_seq,request_elapsed_ms,response_elapsed_ms,wall_clock_ms,poll_interval_ms," +
        "monitor,type,status,energy_uws,timestamp_ms,dt_ms,de_uws,power_mw," +
        "batt_current_ua,batt_voltage_mv,batt_status,batt_plugged,batt_charge_counter_uah,note"

    private const val MONITOR_COLUMNS = "index,name,type,raw_type,polls,unavailable,repeats,resets," +
        "stale_energy_changes,interval_n,interval_min_ms,interval_median_ms,interval_max_ms,interval_mean_ms"

    fun write(
        out: Appendable,
        meta: ExportMeta,
        monitors: List<MonitorInfo>,
        stats: List<RailStats>,
        polls: List<PollRecord>,
    ) {
        out.line("# gymnotus phase0 export")
        out.line("# format_version,3")
        out.line("# exported_at,${meta.exportedAt}")
        out.line("# device,${cell(meta.manufacturer)},${cell(meta.model)},${cell(meta.device)}")
        out.line("# fingerprint,${cell(meta.fingerprint)}")
        out.line("# sdk_int,${meta.sdkInt}")
        out.line("# fine_power_monitors_permission,${meta.finePowerMonitors}")
        out.line("# poll_interval_ms,${meta.pollIntervalMs}")
        out.line("# batt_status,BatteryManager.BATTERY_STATUS_*: 2 charging; 3 discharging; 4 not charging; 5 full")
        out.line("# batt_plugged,BatteryManager.BATTERY_PLUGGED_* bit mask: 0 on battery; 1 AC; 2 USB; 4 wireless; 8 dock")
        out.line("# stats_scope,since last reset (may cover more than the rows below)")
        out.line("# polls,${meta.polls}")
        out.line("# poll_errors,${meta.pollErrors}")
        out.line("# call_latency_ms,${intervalCells(meta.callLatency)}")
        out.line("# monitor_count,${monitors.size}")
        out.line("# monitor_columns,$MONITOR_COLUMNS")
        monitors.forEachIndexed { i, m ->
            val s = stats[i]
            out.line(
                "# monitor,${m.index},${cell(m.name)},${m.type},${m.rawType},${s.polls},${s.unavailable}," +
                    "${s.repeats},${s.resets},${s.staleEnergyChanges},${intervalCells(s.updateIntervals)}",
            )
        }
        out.line(COLUMNS)
        for (poll in polls) {
            val prefix = "${poll.seq},${poll.requestElapsedMs},${poll.responseElapsedMs},${poll.wallClockMs}," +
                "${poll.intervalMs},"
            val b = poll.battery
            val battery = "${b?.currentUa.orEmpty()},${b?.voltageMv.orEmpty()},${b?.status.orEmpty()}," +
                "${b?.plugged.orEmpty()},${b?.chargeCounterUah.orEmpty()}"
            if (poll.error != null) {
                out.line("$prefix,,POLL_ERROR,,,,,,$battery,${cell(poll.error)}")
                continue
            }
            poll.readings.forEachIndexed { i, r ->
                val m = monitors[i]
                out.line(
                    "$prefix${cell(m.name)},${m.type},${r.status},${r.energyUws.orEmpty()},${r.timestampMs}," +
                        "${r.dtMs.orEmpty()},${r.deUws.orEmpty()},${r.powerMw.orEmpty()},$battery,",
                )
            }
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
