package com.symphonia.gate2.spikeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import org.mediasoup.droid.Device
import org.mediasoup.droid.MediasoupClient
import org.mediasoup.droid.Producer
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.Transport
import org.mediasoup.droid.PeerConnection as MediasoupPcOptions
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import com.symphonia.gate2.spike.PcmInjectionProbe

/**
 * THROWAWAY spike capture service.
 *
 * Android-14-correct foreground-service order (verified against the harness):
 *  1. startForeground(MEDIA_PROJECTION) BEFORE getMediaProjection()
 *  2. getMediaProjection(RESULT_OK, resultData)
 *  3. build the injection factory (PCM swap registered)
 *  4. load mediasoup Device with the injected PeerConnectionFactory
 *  5. create send transport -> produce audio track
 *
 * Fail-closed: on any swap failure the producer is closed and the service
 * stops - the microphone path is never streamed.
 */
class SpikeCaptureService : Service() {

    companion object {
        private const val TAG = "Gate2SpikeSvc"
        private const val CHANNEL_ID = "gate2_spike_channel"
        private const val NOTIFICATION_ID = 9101
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SERVER_URL = "extra_server_url"
        @Volatile var lastLog: String = "idle"
            private set
    }

    private val binder = LocalBinder()
    private var projection: MediaProjection? = null
    private var signaling: SpikeSignalingClient? = null
    private var sendTransport: SendTransport? = null
    private var producer: Producer? = null

    inner class LocalBinder : Binder() { fun getService(): SpikeCaptureService = this@SpikeCaptureService }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        MediasoupClient.initialize(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
        if (resultData == null || serverUrl == null) { stopSelf(); return START_NOT_STICKY }

        // 1. FGS with mediaProjection type MUST be running BEFORE
        //    getMediaProjection() on Android 14 - else SecurityException.
        startForeground(NOTIFICATION_ID, buildNotification("starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        Thread {
            try {
                startSession(resultData, serverUrl)
            } catch (t: Throwable) {
                log("FAILED: ${t.message}")
                Log.e(TAG, "session failed", t)
                stopSession()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startSession(resultData: Intent, serverUrl: String) {
        // 2. Projection
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val mediaProjection = pm.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { log("projection revoked - stopping"); stopSession() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        projection = mediaProjection
        log("projection granted")

        // 3. Injection factory (swap fires at onWebRtcAudioRecordStart)
        val pcFactory = PcmInjectionProbe.buildInjectionFactory(applicationContext, mediaProjection) { swapped, reason ->
            if (!swapped) {
                log("FAIL-CLOSED: swap failed ($reason) - stopping, mic never streamed")
                stopSession()
            } else {
                log("capture swap ACTIVE - streaming app audio")
            }
        }

        // 4. mediasoup Device, factory injected via PeerConnection$Options
        val options = MediasoupPcOptions.Options().apply { setFactory(pcFactory) }
        val device = Device()

        signaling = SpikeSignalingClient(serverUrl).also { client ->
            client.connectBlocking()
            client.onNotification = { type, _ -> log("server: $type") }
        }
        // Room FIRST - the server requires membership before any SFU request.
        val room = signaling!!.request("CREATE_ROOM", JSONObject())
        log("room created: ${room.getString("roomId").take(8)}… (full id: ${room.getString("roomId")})")

        val rtpCaps = signaling!!.request("GET_ROUTER_RTP_CAPABILITIES", JSONObject())
            .getJSONObject("rtpCapabilities")
        device.load(rtpCaps.toString(), options)
        log("device loaded")

        val transportInfo = signaling!!.request("CREATE_WEBRTC_TRANSPORT",
            JSONObject().put("direction", "send"))
        sendTransport = device.createSendTransport(sendListener, transportInfo.getString("id"),
            transportInfo.getString("iceParameters"), transportInfo.getString("iceCandidates"),
            transportInfo.getString("dtlsParameters"), null, options, null)

        val audioSource = pcFactory.createAudioSource(MediaConstraints())
        val audioTrack = pcFactory.createAudioTrack("gate2-spike-audio", audioSource)
        // Signature: produce(listener, track, encodings, codecOptions, appData)
        // - kind is inferred from the track (audio track -> "audio")
        // - codecOptions/appData must be null or valid JSON, never a bare string
        producer = sendTransport!!.produce(producerListener, audioTrack, null, null, null)
        log("PRODUCER LIVE: ${producer!!.id} - play music on this phone now")
    }

    private val sendListener = object : SendTransport.Listener {
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            runCatching {
                signaling?.request("CONNECT_WEBRTC_TRANSPORT", JSONObject()
                    .put("transportId", transport.getId())
                    .put("dtlsParameters", JSONObject(dtlsParameters)))
            }.onFailure { Log.e(TAG, "onConnect failed", it) }
        }
        override fun onConnectionStateChange(transport: Transport, newState: String) {
            log("transport: $newState")
        }
        override fun onProduce(transport: Transport, kind: String, rtpParameters: String, appData: String): String {
            // Runs on a mediasoup worker thread; blocking request is fine here.
            val response = signaling!!.request("PRODUCE", JSONObject()
                .put("transportId", transport.getId())
                .put("kind", kind)
                .put("rtpParameters", JSONObject(rtpParameters)))
            return response.getString("id")
        }
        override fun onProduceData(transport: Transport, sctpStreamParameters: String, label: String, protocol: String, appData: String): String {
            throw UnsupportedOperationException("data producers not supported in spike")
        }
    }

    private val producerListener = Producer.Listener {
        log("producer transport closed")
        stopSession()
    }

    private fun stopSession() {
        runCatching { producer?.close() }
        runCatching { sendTransport?.close() }
        runCatching { signaling?.close() }
        runCatching { projection?.stop() }
        producer = null; sendTransport = null; signaling = null; projection = null
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        lastLog = message
    }

    private fun buildNotification(text: String): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Gate2 Spike", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Symphonia Gate2 Spike")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() { buildNotification("starting") }
}
