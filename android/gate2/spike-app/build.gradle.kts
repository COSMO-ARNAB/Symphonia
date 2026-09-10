plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symphonia.gate2.spikeapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.symphonia.gate2.spikeapp"
        minSdk = 29 // AudioPlaybackCapture requires API 29+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-spike-throwaway"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Spike library (probe + mediasoup client pin)
    implementation(project(":gate2:spike"))

    // Plain Android views (NO Compose) - throwaway UI, minimum surface
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // WebSocket client for the signaling protocol (mediasoup AAR does NOT
    // bundle one - verified: its jars contain only org.webrtc + org.mediasoup)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("junit:junit:4.13.2")
}
