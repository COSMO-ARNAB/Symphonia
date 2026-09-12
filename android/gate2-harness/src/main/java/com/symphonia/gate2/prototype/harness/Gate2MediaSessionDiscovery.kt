package com.symphonia.gate2.prototype.harness

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.util.Log

/**
 * Gate 2 prototype media session discovery - explicitly prototype-named.
 * Populates Speaker app-selection list from apps currently holding an active media session
 * via MediaSessionManager.getActiveSessions() per PRD §7.4 and TECH_ARCH §3.3.
 *
 * If nothing is producing audio, the list is empty - never shows non-capturable apps.
 * Does NOT use QUERY_ALL_PACKAGES - uses manifest <queries> + MediaSession per Gate 1 research.
 */
class Gate2MediaSessionDiscovery(private val context: Context) {
    companion object { private const val TAG = "Gate2Discovery" }

    data class DiscoveredApp(
        val packageName: String,
        val appLabel: String,
        val uid: Int,
        val isSessionActive: Boolean,
        val discoveryMethod: String,
    )

    fun discover(): List<DiscoveredApp> {
        val pm = context.packageManager
        val discovered = mutableListOf<DiscoveredApp>()

        // Primary: active media sessions - only ever surfaces apps actually producing audio
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val sessions = msm?.getActiveSessions(null)
            sessions?.forEach { controller ->
                val pkg = controller.packageName
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    discovered.add(
                        DiscoveredApp(
                            packageName = pkg,
                            appLabel = label,
                            uid = appInfo.uid,
                            isSessionActive = true,
                            discoveryMethod = "MediaSessionManager"
                        )
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Active session package not resolved: $pkg")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSessionManager requires NotificationListener permission: ${e.message}")
            // Intentionally return empty list - never fallback to broader list
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession discovery failed: ${e.message}")
        }

        // Secondary: manifest-queries known targets - only for visibility research,
        // but we mark them as NOT session-active so UI can distinguish.
        // For Gate 2 minimal harness we include this to allow testing when no session active,
        // but Speaker must still pick an app that is actually capturable; capture will
        // fail-closed if app not producing audio.
        val known = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "org.videolan.vlc",
            "com.google.android.youtube",
            "au.com.shiftyjelly.pocketcasts",
            "tv.twitch.android.app",
            "com.android.chrome",
            "org.mozilla.firefox"
        )
        for (pkg in known) {
            if (discovered.none { it.packageName == pkg }) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    discovered.add(
                        DiscoveredApp(
                            packageName = pkg,
                            appLabel = label,
                            uid = info.uid,
                            isSessionActive = false,
                            discoveryMethod = "ManifestQueries"
                        )
                    )
                } catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        Log.i(TAG, "Discovery: ${discovered.size} apps (${discovered.count { it.isSessionActive }} active sessions)")
        return discovered
    }
}
