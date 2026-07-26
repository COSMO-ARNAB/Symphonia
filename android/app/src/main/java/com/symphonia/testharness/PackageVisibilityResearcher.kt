package com.symphonia.testharness

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.util.Log

/**
 * Gate 1 Research Task: Package Visibility Investigation
 * Evaluates whether MediaSessionManager + manifest <queries> declarations surface
 * capturable audio sources without needing broad QUERY_ALL_PACKAGES permission.
 */
class PackageVisibilityResearcher(private val context: Context) {

    companion object {
        private const val TAG = "PackageResearcher"
    }

    data class DiscoveredMediaSource(
        val packageName: String,
        val appLabel: String,
        val uid: Int,
        val isMediaSessionActive: Boolean,
        val discoveryMethod: String
    )

    fun evaluateMediaSourceDiscovery(): List<DiscoveredMediaSource> {
        val discoveredSources = mutableListOf<DiscoveredMediaSource>()
        val pm = context.packageManager

        // 1. Method A: Active Media Sessions (MediaSessionManager)
        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val activeSessions = mediaSessionManager?.getActiveSessions(null)
            activeSessions?.forEach { controller ->
                val pkg = controller.packageName
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    discoveredSources.add(
                        DiscoveredMediaSource(
                            packageName = pkg,
                            appLabel = label,
                            uid = appInfo.uid,
                            isMediaSessionActive = true,
                            discoveryMethod = "MediaSessionManager"
                        )
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Active media session package not resolved in PM: $pkg")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "NotificationListenerService permission required for MediaSessionManager: ${e.message}")
        }

        // 2. Method B: Manifest Queries Target Package Resolution
        val targetPackages = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.google.android.youtube",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.android.chrome",
            "org.mozilla.firefox",
            "au.com.shiftyjelly.pocketcasts",
            "tv.twitch.android.app"
        )

        for (pkg in targetPackages) {
            if (discoveredSources.none { it.packageName == pkg }) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    discoveredSources.add(
                        DiscoveredMediaSource(
                            packageName = pkg,
                            appLabel = label,
                            uid = appInfo.uid,
                            isMediaSessionActive = false,
                            discoveryMethod = "ManifestQueries"
                        )
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    // Package not installed on test device
                }
            }
        }

        return discoveredSources
    }
}
