package com.symphonia.gate2.prototype.harness

import android.Manifest
import android.app.Activity
import java.util.Locale
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.symphonia.gate2.benchmark.BenchmarkTrial
import com.symphonia.gate2.benchmark.Gate2BenchmarkReport
import kotlinx.coroutines.delay
import java.io.File

/**
 * Gate 2 Prototype Harness Activity.
 *
 * Implements:
 *  - Speaker mode: App-scoped capture & live mediasoup publishing.
 *  - Listener mode: Room joining, Zod-compliant CONSUME negotiation, & playout.
 *  - Benchmark mode: 3-trial execution and 10 schema-v3 files export.
 */
class Gate2PrototypeHarnessActivity : ComponentActivity() {

    enum class HarnessRole { SPEAKER, LISTENER, BENCHMARK }

    private var captureService: Gate2PrototypeCaptureService? by mutableStateOf(null)
    private var isSpeakerBound by mutableStateOf(false)
    private var isSpeakerActive by mutableStateOf(false)

    private var listenerService: Gate2PrototypeListenerService? by mutableStateOf(null)
    private var isListenerBound by mutableStateOf(false)
    private var isListenerActive by mutableStateOf(false)

    private var selectedApp by mutableStateOf<Gate2MediaSessionDiscovery.DiscoveredApp?>(null)
    private var harnessRole by mutableStateOf(HarnessRole.SPEAKER)

    private var serverUrlInput by mutableStateOf("ws://192.168.0.110:8080/gate2")
    private var roomIdInput by mutableStateOf("")

    private val benchmarkCollector by lazy { Gate2PhysicalBenchmarkCollector(applicationContext) }
    private val recordedTrials = mutableStateListOf<BenchmarkTrial>()
    private var benchmarkReport by mutableStateOf<Gate2BenchmarkReport?>(null)
    private var exportMessage by mutableStateOf<String?>(null)

