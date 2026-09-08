package com.symphonia.gate2.prototype.harness

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.symphonia.gate2.contracts.Gate2Failure
import com.symphonia.gate2.contracts.ShareStoppedReason
import com.symphonia.gate2.pcm.PcmFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gate 2 Prototype Capture Service - explicitly prototype-named.
 *
 * Minimal runnable harness service implementing fail-closed AudioPlaybackCapture
 * with foreground notification. This is NOT production code - it is the Gate 2
 * feasibility experiment harness. Named explicitly to prevent confusion with
 * future production pipeline.
 *
 * Guarantees per AGENTS.md:
 *  - No P2P mesh (SFU only - enforced at transport layer, not here)
 *  - No silence detection (pause/resume via AudioManager callback in controller)
 *  - Never widens capture scope - if UID invalid or config fails -> STOPPED
 *  - Capture scope enforced at source via AudioPlaybackCaptureConfiguration.addMatchingUid
 *  - UI decoupled - activity binds but does not drive capture beyond intents
 *  - 48 kHz Opus baseline, 10ms rechunking, bounded queue
 *  - Persistent notification with Stop Sharing action (PRD §7.8)
 */
class Gate2PrototypeCaptureService : Service() {

    companion object {
        private const val TAG = "Gate2PrototypeSvc"
        private const val CHANNEL_ID = "gate2_prototype_capture"
        private const val NOTIFICATION_ID = 9002

        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_UID = "extra_target_uid"
        const val EXTRA_TARGET_APP_LABEL = "extra_target_app_label"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val ACTION_STOP_CAPTURE = "com.symphonia.gate2.prototype.harness.ACTION_STOP_CAPTURE"

        private fun createStopIntent(context: Context): Intent =
            Intent(context, Gate2PrototypeCaptureService::class.java).apply { action = ACTION_STOP_CAPTURE }
    }

    inner class LocalBinder : Binder() {
        fun getService(): Gate2PrototypeCaptureService = this@Gate2PrototypeCaptureService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    lateinit var captureController: Gate2CaptureController
        private set
    lateinit var telemetry: Gate2PrototypeTelemetryCollector
        private set

    var targetAppLabel: String = "Unknown"
        private set
    var targetUid: Int = -1
        private set
    var roomIdForNotification: String = "No Room"
        private set

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        captureController = Gate2CaptureController(am)
        telemetry = Gate2PrototypeTelemetryCollector(applicationContext)
        createNotificationChannel()
        Log.i(TAG, "Gate2 prototype service created - env ${telemetry.environment}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            Log.i(TAG, "Stop action from notification")
            stopGate2Capture(ShareStoppedReason.HOST_ENDED)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1
        targetAppLabel = intent?.getStringExtra(EXTRA_TARGET_APP_LABEL) ?: "Unknown"
        roomIdForNotification = intent?.getStringExtra(EXTRA_ROOM_ID) ?: "Gate2-Room"

        if (resultData != null && targetUid != -1) {
            // Valid target - request capture via controller (fail-closed enforced inside)
            val state = captureController.requestCapture(targetAppLabel, targetUid)
            if (state is Gate2CaptureController.CaptureState.Stopped) {
                // Fail-closed: do not attempt AudioRecord
                telemetry.recordFailure(state.failure?.wireCode ?: "G2-CAP-02")
                startForegroundWithNotification(isCapturing = false)
                Log.w(TAG, "Controller fail-closed before AudioRecord: $state")
                // Stop after short delay to allow notification visibility, then terminate
                stopGate2Capture(state.reason)
            } else {
                telemetry.markCaptureRequested()
                startForegroundWithNotification(isCapturing = true)
                initAudioCapture(resultData, targetUid)
            }
        } else {
            Log.e(TAG, "Fail-closed: missing MediaProjection or targetUid=$targetUid [G2-CAP-02]")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
            telemetry.recordFailure("G2-CAP-02")
            startForegroundWithNotification(isCapturing = false)
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initAudioCapture(resultData: Intent, targetUid: Int) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
        } catch (e: SecurityException) {
            Log.e(TAG, "MediaProjection permission denied [G2-CAP-01]: ${e.message}")
            captureController.onPermissionRevoked()
            telemetry.recordFailure("G2-CAP-01")
            stopGate2Capture(ShareStoppedReason.PERMISSION_REQUIRED)
            return
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection null -> fail-closed [G2-CAP-02]")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
            telemetry.recordFailure("G2-CAP-02")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            return
        }

        // Build fail-closed capture config - ONLY matching UID, no fallback
        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUid(targetUid)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Capture config failed fail-closed [G2-CAP-03]: ${e.message}")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SCOPE_UNVERIFIED)
            telemetry.recordFailure("G2-CAP-03")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            return
        }

        val sampleRate = 48_000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error $minBuf")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
            telemetry.recordFailure("G2-CAP-02")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            return
        }
        // 100ms buffer for stability, rechunker will produce 10ms frames
        val bufferBytes = (sampleRate * 2 /*stereo*/ * 2 /*16bit*/ / 10).coerceAtLeast(minBuf)

