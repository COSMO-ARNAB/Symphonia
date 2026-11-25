package com.symphonia.gate2.prototype.harness

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.symphonia.gate2.contracts.Gate2Failure
import com.symphonia.gate2.contracts.ShareStoppedReason
import com.symphonia.gate2.transport.MediasoupSpeakerTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gate 2 Prototype Capture Service - Speaker foreground service.
 *
 * Implements:
 *  - Fail-closed app-scoped capture via MediaProjection and MediasoupSpeakerTransport.
 *  - Android 14 compliant foreground service startup order.
 *  - Fix 1: Eliminates manual AudioRecord read thread; WebRTC's read thread forwards
 *    10ms PCM frames directly to captureController.onPcmCaptured(...).
 *  - Fix 4: Immediate MediaProjection.Callback registration upon getMediaProjection(),
 *    and unregistration prior to mediaProjection.stop().
 *  - OS-lifecycle pause/resume synchronization with WebRTC Producer.
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
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_RELAY_ADDRESS = "extra_relay_address"
        const val ACTION_STOP_CAPTURE = "com.symphonia.gate2.prototype.harness.ACTION_STOP_CAPTURE"

        private fun createStopIntent(context: Context): Intent =
            Intent(context, Gate2PrototypeCaptureService::class.java).apply { action = ACTION_STOP_CAPTURE }
    }

    inner class LocalBinder : Binder() {
        fun getService(): Gate2PrototypeCaptureService = this@Gate2PrototypeCaptureService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val isCapturingAtomic = AtomicBoolean(false)
    val isCapturing: Boolean
        get() = isCapturingAtomic.get()

    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var sessionCallback: MediaController.Callback? = null
    private var attachedController: MediaController? = null
    private var volumeReceiver: BroadcastReceiver? = null

    var sessionStartTimeMs: Long = 0L
        private set

    @Volatile private var phoneRelay: Gate2PhoneRelay? = null
    @Volatile var speakerTransport: MediasoupSpeakerTransport? = null
        private set

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
    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()
    private val _listenerInvite = MutableStateFlow<String?>(null)
    val listenerInvite = _listenerInvite.asStateFlow()
    val usesPhoneRelay: Boolean get() = Gate2PhoneRelay.isAvailable(this)

    var serverUrl: String = "ws://10.0.2.2:3000/gate2"
        private set

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        captureController = Gate2CaptureController(am, pauseDebounceMs = Gate2CaptureController.DEFAULT_PAUSE_DEBOUNCE_MS)
        telemetry = Gate2PrototypeTelemetryCollector(applicationContext)

        captureController.onStateChanged = { state ->
            when (state) {
                is Gate2CaptureController.CaptureState.Paused -> {
                    speakerTransport?.pause()
                    updateNotification()
                }
                is Gate2CaptureController.CaptureState.Capturing -> {
                    speakerTransport?.resume()
                    updateNotification()
                }
                is Gate2CaptureController.CaptureState.Stopped -> {
                    speakerTransport?.close()
                    updateNotification()
                }
                else -> updateNotification()
            }
        }

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

        if (speakerTransport != null || phoneRelay != null) return START_NOT_STICKY
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1
        targetAppLabel = intent?.getStringExtra(EXTRA_TARGET_APP_LABEL) ?: "Unknown"
        roomIdForNotification = intent?.getStringExtra(EXTRA_ROOM_ID) ?: "Gate2-Room"
        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://10.0.2.2:3000/gate2"

        if (resultData != null && targetUid != -1) {
            val state = captureController.requestCapture(targetAppLabel, targetUid)
            if (state is Gate2CaptureController.CaptureState.Stopped) {
                telemetry.recordFailure(state.failure?.wireCode ?: "G2-CAP-02")
                startForegroundWithNotification(isCapturing = false)
                Log.w(TAG, "Controller fail-closed before MediaProjection: $state")
                stopGate2Capture(state.reason)
            } else {
                telemetry.markCaptureRequested()
                startForegroundWithNotification(isCapturing = true)
                initAudioCapture(resultData, targetUid, intent?.getStringExtra(EXTRA_RELAY_ADDRESS))
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

    private fun initAudioCapture(resultData: Intent, targetUid: Int, relayAddress: String?) {
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

        val proj = mediaProjection
        if (proj == null) {
            Log.e(TAG, "MediaProjection null -> fail-closed [G2-CAP-02]")
            captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE)
            telemetry.recordFailure("G2-CAP-02")
            stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            return
        }

        // Fix 4: Register MediaProjection.Callback IMMEDIATELY upon getMediaProjection()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked -> permission_revoked")
                captureController.onPermissionRevoked()
                telemetry.recordFailure("G2-CAP-01")
                stopGate2Capture(ShareStoppedReason.PERMISSION_REQUIRED)
            }
        }
        mediaProjectionCallback = callback
        proj.registerCallback(callback, Handler(Looper.getMainLooper()))

        // Fix 1: Wire MediasoupSpeakerTransport and use onSamplesReady callback
        val transport = MediasoupSpeakerTransport(applicationContext)
        transport.onSwapOutcome = { swapped, reason ->
            if (!swapped) {
                Log.e(TAG, "Fail-closed: PCM injection swap failed ($reason)")
                telemetry.recordFailure("G2-CAP-03")
                captureController.onCaptureUnavailable(Gate2Failure.CAPTURE_SCOPE_UNVERIFIED)
                stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            }
        }
        transport.onTransportClosed = {
            stopGate2Capture(ShareStoppedReason.HOST_ENDED)
        }
        speakerTransport = transport
        val relay = if (usesPhoneRelay) Gate2PhoneRelay(applicationContext, relayAddress) {
            if (speakerTransport === transport) stopGate2Capture(ShareStoppedReason.HOST_ENDED)
        } else null
        phoneRelay = relay

        Thread({
            try {
                relay?.bind()
                val endpoint = relay?.awaitReady() ?: serverUrl
                if (speakerTransport !== transport) return@Thread
                val createdRoomId = transport.start(
                    serverUrl = endpoint,
                    mediaProjection = proj,
                    targetUid = targetUid,
                    appLabel = targetAppLabel,
                    onSamplesReady = { _, _, _ ->
                        captureController.onSampleChunkOffered()
                    }
                )
                val invite = relay?.invite(createdRoomId)
                if (speakerTransport !== transport) {
                    transport.close()
                    return@Thread
                }
                roomIdForNotification = createdRoomId
                _listenerInvite.value = invite
                _currentRoomId.value = createdRoomId
                isCapturingAtomic.set(true)
                sessionStartTimeMs = SystemClock.elapsedRealtime()
                captureController.onCaptureStarted(SystemClock.elapsedRealtimeNanos())
                updateNotification()
                registerMediaSessionListener()
                registerVolumeReceiver()
                Log.i(TAG, "Speaker send path live in room: $createdRoomId")
            } catch (t: Throwable) {
                if (speakerTransport !== transport) return@Thread
                Log.e(TAG, "Failed to start WebRTC speaker session", t)
                captureController.onCaptureUnavailable(Gate2Failure.MEDIA_TRANSPORT_UNAVAILABLE)
                telemetry.recordFailure("G2-NET-01")
                stopGate2Capture(ShareStoppedReason.CAPTURE_UNAVAILABLE)
            }
        }, "Gate2SpeakerInitThread").start()
    }

    private fun registerVolumeReceiver() {
        if (volumeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (streamType == AudioManager.STREAM_MUSIC) {
                        val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        Log.d(TAG, "Volume changed: STREAM_MUSIC index=$newVol")
                        // If volume was adjusted while in Paused state, verify active playback
                        // immediately so playback isn't trapped in PAUSED.
                        if (captureController.state.value is Gate2CaptureController.CaptureState.Paused) {
                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && am != null) {
                                captureController.onPlaybackConfigsChanged(am.activePlaybackConfigurations)
                            }
                            attachedController?.playbackState?.state?.let { s ->
                                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                                    captureController.applyPlaybackActive(true, fromMediaSession = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        volumeReceiver = receiver
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        Log.i(TAG, "Registered VOLUME_CHANGED_ACTION receiver")
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            volumeReceiver = null
            Log.i(TAG, "Unregistered VOLUME_CHANGED_ACTION receiver")
        }
    }

    private fun registerMediaSessionListener() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val listenerComponent = ComponentName(this, Gate2NotificationListenerService::class.java)
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToTargetSession(controllers)
        }
        activeSessionsListener = sessionsListener
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, Handler(Looper.getMainLooper()))
            attachToTargetSession(msm.getActiveSessions(listenerComponent))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register MediaSessionManager listener: ${e.message}")
        }
    }

    private fun attachToTargetSession(controllers: List<MediaController>?) {
        val target = controllers?.firstOrNull { controller ->
            try {
                packageManager.getPackageInfo(controller.packageName, 0).applicationInfo.uid == targetUid
            } catch (_: Exception) { false }
        }
        if (target == null) {
            // Absence from active sessions does NOT mean the app paused (e.g. game, non-session app,
            // or temporary session recreation during volume/routing changes).
            return
        }
        if (target.packageName == attachedController?.packageName) {
            val s = target.playbackState?.state
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                captureController.applyPlaybackActive(true, fromMediaSession = true)
            }
            return
        }

        sessionCallback?.let { cb ->
            try { attachedController?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val playbackState = state?.state ?: return
                when (playbackState) {
                    PlaybackState.STATE_PLAYING,
                    PlaybackState.STATE_BUFFERING,
                    PlaybackState.STATE_FAST_FORWARDING,
                    PlaybackState.STATE_REWINDING -> {
                        captureController.applyPlaybackActive(true, fromMediaSession = true)
                    }
                    PlaybackState.STATE_PAUSED,
                    PlaybackState.STATE_STOPPED -> {
                        captureController.applyPlaybackActive(false, fromMediaSession = true)
                    }
                    else -> {
                        // Other states: do not change active state
                    }
                }
            }
            override fun onSessionDestroyed() {
                attachedController = null
                sessionCallback = null
            }
        }
        sessionCallback = cb
        attachedController = target
        target.registerCallback(cb, Handler(Looper.getMainLooper()))

        val s = target.playbackState?.state
        if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
            captureController.applyPlaybackActive(true, fromMediaSession = true)
        } else if (s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED) {
            captureController.applyPlaybackActive(false, fromMediaSession = true)
        }
    }

    fun stopGate2Capture(reason: ShareStoppedReason) {
        isCapturingAtomic.set(false)
        sessionStartTimeMs = 0L

        unregisterVolumeReceiver()

        sessionCallback?.let {
            try { attachedController?.unregisterCallback(it) } catch (_: Exception) {}
            sessionCallback = null
        }
        attachedController = null
        activeSessionsListener?.let {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                msm?.removeOnActiveSessionsChangedListener(it)
            } catch (_: Exception) {}
            activeSessionsListener = null
        }

        mediaProjectionCallback?.let { cb ->
            try { mediaProjection?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        mediaProjectionCallback = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        val transport = speakerTransport
        speakerTransport = null
        transport?.close()
        val relay = phoneRelay
        phoneRelay = null
        relay?.close()
        _listenerInvite.value = null
        roomIdForNotification = "No Room"
        _currentRoomId.value = null

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

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(isCapturing))
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
            .setContentTitle("Symphonia Gate2 Speaker")
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
