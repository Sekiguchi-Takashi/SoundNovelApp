plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sekiguchi.toaru"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sekiguchi.toaru"   // 1巻(com.sekiguchi.soundnovel)と別IDなので共存できる
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        noCompress += listOf("webp")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
