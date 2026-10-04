package io.github.tengigabytes.gymnotus.power

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScaleTest {
    @Test
    fun stepsAreOneTwoOrFiveTimesAPowerOfTen() {
        assertEquals(ChartScale(top = 1500.0, step = 500.0), niceScale(1300.0))
        assertEquals(ChartScale(top = 100.0, step = 50.0), niceScale(90.0))
        assertEquals(ChartScale(top = 8.0, step = 2.0), niceScale(7.2))
        assertEquals(ChartScale(top = 4.0, step = 1.0), niceScale(4.0))
    }

    @Test
    fun topCoversTheMaximumWithoutAnExtraInterval() {
        val scale = niceScale(0.3)
        assertEquals(0.1, scale.step, 1e-12)
        assertEquals(0.3, scale.top, 1e-12)
    }

    @Test
    fun ticksRunFromZeroToTop() {
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), niceScale(1300.0).ticks)
    }

    @Test
    fun emptyOrZeroDataStillGivesAnAxis() {
        assertEquals(ChartScale(top = 1.0, step = 1.0), niceScale(0.0))
        assertEquals(ChartScale(top = 1.0, step = 1.0), niceScale(Double.NaN))
    }
}
