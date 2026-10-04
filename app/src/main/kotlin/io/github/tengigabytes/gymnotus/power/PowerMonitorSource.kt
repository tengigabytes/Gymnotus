// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

import android.content.Context
import android.content.pm.PackageManager
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.SystemClock
import android.os.health.SystemHealthManager
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** @property callbackElapsedMs elapsedRealtime taken inside the result callback, before any thread hop */
class TimedReadings(val readings: PowerMonitorReadings, val callbackElapsedMs: Long)

/** Coroutine wrapper around the API 35 PowerMonitor calls of [SystemHealthManager]. */
class PowerMonitorSource(private val context: Context) {
    private val manager = context.getSystemService(SystemHealthManager::class.java)

    // Callbacks only take a timestamp and resume a continuation, so they run on whatever thread delivers them.
    private val direct = Executor { it.run() }

    /** Whether readings refresh at the fine (250 ms) rather than the default (20 s) limit; granted over adb only. */
    val hasFinePermission: Boolean
        get() = context.checkSelfPermission(FINE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Empty when the device exposes no power monitors. */
    suspend fun supportedMonitors(): List<PowerMonitor> = suspendCancellableCoroutine { cont ->
        manager.getSupportedPowerMonitors(direct) { cont.resume(it) }
    }

    suspend fun read(monitors: List<PowerMonitor>): TimedReadings = suspendCancellableCoroutine { cont ->
        manager.getPowerMonitorReadings(
            monitors,
            direct,
            object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
                override fun onResult(result: PowerMonitorReadings) {
                    cont.resume(TimedReadings(result, SystemClock.elapsedRealtime()))
                }

                override fun onError(error: RuntimeException) {
                    cont.resumeWithException(error)
                }
            },
        )
    }
}

// Not in the public SDK (signature|privileged|development).
const val FINE_PERMISSION = "android.permission.ACCESS_FINE_POWER_MONITORS"

fun PowerMonitor.toInfo(index: Int) = MonitorInfo(
    index = index,
    name = name,
    type = when (type) {
        PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT -> MonitorType.MEASUREMENT
        PowerMonitor.POWER_MONITOR_TYPE_CONSUMER -> MonitorType.CONSUMER
        else -> MonitorType.UNKNOWN
    },
    rawType = type,
)
