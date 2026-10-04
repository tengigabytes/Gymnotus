// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTreeTest {
    @Test
    fun parsesPixelRailNames() {
        assertEquals(RailName("S2M_VDD_CPU2", "CPU", "BIG"), parseRailName("[S2M_VDD_CPU2]:CPU(BIG)"))
        assertEquals(RailName("VSYS_PWR_WLAN_BT", "WLAN_BT", null), parseRailName("[VSYS_PWR_WLAN_BT]:WLAN_BT"))
        assertEquals(RailName("S12S", "MIX", null), parseRailName("[S12S]:MIX"))
    }

    @Test
    fun nameWithoutLabelIsKeptWhole() {
        assertEquals(RailName("S2S_VDD_G3D", null, null), parseRailName("S2S_VDD_G3D"))
    }

    private val rails = listOf(
        TreeInput(0, "[S2M_VDD_CPU2]:CPU(BIG)", 35.0),
        TreeInput(1, "[S4M_VDD_CPU]:CPU(LITTLE)", 70.0),
        TreeInput(2, "[S2S_VDD_GPU]:GPU", 3.0),
        TreeInput(3, "[VSYS_PWR_DISP_G1]:Display", 85.0),
        TreeInput(4, "[S12S]:MIX", null),
        TreeInput(5, "L1M_ALIVE", 1.0),
    )
    private val battery = TreeInput(6, "[VSYS_PWR_VBATT]:Battery", 300.0)

    @Test
    fun groupsBySubsystemLargestFirst() {
        val tree = PowerTreeBuilder.build(rails, TreeGrouping.SUBSYSTEM, onExternalPower = false)
        assertEquals(194.0, tree.railsSumMw!!, 1e-9)
        assertEquals(1, tree.missing)
        assertEquals(listOf("CPU", "Display", "GPU", PowerTreeBuilder.UNLABELLED, "MIX"), tree.groups.map { it.name })
        val cpu = tree.groups[0]
        assertEquals(105.0, cpu.powerMw!!, 1e-9)
        assertEquals(listOf("LITTLE", "BIG"), cpu.leaves.map { it.detail })
        assertEquals(listOf("S4M_VDD_CPU", "S2M_VDD_CPU2"), cpu.leaves.map { it.rail })
    }

    @Test
    fun groupWithoutAnyDataHasNoSum() {
        val mix = PowerTreeBuilder.build(rails, TreeGrouping.SUBSYSTEM, false).groups.single { it.name == "MIX" }
        assertNull(mix.powerMw)
        assertEquals(1, mix.missing)
    }

    @Test
    fun groupsBySourcePrefix() {
        val tree = PowerTreeBuilder.build(rails, TreeGrouping.SOURCE, onExternalPower = false)
        assertEquals(listOf("S<n>M", "VSYS_PWR", "S<n>S", PowerTreeBuilder.OTHER_SOURCE), tree.groups.map { it.name })
        val sub = tree.groups.single { it.name == "S<n>S" }
        assertEquals(3.0, sub.powerMw!!, 1e-9)
        assertEquals(1, sub.missing)
        assertEquals(listOf("GPU", "MIX"), sub.leaves.map { it.detail })
        assertEquals("CPU(LITTLE)", tree.groups[0].leaves[0].detail)
    }

    @Test
    fun noRailsGiveAnEmptyTreeWithoutTotal() {
        val tree = PowerTreeBuilder.build(emptyList(), TreeGrouping.SUBSYSTEM, onExternalPower = false)
        assertNull(tree.baseMw)
        assertFalse(tree.hasBatteryRail)
        assertEquals(emptyList<TreeGroup>(), tree.groups)
    }

    @Test
    fun onBatteryTheBatteryRailIsTheRootAndIsNotALeaf() {
        val tree = PowerTreeBuilder.build(rails + battery, TreeGrouping.SUBSYSTEM, onExternalPower = false)
        assertTrue(tree.batteryIsRoot)
        assertEquals(300.0, tree.baseMw!!, 1e-9)
        assertEquals(194.0, tree.railsSumMw!!, 1e-9)
        assertEquals(106.0, tree.unmeasuredMw!!, 1e-9)
        assertTrue(tree.groups.none { it.name == "Battery" })
    }

    @Test
    fun onExternalPowerTheBatteryRailIsNeitherRootNorSummed() {
        for (source in listOf(true, null)) {
            val tree = PowerTreeBuilder.build(rails + battery, TreeGrouping.SUBSYSTEM, onExternalPower = source)
            assertTrue(tree.hasBatteryRail)
            assertFalse(tree.batteryIsRoot)
            assertEquals(300.0, tree.batteryRailMw!!, 1e-9)
            assertEquals(194.0, tree.baseMw!!, 1e-9)
            assertNull(tree.unmeasuredMw)
            assertTrue(tree.groups.none { it.name == "Battery" })
        }
    }

    @Test
    fun batteryRailWithoutDataIsNotARoot() {
        val tree = PowerTreeBuilder.build(rails + battery.copy(powerMw = null), TreeGrouping.SUBSYSTEM, false)
        assertFalse(tree.batteryIsRoot)
        assertNull(tree.unmeasuredMw)
        assertEquals(194.0, tree.baseMw!!, 1e-9)
    }

    @Test
    fun deviceMapNamesTheSourcesAndTheBatteryRail() {
        val map = DeviceMap(
            devices = listOf("test"),
            description = "",
            batteryRail = "VSYS_PWR_DISP_G1",
            sources = listOf(RailSource("main", "Main PMIC", Regex("^[SL][0-9]+M(_.*)?$"))),
            unmonitored = emptyList(),
        )
        // Large enough to cover the other rails, which a root has to.
        val inputs = (rails + battery).map { if (it.index == 3) it.copy(powerMw = 500.0) else it }
        val tree = PowerTreeBuilder.build(inputs, TreeGrouping.SOURCE, onExternalPower = false, map = map)
        // The map's battery rail is the root; the default one is then an ordinary rail.
        assertEquals(500.0, tree.baseMw!!, 1e-9)
        assertEquals(3, tree.batteryRailIndex)
        val main = tree.groups.single { it.name == "Main PMIC" }
        assertEquals(listOf("S4M_VDD_CPU", "S2M_VDD_CPU2", "L1M_ALIVE"), main.leaves.map { it.rail })
        // Rails the map has no source for fall back to the name-based guess.
        assertTrue(tree.groups.any { it.name == "S<n>S" })
        assertTrue(tree.groups.any { it.name == "VSYS_PWR" && it.leaves.single().rail == "VSYS_PWR_VBATT" })
    }

    @Test
    fun batteryRailBelowTheRailsItFeedsIsNotARoot() {
        val tree = PowerTreeBuilder.build(rails + battery.copy(powerMw = 100.0), TreeGrouping.SUBSYSTEM, false)
        assertFalse(tree.batteryIsRoot)
        assertEquals(194.0, tree.baseMw!!, 1e-9)
        assertNull(tree.unmeasuredMw)
        // The reading itself is kept, so the screen can say what the battery rail showed.
        assertEquals(100.0, tree.batteryRailMw!!, 1e-9)
    }
}
