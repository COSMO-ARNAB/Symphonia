package com.symphonia.testharness

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private var candidateService: CandidateCaptureService? = null
    private var isBound by mutableStateOf(false)
    private var selectedSource by mutableStateOf<PackageVisibilityResearcher.DiscoveredMediaSource?>(null)
    private var isCaptureActive by mutableStateOf(false)
    private var isBenchmarking by mutableStateOf(false)
    private var benchmarkProgress by mutableStateOf<String?>(null)
    private var benchmarkSummaryText by mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CandidateCaptureService.LocalBinder
            candidateService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            candidateService = null
            isBound = false
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && selectedSource != null) {
            val intent = Intent(this, CandidateCaptureService::class.java).apply {
                putExtra(CandidateCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(CandidateCaptureService.EXTRA_TARGET_UID, selectedSource!!.uid)
                putExtra(CandidateCaptureService.EXTRA_TARGET_APP_LABEL, selectedSource!!.appLabel)
            }
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isCaptureActive = true
            benchmarkSummaryText = null
            benchmarkProgress = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val researcher = PackageVisibilityResearcher(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var sources by remember { mutableStateOf(researcher.evaluateMediaSourceDiscovery()) }
                    var hasNotificationAccess by remember { mutableStateOf(researcher.isNotificationListenerEnabled()) }

                    var telemetrySnapshot by remember { mutableStateOf<Gate1TelemetryCollector.TelemetryRecord?>(null) }
                    var currentStateSnapshot by remember { mutableStateOf(CandidateCaptureService.CaptureState.IDLE) }

                    // LaunchedEffect poll loop updating telemetry card every 500ms
                    LaunchedEffect(isCaptureActive, isBound) {
                        while (isCaptureActive && isBound) {
                            candidateService?.let { service ->
                                telemetrySnapshot = service.telemetryCollector.getLatestRecord()
                                currentStateSnapshot = service.currentState
                            }
                            delay(500)
                        }
                        if (!isCaptureActive) {
                            currentStateSnapshot = CandidateCaptureService.CaptureState.IDLE
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Symphonia Gate 1 Test Harness",
                            fontSize = 20.sp,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Physical Device Candidate Capture Profiler (Phase 0)",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Notification Listener Permission Banner
                        if (!hasNotificationAccess) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Notification Access Required for Media Discovery",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "MediaSessionManager requires NotificationListener access to detect which app is playing audio without QUERY_ALL_PACKAGES.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Button(
                                        onClick = {
                                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        },
                                        modifier = Modifier.padding(top = 6.dp)
                                    ) {
                                        Text("Grant Notification Access")
                                    }
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Package Visibility Research",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Discovered Sources (${sources.size})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = {
                                        hasNotificationAccess = researcher.isNotificationListenerEnabled()
                                        sources = researcher.evaluateMediaSourceDiscovery()
                                    },
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text("Refresh Discovery")
                                }
                            }
                        }

                        Text(
                            text = "Select App Category Source:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(sources) { source ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selectedSource?.packageName == source.packageName)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = source.appLabel, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                text = "${source.packageName} (Method: ${source.discoveryMethod}, Active: ${source.isMediaSessionActive})",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        RadioButton(
                                            selected = (selectedSource?.packageName == source.packageName),
                                            onClick = { selectedSource = source }
                                        )
                                    }
                                }
                            }
                        }

                        // Live Telemetry Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Gate 1 Live Telemetry & Environment", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (isCaptureActive && candidateService != null) {
                                        val snap = telemetrySnapshot ?: candidateService!!.telemetryCollector.getLatestRecord()
                                        val meta = candidateService!!.telemetryCollector.environmentMetadata
                                        "State: ${currentStateSnapshot.name} | Target: ${candidateService!!.targetAppLabel}\n" +
                                                "Device: ${meta.manufacturer} ${meta.model} (Android ${meta.androidVersion}, API ${meta.apiLevel})\n" +
                                                "Startup Latency: ${snap.startupLatencyMs} ms | CPU: ${String.format("%.1f", snap.cpuUsagePct)}%\n" +
                                                "PCM Bytes: ${snap.totalBytesCaptured} | Buffers: ${snap.bufferCount}\n" +
                                                "Route: ${snap.audioRoute} | Failure: ${snap.failureCode}\n" +
                                                "Thermal: ${snap.thermalStatus} | Memory: ${snap.memoryState}"
                                    } else {
                                        "Candidate Backend Idle. Run test on Physical Android Device."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (benchmarkProgress != null) {
                                    Text(
                                        text = "Benchmark: $benchmarkProgress",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                if (benchmarkSummaryText != null) {
                                    Text(
                                        text = benchmarkSummaryText!!,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        // Action Buttons: Capture, Benchmark, Export
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = selectedSource != null && !isCaptureActive,
                                onClick = {
                                    val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(projManager.createScreenCaptureIntent())
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Start Capture")
                            }

                            Button(
                                enabled = isCaptureActive,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                onClick = {
                                    if (isBound) {
                                        unbindService(serviceConnection)
                                        isBound = false
                                    }
                                    candidateService?.stopCapture()
                                    isCaptureActive = false
                                    isBenchmarking = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop Capture")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = isCaptureActive && !isBenchmarking && selectedSource != null,
                                onClick = {
                                    isBenchmarking = true
                                    benchmarkProgress = "Starting 3-Trial Benchmark..."
                                    candidateService?.runThreeTrialBenchmark(
                                        targetUid = selectedSource!!.uid,
                                        targetAppLabel = selectedSource!!.appLabel,
                                        onProgress = { trial, status ->
                                            runOnUiThread {
                                                benchmarkProgress = "Trial $trial / 3: $status"
                                            }
                                        },
                                        onComplete = { summary ->
                                            runOnUiThread {
                                                isBenchmarking = false
                                                benchmarkProgress = "Completed"
                                                benchmarkSummaryText = "3-Trial Summary (${summary.targetAppLabel}): Mean=${String.format("%.1f", summary.meanLatencyMs)}ms, StdDev=${String.format("%.2f", summary.stdDevLatencyMs)}ms, Status=${summary.passStatus}"
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Run 3 Trials")
                            }

                            Button(
                                enabled = candidateService != null,
                                onClick = {
                                    val exportDir = File(getExternalFilesDir(null), "telemetry/run-001")
                                    val exported = candidateService?.telemetryCollector?.exportAllToDirectory(exportDir) ?: emptyList()
                                    Toast.makeText(this@MainActivity, "Exported ${exported.size} files to ${exportDir.name}", Toast.LENGTH_LONG).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Export Telemetry")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