        try {
            val fmt = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(bufferBytes)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized state=${audioRecord?.state}")
                captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
                telemetry.recordFailure("G2-CAP-02")
                stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
                return
            }

            audioRecord?.startRecording()
            // Check recording state
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not recording state=${audioRecord?.recordingState}")
                captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
                telemetry.recordFailure("G2-CAP-02")
                stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
                return
            }

            isCapturing.set(true)
            captureController.onCaptureStarted(SystemClock.elapsedRealtimeNanos())
            Log.i(TAG, "AudioRecord started for uid=$targetUid label=$targetAppLabel sr=$sampleRate buf=$bufferBytes")

            // Start PCM read thread with validation/rechunking
            captureThread = Thread({
                // Buffer as Short for 16-bit PCM
                val byteBuffer = ByteArray(bufferBytes)
                val pcmFormat = PcmFormat(sampleRateHz = sampleRate, channelCount = 2)
                while (isCapturing.get()) {
                    val read = audioRecord?.read(byteBuffer, 0, byteBuffer.size) ?: -1
                    if (read > 0) {
                        // Convert byte[] little-endian PCM16 -> ShortArray
                        val shorts = ShortArray(read / 2)
                        for (i in shorts.indices) {
                            val lo = byteBuffer[i * 2].toInt() and 0xFF
                            val hi = byteBuffer[i * 2 + 1].toInt()
                            shorts[i] = ((hi shl 8) or lo).toShort()
                        }
                        if (shorts.isNotEmpty()) {
                            captureController.onPcmCaptured(shorts, pcmFormat, SystemClock.elapsedRealtimeNanos())
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.w(TAG, "AudioRecord read error $read [G2-CAP-02]")
                        captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
                        telemetry.recordFailure("G2-CAP-02")
                        isCapturing.set(false)
                    } else if (read == 0) {
                        // No data - not silence detection, just no callback; continue
                    }
                }
            }, "Gate2PrototypeCaptureThread").apply { isDaemon = true; start() }

            // MediaProjection callback for permission revocation -> STOPPED
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped -> permission_revoked")
                    captureController.onPermissionRevoked()
                    telemetry.recordFailure("G2-CAP-01")
                    stopGate2Capture(ShareStoppedReason.PERMISSION_REQUIRED)
                }
            }, null)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException startRecording [G2-CAP-01]: ${e.message}")
            captureController.onPermissionRevoked()
            telemetry.recordFailure("G2-CAP-01")
            stopGate2Capture(ShareStoppedReason.PERMISSION_REQUIRED)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgument capture [G2-CAP-02/03]: ${e.message}")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
            telemetry.recordFailure("G2-CAP-02")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "Unsupported capture [G2-CAP-03]: ${e.message}")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SCOPE_UNVERIFIED)
            telemetry.recordFailure("G2-CAP-03")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected capture failure [G2-INT-01]: ${e.message}", e)
            captureController.onCaptureUnavailable(Gate2Failure.INTERNAL_ERROR)
            telemetry.recordFailure("G2-INT-01")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
        }
    }

    fun stopGate2Capture(reason: ShareStoppedReason) {
        isCapturing.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        captureThread?.interrupt()
        captureThread = null
        if (captureController.state.value !is Gate2CaptureController.CaptureState.Stopped) {
            captureController.stopCapture(reason)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Gate2 capture stopped reason=$reason")
    }

    private fun startForegroundWithNotification(isCapturing: Boolean) {
        val notification = buildNotification(isCapturing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(isCapturing: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, createStopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stateText = when (val s = captureController.state.value) {
            is Gate2CaptureController.CaptureState.Idle -> "Idle"
            is Gate2CaptureController.CaptureState.RequestingCapture -> "Requesting capture…"
            is Gate2CaptureController.CaptureState.Capturing -> "Capturing ${s.appLabel}"
            is Gate2CaptureController.CaptureState.Paused -> "Paused — ${s.appLabel} switched away"
            is Gate2CaptureController.CaptureState.Stopped -> "Stopped — ${s.reason}"
        }
        val content = if (targetAppLabel != "Unknown") "$stateText • $targetAppLabel • $roomIdForNotification" else stateText
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Symphonia Gate2 Prototype")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(isCapturing)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Gate2 Prototype Capture", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground capture for Gate 2 prototype - metadata only, no audio logged"
                enableVibration(false)
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        stopGate2Capture(ShareStoppedReason.HOST_ENDED)
        super.onDestroy()
    }
}
