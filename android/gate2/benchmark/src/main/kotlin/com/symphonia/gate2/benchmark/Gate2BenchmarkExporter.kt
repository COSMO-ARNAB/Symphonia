package com.symphonia.gate2.benchmark

import java.util.Locale

data class BenchmarkExport(val filename: String, val content: String)

object Gate2BenchmarkExporter {
    val filenames: Set<String> = linkedSetOf(
        "environment.json", "latency.csv", "network.csv", "battery.csv", "memory.csv", "cpu.csv",
        "jitter.csv", "packet_loss.csv", "reconnect.csv", "summary.json",
    )

    fun export(report: Gate2BenchmarkReport): List<BenchmarkExport> = listOf(
        BenchmarkExport("environment.json", environmentJson(report)),
        BenchmarkExport("latency.csv", metricCsv(report, listOf("end_to_end_latency_ms", "encode_latency_ms", "decode_latency_ms"))),
        BenchmarkExport("network.csv", networkCsv(report)),
        BenchmarkExport("battery.csv", metricCsv(report, listOf("battery_percent_per_hour"))),
        BenchmarkExport("memory.csv", memoryCsv(report)),
        BenchmarkExport("cpu.csv", cpuCsv(report)),
        BenchmarkExport("jitter.csv", metricCsv(report, listOf("average_jitter_ms"))),
        BenchmarkExport("packet_loss.csv", packetLossCsv(report)),
        BenchmarkExport("reconnect.csv", metricCsv(report, listOf("reconnect_ms"))),
        BenchmarkExport("summary.json", summaryJson(report)),
    ).also { require(it.map(BenchmarkExport::filename).toSet() == filenames) }

    private fun environmentJson(report: Gate2BenchmarkReport): String = with(report.environment) {
        "{\"schemaVersion\":${report.schemaVersion},\"runId\":${json(report.runId)}," +
            "\"generatedAtEpochMs\":${report.generatedAtEpochMs},\"deviceManufacturer\":${json(deviceManufacturer)}," +
            "\"deviceModel\":${json(deviceModel)},\"osVersion\":${json(osVersion)},\"appVersion\":${json(appVersion)}," +
            "\"networkType\":${json(networkType)},\"networkConditions\":${json(networkConditions)}," +
            "\"physicalDeviceEvidence\":${json(physicalDeviceEvidence.name)},\"evidenceReference\":${nullableJson(evidenceReference)}}"
    }

    private fun metricCsv(report: Gate2BenchmarkReport, metrics: List<String>): String = buildString {
        append("schema_version,run_id,trial_number,trial_status,metric,value,duration_ms,crash_count\n")
        report.trials.forEach { trial ->
            metrics.forEach { metric -> row(report, trial, metric, value(trial.diagnostics, metric)) }
        }
        summaries(report, metrics)
    }

    private fun networkCsv(report: Gate2BenchmarkReport): String = buildString {
        append("schema_version,run_id,trial_number,trial_status,rtt_ms,bitrate_kbps,packets_sent,packets_lost,offered_frames,dropped_frames,dropped_frame_rate\n")
        report.trials.forEach { t ->
            append(prefix(report, t)).append(',').append(number(t.diagnostics?.rttMs)).append(',')
                .append(number(t.diagnostics?.bitrateKbps)).append(',').append(t.diagnostics?.packetsSent ?: "")
                .append(',').append(t.diagnostics?.packetsLost ?: "").append(',').append(t.diagnostics?.offeredFrameCount ?: "")
                .append(',').append(t.diagnostics?.droppedFrameCount ?: "").append(',').append(number(t.diagnostics?.droppedFrameRate)).append('\n')
        }
    }

    private fun memoryCsv(report: Gate2BenchmarkReport): String = buildString {
        append("schema_version,run_id,trial_number,trial_status,memory_slope_mib_per_hour,memory_stable\n")
        report.trials.forEach { t -> append(prefix(report, t)).append(',').append(number(t.diagnostics?.memorySlopeMibPerHour))
            .append(',').append(t.diagnostics?.memoryStable ?: "").append('\n') }
        summaries(report, listOf("memory_slope_mib_per_hour"))
    }

