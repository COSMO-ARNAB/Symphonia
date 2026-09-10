package com.symphonia.gate2.spikeapp

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * THROWAWAY spike UI - one screen, plain views, no Compose.
 *
 * Server URL field (default: laptop LAN IP) + START SHARING button ->
 * system capture-consent dialog -> SpikeCaptureService. Live status line +
 * scrolling log; last line drives the status text.
 */
class SpikeActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var urlField: EditText
    private var pendingServerUrl: String? = null

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, SpikeCaptureService::class.java)
                .putExtra(SpikeCaptureService.EXTRA_RESULT_DATA, result.data)
                .putExtra(SpikeCaptureService.EXTRA_SERVER_URL, pendingServerUrl)
            ContextCompat.startForegroundService(this, intent)
            post("consent granted - service starting")
        } else {
            post("consent DENIED - nothing captured")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = 24
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val title = TextView(this).apply {
            text = "Symphonia Gate2 Spike (throwaway)"
            textSize = 18f
        }
        urlField = EditText(this).apply {
            hint = "ws://10.127.223.50:8080"
            setText("ws://10.127.223.50:8080")
        }
        val start = Button(this).apply { text = "START SHARING (app audio)" }
        start.setOnClickListener {
            val url = urlField.text.toString().trim()
            if (!url.startsWith("ws://")) { Toast.makeText(this, "URL must start ws://", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            pendingServerUrl = url
            post("requesting capture consent…")
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consentLauncher.launch(pm.createScreenCaptureIntent())
        }
        status = TextView(this).apply { text = "idle"; textSize = 16f }
        logView = TextView(this).apply { text = "log:\n"; setTextIsSelectable(true); textSize = 12f }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(urlField)
        root.addView(start)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Surface service log tail into the UI
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                status.text = SpikeCaptureService.lastLog
                postLogOnly(SpikeCaptureService.lastLog)
                Handler(Looper.getMainLooper()).postDelayed(this, 500)
            }
        })
    }

    private fun post(message: String) {
        status.text = message
        postLogOnly(message)
    }

    private fun postLogOnly(message: String) {
        logView.append(message + "\n")
    }
}
