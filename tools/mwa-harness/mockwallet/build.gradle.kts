plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clockin.mockwallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clockin.mockwallet"
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

dependencies {
    // The OFFICIAL wallet-side library: this is what Phantom/Solflare build on.
    // Using it (rather than hand-rolling JSON-RPC + AES-GCM) means the mock wallet speaks the
    // real MWA wire protocol, so a successful round-trip is genuine protocol-level evidence.
    implementation("com.solanamobile:mobile-wallet-adapter-walletlib:2.0.3")
    // Ed25519 primitives for the harness: the AOSP API-35 image ships no software Ed25519 JCA
    // provider, so both apps use BouncyCastle's low-level crypto directly. Harness-only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
