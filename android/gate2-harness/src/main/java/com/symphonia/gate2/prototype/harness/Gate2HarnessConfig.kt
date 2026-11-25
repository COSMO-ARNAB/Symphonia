package com.symphonia.gate2.prototype.harness

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Persists Speaker demo configuration across app restarts and device reboots,
 * and applies optional ADB launch extras during setup.
 */
internal class Gate2HarnessConfig(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    data class Snapshot(
        val signalingUrl: String,
        val demoMode: Boolean,
    )

    companion object {
        const val PREFS_NAME = "gate2_harness_config"
        const val KEY_SIGNALING_URL = "signaling_url"
        const val KEY_DEMO_MODE = "demo_mode"
        const val DEFAULT_SIGNALING_URL = "ws://10.77.0.1:8080/gate2"

        const val EXTRA_SIGNALING_URL = "signaling_url"
        const val EXTRA_DEMO_MODE = "demo_mode"
    }

    fun load(): Snapshot = Snapshot(
        signalingUrl = prefs.getString(KEY_SIGNALING_URL, DEFAULT_SIGNALING_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SIGNALING_URL,
        demoMode = prefs.getBoolean(KEY_DEMO_MODE, false),
    )

    fun saveSignalingUrl(url: String): Snapshot {
        val current = load()
        val trimmed = url.trim()
        prefs.edit().putString(KEY_SIGNALING_URL, trimmed).apply()
        return current.copy(signalingUrl = trimmed.ifBlank { DEFAULT_SIGNALING_URL })
    }

    fun saveDemoMode(enabled: Boolean): Snapshot {
        val current = load()
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
        return current.copy(demoMode = enabled)
    }

    fun applyValues(signalingUrl: String? = null, demoModeRaw: Any? = null): Snapshot {
        val current = load()
        val editor = prefs.edit()
        var changed = false

        val trimmedUrl = signalingUrl?.trim()
        if (!trimmedUrl.isNullOrBlank()) {
            editor.putString(KEY_SIGNALING_URL, trimmedUrl)
            changed = true
        }

        if (demoModeRaw != null) {
            val parsed = when (demoModeRaw) {
                is Boolean -> demoModeRaw
                is String -> demoModeRaw.equals("true", ignoreCase = true) || demoModeRaw == "1"
                is Number -> demoModeRaw.toInt() != 0
                else -> current.demoMode
            }
            editor.putBoolean(KEY_DEMO_MODE, parsed)
            changed = true
        }

        if (changed) editor.apply()
        return load()
    }

    /**
     * Applies ADB launch extras if provided, e.g.:
     * `am start ... --es signaling_url ws://10.77.0.1:8080/gate2 --ez demo_mode true`
     */
    fun applyIntentExtras(intent: Intent?): Snapshot {
        if (intent == null) return load()
        val extraUrl = intent.getStringExtra(EXTRA_SIGNALING_URL)
        val demoRaw: Any? = if (!intent.hasExtra(EXTRA_DEMO_MODE)) null else {
            intent.getStringExtra(EXTRA_DEMO_MODE)
                ?: intent.getIntExtra(EXTRA_DEMO_MODE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?: intent.getBooleanExtra(EXTRA_DEMO_MODE, load().demoMode)
        }
        return applyValues(signalingUrl = extraUrl, demoModeRaw = demoRaw)
    }
}
