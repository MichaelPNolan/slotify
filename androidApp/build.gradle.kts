plugins {
    alias(libs.plugins.android.application)
}

android {
    // Thin APK shell — shared code lives in :composeApp (KMP library).
    namespace = "com.slotify.app.shell"
    // 37 required by androidx.compose 1.12.x + okhttp 5.5 AAR metadata.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.slotify.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // yt-dlp-android + ffmpeg-kit ship arm64-v8a and x86_64 only (no 32-bit).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Shared debug keystore (committed on purpose — repo is private) so every
    // workstation signs with the same key and APKs install over each other on a
    // device without uninstalling (which wipes app-private data). Valid 2026–2056.
    signingConfigs {
        create("sharedDebug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
    }

    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Chaquopy / ffmpeg-kit need .so extracted from the APK.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
}
