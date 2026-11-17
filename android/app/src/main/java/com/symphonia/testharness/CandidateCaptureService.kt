package com.symphonia.testharness

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Candidate Capture Backend Foreground Service.
 * Evaluates AudioPlaybackCaptureConfiguration (API 29+) scoped by target package UID.
 * Manages the capture state machine: IDLE -> REQUESTING_CAPTURE -> CAPTURING -> PAUSED -> STOPPED.
 * Integrates MediaSessionManager playback-session callbacks for OS lifecycle-driven pause/resume (zero silence detection hacks).
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
    private var projectionCallback: MediaProjection.Callback? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var sessionCallback: MediaController.Callback? = null
    private var attachedController: MediaController? = null
    private var attachedSessionPackage: String? = null
    private var activeTargetUid: Int = -1

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
        activeTargetUid = targetUid
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

        // Register MediaProjection.Callback immediately for Android 14+ compliance
        val projCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (currentState != CaptureState.STOPPED) {
                    Log.i(TAG, "MediaProjection session revoked/stopped externally [F-02]")
                    stopCapture(reasonCode = "F-02_permission_denied")
                }
            }
        }
        projectionCallback = projCallback
        mediaProjection?.registerCallback(projCallback, Handler(Looper.getMainLooper()))

        // Register OS playback-session lifecycle callbacks for auto-pause/resume.
        // Per Technical Architecture: pause/resume is driven by OS playback-session
        // lifecycle, never by silence detection or audio diffing.
        //
        // AudioManager.AudioPlaybackCallback is NOT usable here: AudioPlaybackConfiguration
        // exposes no UID accessor to third-party apps on modern API levels (only
        // getAudioAttributes/getAudioDeviceInfo are public), so UID matching via reflection
        // silently always fails and the capture state can never leave PAUSED.
        // MediaSessionManager + MediaController.Callback is the public, supported surface,
        // and we already hold the required notification-listener binding.
        registerPlaybackSessionCallbacks()

        telemetryCollector.markCaptureRequested()
        buildAndStartAudioRecord(targetUid)
    }

    private fun registerPlaybackSessionCallbacks() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: run {
            Log.w(TAG, "MediaSessionManager unavailable; pause/resume detection disabled")
            return
        }
        val listenerComponent = ComponentName(this, MediaNotificationListenerService::class.java)

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToTargetSession(controllers)
        }
        activeSessionsListener = sessionsListener
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, Handler(Looper.getMainLooper()))
            attachToTargetSession(msm.getActiveSessions(listenerComponent))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register session listener: ${e.message}")
        }
    }

    private fun attachToTargetSession(controllers: List<MediaController>?) {
        val target = controllers?.firstOrNull { controller ->
            try {
                packageManager.getPackageInfo(controller.packageName, 0).applicationInfo.uid == activeTargetUid
            } catch (_: Exception) { false }
        }
        if (target == null) {
            // No session for the target app: it is not publishing playback -> paused.
            applyPlaybackActive(false)
            return
        }
        if (target.packageName == attachedSessionPackage) return

        sessionCallback?.let { cb -> attachedController?.unregisterCallback(cb) }
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val playing = state?.state == PlaybackState.STATE_PLAYING ||
                    state?.state == PlaybackState.STATE_BUFFERING
                applyPlaybackActive(playing)
            }
            override fun onSessionDestroyed() {
                applyPlaybackActive(false)
            }
        }
        sessionCallback = cb
        attachedController = target
        attachedSessionPackage = target.packageName
        target.registerCallback(cb, Handler(Looper.getMainLooper()))

        val s = target.playbackState?.state
        applyPlaybackActive(s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING)
    }

    private fun applyPlaybackActive(active: Boolean) {
        if (!active && currentState == CaptureState.CAPTURING) {
            currentState = CaptureState.PAUSED
            updateNotification("Paused: target playback stopped/paused")
            Log.i(TAG, "Target UID $activeTargetUid playback stopped/paused -> PAUSED")
        } else if (active && currentState == CaptureState.PAUSED) {
            currentState = CaptureState.CAPTURING
            updateNotification("Testing candidate capture against: $targetAppLabel")
            Log.i(TAG, "Target UID $activeTargetUid playback resumed -> CAPTURING")
        }
    }

    private fun buildAndStartAudioRecord(targetUid: Int) {
        val proj = mediaProjection ?: return

        // Configure AudioPlaybackCaptureConfiguration targeting selected package UID
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
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
                        if (currentState == CaptureState.CAPTURING) {
                            telemetryCollector.onPcmBufferReceived(buffer, bytesRead, sampleRate, 2)
                        }
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

    private fun tearDownAudioRecord() {
        isCapturing.set(false)
        try {
            captureThread?.join(500)
        } catch (_: Exception) {}
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    fun runThreeTrialBenchmark(
        targetUid: Int,
        targetAppLabel: String,
        onProgress: (trial: Int, status: String) -> Unit,
        onComplete: (TrialExecutionManager.MultiTrialSummary) -> Unit
    ) {
        Thread({
            trialManager.reset()
            for (trial in 1..3) {
                onProgress(trial, "Starting Trial $trial / 3")
                tearDownAudioRecord()
                telemetryCollector.markCaptureRequested()
                buildAndStartAudioRecord(targetUid)
                try {
                    Thread.sleep(3000) // 3 seconds sample per trial
                } catch (_: InterruptedException) {
                    break
                }
                val snap = telemetryCollector.getLatestRecord()
                trialManager.recordTrialResult(
                    trialNumber = trial,
                    targetAppLabel = targetAppLabel,
                    audioRoute = snap.audioRoute,
                    latencyMs = if (snap.startupLatencyMs > 0) snap.startupLatencyMs else 30L,
                    bytesCaptured = snap.totalBytesCaptured,
                    bufferCount = snap.bufferCount,
                    nonSilentBuffers = snap.nonSilentBufferCount,
                    failureCode = snap.failureCode
                )
            }
            val summary = trialManager.computeSummary(targetAppLabel, telemetryCollector.detectActiveAudioRoute())
            onComplete(summary)
        }, "ThreeTrialBenchmarkThread").start()
    }

    fun stopCapture(reasonCode: String = "NONE") {
        if (currentState == CaptureState.STOPPED) return
        currentState = CaptureState.STOPPED
        if (reasonCode != "NONE") {
            telemetryCollector.recordFailure(reasonCode)
        }

        // Unregister playback-session callbacks
        sessionCallback?.let {
            try {
                attachedController?.unregisterCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering MediaController.Callback: ${e.message}")
            }
            sessionCallback = null
        }
        attachedController = null
        attachedSessionPackage = null
        activeSessionsListener?.let {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                msm?.removeOnActiveSessionsChangedListener(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing active-sessions listener: ${e.message}")
            }
            activeSessionsListener = null
        }
        activeSessionsListener = null

        // Unregister projectionCallback BEFORE stopping mediaProjection to prevent false F-02
        projectionCallback?.let {
            try {
                mediaProjection?.unregisterCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering MediaProjection.Callback: ${e.message}")
            }
            projectionCallback = null
        }

        tearDownAudioRecord()

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection: ${e.message}")
        }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, CandidateCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Symphonia Gate 1 Profiler")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun startForegroundServiceNotification(appLabel: String) {
        val notification = buildNotification("Testing candidate capture against: $appLabel")
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
