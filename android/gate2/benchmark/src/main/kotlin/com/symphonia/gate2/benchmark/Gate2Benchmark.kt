package com.symphonia.gate2.benchmark

import com.symphonia.gate2.diagnostics.StatisticalSummary
import com.symphonia.gate2.diagnostics.Statistics

const val GATE2_BENCHMARK_SCHEMA_VERSION: Int = 3
const val GATE2_REQUIRED_TRIAL_COUNT: Int = 3

enum class EvidenceStatus { VERIFIED, MISSING }
enum class MeasurementProvenance { VERIFIED_PHYSICAL_COLLECTOR, MANUAL, SYNTHETIC }
enum class TrialStatus { COMPLETE, FAILED, INCONCLUSIVE }
enum class GateStatus { PASS, FAIL, INCONCLUSIVE }

data class BenchmarkEnvironment(
    val deviceManufacturer: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val networkType: String,
    val networkConditions: String,
    val physicalDeviceEvidence: EvidenceStatus,
    val evidenceReference: String?,
) {
    val complete: Boolean
        get() = listOf(deviceManufacturer, deviceModel, osVersion, appVersion, networkType, networkConditions)
            .all { it.isNotBlank() } && physicalDeviceEvidence == EvidenceStatus.VERIFIED && !evidenceReference.isNullOrBlank()
}

data class TrialDiagnostics(
    val endToEndLatencyMs: Double,
    val encodeLatencyMs: Double,
    val decodeLatencyMs: Double,
    val rttMs: Double,
    val packetsSent: Long,
    val packetsLost: Long,
    val bitrateKbps: Double,
    val averageJitterMs: Double,
    val reconnectMs: Double,
    val offeredFrameCount: Long,
    val droppedFrameCount: Long,
    val averageCpuPercent: Double,
    val peakThermalStatus: Int,
    val memorySlopeMibPerHour: Double,
    val memoryStable: Boolean,
    val batteryPercentPerHour: Double,
    val durationMs: Long,
    val crashCount: Int,
    val playbackAtFivePercentLossVerified: Boolean,
    val evidenceComplete: Boolean,
    val measurementProvenance: MeasurementProvenance,
    val evidenceReference: String?,
) {
    init {
        val nonNegative = listOf(
            endToEndLatencyMs, encodeLatencyMs, decodeLatencyMs, rttMs, bitrateKbps,
            averageJitterMs, reconnectMs, averageCpuPercent, batteryPercentPerHour,
        )
        require(nonNegative.all { it.isFinite() && it >= 0.0 }) { "Diagnostics must be finite and non-negative" }
        require(memorySlopeMibPerHour.isFinite()) { "Memory slope must be finite" }
        require(packetsSent > 0 && packetsLost in 0..packetsSent) { "Packet counters are invalid" }
        require(offeredFrameCount > 0 && droppedFrameCount in 0..offeredFrameCount) { "Frame counters are invalid" }
        require(averageCpuPercent <= 100.0) { "CPU percent cannot exceed 100" }
        require(peakThermalStatus >= 0) { "Thermal status must be non-negative" }
        require(durationMs > 0 && crashCount >= 0) { "Duration and crash count are invalid" }
    }

    val packetLossRate: Double get() = packetsLost.toDouble() / packetsSent
    val droppedFrameRate: Double get() = droppedFrameCount.toDouble() / offeredFrameCount
    val verifiedCollectorEvidence: Boolean
        get() = evidenceComplete && measurementProvenance == MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR &&
            !evidenceReference.isNullOrBlank()
}

class BenchmarkTrial private constructor(
    val number: Int,
    val status: TrialStatus,
    val diagnostics: TrialDiagnostics?,
    val detail: String?,
) {
    init {
        require(number in 1..GATE2_REQUIRED_TRIAL_COUNT) { "Trial number must be 1 through 3" }
        require((status == TrialStatus.COMPLETE) == (diagnostics != null)) { "Only complete trials contain diagnostics" }
        require(status == TrialStatus.COMPLETE || !detail.isNullOrBlank()) { "Non-complete trials require a detail" }
    }

    companion object {
        fun complete(number: Int, diagnostics: TrialDiagnostics) = BenchmarkTrial(number, TrialStatus.COMPLETE, diagnostics, null)
        fun failed(number: Int, detail: String) = BenchmarkTrial(number, TrialStatus.FAILED, null, detail)
        fun inconclusive(number: Int, detail: String) = BenchmarkTrial(number, TrialStatus.INCONCLUSIVE, null, detail)
    }
}

