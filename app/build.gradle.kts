plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.laughterlabs.nova"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.laughterlabs.nova"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
        // Real phones only (drops emulator libraries, halves the APK size)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // One fixed key, so every new build installs over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("nova-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    // Offline "Hey Boba" wake word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
