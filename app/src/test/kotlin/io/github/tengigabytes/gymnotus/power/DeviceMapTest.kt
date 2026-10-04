package io.github.tengigabytes.gymnotus.power

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMapTest {
    // Unit tests run with the module directory as working directory.
    private val mapsDir = File("../data/device-maps")

    @Test
    fun everyShippedMapParses() {
        val files = mapsDir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("no device maps found in ${mapsDir.absolutePath}", files.isNotEmpty())
        for (file in files) {
            val map = DeviceMap.parse(file.readText())
            assertTrue("${file.name}: no devices", map.devices.isNotEmpty())
            assertTrue("${file.name}: no sources", map.sources.isNotEmpty())
        }
    }

    private val blazer by lazy { DeviceMap.parse(File(mapsDir, "blazer.json").readText()) }

    @Test
    fun blazerRailsResolveToTheirSource() {
        assertEquals("main", blazer.sourceOf("S1M_VDD_AMB")?.id)
        assertEquals("main", blazer.sourceOf("S11M_VDD_CPU_M")?.id)
        assertEquals("sub", blazer.sourceOf("S12S")?.id)
        assertEquals("sub", blazer.sourceOf("S10S_VDD_INFRA_MM_GPU_M")?.id)
        assertEquals("ext", blazer.sourceOf("VSYS_PWR_DISP_G1")?.id)
        assertNull(blazer.sourceOf("SOMETHING_ELSE"))
    }

    @Test
    fun blazerNamesItsBatteryRailAndItsUnmonitoredBucks() {
        assertEquals("VSYS_PWR_VBATT", blazer.batteryRail)
        assertEquals(
            listOf("S13M_VDD_CPU2_M", "S13S_UFS_VCCQ", "BB_HLDO"),
            blazer.unmonitored.filter { it.kind == "buck" }.map { it.rail },
        )
        assertEquals(56, blazer.unmonitored.count { it.kind == "ldo" })
    }

    @Test
    fun mapWithoutOptionalFields() {
        val map = DeviceMap.parse("""{"devices":["x"],"sources":[{"id":"a","name":"A","railPattern":"^A.*$"}]}""")
        assertNull(map.batteryRail)
        assertEquals(emptyList<UnmonitoredRail>(), map.unmonitored)
        assertEquals("A", map.sourceOf("ABC")?.name)
    }
}
