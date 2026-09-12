package com.symphonia.gate2.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsTest {
    @Test
    fun `calculates mean median and sample standard deviation`() {
        val summary = Statistics.summarize(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))

        assertEquals(5.0, summary.mean, 0.000001)
        assertEquals(4.5, summary.median, 0.000001)
        assertEquals(2.138089935, summary.sampleStandardDeviation, 0.000001)
    }

    @Test
    fun `calculates median for odd sample count`() {
        assertEquals(20.0, Statistics.summarize(listOf(30.0, 10.0, 20.0)).median, 0.0)
    }

    @Test
    fun `single sample has zero sample standard deviation`() {
        val summary = Statistics.summarize(listOf(12.5))
        assertEquals(12.5, summary.mean, 0.0)
        assertEquals(12.5, summary.median, 0.0)
        assertEquals(0.0, summary.sampleStandardDeviation, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-finite samples are rejected`() {
        Statistics.summarize(listOf(Double.NaN))
    }
}
