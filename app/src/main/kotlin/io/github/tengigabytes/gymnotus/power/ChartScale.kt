package io.github.tengigabytes.gymnotus.power

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** A zero-based axis: gridlines every [step] from 0 up to [top]. */
data class ChartScale(val top: Double, val step: Double) {
    val ticks: List<Double> get() = List((top / step).roundToInt() + 1) { it * step }
}

/**
 * Zero-based axis whose step is 1, 2 or 5 times a power of ten, with about [targetTicks] intervals, tall enough
 * for [maxValue].
 */
fun niceScale(maxValue: Double, targetTicks: Int = 4): ChartScale {
    if (maxValue.isNaN() || maxValue <= 0.0) return ChartScale(top = 1.0, step = 1.0)
    val raw = maxValue / targetTicks
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalised = raw / magnitude
    val step = magnitude * when {
        normalised <= 1.0 -> 1.0
        normalised <= 2.0 -> 2.0
        normalised <= 5.0 -> 5.0
        else -> 10.0
    }
    // The epsilon keeps 0.3 / 0.1 = 2.9999… from rounding up to a fourth interval.
    return ChartScale(top = ceil(maxValue / step - 1e-9) * step, step = step)
}
