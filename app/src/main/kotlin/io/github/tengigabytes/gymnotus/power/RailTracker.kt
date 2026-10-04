// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

/** How one reading of a rail relates to the previous distinct sample of the same rail. */
enum class ReadingStatus {
    /** First valid sample (or first after a rebaseline): no power can be computed yet. */
    FIRST,

    /** Timestamp advanced: power is computed from this energy / time difference. */
    OK,

    /** Timestamp did not advance: the reading is a repeat, power is carried over from the last OK sample. */
    STALE,

    /** The API returned ENERGY_UNAVAILABLE. */
    UNAVAILABLE,

    /** Energy or timestamp went backwards (counter reset); the baseline restarts here. */
    RESET,
}

/**
 * One rail in one poll. Null means "no data"; nothing here is ever replaced by 0.
 *
 * @property energyUws raw accumulated energy in µW·s (= µJ), null when unavailable
 * @property timestampMs raw snapshot time of this rail, elapsedRealtime base, as reported by the API
 * @property dtMs timestamp difference to the previous distinct sample, only for [ReadingStatus.OK]
 * @property deUws energy difference to the previous distinct sample, only for [ReadingStatus.OK]
 * @property powerMw computed for OK, carried over for STALE, otherwise null
 */
data class RailReading(
    val status: ReadingStatus,
    val energyUws: Long?,
    val timestampMs: Long,
    val dtMs: Long? = null,
    val deUws: Long? = null,
    val powerMw: Double? = null,
)

data class IntervalSummary(
    val count: Long,
    val minMs: Long?,
    val medianMs: Long?,
    val maxMs: Long?,
    val meanMs: Double?,
)

/** Running statistics over millisecond intervals; the median covers only the most recent [window] values. */
class IntervalStats(private val window: Int = 512) {
    private var count = 0L
    private var min = Long.MAX_VALUE
    private var max = Long.MIN_VALUE
    private var sum = 0L
    private val recent = ArrayDeque<Long>()

    fun add(valueMs: Long) {
        count++
        if (valueMs < min) min = valueMs
        if (valueMs > max) max = valueMs
        sum += valueMs
        if (recent.size == window) recent.removeFirst()
        recent.addLast(valueMs)
    }

    fun clear() {
        count = 0
        min = Long.MAX_VALUE
        max = Long.MIN_VALUE
        sum = 0
        recent.clear()
    }

    fun summary(): IntervalSummary {
        if (count == 0L) return IntervalSummary(0, null, null, null, null)
        val sorted = recent.sorted()
        return IntervalSummary(count, min, sorted[sorted.size / 2], max, sum.toDouble() / count)
    }
}

/**
 * @property polls readings seen for this rail
 * @property unavailable readings that were ENERGY_UNAVAILABLE
 * @property repeats readings whose timestamp equalled the previous one (not updated)
 * @property resets readings where energy or timestamp went backwards
 * @property staleEnergyChanges repeats whose energy nevertheless changed (would mean the timestamp is unreliable)
 * @property updateIntervals distribution of timestamp differences between distinct samples
 */
data class RailStats(
    val polls: Long,
    val unavailable: Long,
    val repeats: Long,
    val resets: Long,
    val staleEnergyChanges: Long,
    val updateIntervals: IntervalSummary,
)

/**
 * Turns the accumulated-energy readings of one rail into power and update statistics.
 *
 * Power is P = ΔE / Δt with both differences taken from the rail's own readings: µW·s / ms = mW.
 */
class RailTracker {
    private var hasBaseline = false
    private var lastEnergyUws = 0L
    private var lastTimestampMs = 0L
    private var lastPowerMw: Double? = null

    private var polls = 0L
    private var unavailable = 0L
    private var repeats = 0L
    private var resets = 0L
    private var staleEnergyChanges = 0L
    private val updateIntervals = IntervalStats()

    fun onReading(energyUws: Long, timestampMs: Long): RailReading {
        polls++
        if (energyUws < 0) {
            unavailable++
            return RailReading(ReadingStatus.UNAVAILABLE, null, timestampMs)
        }
        if (!hasBaseline) {
            setBaseline(energyUws, timestampMs)
            return RailReading(ReadingStatus.FIRST, energyUws, timestampMs)
        }
        val dtMs = timestampMs - lastTimestampMs
        val deUws = energyUws - lastEnergyUws
        if (dtMs == 0L) {
            repeats++
            if (deUws != 0L) staleEnergyChanges++
            return RailReading(ReadingStatus.STALE, energyUws, timestampMs, powerMw = lastPowerMw)
        }
        if (dtMs < 0 || deUws < 0) {
            resets++
            lastPowerMw = null
            setBaseline(energyUws, timestampMs)
            return RailReading(ReadingStatus.RESET, energyUws, timestampMs)
        }
        val powerMw = deUws.toDouble() / dtMs
        updateIntervals.add(dtMs)
        lastPowerMw = powerMw
        setBaseline(energyUws, timestampMs)
        return RailReading(ReadingStatus.OK, energyUws, timestampMs, dtMs, deUws, powerMw)
    }

    /** Forget the previous sample (e.g. after polling was paused) but keep the statistics. */
    fun dropBaseline() {
        hasBaseline = false
        lastPowerMw = null
    }

    fun reset() {
        dropBaseline()
        polls = 0
        unavailable = 0
        repeats = 0
        resets = 0
        staleEnergyChanges = 0
        updateIntervals.clear()
    }

    fun stats() = RailStats(polls, unavailable, repeats, resets, staleEnergyChanges, updateIntervals.summary())

    private fun setBaseline(energyUws: Long, timestampMs: Long) {
        hasBaseline = true
        lastEnergyUws = energyUws
        lastTimestampMs = timestampMs
    }
}