data class BenchmarkGates(
    val experimentalEndToEndLatencyMs: Double = 120.0,
    val authoritativeEndToEndLatencyMs: Double = 300.0,
    val reconnectMs: Double = 5_000.0,
    val averageJitterMs: Double = 30.0,
    val batteryPercentPerHour: Double = 10.0,
    val absoluteMemorySlopeMibPerHour: Double = 1.0,
    val averageCpuPercent: Double = 80.0,
    val maximumThermalStatus: Int = 3,
) {
    init {
        require(experimentalEndToEndLatencyMs in 0.0..authoritativeEndToEndLatencyMs)
        require(listOf(reconnectMs, averageJitterMs, batteryPercentPerHour, absoluteMemorySlopeMibPerHour, averageCpuPercent).all { it >= 0 })
        require(averageCpuPercent <= 100.0 && maximumThermalStatus >= 0)
    }
}

class GateResult internal constructor(
    val name: String,
    val measured: Double?,
    val maximum: Double?,
    val status: GateStatus,
    val authoritative: Boolean = true,
)

class Gate2BenchmarkReport internal constructor(
    val schemaVersion: Int,
    val runId: String,
    val generatedAtEpochMs: Long,
    val environment: BenchmarkEnvironment,
    val trials: List<BenchmarkTrial>,
    val summaries: Map<String, StatisticalSummary>,
    val weightedPacketLossRate: Double?,
    val gates: List<GateResult>,
    val status: GateStatus,
) {
    init {
        require(schemaVersion == GATE2_BENCHMARK_SCHEMA_VERSION && runId.isNotBlank() && generatedAtEpochMs >= 0)
        require(trials.size == GATE2_REQUIRED_TRIAL_COUNT)
        require(trials.map { it.number } == (1..GATE2_REQUIRED_TRIAL_COUNT).toList())
        require(gates.isNotEmpty()) { "A report cannot pass without evaluated gates" }
    }
}

class Gate2BenchmarkRunner(private val thresholds: BenchmarkGates = BenchmarkGates()) {
    fun run(
        runId: String,
        generatedAtEpochMs: Long,
        environment: BenchmarkEnvironment,
        trial: (Int) -> BenchmarkTrial,
    ): Gate2BenchmarkReport = evaluate(
        runId,
        generatedAtEpochMs,
        environment,
        (1..GATE2_REQUIRED_TRIAL_COUNT).map { number ->
            trial(number).also { require(it.number == number) { "Trial callback returned an unexpected trial number" } }
        },
    )

    fun evaluate(
        runId: String,
        generatedAtEpochMs: Long,
        environment: BenchmarkEnvironment,
        trials: List<BenchmarkTrial>,
    ): Gate2BenchmarkReport {
        require(runId.isNotBlank() && generatedAtEpochMs >= 0)
        require(trials.size == GATE2_REQUIRED_TRIAL_COUNT) { "Gate 2 requires exactly 3 trials" }
        require(trials.map { it.number } == (1..GATE2_REQUIRED_TRIAL_COUNT).toList()) {
            "Gate 2 trials must be ordered and numbered 1 through 3"
        }
        val diagnostics = trials.mapNotNull { it.diagnostics }
        val completeEvidence = environment.complete && diagnostics.size == GATE2_REQUIRED_TRIAL_COUNT &&
            diagnostics.all { it.verifiedCollectorEvidence }
        val summaries = if (diagnostics.size == GATE2_REQUIRED_TRIAL_COUNT) summaries(diagnostics) else emptyMap()
        val weightedLoss = if (diagnostics.size == GATE2_REQUIRED_TRIAL_COUNT) {
            diagnostics.sumOf { it.packetsLost }.toDouble() / diagnostics.sumOf { it.packetsSent }
        } else null

        val gates = if (!completeEvidence) inconclusiveGates() else evaluateGates(diagnostics, summaries)
        val status = when {
            trials.any { it.status == TrialStatus.FAILED } -> GateStatus.FAIL
            !completeEvidence || gates.any { it.authoritative && it.status == GateStatus.INCONCLUSIVE } -> GateStatus.INCONCLUSIVE
            gates.any { it.authoritative && it.status == GateStatus.FAIL } -> GateStatus.FAIL
            else -> GateStatus.PASS
        }
        return Gate2BenchmarkReport(
            GATE2_BENCHMARK_SCHEMA_VERSION, runId, generatedAtEpochMs, environment, trials,
            summaries, weightedLoss, gates, status,
        )
    }

