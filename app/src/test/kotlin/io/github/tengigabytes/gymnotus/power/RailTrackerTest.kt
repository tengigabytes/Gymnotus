package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailTrackerTest {
    @Test
    fun firstSampleHasNoPower() {
        val reading = RailTracker().onReading(energyUws = 1_000, timestampMs = 100)
        assertEquals(ReadingStatus.FIRST, reading.status)
        assertNull(reading.powerMw)
    }

    @Test
    fun powerIsEnergyDeltaOverRailTimestampDelta() {
        val tracker = RailTracker()
        tracker.onReading(1_000_000, 1_000)
        // 500 000 µJ over 250 ms = 2000 mW
        val reading = tracker.onReading(1_500_000, 1_250)
        assertEquals(ReadingStatus.OK, reading.status)
        assertEquals(250L, reading.dtMs)
        assertEquals(500_000L, reading.deUws)
        assertEquals(2000.0, reading.powerMw!!, 1e-9)
    }

    @Test
    fun repeatedTimestampIsStaleAndCarriesPreviousPower() {
        val tracker = RailTracker()
        tracker.onReading(0, 0)
        tracker.onReading(1_000, 100)
        val stale = tracker.onReading(1_000, 100)
        assertEquals(ReadingStatus.STALE, stale.status)
        assertEquals(10.0, stale.powerMw!!, 1e-9)
        // The next distinct sample is measured against the last distinct one, not the repeat.
        val next = tracker.onReading(3_000, 300)
        assertEquals(10.0, next.powerMw!!, 1e-9)
        assertEquals(200L, next.dtMs)

        val stats = tracker.stats()
        assertEquals(4L, stats.polls)
        assertEquals(1L, stats.repeats)
        assertEquals(0L, stats.staleEnergyChanges)
        assertEquals(IntervalSummary(2, 100, 200, 200, 150.0), stats.updateIntervals)
    }

    @Test
    fun staleBeforeAnyPowerHasNoPower() {
        val tracker = RailTracker()
        tracker.onReading(1_000, 100)
        val stale = tracker.onReading(1_000, 100)
        assertEquals(ReadingStatus.STALE, stale.status)
        assertNull(stale.powerMw)
    }

    @Test
    fun energyChangeWithoutTimestampChangeIsCounted() {
        val tracker = RailTracker()
        tracker.onReading(1_000, 100)
        tracker.onReading(2_000, 100)
        assertEquals(1L, tracker.stats().staleEnergyChanges)
    }

    @Test
    fun unavailableIsNeverZeroAndKeepsBaseline() {
        val tracker = RailTracker()
        tracker.onReading(1_000, 100)
        val unavailable = tracker.onReading(-1, 200)
        assertEquals(ReadingStatus.UNAVAILABLE, unavailable.status)
        assertNull(unavailable.energyUws)
        assertNull(unavailable.powerMw)
        // Average over the whole gap once data returns.
        val next = tracker.onReading(4_000, 400)
        assertEquals(10.0, next.powerMw!!, 1e-9)
        assertEquals(1L, tracker.stats().unavailable)
    }

    @Test
    fun unchangedEnergyWithAdvancingTimestampIsMeasuredZero() {
        val tracker = RailTracker()
        tracker.onReading(1_000, 100)
        val reading = tracker.onReading(1_000, 200)
        assertEquals(ReadingStatus.OK, reading.status)
        assertEquals(0.0, reading.powerMw!!, 0.0)
    }

    @Test
    fun counterGoingBackwardsRebaselines() {
        val tracker = RailTracker()
        tracker.onReading(5_000, 500)
        tracker.onReading(6_000, 600)
        val reset = tracker.onReading(100, 700)
        assertEquals(ReadingStatus.RESET, reset.status)
        assertNull(reset.powerMw)
        val next = tracker.onReading(1_100, 800)
        assertEquals(10.0, next.powerMw!!, 1e-9)
        assertEquals(1L, tracker.stats().resets)
    }

    @Test
    fun dropBaselineKeepsStatisticsButSkipsTheGap() {
        val tracker = RailTracker()
        tracker.onReading(0, 0)
        tracker.onReading(1_000, 100)
        tracker.dropBaseline()
        assertEquals(ReadingStatus.FIRST, tracker.onReading(900_000, 60_000).status)
        assertEquals(1L, tracker.stats().updateIntervals.count)
    }
}
