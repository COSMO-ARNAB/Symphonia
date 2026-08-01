package com.symphonia.gate2.diagnostics

import kotlin.math.sqrt

data class StatisticalSummary(
    val count: Int,
    val mean: Double,
    val median: Double,
    val sampleStandardDeviation: Double,
)

object Statistics {
    fun summarize(values: List<Double>): StatisticalSummary {
        require(values.isNotEmpty()) { "At least one value is required" }
        require(values.all(Double::isFinite)) { "Statistics require finite values" }

        val sorted = values.sorted()
        val mean = values.average()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val sampleStandardDeviation = if (values.size == 1) {
            0.0
        } else {
            sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        }
        return StatisticalSummary(values.size, mean, median, sampleStandardDeviation)
    }
}