    private fun summaries(values: List<TrialDiagnostics>): Map<String, StatisticalSummary> = linkedMapOf(
        "end_to_end_latency_ms" to Statistics.summarize(values.map { it.endToEndLatencyMs }),
        "encode_latency_ms" to Statistics.summarize(values.map { it.encodeLatencyMs }),
        "decode_latency_ms" to Statistics.summarize(values.map { it.decodeLatencyMs }),
        "rtt_ms" to Statistics.summarize(values.map { it.rttMs }),
        "packets_sent" to Statistics.summarize(values.map { it.packetsSent.toDouble() }),
        "packets_lost" to Statistics.summarize(values.map { it.packetsLost.toDouble() }),
        "packet_loss_rate" to Statistics.summarize(values.map { it.packetLossRate }),
        "bitrate_kbps" to Statistics.summarize(values.map { it.bitrateKbps }),
        "average_jitter_ms" to Statistics.summarize(values.map { it.averageJitterMs }),
        "reconnect_ms" to Statistics.summarize(values.map { it.reconnectMs }),
        "offered_frame_count" to Statistics.summarize(values.map { it.offeredFrameCount.toDouble() }),
        "dropped_frame_count" to Statistics.summarize(values.map { it.droppedFrameCount.toDouble() }),
        "dropped_frame_rate" to Statistics.summarize(values.map { it.droppedFrameRate }),
        "average_cpu_percent" to Statistics.summarize(values.map { it.averageCpuPercent }),
        "peak_thermal_status" to Statistics.summarize(values.map { it.peakThermalStatus.toDouble() }),
        "memory_slope_mib_per_hour" to Statistics.summarize(values.map { it.memorySlopeMibPerHour }),
        "battery_percent_per_hour" to Statistics.summarize(values.map { it.batteryPercentPerHour }),
        "duration_ms" to Statistics.summarize(values.map { it.durationMs.toDouble() }),
        "crash_count" to Statistics.summarize(values.map { it.crashCount.toDouble() }),
    )

    private fun evaluateGates(d: List<TrialDiagnostics>, s: Map<String, StatisticalSummary>): List<GateResult> {
        return listOf(
            maximum("experimental_e2e_latency", s.getValue("end_to_end_latency_ms").mean, thresholds.experimentalEndToEndLatencyMs, false),
            maximum("authoritative_e2e_latency", s.getValue("end_to_end_latency_ms").mean, thresholds.authoritativeEndToEndLatencyMs),
            maximum("reconnect", d.maxOf { it.reconnectMs }, thresholds.reconnectMs),
            booleanGate(
                "playback_at_5_percent_loss",
                d.all { it.packetLossRate >= FIVE_PERCENT_LOSS && it.playbackAtFivePercentLossVerified },
            ),
            maximum("average_jitter", s.getValue("average_jitter_ms").mean, thresholds.averageJitterMs),
            if (d.any { it.durationMs >= TWO_HOURS_MS }) booleanGate("zero_crashes_over_2h", d.sumOf { it.crashCount } == 0)
            else GateResult("zero_crashes_over_2h", null, 0.0, GateStatus.INCONCLUSIVE),
            maximum("battery", s.getValue("battery_percent_per_hour").mean, thresholds.batteryPercentPerHour),
            maximum("memory_slope", d.maxOf { kotlin.math.abs(it.memorySlopeMibPerHour) }, thresholds.absoluteMemorySlopeMibPerHour),
            booleanGate("memory_stability", d.all { it.memoryStable }),
            maximum("cpu", s.getValue("average_cpu_percent").mean, thresholds.averageCpuPercent),
            maximum("thermal", d.maxOf { it.peakThermalStatus }.toDouble(), thresholds.maximumThermalStatus.toDouble()),
        )
    }

    private fun inconclusiveGates(): List<GateResult> = listOf(
        "experimental_e2e_latency", "authoritative_e2e_latency", "reconnect", "playback_at_5_percent_loss",
        "average_jitter", "zero_crashes_over_2h", "battery", "memory_slope", "memory_stability", "cpu", "thermal",
    ).map { GateResult(it, null, null, GateStatus.INCONCLUSIVE, it != "experimental_e2e_latency") }

    private fun maximum(name: String, measured: Double, maximum: Double, authoritative: Boolean = true) =
        GateResult(name, measured, maximum, if (measured <= maximum) GateStatus.PASS else GateStatus.FAIL, authoritative)

    private fun booleanGate(name: String, passed: Boolean) =
        GateResult(name, if (passed) 1.0 else 0.0, 1.0, if (passed) GateStatus.PASS else GateStatus.FAIL)

    private companion object {
        const val TWO_HOURS_MS = 7_200_000L
        const val FIVE_PERCENT_LOSS = 0.05
    }
}
