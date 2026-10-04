package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {
    private val meta = ExportMeta(
        exportedAt = "2026-10-04T12:00:00+08:00",
        manufacturer = "Google",
        model = "Pixel 8, Pro",
        device = "husky",
        fingerprint = "google/husky/husky:16/X/1:user/release-keys",
        sdkInt = 36,
        pollIntervalMs = 250,
        polls = 3,
        pollErrors = 1,
        callLatency = IntervalSummary(2, 3, 4, 4, 3.5),
        finePowerMonitors = false,
    )
    private val monitors = listOf(
        MonitorInfo(0, "S2M_VDD_CPUCL2", MonitorType.MEASUREMENT, 1),
        MonitorInfo(1, "CPU", MonitorType.CONSUMER, 0),
    )

    private fun export(): List<String> {
        val trackers = monitors.map { RailTracker() }
        val polls = listOf(
            PollRecord(1, 1_000, 1_003, 1_700_000_000_000, 250, listOf(trackers[0].onReading(1_000_000, 990), trackers[1].onReading(-1, 0))),
            PollRecord(2, 1_250, 1_254, 1_700_000_000_250, 250, listOf(trackers[0].onReading(1_500_000, 1_240), trackers[1].onReading(-1, 0)), battery = BatterySample(-250_000, 3_900, 3, 0, 2_127_500)),
            PollRecord(3, 1_500, 6_500, 1_700_000_000_500, 250, emptyList(), error = "timeout, \"no callback\""),
        )
        val out = StringBuilder()
        CsvExport.write(out, meta, monitors, trackers.map { it.stats() }, polls)
        return out.toString().trimEnd('\n').split('\n')
    }

    @Test
    fun metadataListsEveryMonitorWithItsType() {
        val lines = export()
        assertTrue(lines.contains("# device,Google,\"Pixel 8, Pro\",husky"))
        assertTrue(lines.any { it.startsWith("# monitor,0,S2M_VDD_CPUCL2,MEASUREMENT,1,2,0,0,0,0,1,250,250,250,") })
        assertTrue(lines.any { it.startsWith("# monitor,1,CPU,CONSUMER,0,2,2,0,0,0,0,,,,") })
    }

    @Test
    fun tableRowsKeepRawValuesAndLeaveMissingOnesEmpty() {
        val table = export().filterNot { it.startsWith("#") }
        assertEquals(CsvExport.COLUMNS, table[0])
        assertEquals(
            listOf(
                "1,1000,1003,1700000000000,250,S2M_VDD_CPUCL2,MEASUREMENT,FIRST,1000000,990,,,,,,,,,",
                "1,1000,1003,1700000000000,250,CPU,CONSUMER,UNAVAILABLE,,0,,,,,,,,,",
                "2,1250,1254,1700000000250,250,S2M_VDD_CPUCL2,MEASUREMENT,OK,1500000,1240,250,500000,2000.0000,-250000,3900,3,0,2127500,",
                "2,1250,1254,1700000000250,250,CPU,CONSUMER,UNAVAILABLE,,0,,,,-250000,3900,3,0,2127500,",
                "3,1500,6500,1700000000500,250,,,POLL_ERROR,,,,,,,,,,,\"timeout, \"\"no callback\"\"\"",
            ),
            table.drop(1),
        )
    }

    @Test
    fun everyUnquotedRowHasTheHeaderColumnCount() {
        val table = export().filterNot { it.startsWith("#") }
        val columns = table[0].split(',').size
        table.filterNot { it.contains('"') }.forEach { assertEquals(it, columns, it.split(',').size) }
    }
}
