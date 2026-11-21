package com.symphonia.gate2.prototype.harness

import android.content.Context
import android.content.ContextWrapper
import com.symphonia.gate2.benchmark.BenchmarkTrial
import com.symphonia.gate2.benchmark.Gate2BenchmarkExporter
import com.symphonia.gate2.benchmark.GateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Gate2PhysicalBenchmarkCollectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var collector: Gate2PhysicalBenchmarkCollector

    @Before
    fun setUp() {
        mockContext = ContextWrapper(null)
        collector = Gate2PhysicalBenchmarkCollector(mockContext)
    }

    @Test
    fun testEnvironmentCreation() {
        val env = collector.getEnvironment(
            networkType = "Wi-Fi (5GHz)",
            networkConditions = "LAN Baseline",
            evidenceRef = "docs/gate2/runbook_two_devices.md#env"
        )
        assertNotNull(env)
        assertTrue(env.complete)
        assertEquals("Wi-Fi (5GHz)", env.networkType)
        assertEquals("LAN Baseline", env.networkConditions)
    }

    @Test
    fun testBuildTrialDiagnosticsStandard() {
        val metrics = Gate2PhysicalBenchmarkCollector.LiveCollectorMetrics(
            rttMs = 20.0,
            packetsSent = 2000L,
            packetsLost = 0L,
            bitrateKbps = 85.0,
            jitterMs = 2.0,
            offeredFrameCount = 5000L,
            droppedFrameCount = 0L,
            cpuPercent = 15.0,
            thermalStatus = 0,
            batteryPercent = 95.0
        )

        val diag = collector.buildTrialDiagnostics(
            trialNumber = 1,
            metrics = metrics,
            durationMs = 120_000L,
            lossTestingVerified = false,
            evidenceComplete = true,
            measurementProvenance = com.symphonia.gate2.benchmark.MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR,
            evidenceRef = "docs/gate2/runbook_two_devices.md#trial-1"
        )

        assertEquals(2000L, diag.packetsSent)
        assertEquals(0L, diag.packetsLost)
        assertEquals(0.0, diag.packetLossRate, 0.001)
        assertFalse(diag.playbackAtFivePercentLossVerified)
        assertEquals(120_000L, diag.durationMs)
        assertTrue(diag.evidenceComplete)
    }

    @Test
    fun testBuildTrialDiagnosticsWithFivePercentLossVerified() {
        val metrics = Gate2PhysicalBenchmarkCollector.LiveCollectorMetrics(
            rttMs = 25.0,
            packetsSent = 1000L,
            packetsLost = 55L, // 5.5% measured on wire
            bitrateKbps = 80.0,
            jitterMs = 5.0,
            offeredFrameCount = 5000L,
            droppedFrameCount = 0L,
            cpuPercent = 18.0,
            thermalStatus = 1,
            batteryPercent = 90.0
        )

        // Measured packet loss is used directly without synthetic coercion
        val diag = collector.buildTrialDiagnostics(
            trialNumber = 2,
            metrics = metrics,
            durationMs = 7_200_000L,
            lossTestingVerified = true,
            evidenceComplete = true,
            measurementProvenance = com.symphonia.gate2.benchmark.MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR,
            evidenceRef = "docs/gate2/runbook_two_devices.md#trial-2"
        )

        assertTrue("Packet loss rate must be >= 5%", diag.packetLossRate >= 0.05)
        assertTrue(diag.playbackAtFivePercentLossVerified)
        assertEquals(7_200_000L, diag.durationMs)
    }

    @Test
    fun testFullTwoHourCertificationSuiteEvaluatesToPassAndExports10Files() {
        val outputDir = File(tempFolder.root, "run-002")

        val trials = (1..3).map { number ->
            val metrics = Gate2PhysicalBenchmarkCollector.LiveCollectorMetrics(
                rttMs = 25.0,
                packetsSent = 5000L,
                packetsLost = 260L, // 5.2% measured loss
                bitrateKbps = 82.0,
                jitterMs = 3.5,
                offeredFrameCount = 10000L,
                droppedFrameCount = 1L,
                cpuPercent = 20.0,
                thermalStatus = 1,
                batteryPercent = 88.0
            )
            val diag = collector.buildTrialDiagnostics(
                trialNumber = number,
                metrics = metrics,
                durationMs = 7_200_000L, // 2 hours
                lossTestingVerified = true,
                batteryPercentPerHour = 4.0, // <= 8% / hr threshold
                memorySlopeMibPerHour = 0.1, // <= 1.0 MiB / hr threshold
                memoryStable = true,
                evidenceComplete = true,
                measurementProvenance = com.symphonia.gate2.benchmark.MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR,
                evidenceRef = "docs/gate2/runbook_two_devices.md#trial-$number"
            )
            BenchmarkTrial.complete(number, diag)
        }

        val (report, exportedDir) = collector.exportBenchmarkSuite(
            runId = "run-002",
            trials = trials,
            outputDir = outputDir
        )

        // Verifies all authoritative gates pass
        assertEquals(GateStatus.PASS, report.status)
        assertTrue(report.gates.all { !it.authoritative || it.status == GateStatus.PASS })

        // Verifies exactly 10 schema-v3 files exported
        val exportedFiles = exportedDir.listFiles() ?: emptyArray()
        assertEquals(10, exportedFiles.size)
        assertEquals(Gate2BenchmarkExporter.filenames, exportedFiles.map { it.name }.toSet())
    }

    @Test
    fun testShortDurationTrialsAreInconclusiveOnCrashGate() {
        val outputDir = File(tempFolder.root, "run-dev")

        val trials = (1..3).map { number ->
            val metrics = Gate2PhysicalBenchmarkCollector.LiveCollectorMetrics(
                rttMs = 20.0,
                packetsSent = 1000L,
                packetsLost = 60L,
                bitrateKbps = 80.0,
                jitterMs = 2.0,
                offeredFrameCount = 2000L,
                droppedFrameCount = 0L,
                cpuPercent = 15.0,
                thermalStatus = 0,
                batteryPercent = 95.0
            )
            val diag = collector.buildTrialDiagnostics(
                trialNumber = number,
                metrics = metrics,
                durationMs = 120_000L, // 2 minutes (short)
                lossTestingVerified = true
            )
            BenchmarkTrial.complete(number, diag)
        }

        val (report, _) = collector.exportBenchmarkSuite(
            runId = "run-dev",
            trials = trials,
            outputDir = outputDir
        )

        // 2-minute trials must be INCONCLUSIVE on zero_crashes_over_2h
        val crashGate = report.gates.single { it.name == "zero_crashes_over_2h" }
        assertEquals(GateStatus.INCONCLUSIVE, crashGate.status)
        assertEquals(GateStatus.INCONCLUSIVE, report.status)
    }
}
