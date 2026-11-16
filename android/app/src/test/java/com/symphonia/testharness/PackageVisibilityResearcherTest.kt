package com.symphonia.testharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageVisibilityResearcherTest {
    @Test
    fun testDiscoveredMediaSourceProperties() {
        val source = PackageVisibilityResearcher.DiscoveredMediaSource(
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            uid = 10123,
            isMediaSessionActive = true,
            discoveryMethod = "MediaSessionManager"
        )
        assertEquals("com.spotify.music", source.packageName)
        assertEquals("Spotify", source.appLabel)
        assertEquals(10123, source.uid)
        assertTrue(source.isMediaSessionActive)
        assertEquals("MediaSessionManager", source.discoveryMethod)
    }
}
