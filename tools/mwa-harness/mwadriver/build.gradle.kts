plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clockin.mwadriver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clockin.mwadriver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// Same pinning trick as app/build.gradle.kts: newer AndroidX requires compileSdk 36, and
// platform-36 is not obtainable on this network. Force versions that are happy with compileSdk 35.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
        force("androidx.activity:activity:1.9.2")
        force("androidx.activity:activity-ktx:1.9.2")
        force("androidx.lifecycle:lifecycle-runtime:2.8.6")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
        force("androidx.lifecycle:lifecycle-common:2.8.6")
    }
}

dependencies {
    // The exact artifact the CLOCK IN app is pinned to.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Ed25519 primitives for the harness: the AOSP API-35 image ships no software Ed25519 JCA
    // provider, so both apps use BouncyCastle's low-level crypto directly. Harness-only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
