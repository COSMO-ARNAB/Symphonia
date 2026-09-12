package com.symphonia.gate2.prototype.harness

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Gate 2 prototype telemetry - explicitly prototype-named, metadata-only.
 * No audio payload is ever logged or persisted per AGENTS.md.
 * Captures environment metadata + streaming metrics for physical-device evidence.
 */
class Gate2PrototypeTelemetryCollector(private val context: Context) {
    companion object { private const val TAG = "Gate2Telemetry" }

    data class EnvironmentMetadata(
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val device: String = Build.DEVICE,
        val androidVersion: String = Build.VERSION.RELEASE,
        val apiLevel: Int = Build.VERSION.SDK_INT,
        val securityPatch: String = Build.VERSION.SECURITY_PATCH,
        val fingerprint: String = Build.FINGERPRINT,
        val cpuAbis: String = Build.SUPPORTED_ABIS.joinToString(","),
        val totalRamGb: Double = 0.0,
    )

    data class Snapshot(
        val captureState: String,
        val startedAtMs: Long,
        val elapsedMs: Long,
        val totalOfferedFrames: Long,
        val totalDroppedFrames: Long,
        val queueSize: Int,
        val audioRoute: String,
        val thermalStatus: String,
        val memoryState: String,
        val failureCode: String,
    )

    val environment: EnvironmentMetadata by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val gb = (mem.totalMem.toDouble() / (1024*1024*1024)).let { String.format("%.2f", it).toDouble() }
        EnvironmentMetadata(totalRamGb = gb)
    }

    private var captureStartMs: Long = 0
    var failureCode: String = "NONE"
        private set

    fun markCaptureRequested() {
        captureStartMs = SystemClock.elapsedRealtime()
        failureCode = "NONE"
    }

    fun recordFailure(code: String) {
        failureCode = code
        Log.w(TAG, "Failure recorded: $code")
    }

    fun snapshot(controller: Gate2CaptureController, captureState: String): Snapshot {
        val elapsed = if (captureStartMs == 0L) 0 else SystemClock.elapsedRealtime() - captureStartMs
        return Snapshot(
            captureState = captureState,
            startedAtMs = captureStartMs,
            elapsedMs = elapsed,
            totalOfferedFrames = controller.totalOfferedFrames,
            totalDroppedFrames = controller.totalDroppedFrames,
            queueSize = controller.queueSize(),
            audioRoute = detectRoute(),
            thermalStatus = thermalStatus(),
            memoryState = memoryState(),
            failureCode = failureCode
        )
    }

    private fun detectRoute(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Unknown"
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return "Bluetooth (${d.productName})"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> return "Wired (${d.productName})"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return "Speaker"
            }
        }
        return "Unknown"
    }

    fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
                PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Hot"
                else -> "Normal"
            }
        }
        return "Normal"
    }

    fun memoryState(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return when {
            mi.lowMemory -> "LowMem"
            mi.availMem < 500*1024*1024 -> "Moderate"
            else -> "Clean"
        }
    }

    fun exportEnvJson(): String {
        val e = environment
        return """{"manufacturer":"${e.manufacturer}","model":"${e.model}","device":"${e.device}","androidVersion":"${e.androidVersion}","apiLevel":${e.apiLevel},"securityPatch":"${e.securityPatch}","fingerprint":"${e.fingerprint}","cpuAbis":"${e.cpuAbis}","ramGb":${e.totalRamGb}}"""
    }
}