    private val speakerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as Gate2PrototypeCaptureService.LocalBinder
            captureService = binder.getService()
            isSpeakerBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isSpeakerBound = false
            isSpeakerActive = false
        }
    }

    private val listenerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as Gate2PrototypeListenerService.LocalBinder
            listenerService = binder.getService()
            isListenerBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            listenerService = null
            isListenerBound = false
            isListenerActive = false
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
                putExtra(Gate2PrototypeCaptureService.EXTRA_SERVER_URL, serverUrlInput)
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            startForegroundService(svcIntent)
            bindService(svcIntent, speakerConnection, Context.BIND_AUTO_CREATE)
            isSpeakerActive = true
        } else {
            Toast.makeText(this, "Capture cancelled or no app selected (fail-closed)", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Notification permission denied", Toast.LENGTH_LONG).show()
    }

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
                    var pollTick by remember { mutableStateOf(0) }
                    var runIdInput by remember { mutableStateOf("run-002") }

                    LaunchedEffect(isSpeakerActive, isListenerActive, harnessRole) {
                        while (isSpeakerActive || isListenerActive || harnessRole == HarnessRole.BENCHMARK) {
                            delay(500)
                            pollTick++
                            if (benchmarkCollector.getActiveTrialSession() != null) {
                                benchmarkCollector.sampleActiveTrial(captureService?.speakerTransport)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Prototype header
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("SYMPHONIA GATE 2 HARNESS", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Physical two-device media transport validation • Fail-closed capture • Opus 48kHz SRTP",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Role Selector
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Mode Selection", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = harnessRole == HarnessRole.SPEAKER,
                                        onClick = { harnessRole = HarnessRole.SPEAKER }
                                    )
                                    Text("Speaker", modifier = Modifier.padding(end = 8.dp))

                                    RadioButton(
                                        selected = harnessRole == HarnessRole.LISTENER,
                                        onClick = { harnessRole = HarnessRole.LISTENER }
                                    )
                                    Text("Listener", modifier = Modifier.padding(end = 8.dp))

                                    RadioButton(
                                        selected = harnessRole == HarnessRole.BENCHMARK,
                                        onClick = { harnessRole = HarnessRole.BENCHMARK }
                                    )
                                    Text("Benchmark")
                                }
                            }
                        }

                        // Connection Settings
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Signaling Configuration", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = serverUrlInput,
                                    onValueChange = { serverUrlInput = it },
                                    label = { Text("Signaling Server (ws://host:8080/gate2)") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    singleLine = true
                                )
                                if (harnessRole == HarnessRole.LISTENER) {
                                    OutlinedTextField(
                                        value = roomIdInput,
                                        onValueChange = { input ->
                                            roomIdInput = input.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }.take(6)
                                        },
                                        label = { Text("Room ID to Join (6 alphanumeric)") },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        singleLine = true
                                    )
                                }
                            }
                        }

                        // Role Views
                        when (harnessRole) {
                            HarnessRole.SPEAKER -> {
                                SpeakerView(
                                    discovered = discovered,
                                    selectedApp = selectedApp,
                                    isCapturing = isSpeakerActive,
                                    captureService = captureService,
                                    pollTick = pollTick,
                                    onRefresh = { discovered = discovery.discover() },
                                    onSelectApp = { selectedApp = it },
                                    onStartCapture = {
                                        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        projectionLauncher.launch(pm.createScreenCaptureIntent())
                                    },
                                    onStopCapture = {
                                        if (isSpeakerBound) {
                                            unbindService(speakerConnection)
                                            isSpeakerBound = false
                                        }
                                        captureService?.stopGate2Capture(com.symphonia.gate2.contracts.ShareStoppedReason.HOST_ENDED)
                                        captureService = null
                                        isSpeakerActive = false
                                    }
                                )
                            }
                            HarnessRole.LISTENER -> {
                                ListenerView(
                                    isListening = isListenerActive,
                                    listenerService = listenerService,
                                    roomIdInput = roomIdInput,
                                    pollTick = pollTick,
                                    onStartListening = {
                                        if (roomIdInput.length == 6) {
                                            val lIntent = Intent(this@Gate2PrototypeHarnessActivity, Gate2PrototypeListenerService::class.java).apply {
                                                putExtra(Gate2PrototypeListenerService.EXTRA_SERVER_URL, serverUrlInput)
                                                putExtra(Gate2PrototypeListenerService.EXTRA_ROOM_ID, roomIdInput)
                                            }
                                            startForegroundService(lIntent)
                                            bindService(lIntent, listenerConnection, Context.BIND_AUTO_CREATE)
                                            isListenerActive = true
                                        } else {
                                            Toast.makeText(this@Gate2PrototypeHarnessActivity, "Enter a valid 6-character Room ID first", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onStopListening = {
                                        if (isListenerBound) {
                                            unbindService(listenerConnection)
                                            isListenerBound = false
                                        }
                                        listenerService?.stopListening()
                                        listenerService = null
                                        isListenerActive = false
                                    }
                                )
                            }
                            HarnessRole.BENCHMARK -> {
                                BenchmarkView(
                                    runIdInput = runIdInput,
                                    onRunIdChange = { runIdInput = it },
                                    recordedTrials = recordedTrials,
                                    benchmarkReport = benchmarkReport,
                                    exportMessage = exportMessage,
                                    captureService = captureService,
                                    pollTick = pollTick,
                                    onRecordTrial = { lossVerified, durationMs ->
                                        val trialNum = recordedTrials.size + 1
                                        if (trialNum <= 3) {
                                            val diag = benchmarkCollector.completeActiveTrialSession(
                                                captureController = captureService?.captureController,
                                                speakerTransport = captureService?.speakerTransport,
                                                lossTestingVerified = lossVerified,
                                                evidenceComplete = true,
                                                measurementProvenance = com.symphonia.gate2.benchmark.MeasurementProvenance.VERIFIED_PHYSICAL_COLLECTOR,
                                                evidenceRef = "docs/gate2/runbook_two_devices.md#trial-$trialNum"
                                            )
                                            recordedTrials.add(BenchmarkTrial.complete(trialNum, diag))
                                        }
                                    },
                                    onStartTrialSession = { trialNum ->
                                        benchmarkCollector.startTrialSession(trialNum)
                                    },
                                    onClearTrials = {
                                        recordedTrials.clear()
                                        benchmarkReport = null
                                        exportMessage = null
                                    },
                                    onExport = {
                                        if (recordedTrials.size == 3) {
                                            val (rep, dir) = benchmarkCollector.exportBenchmarkSuite(
                                                runId = runIdInput,
                                                trials = recordedTrials.toList()
                                            )
                                            benchmarkReport = rep
                                            val gateSummary = rep.gates.joinToString("\n") { g ->
                                                " • ${g.name}: ${g.status}${if (g.authoritative) " (Authoritative)" else ""}"
                                            }
                                            exportMessage = "Exported 10 schema-v3 files to:\n${dir.absolutePath}\nOverall Status: ${rep.status}\n$gateSummary"
                                        } else {
                                            Toast.makeText(this@Gate2PrototypeHarnessActivity, "Need exactly 3 trials before export", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isSpeakerBound) {
            try { unbindService(speakerConnection) } catch (_: Exception) {}
            isSpeakerBound = false
        }
        if (isListenerBound) {
            try { unbindService(listenerConnection) } catch (_: Exception) {}
            isListenerBound = false
        }
        super.onDestroy()
    }
}

@Composable
private fun SpeakerView(
    discovered: List<Gate2MediaSessionDiscovery.DiscoveredApp>,
    selectedApp: Gate2MediaSessionDiscovery.DiscoveredApp?,
    isCapturing: Boolean,
    captureService: Gate2PrototypeCaptureService?,
    pollTick: Int,
    onRefresh: () -> Unit,
    onSelectApp: (Gate2MediaSessionDiscovery.DiscoveredApp) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("App Selection (Active Media Sessions)", style = MaterialTheme.typography.titleSmall)
                Button(onClick = onRefresh) { Text("Refresh") }
            }

            if (discovered.isEmpty()) {
                Text("No playing apps detected. Start audio playback in Spotify/VLC and tap Refresh.", modifier = Modifier.padding(vertical = 8.dp))
            } else {
                discovered.forEach { app ->
                    val isSelected = selectedApp?.packageName == app.packageName
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
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
                                Text("${app.packageName} (UID: ${app.uid})", style = MaterialTheme.typography.labelSmall)
                            }
                            RadioButton(selected = isSelected, onClick = { onSelectApp(app) })
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = selectedApp != null && !isCapturing,
                    onClick = onStartCapture,
                    modifier = Modifier.weight(1f)
                ) { Text("Start Sharing") }

                Button(
                    enabled = isCapturing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = onStopCapture,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop Sharing") }
            }
        }
    }

    // Live Speaker Telemetry
    if (captureService != null) {
        // Collect reactive state flows from the service and controller
        val activeRoomId by captureService.currentRoomId.collectAsState()
        val captureState by captureService.captureController.state.collectAsState()

        // Re-read stats on every 500ms timer tick
        @Suppress("UNUSED_VARIABLE")
        val tick = pollTick
        val stats = captureService.speakerTransport?.getStats()
        val ctl = captureService.captureController

        val displayRoomId = activeRoomId
            ?: captureService.speakerTransport?.roomId
            ?: captureService.roomIdForNotification.takeIf { it != "No Room" && it != "Gate2-Room" }
            ?: if (isCapturing) "Creating..." else "Not running"

        val stateLabel = when (val s = captureState) {
            is Gate2CaptureController.CaptureState.Idle -> "Idle"
            is Gate2CaptureController.CaptureState.RequestingCapture -> "Requesting capture…"
            is Gate2CaptureController.CaptureState.Capturing -> "Capturing (${s.appLabel})"
            is Gate2CaptureController.CaptureState.Paused -> "Paused (${s.appLabel} in background)"
            is Gate2CaptureController.CaptureState.Stopped -> "Stopped (${s.reason})"
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Speaker Live Telemetry", style = MaterialTheme.typography.titleSmall)
                Text("Room ID: $displayRoomId", style = MaterialTheme.typography.bodyMedium)
                Text("State: $stateLabel", style = MaterialTheme.typography.bodyMedium)
                Text("Offered Frames: ${ctl.totalOfferedFrames} | Dropped: ${ctl.totalDroppedFrames}")
                if (stats != null) {
                    Text("Packets Sent: ${stats.packetsSent} | Lost: ${stats.packetsLost}")
                    Text("Bitrate: %.1f kbps | Jitter: %.2f ms | RTT: %.1f ms".format(stats.bitrateKbps, stats.jitterMs, stats.rttMs))
                }
            }
        }
    }
}

@Composable
private fun ListenerView(
    isListening: Boolean,
    listenerService: Gate2PrototypeListenerService?,
    roomIdInput: String,
    pollTick: Int,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Listener Controls", style = MaterialTheme.typography.titleSmall)
            Text(
                "Consumes Opus 48kHz audio over WebRTC and plays via Android AudioTrack.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !isListening && roomIdInput.length == 6,
                    onClick = onStartListening,
                    modifier = Modifier.weight(1f)
                ) { Text("Join & Listen") }

                Button(
                    enabled = isListening,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = onStopListening,
                    modifier = Modifier.weight(1f)
                ) { Text("Leave Room") }
            }
        }
    }

    if (listenerService != null) {
        @Suppress("UNUSED_VARIABLE")
        val tick = pollTick
        val stats = listenerService.listenerTransport?.getStats()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Listener Live Telemetry", style = MaterialTheme.typography.titleSmall)
                Text("Remote State: ${listenerService.remoteState}")
                listenerService.remoteAppLabel?.let { Text("Now Playing: $it") }
                if (stats != null) {
                    Text("Packets Received: ${stats.packetsReceived} | Lost: ${stats.packetsLost}")
                    Text("Bitrate: %.1f kbps | Jitter: %.2f ms | RTT: %.1f ms".format(stats.bitrateKbps, stats.jitterMs, stats.rttMs))
                }
            }
        }
    }
}

