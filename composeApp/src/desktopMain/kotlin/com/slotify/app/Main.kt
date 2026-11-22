package com.slotify.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.slotify.app.data.AppFiles
import com.slotify.app.spotify.JsonFileTokenStorage
import com.slotify.app.spotify.SPOTIFY_REDIRECT_URI
import com.slotify.app.spotify.desktopAwaitPkceCode
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

fun main() = application {
    val state = remember { createAppState() }
    Window(
        onCloseRequest = {
            state.release()
            exitApplication()
        },
        title = "Slotify",
        state = rememberWindowState(width = 1100.dp, height = 740.dp),
    ) {
        App(
            state = state,
            pkceAwait = { url -> desktopAwaitPkceCode(url, SPOTIFY_REDIRECT_URI) },
        )
    }
}

private fun createAppState(): AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val spotify = createSpotifyPort(JsonFileTokenStorage(AppFiles.dataDirPath()), scope)
    val state = AppState(spotify = spotify, scope = scope)

    if (System.getenv("SLOTIFY_DEMO") == "1") {
        val demoDir = File(System.getProperty("user.home"), "slotify-demo-music")
        if (demoDir.isDirectory) {
            state.updateSettings { it.copy(libraryRoots = listOf(demoDir.absolutePath)) }
            state.scanLibrary()
            state.importFromJsonFile(File(demoDir, "classic-jazz-demo.json").absolutePath)
        }
    }
    return state
}
