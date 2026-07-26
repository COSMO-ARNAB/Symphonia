package com.symphonia.testharness

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
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

class MainActivity : ComponentActivity() {

    private var candidateService: CandidateCaptureService? = null
    private var isBound by mutableStateOf(false)
    private var selectedSource by mutableStateOf<PackageVisibilityResearcher.DiscoveredMediaSource?>(null)
    private var isCaptureActive by mutableStateOf(false)

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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        val researcher = PackageVisibilityResearcher(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var sources by remember { mutableStateOf(researcher.evaluateMediaSourceDiscovery()) }

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
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
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
                                    onClick = { sources = researcher.evaluateMediaSourceDiscovery() },
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
                                                text = "${source.packageName} (Method: ${source.discoveryMethod})",
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

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Gate 1 Telemetry & Environment Metadata", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (isCaptureActive && candidateService != null) {
                                        val snap = candidateService!!.telemetryCollector.getSnapshot()
                                        val meta = snap.metadata
                                        "Device: ${meta.manufacturer} ${meta.model} (Android ${meta.androidVersion}, API ${meta.apiLevel})\n" +
                                                "Patch: ${meta.securityPatch} | RAM: ${meta.totalRamGb} GB\n" +
                                                "Target App: ${candidateService!!.targetAppLabel}\n" +
                                                "Startup Latency: ${snap.startupLatencyMs} ms\n" +
                                                "PCM Bytes: ${snap.totalBytesCaptured} | Buffers: ${snap.bufferCount}\n" +
                                                "Route: ${snap.activeAudioOutputRoute}\n" +
                                                "Thermal: ${snap.thermalStatus} | Memory: ${snap.memoryPressureState}"
                                    } else {
                                        "Candidate Backend Idle. Run test on Physical Android Device."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(
                                enabled = selectedSource != null && !isCaptureActive,
                                onClick = {
                                    val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(projManager.createScreenCaptureIntent())
                                }
                            ) {
                                Text("Start Candidate Capture")
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
                                }
                            ) {
                                Text("Stop Capture")
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
