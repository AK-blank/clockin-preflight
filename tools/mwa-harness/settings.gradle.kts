// Standalone MWA test harness — deliberately NOT part of the app's settings.gradle.kts.
// It exists to prove the Mobile Wallet Adapter 2.0.3 protocol round-trip on the emulator.
//
// Build from the repository root (the toolchain path is relative to it):
//   ./toolchain/gradle/bin/gradle -p tools/mwa-harness \
//       :mockwallet:assembleDebug :mwadriver:assembleDebug
// or simply:  ./scripts/mwa_proof.sh
//
// Mirrors are the same fast ones the app uses (dl.google.com / Maven Central are throttled here).
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo.huaweicloud.com/repository/maven")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo.huaweicloud.com/repository/maven")
    }
}

rootProject.name = "mwa-harness"
include(":mockwallet")
include(":mwadriver")
