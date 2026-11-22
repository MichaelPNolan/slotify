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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
}