    private fun cpuCsv(report: Gate2BenchmarkReport): String = buildString {
        append("schema_version,run_id,trial_number,trial_status,average_cpu_percent,peak_thermal_status\n")
        report.trials.forEach { t -> append(prefix(report, t)).append(',').append(number(t.diagnostics?.averageCpuPercent))
            .append(',').append(t.diagnostics?.peakThermalStatus ?: "").append('\n') }
        summaries(report, listOf("average_cpu_percent"))
    }

    private fun packetLossCsv(report: Gate2BenchmarkReport): String = buildString {
        append("schema_version,run_id,trial_number,trial_status,packets_sent,packets_lost,packet_loss_rate,playback_at_5_percent_loss_verified\n")
        report.trials.forEach { t -> append(prefix(report, t)).append(',').append(t.diagnostics?.packetsSent ?: "")
            .append(',').append(t.diagnostics?.packetsLost ?: "").append(',').append(number(t.diagnostics?.packetLossRate))
            .append(',').append(t.diagnostics?.playbackAtFivePercentLossVerified ?: "").append('\n') }
        append("# weighted_packet_loss_rate,").append(number(report.weightedPacketLossRate)).append('\n')
        summaries(report, listOf("packet_loss_rate"))
    }

    private fun summaryJson(report: Gate2BenchmarkReport): String = buildString {
        append("{\"schemaVersion\":").append(report.schemaVersion).append(",\"runId\":").append(json(report.runId))
        append(",\"generatedAtEpochMs\":").append(report.generatedAtEpochMs).append(",\"status\":").append(json(report.status.name))
        append(",\"weightedPacketLossRate\":").append(numberOrNull(report.weightedPacketLossRate)).append(",\"trials\":[")
        report.trials.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append("{\"number\":${t.number},\"status\":${json(t.status.name)},\"detail\":${nullableJson(t.detail)},")
                .append("\"measurementProvenance\":${t.diagnostics?.measurementProvenance?.name?.let(::json) ?: "null"},")
                .append("\"evidenceReference\":${nullableJson(t.diagnostics?.evidenceReference)}}")
        }
        append("],\"summaries\":{")
        report.summaries.entries.forEachIndexed { i, (name, s) ->
            if (i > 0) append(',')
            append(json(name)).append(":{\"count\":${s.count},\"mean\":${number(s.mean)},\"median\":${number(s.median)},")
                .append("\"sampleStandardDeviation\":${number(s.sampleStandardDeviation)}}")
        }
        append("},\"gates\":[")
        report.gates.forEachIndexed { i, g ->
            if (i > 0) append(',')
            append("{\"name\":${json(g.name)},\"measured\":${numberOrNull(g.measured)},\"maximum\":${numberOrNull(g.maximum)},")
                .append("\"status\":${json(g.status.name)},\"authoritative\":${g.authoritative}}")
        }
        append("]}")
    }

    private fun StringBuilder.row(report: Gate2BenchmarkReport, trial: BenchmarkTrial, metric: String, value: Double?) {
        append(prefix(report, trial)).append(',').append(metric).append(',').append(number(value)).append(',')
            .append(trial.diagnostics?.durationMs ?: "").append(',').append(trial.diagnostics?.crashCount ?: "").append('\n')
    }

    private fun StringBuilder.summaries(report: Gate2BenchmarkReport, metrics: List<String>) {
        append("# metric,count,mean,median,sample_standard_deviation\n")
        metrics.forEach { metric -> report.summaries[metric]?.let { s ->
            append("# ").append(metric).append(',').append(s.count).append(',').append(number(s.mean)).append(',')
                .append(number(s.median)).append(',').append(number(s.sampleStandardDeviation)).append('\n')
        } }
    }

    private fun prefix(report: Gate2BenchmarkReport, trial: BenchmarkTrial) =
        "${report.schemaVersion},${csv(report.runId)},${trial.number},${trial.status.name}"

    private fun value(d: TrialDiagnostics?, metric: String): Double? = when (metric) {
        "end_to_end_latency_ms" -> d?.endToEndLatencyMs
        "encode_latency_ms" -> d?.encodeLatencyMs
        "decode_latency_ms" -> d?.decodeLatencyMs
        "average_jitter_ms" -> d?.averageJitterMs
        "reconnect_ms" -> d?.reconnectMs
        "battery_percent_per_hour" -> d?.batteryPercentPerHour
        else -> null
    }

    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.9f", it) } ?: ""
    private fun numberOrNull(value: Double?): String = value?.let(::number) ?: "null"
    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun nullableJson(value: String?): String = value?.let(::json) ?: "null"
    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
}
