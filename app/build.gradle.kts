plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.clockin.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clockin.probe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing: the APK we hand to the judges must install on a normal device.
    // Keystore is generated locally (see tools/ or README) and never committed with real secrets.
    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/clockin-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("CLOCKIN_STORE_PASSWORD") ?: "clockin-local"
                keyAlias = System.getenv("CLOCKIN_KEY_ALIAS") ?: "clockin"
                keyPassword = System.getenv("CLOCKIN_KEY_PASSWORD") ?: "clockin-local"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
        force("androidx.activity:activity:1.9.2")
        force("androidx.activity:activity-compose:1.9.2")
        force("androidx.lifecycle:lifecycle-runtime:2.8.6")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
        force("androidx.lifecycle:lifecycle-common:2.8.6")
    }
}

dependencies {
    // Solana Kotlin SDK (nominal / web3 / rpc) — the "interact meaningfully with Solana" path
    implementation("com.solanamobile:web3-solana:0.3.1")
    

    // Mobile Wallet Adapter client — the mandatory SMS/MWA integration
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3")

    // Verified necessary: the AOSP API-35 image ships no software Ed25519 JCA provider
    // (AndroidOpenSSL and BC both NoSuchAlgorithmException; the default falls through to
    // AndroidKeyStore and throws "Not initialized"). Any local signature check needs these.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Coroutines + JSON: already present transitively via web3-solana, pinned explicitly so the
    // data/ and ui/ layers can rely on them without adding a risky new coordinate.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
