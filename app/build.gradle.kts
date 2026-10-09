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
        versionCode = 1
        versionName = "0.1-prototype"
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

// No extra libraries: everything uses built-in Android APIs, so the APK stays tiny and fast.
dependencies {}
