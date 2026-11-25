plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val relayProbeBundle = providers.gradleProperty("gate2RelayProbeBundle").orNull

android {
    if (relayProbeBundle != null) {
        val bundle = file(relayProbeBundle)
        require(bundle.resolve("assets/probe.zip").isFile) { "Missing relay probe assets" }
        for (library in listOf("libnode.so", "libc++_shared.so", "librelay_probe.so", "libmediasoup_worker.so")) {
            require(bundle.resolve("jniLibs/arm64-v8a/$library").isFile) { "Missing relay probe library: $library" }
        }
        sourceSets.getByName("debug") {
            assets.srcDir(bundle.resolve("assets"))
            jniLibs.srcDir(bundle.resolve("jniLibs"))
        }
        packaging.jniLibs.useLegacyPackaging = true
    }
    namespace = "com.symphonia.gate2.prototype.harness"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.symphonia.gate2.prototype.harness"
        minSdk = 29 // Android 10+ required for AudioPlaybackCapture
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-gate2-harness"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Gate2 internal modules
    implementation(project(":gate2:contracts"))
    implementation(project(":gate2:pcm"))
    implementation(project(":gate2:diagnostics"))
    implementation(project(":gate2:signaling"))
    implementation(project(":gate2:transport"))
    implementation(project(":gate2:benchmark"))

    // AndroidX & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // QR Code Generation (Lightweight pure-Java)
    implementation("com.google.zxing:core:3.5.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
