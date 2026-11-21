package com.symphonia.gate2.prototype.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.symphonia.gate2.transport.MediasoupListenerTransport

/**
 * Gate 2 Prototype Listener Service - Listener foreground service (Fix 3).
 *
 * Runs with foregroundServiceType="mediaPlayback" to keep WebRTC audio playout
 * alive through device screen-off and background transitions without being killed
 * by Android OEM aggressive battery managers.
 */
class Gate2PrototypeListenerService : Service() {

    companion object {
        private const val TAG = "Gate2ListenerSvc"
        private const val CHANNEL_ID = "gate2_listener_playback"
        private const val NOTIFICATION_ID = 9003

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val ACTION_STOP_LISTENING = "com.symphonia.gate2.prototype.harness.ACTION_STOP_LISTENING"

        fun createStopIntent(context: Context): Intent =
            Intent(context, Gate2PrototypeListenerService::class.java).apply { action = ACTION_STOP_LISTENING }
    }

    inner class LocalBinder : Binder() {
        fun getService(): Gate2PrototypeListenerService = this@Gate2PrototypeListenerService
    }

    private val binder = LocalBinder()
    var listenerTransport: MediasoupListenerTransport? = null
        private set

    var targetRoomId: String = ""
        private set
    var serverUrl: String = ""
        private set

    @Volatile var isListening: Boolean = false
        private set
    @Volatile var remoteState: String = "IDLE"
        private set
    @Volatile var remoteAppLabel: String? = null
        private set

    var onStatusChanged: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Gate2PrototypeListenerService created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LISTENING) {
            Log.i(TAG, "Stop listening requested from notification")
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }

        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "ws://10.0.2.2:3000/gate2"
        targetRoomId = intent?.getStringExtra(EXTRA_ROOM_ID) ?: ""

        if (targetRoomId.isBlank()) {
            Log.e(TAG, "No room ID provided for listener")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        startListenerSession(serverUrl, targetRoomId)

        return START_NOT_STICKY
    }

    private fun startListenerSession(url: String, roomId: String) {
        val transport = MediasoupListenerTransport(applicationContext)
        listenerTransport = transport

        transport.onStateUpdate = { state, appLabel, _ ->
            remoteState = state
            remoteAppLabel = appLabel
            updateNotification()
            onStatusChanged?.invoke()
        }

        transport.onTransportClosed = {
            Log.i(TAG, "Transport closed by remote host")
            stopListening()
            stopSelf()
        }

        Thread({
            try {
                transport.start(url, roomId)
                isListening = true
                remoteState = "CONNECTED"
                updateNotification()
                onStatusChanged?.invoke()
                Log.i(TAG, "Listener transport connected to room: $roomId")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start listener transport", t)
                remoteState = "FAILED (${t.message})"
                updateNotification()
                onStatusChanged?.invoke()
                stopListening()
                stopSelf()
            }
        }, "Gate2ListenerInitThread").start()
    }

    fun stopListening() {
        isListening = false
        listenerTransport?.close()
        listenerTransport = null
        remoteState = "STOPPED"
        stopForeground(STOP_FOREGROUND_REMOVE)
        onStatusChanged?.invoke()
        Log.i(TAG, "Gate2 listener stopped")
    }

    private fun startForegroundWithNotification() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, createStopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "Symphonia Gate2 Listener"
        val label = remoteAppLabel?.let { " • $it" } ?: ""
        val content = "Room: $targetRoomId$label ($remoteState)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(isListening)
            .addAction(android.R.drawable.ic_delete, "Leave Room", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Gate2 Listener Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground playback for Gate 2 prototype listener"
                enableVibration(false)
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}
