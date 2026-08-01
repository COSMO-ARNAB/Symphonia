package com.symphonia.gate2.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gate2BenchmarkTest {
    private val environment = BenchmarkEnvironment(
        "Google", "Pixel", "Android 14", "gate2", "Wi-Fi", "controlled 5% loss",
        EvidenceStatus.VERIFIED, "physical-device-run-001",
    )

    private fun diagnostics(
        e2e: Double = 100.0,
        sent: Long = 1_000,
        lost: Long = 50,
        durationMs: Long = 7_200_000,
        evidenceComplete: Boolean = true,
        measurementProvenance: MeasurementProvenance = MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR,
        evidenceReference: String? = "collector-trial-evidence",
    ) = TrialDiagnostics(
        endToEndLatencyMs = e2e,
        encodeLatencyMs = 10.0,
        decodeLatencyMs = 8.0,
        rttMs = 40.0,
        packetsSent = sent,
        packetsLost = lost,
        bitrateKbps = 80.0,
        averageJitterMs = 20.0,
        reconnectMs = 4_000.0,
        offeredFrameCount = 10_000,
        droppedFrameCount = 2,
        averageCpuPercent = 40.0,
        peakThermalStatus = 2,
        memorySlopeMibPerHour = 0.2,
        memoryStable = true,
        batteryPercentPerHour = 8.0,
        durationMs = durationMs,
        crashCount = 0,
        playbackAtFivePercentLossVerified = true,
        evidenceComplete = evidenceComplete,
        measurementProvenance = measurementProvenance,
        evidenceReference = evidenceReference,
    )

    private fun complete(number: Int, diagnostics: TrialDiagnostics = diagnostics()) =
        BenchmarkTrial.complete(number, diagnostics)

    @Test
    fun `runner executes exactly three complete evidence-backed trials and passes`() {
        val visited = mutableListOf<Int>()
        val report = Gate2BenchmarkRunner().run("run-001", 10, environment) { number ->
            visited += number
            complete(number)
        }

        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(GateStatus.PASS, report.status)
        val e2e = report.summaries.getValue("end_to_end_latency_ms")
        assertEquals(3, e2e.count)
        assertEquals(100.0, e2e.mean, 0.0)
        assertEquals(100.0, e2e.median, 0.0)
        assertEquals(0.0, e2e.sampleStandardDeviation, 0.0)
        assertEquals(
            setOf(
                "end_to_end_latency_ms", "encode_latency_ms", "decode_latency_ms", "rtt_ms", "packets_sent",
                "packets_lost", "packet_loss_rate", "bitrate_kbps", "average_jitter_ms", "reconnect_ms",
                "offered_frame_count", "dropped_frame_count", "dropped_frame_rate", "average_cpu_percent",
                "peak_thermal_status", "memory_slope_mib_per_hour", "battery_percent_per_hour", "duration_ms", "crash_count",
            ),
            report.summaries.keys,
        )
        assertTrue(report.gates.all { !it.authoritative || it.status == GateStatus.PASS })
    }

    @Test
    fun `experimental latency may fail without failing authoritative report`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-002", 20, environment, (1..3).map { complete(it, diagnostics(e2e = 200.0)) },
        )

        assertEquals(GateStatus.FAIL, report.gates.single { it.name == "experimental_e2e_latency" }.status)
        assertEquals(GateStatus.PASS, report.gates.single { it.name == "authoritative_e2e_latency" }.status)
        assertEquals(GateStatus.PASS, report.status)
    }

    @Test
    fun `five percent playback gate requires measured loss and playback evidence`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-loss", 25, environment,
            (1..3).map { complete(it, diagnostics(sent = 1_000, lost = if (it == 2) 49 else 50)) },
        )

        assertEquals(GateStatus.FAIL, report.gates.single { it.name == "playback_at_5_percent_loss" }.status)
        assertEquals(GateStatus.FAIL, report.status)
    }

    @Test
    fun `weighted packet loss uses packet counts rather than trial mean`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-003", 30, environment,
            listOf(
                complete(1, diagnostics(sent = 100, lost = 10)),
                complete(2, diagnostics(sent = 900, lost = 0)),
                complete(3, diagnostics(sent = 1_000, lost = 100)),
            ),
        )

        assertEquals(0.055, report.weightedPacketLossRate!!, 0.000001)
        assertEquals(0.066666667, report.summaries.getValue("packet_loss_rate").mean, 0.000001)
    }

    @Test
    fun `failed trial fails without fabricating summaries or pass`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-004", 40, environment,
            listOf(complete(1), BenchmarkTrial.failed(2, "transport disconnected"), complete(3)),
        )

        assertEquals(GateStatus.FAIL, report.status)
        assertTrue(report.summaries.isEmpty())
        assertNull(report.weightedPacketLossRate)
        assertTrue(report.gates.all { it.status == GateStatus.INCONCLUSIVE })
    }

    @Test
    fun `missing physical evidence and incomplete diagnostics are inconclusive`() {
        val missingDevice = environment.copy(physicalDeviceEvidence = EvidenceStatus.MISSING, evidenceReference = null)
        val missingEnvironment = Gate2BenchmarkRunner().evaluate("run-005", 50, missingDevice, (1..3).map { complete(it) })
        val missingTrialEvidence = Gate2BenchmarkRunner().evaluate(
            "run-006", 60, environment, (1..3).map { complete(it, diagnostics(evidenceComplete = it != 2)) },
        )

        assertEquals(GateStatus.INCONCLUSIVE, missingEnvironment.status)
        assertEquals(GateStatus.INCONCLUSIVE, missingTrialEvidence.status)
        assertFalse(missingEnvironment.gates.any { it.status == GateStatus.PASS })
    }

    @Test
    fun `three separate forty minute trials cannot satisfy continuous stability gate`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-stability-split", 65, environment,
            (1..3).map { complete(it, diagnostics(durationMs = 2_400_000)) },
        )

        assertEquals(GateStatus.INCONCLUSIVE, report.gates.single { it.name == "zero_crashes_over_2h" }.status)
        assertEquals(GateStatus.INCONCLUSIVE, report.status)
    }

    @Test
    fun `one continuous two hour trial passes stability gate with zero crashes`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-stability-continuous", 66, environment,
            listOf(
                complete(1, diagnostics(durationMs = 7_200_000)),
                complete(2, diagnostics(durationMs = 2_400_000)),
                complete(3, diagnostics(durationMs = 2_400_000)),
            ),
        )

        assertEquals(GateStatus.PASS, report.gates.single { it.name == "zero_crashes_over_2h" }.status)
        assertEquals(GateStatus.PASS, report.status)
    }

    @Test
    fun `manual trial values cannot produce a passing report`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-manual", 67, environment,
            (1..3).map {
                complete(it, diagnostics(measurementProvenance = MeasurementProvenance.MANUAL))
            },
        )

        assertEquals(GateStatus.INCONCLUSIVE, report.status)
        assertFalse(report.gates.any { it.status == GateStatus.PASS })
    }

    @Test
    fun `inconclusive trial cannot vacuously pass`() {
        val report = Gate2BenchmarkRunner().evaluate(
            "run-007", 70, environment,
            listOf(complete(1), BenchmarkTrial.inconclusive(2, "battery evidence missing"), complete(3)),
        )

        assertEquals(GateStatus.INCONCLUSIVE, report.status)
    }

    @Test
    fun `export has exact filenames schema and all diagnostics without audio`() {
        val report = Gate2BenchmarkRunner().evaluate("run,\"008\"", 80, environment, (1..3).map { complete(it) })
        val exports = Gate2BenchmarkExporter.export(report)

        assertEquals(Gate2BenchmarkExporter.filenames, exports.map { it.filename }.toSet())
        assertEquals(10, exports.size)
        assertTrue(exports.all { it.content.contains("schemaVersion\":3") || it.content.startsWith("schema_version") })
        val combined = exports.joinToString("\n") { it.content }
        listOf(
            "end_to_end_latency_ms", "encode_latency_ms", "decode_latency_ms", "rtt_ms", "bitrate_kbps",
            "average_jitter_ms", "reconnect_ms", "dropped_frame_rate", "average_cpu_percent",
            "memory_slope_mib_per_hour", "battery_percent_per_hour", "packet_loss_rate", "peak_thermal_status",
        ).forEach { assertTrue("missing $it", combined.contains(it)) }
        assertTrue(combined.contains("VERIFIED_PHYSICAL_COLLECTOR"))
        assertTrue(combined.contains("collector-trial-evidence"))
        assertFalse(combined.contains("pcm", ignoreCase = true))
        assertFalse(combined.contains("samples", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `evaluation rejects fewer than three trials`() {
        Gate2BenchmarkRunner().evaluate("run-009", 90, environment, listOf(complete(1), complete(2)))
    }
}
