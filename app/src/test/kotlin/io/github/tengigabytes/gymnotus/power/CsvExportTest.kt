package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {
    private val meta = ExportMeta(
        kind = "buffer",
        writtenAt = "2026-10-04T12:00:00+08:00",
        manufacturer = "Google",
        model = "Pixel 8, Pro",
        device = "husky",
        fingerprint = "google/husky/husky:16/X/1:user/release-keys",
        sdkInt = 36,
        pollIntervalMs = 250,
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
            PollRecord(
                2, 1_250, 1_254, 1_700_000_000_250, 250,
                listOf(trackers[0].onReading(1_500_000, 1_240), trackers[1].onReading(-1, 0)),
                battery = BatterySample(-250_000, 3_900, 3, 0, 2_127_500, 323),
                context = DeviceContext(2, 128, 0, appVisible = true),
            ),
            PollRecord(3, 1_500, 6_500, 1_700_000_000_500, 250, emptyList(), error = "timeout, \"no callback\""),
        )
        val stats = ExportStats(3, 1, IntervalSummary(2, 3, 4, 4, 3.5), trackers.map { it.stats() })
        val out = StringBuilder()
        CsvExport.write(out, meta, monitors, polls, stats)
        return out.toString().trimEnd('\n').split('\n')
    }

    @Test
    fun headerListsDeviceAndEveryMonitorWithItsType() {
        val lines = export()
        val header = lines.take(lines.indexOf(CsvExport.COLUMNS))
        assertTrue(header.all { it.startsWith("#") })
        assertTrue(header.contains("# kind,buffer"))
        assertTrue(header.contains("# device,Google,\"Pixel 8, Pro\",husky"))
        assertTrue(header.contains("# monitor,0,S2M_VDD_CPUCL2,MEASUREMENT,1"))
        assertTrue(header.contains("# monitor,1,CPU,CONSUMER,0"))
    }

    @Test
    fun statisticsFollowTheRows() {
        val lines = export()
        val trailer = lines.drop(lines.indexOfLast { !it.startsWith("#") } + 1)
        assertTrue(trailer.contains("# polls,3"))
        assertTrue(trailer.contains("# poll_errors,1"))
        assertTrue(trailer.contains("# call_latency_ms,2,3,4,4,3.5000"))
        assertTrue(trailer.contains("# monitor_stats,0,S2M_VDD_CPUCL2,2,0,0,0,0,1,250,250,250,250.0000"))
        assertTrue(trailer.contains("# monitor_stats,1,CPU,2,2,0,0,0,0,,,,"))
    }

    @Test
    fun tableRowsKeepRawValuesAndLeaveMissingOnesEmpty() {
        val table = export().filterNot { it.startsWith("#") }
        assertEquals(CsvExport.COLUMNS, table[0])
        val state = "-250000,3900,3,0,2127500,323,2,128,0,1"
        assertEquals(
            listOf(
                // dt, de, power, 6 battery cells, 4 context cells and the note are all empty
                "1,1000,1003,1700000000000,250,S2M_VDD_CPUCL2,MEASUREMENT,FIRST,1000000,990" + ",".repeat(14),
                "1,1000,1003,1700000000000,250,CPU,CONSUMER,UNAVAILABLE,,0" + ",".repeat(14),
                "2,1250,1254,1700000000250,250,S2M_VDD_CPUCL2,MEASUREMENT,OK,1500000,1240,250,500000,2000.0000,$state,",
                "2,1250,1254,1700000000250,250,CPU,CONSUMER,UNAVAILABLE,,0,,,,$state,",
                "3,1500,6500,1700000000500,250,,,POLL_ERROR" + ",".repeat(16) + "\"timeout, \"\"no callback\"\"\"",
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
