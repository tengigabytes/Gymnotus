// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SankeyTest {
    private val map = DeviceMap(
        devices = listOf("test"),
        description = "",
        batteryRail = "VSYS_PWR_VBATT",
        sources = listOf(
            RailSource("main", "Main PMIC", Regex("^[SL][0-9]+M(_.*)?$")),
            RailSource("sub", "Sub PMIC", Regex("^[SL][0-9]+S(_.*)?$")),
            RailSource("ext", "System rail", Regex("^VSYS_PWR_.*$")),
        ),
        unmonitored = emptyList(),
    )

    // Deliberately not in source order, and with the display rail listed before the PMIC ones.
    private val rails = listOf(
        TreeInput(0, "[VSYS_PWR_DISP_G1]:Display", 100.0),
        TreeInput(1, "[S2S_VDD_GPU]:GPU", 10.0),
        TreeInput(2, "[S2M_VDD_CPU2]:CPU(BIG)", 60.0),
        TreeInput(3, "[S4M_VDD_CPU]:CPU(LITTLE)", 40.0),
        TreeInput(4, "[S3S_LLDO1]:LDO", 30.0),
        TreeInput(5, "[S6M_LLDO1]:LDO", 20.0),
        TreeInput(6, "[S7M_VDD_TPU]:TPU", null),
        TreeInput(7, "[VSYS_PWR_VBATT]:Battery", 400.0),
    )

    @Test
    fun onBatteryTheRootIsTheBatteryRailAndTheRestIsUnmeasured() {
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map)!!
        assertTrue(model.rootIsBattery)
        assertEquals(400.0, model.rootMw, 1e-9)
        assertEquals(140.0, model.unmeasuredMw!!, 1e-9)
    }

    @Test
    fun onExternalPowerTheRootIsTheSumOfRailsAndNothingIsUnmeasured() {
        val model = SankeyBuilder.build(rails, onExternalPower = true, map = map)!!
        assertFalse(model.rootIsBattery)
        assertEquals(260.0, model.rootMw, 1e-9)
        assertNull(model.unmeasuredMw)
    }

    @Test
    fun batteryRailBelowTheRailsItFeedsIsNotUsedAsRoot() {
        val low = rails.map { if (it.index == 7) it.copy(powerMw = 50.0) else it }
        val model = SankeyBuilder.build(low, onExternalPower = false, map = map)!!
        assertFalse(model.rootIsBattery)
        assertEquals(260.0, model.rootMw, 1e-9)
        assertNull(model.unmeasuredMw)
        // What leaves the root still equals the root.
        assertEquals(model.rootMw, model.sources.sumOf { it.valueMw }, 1e-9)
    }

    @Test
    fun nodeOrderFollowsTheMapNotTheReadings() {
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map)!!
        assertEquals(listOf("Main PMIC", "Sub PMIC", "System rail"), model.sources.map { it.name })
        // Subsystems in the order their first source feeds them; LDO belongs with the main PMIC it first appears in.
        assertEquals(listOf("CPU", "LDO", "TPU", "GPU", "Display"), model.subsystems.map { it.name })

        val swapped = rails.map { if (it.index == 0) it.copy(powerMw = 1.0) else it }
        val again = SankeyBuilder.build(swapped, onExternalPower = false, map = map)!!
        assertEquals(model.sources.map { it.name }, again.sources.map { it.name })
        assertEquals(model.subsystems.map { it.name }, again.subsystems.map { it.name })
    }

    @Test
    fun everyRailWithDataIsALinkAndNodesSumTheirRails() {
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map)!!
        assertEquals(listOf(0, 1, 2, 3, 4, 5), model.links.map { it.railIndex })
        assertEquals(120.0, model.sources[0].valueMw, 1e-9)
        assertEquals(listOf(2, 3, 5, 6), model.sources[0].indices)
        val ldo = model.subsystems.single { it.name == "LDO" }
        assertEquals(50.0, ldo.valueMw, 1e-9)
        // A rail without data is no link, and its subsystem node stays at zero rather than disappearing.
        assertEquals(0.0, model.subsystems.single { it.name == "TPU" }.valueMw, 0.0)
        for ((i, source) in model.sources.withIndex()) {
            assertEquals(source.valueMw, model.links.filter { it.source == i }.sumOf { it.valueMw }, 1e-9)
        }
    }

    @Test
    fun smallSubsystemsFoldIntoOneNodeJudgedByTheGivenRank() {
        // Five subsystems, room for three nodes: the two largest keep theirs, the rest share "other".
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map, maxSubsystems = 3)!!
        assertEquals(listOf("CPU", "Display", SankeyBuilder.OTHER), model.subsystems.map { it.name })
        val other = model.subsystems.last()
        assertEquals(3, other.merged)
        assertEquals(60.0, other.valueMw, 1e-9)
        assertEquals(listOf(1, 4, 5, 6), other.indices)
        // Folding moves links, it does not drop them.
        assertEquals(260.0, model.links.sumOf { it.valueMw }, 1e-9)

        // The rank, not the momentary reading, decides: here GPU is ranked far above everything else.
        val byRank = SankeyBuilder.build(rails, false, map, maxSubsystems = 3, rankMw = { if (it.index == 1) 1e6 else it.powerMw ?: 0.0 })!!
        assertEquals(listOf("CPU", "GPU", SankeyBuilder.OTHER), byRank.subsystems.map { it.name })
    }

    @Test
    fun layoutStillShowsPowerWhenThereAreManyNodes() {
        val many = (0 until 18).map { TreeInput(it, "[S${it + 1}M_X]:SUB$it", if (it == 0) 500.0 else 5.0) }
        val model = SankeyBuilder.build(many, onExternalPower = true, map = null)!!
        val layout = SankeyLayouter.layout(model, height = 300f, minSlot = 16f, gap = 3f)
        // The label slots alone would need 18 x 16 + 17 x 3 = 339 px; they shrink instead of squeezing the scale to nothing.
        assertTrue("scale ${layout.pxPerMw}", layout.pxPerMw > 0.2f)
        assertTrue(layout.subsystems.last().slot.bottom <= 300.01f)
    }

    @Test
    fun nothingToDrawWithoutData() {
        assertNull(SankeyBuilder.build(rails.map { it.copy(powerMw = null) }, onExternalPower = false, map = map))
    }

    @Test
    fun layoutFitsTheHeightAndRibbonsFillTheirNodes() {
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map)!!
        val layout = SankeyLayouter.layout(model, height = 400f, minSlot = 20f, gap = 4f)
        val tallest = maxOf(layout.subsystems.last().slot.bottom, (layout.unmeasured ?: layout.sources.last()).slot.bottom)
        assertTrue("tallest column $tallest", tallest <= 400f + 0.01f)
        assertTrue("uses the height, got $tallest", tallest > 390f)

        // Bars are to scale, even where the slot was padded for the label.
        model.sources.forEachIndexed { i, node -> assertEquals(node.valueMw.toFloat() * layout.pxPerMw, layout.sources[i].bar.height, 0.01f) }
        assertEquals(0f, layout.subsystems[2].bar.height, 0f)
        assertEquals(20f, layout.subsystems[2].slot.height, 0.01f)

        // What leaves the root equals the root; what enters a subsystem equals its bar.
        val leaving = layout.rootRibbons.sumOf { it.from.height.toDouble() } + (layout.unmeasuredRibbon?.from?.height ?: 0f)
        assertEquals(layout.root.height.toDouble(), leaving, 0.01)
        model.subsystems.forEachIndexed { s, _ ->
            val entering = model.links.indices.filter { model.links[it].subsystem == s }.sumOf { layout.railRibbons[it].to.height.toDouble() }
            assertEquals(layout.subsystems[s].bar.height.toDouble(), entering, 0.01)
        }
    }

    @Test
    fun ribbonsAtANodeDoNotOverlap() {
        val model = SankeyBuilder.build(rails, onExternalPower = false, map = map)!!
        val layout = SankeyLayouter.layout(model, height = 400f, minSlot = 20f, gap = 4f)
        for (s in model.sources.indices) {
            val spans = model.links.indices.filter { model.links[it].source == s }.map { layout.railRibbons[it].from }.sortedBy { it.top }
            spans.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top, 0.01f) }
            assertEquals(layout.sources[s].bar.top, spans.first().top, 0.01f)
        }
    }
}
