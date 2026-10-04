package io.github.tengigabytes.gymnotus.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Battery state from BatteryManager: an independent source next to the PowerMonitor rails. */
class BatteryReader(private val context: Context) {
    private val manager = context.getSystemService(BatteryManager::class.java)
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    fun sample(): BatterySample {
        // Sticky broadcast: a null receiver just returns the latest state.
        val state = context.registerReceiver(null, filter)
        return BatterySample(
            currentUa = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE },
            voltageMv = state?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            status = state?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 },
            plugged = state?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 },
            chargeCounterUah = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it != Int.MIN_VALUE },
        )
    }
}
