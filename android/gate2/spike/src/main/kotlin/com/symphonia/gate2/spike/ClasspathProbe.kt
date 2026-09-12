package com.symphonia.gate2.spike

import org.mediasoup.droid.Device
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * THROWAWAY compile-classpath probe: proves the AAR's bundled libwebrtc.jar
 * (M137) and the mediasoup wrapper are reachable from spike Kotlin code.
 * Never executed on a device; compiled only.
 */
object ClasspathProbe {
    fun touch(): String {
        val marker: Class<PeerConnectionFactory> = PeerConnectionFactory::class.java
        val adm: Class<JavaAudioDeviceModule> = JavaAudioDeviceModule::class.java
        val device: Class<Device> = Device::class.java
        return "${marker.simpleName}/${adm.simpleName}/${device.simpleName}"
    }
}
