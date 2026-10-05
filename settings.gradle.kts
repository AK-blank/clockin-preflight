pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://plugins.gradle.org/m2")
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo.huaweicloud.com/repository/maven")
        maven("https://dl.google.com/dl/android/maven2")
    }
}

rootProject.name = "clockin-probe"
include(":app")
