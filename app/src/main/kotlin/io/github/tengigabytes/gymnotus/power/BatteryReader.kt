package io.github.tengigabytes.gymnotus.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display

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
            temperatureDeciC = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE },
        )
    }
}

/** Screen and thermal state logged next to each poll. */
class ContextReader(private val context: Context) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun sample(appVisible: Boolean) = DeviceContext(
        screenState = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state,
        brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1).takeIf { it >= 0 },
        thermalStatus = power.currentThermalStatus,
        appVisible = appVisible,
    )
}
