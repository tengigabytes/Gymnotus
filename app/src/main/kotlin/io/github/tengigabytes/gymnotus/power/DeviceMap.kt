// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

import org.json.JSONObject

/** Where a rail is measured: one PMIC, or the shunts on the system rail. */
data class RailSource(val id: String, val name: String, val pattern: Regex)

/** A rail the device has but exposes no monitor for; its power is part of whatever is left unmeasured. */
data class UnmonitoredRail(val rail: String, val subsystem: String, val kind: String)

/**
 * A consumer the system models itself and the rails it should add up to: a check, built into the device, that
 * rails are read and summed correctly.
 */
data class CrossCheck(val consumer: String, val rails: List<String>)

/**
 * Per-device facts that the PowerMonitor API does not give, taken from the device's published device tree.
 * Files live in `data/device-maps/` so they can be contributed without touching code.
 *
 * It deliberately holds no parent/child links between rails: the device trees seen so far do not say which LDO
 * hangs off which buck, and guessing that would put invented structure on screen.
 *
 * @property devices Build.DEVICE values this map applies to
 * @property batteryRail rail measuring the battery's output, the root of the tree on battery power
 */
data class DeviceMap(
    val devices: List<String>,
    val description: String,
    val batteryRail: String?,
    val sources: List<RailSource>,
    val unmonitored: List<UnmonitoredRail>,
    val crossChecks: List<CrossCheck> = emptyList(),
) {
    fun sourceOf(rail: String): RailSource? = sources.firstOrNull { it.pattern.matches(rail) }

    companion object {
        /** @throws org.json.JSONException if a required field is missing or has the wrong type */
        fun parse(json: String): DeviceMap {
            val root = JSONObject(json)
            val sources = root.getJSONArray("sources")
            val unmonitored = root.optJSONArray("unmonitored")
            val crossChecks = root.optJSONArray("crossChecks")
            return DeviceMap(
                devices = root.getJSONArray("devices").let { array -> List(array.length()) { array.getString(it) } },
                description = root.optString("description"),
                batteryRail = if (root.has("batteryRail")) root.getString("batteryRail") else null,
                sources = List(sources.length()) { i ->
                    val source = sources.getJSONObject(i)
                    RailSource(source.getString("id"), source.getString("name"), Regex(source.getString("railPattern")))
                },
                unmonitored = List(unmonitored?.length() ?: 0) { i ->
                    val rail = unmonitored!!.getJSONObject(i)
                    UnmonitoredRail(rail.getString("rail"), rail.getString("subsystem"), rail.getString("kind"))
                },
                crossChecks = List(crossChecks?.length() ?: 0) { i ->
                    val check = crossChecks!!.getJSONObject(i)
                    val rails = check.getJSONArray("rails")
                    CrossCheck(check.getString("consumer"), List(rails.length()) { rails.getString(it) })
                },
            )
        }
    }
}
