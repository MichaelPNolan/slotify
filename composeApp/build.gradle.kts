import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // AGP 9 supported KMP Android target (not com.android.application/library).
    android {
        namespace = "com.slotify.app"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // commonTest sources exist — opt in so they actually run on Android host.
        withHostTest {}
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain.dependencies {
            // Direct coordinates — compose.runtime / foundation / … accessors are deprecated.
            // Versions match ComposePlugin.Dependencies for CMP 1.12.2 (material3 is 1.9.0).
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.2")
            implementation("org.jetbrains.compose.components:components-resources:1.12.2")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopMain") {
            dependencies {
                // OS-specific desktop runtime (was compose.desktop.currentOs).
                implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.12.2")
                implementation(libs.ktor.client.cio)
                implementation(libs.jaudiotagger)
                implementation(libs.vlcj)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                // Read Floorp/Firefox cookies.sqlite for the sp_dc browser-session path.
                implementation(libs.sqlite.jdbc)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.slotify.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Slotify"
            packageVersion = "0.1.0"
        }
    }
}

// Headless: import a public Spotify playlist URL and match the local library.
//   ./gradlew :composeApp:populatePlaylist --args="https://open.spotify.com/playlist/…"
tasks.register<JavaExec>("populatePlaylist") {
    group = "slotify"
    description = "Import a Spotify playlist URL and match against library roots"
    val compilation = kotlin.targets.getByName("desktop")
        .compilations.getByName("main")
    dependsOn(compilation.compileTaskProvider)
    classpath = compilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    mainClass.set("com.slotify.app.tools.PopulateCliKt")
    standardInput = System.`in`
}
