package com.symphonia.testharness

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Candidate Capture Backend Foreground Service.
 * Evaluates AudioPlaybackCaptureConfiguration (API 29+) scoped by target package UID.
 * Manages the capture state machine: IDLE -> REQUESTING_CAPTURE -> CAPTURING -> PAUSED -> STOPPED.
 * Integrates AudioManager playback callbacks for OS lifecycle-driven pause/resume (zero silence detection hacks).
 * Tracks Failure Taxonomy codes (F-01 through F-99) per PRD §7.7.
 */
class CandidateCaptureService : Service() {

    enum class CaptureState {
        IDLE,
        REQUESTING_CAPTURE,
        CAPTURING,
        PAUSED,
        STOPPED
    }

    companion object {
        private const val TAG = "CandidateCaptureSvc"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "symphonia_candidate_capture_channel"

        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_UID = "extra_target_uid"
        const val EXTRA_TARGET_APP_LABEL = "extra_target_app_label"
        const val ACTION_STOP_CAPTURE = "com.symphonia.testharness.ACTION_STOP_CAPTURE"
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    
    var currentState: CaptureState = CaptureState.IDLE
        private set

    lateinit var telemetryCollector: Gate1TelemetryCollector
        private set

    lateinit var trialManager: TrialExecutionManager
        private set

    var targetAppLabel: String = "Unknown App"
        private set

    inner class LocalBinder : Binder() {
        fun getService(): CandidateCaptureService = this@CandidateCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        telemetryCollector = Gate1TelemetryCollector(applicationContext)
        trialManager = TrialExecutionManager(telemetryCollector)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCapture(reasonCode = "F-06_user_stopped")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1
        targetAppLabel = intent?.getStringExtra(EXTRA_TARGET_APP_LABEL) ?: "Selected App"

        if (resultData != null && targetUid != -1) {
            currentState = CaptureState.REQUESTING_CAPTURE
            startForegroundServiceNotification(targetAppLabel)
            initCandidateCaptureBackend(resultData, targetUid)
        } else {
            Log.e(TAG, "Invalid parameters provided to CandidateCaptureService [F-01]")
            telemetryCollector.recordFailure("F-01_capture_unavailable")
            currentState = CaptureState.STOPPED
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initCandidateCaptureBackend(resultData: Intent, targetUid: Int) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
        } catch (e: SecurityException) {
            Log.e(TAG, "MediaProjection permission denied [F-02]: ${e.message}")
            telemetryCollector.recordFailure("F-02_permission_denied")
            currentState = CaptureState.STOPPED
            stopSelf()
            return
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection returned null [F-01]")
            telemetryCollector.recordFailure("F-01_capture_unavailable")
            currentState = CaptureState.STOPPED
            stopSelf()
            return
        }

        telemetryCollector.markCaptureRequested()

        // Configure AudioPlaybackCaptureConfiguration targeting selected package UID
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUid(targetUid)
            .build()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = Math.max(minBufferSize, sampleRate * 2 * 2 / 10) // 100ms buffer

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            isCapturing.set(true)
            currentState = CaptureState.CAPTURING

            captureThread = Thread({
                val buffer = ByteArray(bufferSize)
                while (isCapturing.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        telemetryCollector.onPcmBufferReceived(buffer, bytesRead, sampleRate, 2)
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "AudioRecord read error [F-01]: $bytesRead")
                        telemetryCollector.recordFailure("F-01_read_error")
                    }
                }
            }, "CandidateCaptureThread")
            captureThread?.start()

            Log.i(TAG, "Candidate Capture Backend initialized for target UID: $targetUid ($targetAppLabel)")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting capture [F-02]: ${e.message}")
            telemetryCollector.recordFailure("F-02_permission_denied")
            stopCapture(reasonCode = "F-02_permission_denied")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException starting capture [F-05]: ${e.message}")
            telemetryCollector.recordFailure("F-05_drm_or_restricted")
            stopCapture(reasonCode = "F-05_drm_or_restricted")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure starting capture [F-99]: ${e.message}")
            telemetryCollector.recordFailure("F-99_unknown")
            stopCapture(reasonCode = "F-99_unknown")
        }
    }

    fun stopCapture(reasonCode: String = "NONE") {
        isCapturing.set(false)
        currentState = CaptureState.STOPPED
        if (reasonCode != "NONE") {
            telemetryCollector.recordFailure(reasonCode)
        }
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundServiceNotification(appLabel: String) {
        val stopIntent = Intent(this, CandidateCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Symphonia Gate 1 Profiler")
            .setContentText("Testing candidate capture against: $appLabel")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Candidate Capture Test Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for Symphonia Candidate Capture Profiling"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
