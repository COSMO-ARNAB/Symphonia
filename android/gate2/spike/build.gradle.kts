plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symphonia.gate2.spike"
    compileSdk = 34

    defaultConfig {
        minSdk = 29 // AudioPlaybackCapture requires API 29+
        consumerProguardFiles("consumer-rules.pro")
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
    // Gate 2 spike: pin the MediaSFU mediasoup client (WebRTC M137) per
    // docs/gate2/client-adapter-decision.md. Fallback A is a one-line swap:
    // io.github.haiyangwu:mediasoup-client:3.4.0
    implementation("com.mediasfu:mediasoup-client:1.0.8")

    // Existing validated PCM pipeline from the repo
    implementation(project(":gate2:contracts"))
    implementation(project(":gate2:pcm"))
    implementation(project(":gate2:transport"))

    testImplementation("junit:junit:4.13.2")
}
