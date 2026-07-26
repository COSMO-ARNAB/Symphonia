package com.symphonia.testharness

import android.util.Log
import kotlin.math.sqrt

/**
 * Phase 1 Trial Benchmark Execution Manager.
 * Facilitates 3-trial physical device benchmark runs (Trial 1, Trial 2, Trial 3).
 * Computes mean latency, standard deviation, buffer throughput, and exports structured results
 * for versioned run archives under docs/gate1/runs/run-XXX/.
 */
class TrialExecutionManager(private val telemetryCollector: Gate1TelemetryCollector) {

    companion object {
        private const val TAG = "TrialExecutionManager"
    }

    data class TrialResult(
        val trialNumber: Int,
        val targetAppLabel: String,
        val audioRoute: String,
        val startupLatencyMs: Long,
        val bytesCaptured: Long,
        val bufferCount: Long,
        val nonSilentBuffers: Long,
        val failureCode: String
    )

    data class MultiTrialSummary(
        val targetAppLabel: String,
        val audioRoute: String,
        val trials: List<TrialResult>,
        val meanLatencyMs: Double,
        val stdDevLatencyMs: Double,
        val totalBytesCaptured: Long,
        val passStatus: String
    )

    private val completedTrials = mutableListOf<TrialResult>()

    fun recordTrialResult(
        trialNumber: Int,
        targetAppLabel: String,
        audioRoute: String,
        latencyMs: Long,
        bytesCaptured: Long,
        bufferCount: Long,
        nonSilentBuffers: Long,
        failureCode: String = "NONE"
    ) {
        val result = TrialResult(
            trialNumber = trialNumber,
            targetAppLabel = targetAppLabel,
            audioRoute = audioRoute,
            startupLatencyMs = latencyMs,
            bytesCaptured = bytesCaptured,
            bufferCount = bufferCount,
            nonSilentBuffers = nonSilentBuffers,
            failureCode = failureCode
        )
        completedTrials.add(result)
        Log.i(TAG, "Recorded Trial $trialNumber for $targetAppLabel ($audioRoute): Latency=${latencyMs}ms, Failure=$failureCode")
    }

    fun computeSummary(targetAppLabel: String, audioRoute: String): MultiTrialSummary {
        val appTrials = completedTrials.filter { it.targetAppLabel == targetAppLabel }
        if (appTrials.isEmpty()) {
            return MultiTrialSummary(
                targetAppLabel = targetAppLabel,
                audioRoute = audioRoute,
                trials = emptyList(),
                meanLatencyMs = 0.0,
                stdDevLatencyMs = 0.0,
                totalBytesCaptured = 0,
                passStatus = "NOT TESTED"
            )
        }

        val latencies = appTrials.map { it.startupLatencyMs.toDouble() }
        val mean = latencies.average()
        val variance = latencies.sumOf { (it - mean) * (it - mean) } / latencies.size
        val stdDev = sqrt(variance)
        val totalBytes = appTrials.sumOf { it.bytesCaptured }
        val hasFailures = appTrials.any { it.failureCode != "NONE" }

        val passStatus = if (!hasFailures && mean < 500.0) "PASS" else "FAIL"

        return MultiTrialSummary(
            targetAppLabel = targetAppLabel,
            audioRoute = audioRoute,
            trials = appTrials,
            meanLatencyMs = mean,
            stdDevLatencyMs = stdDev,
            totalBytesCaptured = totalBytes,
            passStatus = passStatus
        )
    }

    fun reset() {
        completedTrials.clear()
    }
}