@Composable
private fun BenchmarkView(
    runIdInput: String,
    onRunIdChange: (String) -> Unit,
    recordedTrials: List<BenchmarkTrial>,
    benchmarkReport: Gate2BenchmarkReport?,
    exportMessage: String?,
    captureService: Gate2PrototypeCaptureService?,
    pollTick: Int,
    onRecordTrial: (Boolean, Long) -> Unit,
    onStartTrialSession: (Int) -> Unit,
    onClearTrials: () -> Unit,
    onExport: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val tick = pollTick
    var trialStartWallNanos by remember { mutableStateOf(0L) }
    var isTrialRunning by remember { mutableStateOf(false) }
    var lossTestingChecked by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Gate 2 Physical Benchmark Suite", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = runIdInput,
                onValueChange = onRunIdChange,
                label = { Text("Run ID (e.g. run-002)") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true
            )

            val elapsedTrialSec = if (trialStartWallNanos > 0L) {
                (android.os.SystemClock.elapsedRealtimeNanos() - trialStartWallNanos) / 1_000_000_000L
            } else 0L

            Text(
                if (isTrialRunning) "Trial in progress: ${elapsedTrialSec}s elapsed (measuring actual window)"
                else "Status: Idle — start trial to measure real time window",
                style = MaterialTheme.typography.bodySmall,
                color = if (isTrialRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp)
            )

            Text("Recorded Trials: ${recordedTrials.size} / 3", modifier = Modifier.padding(top = 8.dp))
            recordedTrials.forEach { t ->
                val d = t.diagnostics
                val durMin = (d?.durationMs ?: 0L) / 60_000L
                Text(
                    "Trial ${t.number} (${durMin}m): Loss=%.1f%% Drop=%.2f%% Prov=${d?.measurementProvenance}".format(
                        (d?.packetLossRate ?: 0.0) * 100.0, (d?.droppedFrameRate ?: 0.0) * 100.0
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isTrialRunning) {
                    Button(
                        enabled = recordedTrials.size < 3 && captureService?.isCapturing == true,
                        onClick = {
                            val nextTrial = recordedTrials.size + 1
                            onStartTrialSession(nextTrial)
                            trialStartWallNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            isTrialRunning = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Start Trial ${recordedTrials.size + 1}") }
                } else {
                    Button(
                        onClick = {
                            val durationMs = (android.os.SystemClock.elapsedRealtimeNanos() - trialStartWallNanos) / 1_000_000L
                            onRecordTrial(lossTestingChecked, durationMs)
                            isTrialRunning = false
                            trialStartWallNanos = 0L
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Complete Trial (${elapsedTrialSec}s)") }
                }
            }

            if (isTrialRunning) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Checkbox(
                        checked = lossTestingChecked,
                        onCheckedChange = { lossTestingChecked = it }
                    )
                    Text(
                        "5% Loss Test Condition Active (loss shaping active & audible)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = recordedTrials.size == 3,
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) { Text("Export Evidence (10 Files)") }

                Button(
                    onClick = onClearTrials,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
            }

            if (exportMessage != null) {
                Text(
                    exportMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
