plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symphonia.gate2.transport"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":gate2:contracts"))
    implementation(project(":gate2:pcm"))
    implementation(project(":gate2:signaling"))
    api("com.mediasfu:mediasoup-client:1.0.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
