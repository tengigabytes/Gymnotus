package io.github.tengigabytes.gymnotus.ui

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/**
 * The unit power is shown in. Readings are always held in mW; this only decides how they are written out.
 * Raw readings on the Details page and exported files stay in mW regardless.
 */
enum class PowerUnit(val symbol: String) {
    MILLIWATT("mW"),
    WATT("W"),
    ;

    /**
     * The figure alone, without the unit symbol.
     *
     * @param fine one decimal of a milliwatt (three of a watt); otherwise whole milliwatts (hundredths of a watt)
     */
    fun number(powerMw: Double, fine: Boolean = true): String = when (this) {
        MILLIWATT -> String.format(Locale.ROOT, if (fine) "%,.1f" else "%,.0f", powerMw)
        WATT -> String.format(Locale.ROOT, if (fine) "%.3f" else "%.2f", powerMw / 1000.0)
    }

    fun text(powerMw: Double, fine: Boolean = true): String = "${number(powerMw, fine)} $symbol"
}

val LocalPowerUnit = compositionLocalOf { PowerUnit.MILLIWATT }

/** What the user set on the settings page; each has a remembered value and a default. */
data class UiSettings(
    val keepScreenOn: Boolean = true,
    val flowSubsystems: Int = DEFAULT_FLOW_SUBSYSTEMS,
    val powerUnit: PowerUnit = PowerUnit.MILLIWATT,
) {
    companion object {
        // Eight labelled rows is what fits beside a 300 dp diagram without the labels crowding the bars.
        const val DEFAULT_FLOW_SUBSYSTEMS = 8
        val FLOW_SUBSYSTEM_CHOICES = listOf(6, 8, 10, 12)
    }
}
