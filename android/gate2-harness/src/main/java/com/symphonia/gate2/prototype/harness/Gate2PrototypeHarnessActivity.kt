package com.symphonia.gate2.prototype.harness

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Gate 2 Prototype Harness Activity - explicitly prototype-named.
 *
 * Minimal UI required to test Speaker/Listener behavior for the Gate 2 feasibility
 * experiment. This is NOT the final product UI - per instructions "Do not build
 * the final product UI."
 *
 * Responsibilities:
 *  - Role selection (Speaker / Listener) for the one-to-many experiment
 *  - App discovery via Gate2MediaSessionDiscovery (active sessions only)
 *  - MediaProjection request + bind to Gate2PrototypeCaptureService (fail-closed)
 *  - Signaling server URL + room ID inputs (stub for Step 4, shows expected protocol)
 *  - Live status: capture state, route, thermal, queue, telemetry
 *
 * Engine vs UI separation: Activity only binds to service and observes state;
 * it never drives capture via Activity lifecycle callbacks.
 */
class Gate2PrototypeHarnessActivity : ComponentActivity() {

    private var captureService: Gate2PrototypeCaptureService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)
    private var selectedApp by mutableStateOf<Gate2MediaSessionDiscovery.DiscoveredApp?>(null)
    private var harnessRole by mutableStateOf(HarnessRole.SPEAKER)
    private var isCaptureActive by mutableStateOf(false)

    enum class HarnessRole { SPEAKER, LISTENER }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as Gate2PrototypeCaptureService.LocalBinder
            captureService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isBound = false
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && selectedApp != null) {
            val svcIntent = Intent(this, Gate2PrototypeCaptureService::class.java).apply {
                putExtra(Gate2PrototypeCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(Gate2PrototypeCaptureService.EXTRA_TARGET_UID, selectedApp!!.uid)
                putExtra(Gate2PrototypeCaptureService.EXTRA_TARGET_APP_LABEL, selectedApp!!.appLabel)
                putExtra(Gate2PrototypeCaptureService.EXTRA_ROOM_ID, roomIdInput)
            }
            // Android 13+ needs POST_NOTIFICATIONS before foreground service
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            startForegroundService(svcIntent)
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            isCaptureActive = true
        } else {
            Toast.makeText(this, "Capture cancelled or no app selected (fail-closed)", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Notification permission denied - foreground notification may not show", Toast.LENGTH_LONG).show()
    }

    private var roomIdInput: String = "Gate2-Local-Test"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val discovery = Gate2MediaSessionDiscovery(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var discovered by remember { mutableStateOf(discovery.discover()) }
                    var serverUrl by remember { mutableStateOf("ws://10.0.2.2:3000/gate2") }
                    var localRoomId by remember { mutableStateOf(roomIdInput) }
                    var captureTick by remember { mutableStateOf(0) }

                    // Poll service state for live updates (StateFlow observe would need lifecycle)
                    LaunchedEffect(isCaptureActive, isBound) {
                        while (isCaptureActive) {
                            kotlinx.coroutines.delay(500)
                            captureTick++
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Prototype banner
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "GATE 2 PROTOTYPE HARNESS",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "Not production • Fail-closed capture only • Physical device required • No audio logged",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "Steps 2-6 not yet implemented: native WebRTC, signaling, listener, evidence collector",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        Text(
                            "Symphonia Gate 2 Prototype Harness",
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Speaker → PCM → WebRTC → mediasoup → Listener (prototype)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Role selector (minimal for one-to-many test)
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Harness Role (for Step 5 Listener path)", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = harnessRole == HarnessRole.SPEAKER, onClick = { harnessRole = HarnessRole.SPEAKER })
                                    Text("Speaker (capture + publish)", modifier = Modifier.padding(end = 16.dp))
                                    RadioButton(selected = harnessRole == HarnessRole.LISTENER, onClick = { harnessRole = HarnessRole.LISTENER })
                                    Text("Listener (subscribe + playout)")
                                }
                                Text(
                                    if (harnessRole == HarnessRole.SPEAKER) "Speaker: select app below, then Start Capture. Audio goes to mediasoup (Step 3-4)."
                                    else "Listener: join room below, then await Producer (Step 5). No mic fallback.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Signaling config (Step 4 placeholder)
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Signaling / Room (Step 4 – mediasoup params, not OFFER/ANSWER)", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = serverUrl,
                                    onValueChange = { serverUrl = it },
                                    label = { Text("Signaling URL (ws://host:port/gate2)") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = localRoomId,
                                    onValueChange = { localRoomId = it; roomIdInput = it },
                                    label = { Text("Room ID for notification / future CREATE_ROOM") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    singleLine = true
                                )
                                Text(
                                    "Node prototype: CREATE_ROOM / JOIN_ROOM / CREATE_WEBRTC_TRANSPORT / CONNECT / PRODUCE / CONSUME / RESUME_CONSUMER. No auth/TLS in prototype. Firewall to test devices only.",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = false, onClick = {}) { Text("Create Room (Step 4)") }
                                    Button(enabled = false, onClick = {}) { Text("Join Room (Step 4)") }
                                }
                            }
                        }

                        // Speaker: App discovery
                        if (harnessRole == HarnessRole.SPEAKER) {
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("App Selection (MediaSessionManager)", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Only apps with active media session are capturable. Empty = nothing playing (fail-closed).",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Button(onClick = { discovered = discovery.discover() }) { Text("Refresh") }
                                    }
                                    if (discovered.isEmpty()) {
                                        Text(
                                            "No apps discovered. Start playback in Spotify/VLC/YouTube Music and tap Refresh.",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    } else {
                                        // Inline list (not LazyColumn inside scroll - use Column)
                                        discovered.forEach { app ->
                                            val isSelected = selectedApp?.packageName == app.packageName
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(app.appLabel, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            "${app.packageName} • ${app.discoveryMethod} • ${if (app.isSessionActive) "ACTIVE" else "installed, not playing"}",
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = { selectedApp = app }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (selectedApp != null) {
                                        Text(
                                            "Selected: ${selectedApp!!.appLabel} (uid=${selectedApp!!.uid}) • Fail-closed: if capture unavailable, shows \"sharing unavailable\" - never widens.",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Capture controls
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Capture Control (Foreground Service + MediaProjection)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "48 kHz stereo PCM → 10ms rechunk → bounded queue (100 frames) → WebRTC injection (Step 3). OS-lifecycle pause/resume, never silence detection.",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            enabled = selectedApp != null && !isCaptureActive,
                                            onClick = {
                                                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                // Check RECORD_AUDIO permission for capture (AudioPlaybackCapture still needs it implicitly via projection)
                                                projectionLauncher.launch(pm.createScreenCaptureIntent())
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Start Capture") }
                                        Button(
                                            enabled = isCaptureActive,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            onClick = {
                                                if (isBound) { unbindService(serviceConnection); isBound = false }
                                                captureService?.stopGate2Capture(com.symphonia.gate2.contracts.ShareStoppedReason.HOST_ENDED)
                                                captureService = null
                                                isCaptureActive = false
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Stop Capture") }
                                    }
                                    Text(
                                        "Notification will show: \"Symphonia Gate2 Prototype\" + app + room + Stop Sharing action (PRD §7.8).",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        } else {
                            // Listener placeholder
                            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Listener Path (Step 5 – not yet implemented)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Will consume mediasoup Consumer → WebRTC receive → decode → AudioTrack + jitter stats. Physical device only. No synthesis.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Current harness shows Speaker status only. Listener UI will add: Consumer state, playout volume, RTP stats, reconnect.",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Button(enabled = false, onClick = {}) { Text("Start Listener (Step 5)") }
                                }
                            }
                        }

                        // Live status - observes service
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Live Capture Status + Telemetry (Step 6 collector preview)", style = MaterialTheme.typography.titleSmall)
                                // Force recompose with captureTick
                                @Suppress("UNUSED_VARIABLE") val _tick = captureTick
                                val svc = captureService
                                val stateText = when (val s = svc?.captureController?.state?.value) {
                                    is Gate2CaptureController.CaptureState.Idle -> "IDLE"
                                    is Gate2CaptureController.CaptureState.RequestingCapture -> "REQUESTING_CAPTURE"
                                    is Gate2CaptureController.CaptureState.Capturing -> "CAPTURING (${s.appLabel} uid=${s.targetUid})"
                                    is Gate2CaptureController.CaptureState.Paused -> "PAUSED — ${s.appLabel} switched away (auto-resume if returns)"
                                    is Gate2CaptureController.CaptureState.Stopped -> "STOPPED — ${s.reason} ${s.failure?.wireCode ?: ""}"
                                    null -> if (isCaptureActive) "Binding…" else "Idle – no capture"
                                }
                                val snap = if (svc != null) {
                                    val ctrl = svc.captureController
                                    svc.telemetry.snapshot(ctrl, stateText)
                                } else null

                                Text(
                                    if (svc == null) {
                                        "Harness idle. ${if (harnessRole == HarnessRole.SPEAKER) "Select app and Start Capture on physical device." else "Switch to Speaker to test capture."}\n" +
                                        "Physical-device mandate: emulator is informational only."
                                    } else {
                                        buildString {
                                            append("State: $stateText\n")
                                            append("Target: ${svc.targetAppLabel} uid=${svc.targetUid}\n")
                                            append("Room (notif): ${svc.roomIdForNotification}\n")
                                            if (snap != null) {
                                                append("Offered: ${snap.totalOfferedFrames}  Dropped: ${snap.totalDroppedFrames}  Queue: ${snap.queueSize}/100\n")
                                                append("Route: ${snap.audioRoute}  Thermal: ${snap.thermalStatus}  Mem: ${snap.memoryState}\n")
                                                append("Failure: ${snap.failureCode}  Elapsed: ${snap.elapsedMs}ms\n")
                                            }
                                            append("Env: ${svc.telemetry.environment.manufacturer} ${svc.telemetry.environment.model} Android ${svc.telemetry.environment.androidVersion} API ${svc.telemetry.environment.apiLevel}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (svc != null) {
                                    Text(
                                        "Audio: 48kHz PCM_16BIT stereo → PcmRechunker 10ms → BoundedQueue drop-oldest (no silence detection).",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        // Evidence placeholder
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Evidence Instrumentation (Step 6)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Collector will record: timestamp, device identity, trial identity, metric, provenance VERIFIED_PHYSICAL_COLLECTOR + evidence reference. " +
                                    "Current run: runs/run-002 (not yet created). Do NOT modify runs/run-001.",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "Verified: harness builds & captures on physical device. Unverified: WebRTC, signaling, listener, 2-device E2E, latency, reconnect, loss, battery, memory, CPU, thermal.",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) { try { unbindService(serviceConnection) } catch (_: Exception) {} ; isBound = false }
        super.onDestroy()
    }
}

// Minimal composable helpers not needed elsewhere - kept inline for harness simplicity
