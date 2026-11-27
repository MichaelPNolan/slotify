package com.slotify.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import com.slotify.app.data.AppFiles
import com.slotify.app.spotify.JsonFileTokenStorage
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.SwingUtilities

/** Soft open size only — the WM owns geometry after map. */
private val OPEN_WIDTH_DP = 1100.dp
private val OPEN_HEIGHT_DP = 740.dp

/**
 * Normal desktop window: soft open size; WM owns geometry after map.
 *
 * **Event-driven sizing only** (no geometry polling loop).
 *
 * External changes (retile, maximize, window rules) arrive as AWT
 * `ComponentEvent.RESIZE` on the Compose top-level window. Compose binds
 * `windowState` to those events; we must not push sizes from a side channel
 * (old JNA `XGetGeometry` → `setSize` poll fought KWin/niri and shrank the
 * window stepwise).
 */
fun main() = application {
    val state = remember { createAppState() }
    val windowState = rememberWindowState(size = DpSize(OPEN_WIDTH_DP, OPEN_HEIGHT_DP))
    Window(
        onCloseRequest = {
            state.release()
            exitApplication()
        },
        title = "Slotify",
        state = windowState,
        resizable = true,
    ) {
        val awt = window
        DisposableEffect(awt) {
            // Event-driven: WM/OS resize → AWT → Compose windowState (no X11 poll).
            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    val c = e.component
                    val insets = awt.insets
                    println(
                        "RESIZE awt=${c.width}x${c.height} " +
                            "insets=${insets.left},${insets.top},${insets.right},${insets.bottom} " +
                            "state=${windowState.size}",
                    )
                }
            }
            awt.addComponentListener(listener)
            // Ensure the first map is visible on the EDT if the WM races composition.
            SwingUtilities.invokeLater {
                if (awt.width > 0 && awt.height > 0) {
                    println("RESIZE mapped awt=${awt.width}x${awt.height} state=${windowState.size}")
                }
            }
            onDispose {
                awt.removeComponentListener(listener)
            }
        }
        App(state = state)
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
