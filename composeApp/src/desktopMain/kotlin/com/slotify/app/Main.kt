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
import com.slotify.app.spotify.SPOTIFY_REDIRECT_URI
import com.slotify.app.spotify.desktopAwaitPkceCode
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.SwingUtilities

/** Soft open size only — the tiling WM owns geometry after map. */
private val OPEN_WIDTH_DP = 1100.dp
private val OPEN_HEIGHT_DP = 740.dp

/**
 * Normal desktop window: soft open size; WM owns geometry after map.
 *
 * On some X11/XWayland + tiling setups, the compositor resizes the X window
 * but AWT never receives ConfigureNotify — Java keeps the initial bounds and
 * Compose draws a fixed box in the corner of a larger frame. Poll real X
 * geometry (JNA) and push it into AWT + [windowState] so the UI fills the tile.
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
        DisposableEffect(awt, windowState) {
            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = syncFromAwt(awt, windowState)
            }
            awt.addComponentListener(listener)
            val job = CoroutineScope(Dispatchers.IO).launch {
                // A few seconds of polling: map + first tile assign often race listeners.
                repeat(40) {
                    delay(250)
                    if (!isActive) return@launch
                    syncFromX11(awt, windowState, title = "Slotify")
                }
                // Keep a slow heartbeat in case the user retiles later and AWT still misses it.
                while (isActive) {
                    delay(1000)
                    syncFromX11(awt, windowState, title = "Slotify")
                }
            }
            onDispose {
                awt.removeComponentListener(listener)
                job.cancel()
            }
        }
        App(
            state = state,
            pkceAwait = { url -> desktopAwaitPkceCode(url, SPOTIFY_REDIRECT_URI) },
        )
    }
}

private fun syncFromAwt(awt: java.awt.Window, windowState: androidx.compose.ui.window.WindowState) {
    val w = awt.width
    val h = awt.height
    if (w <= 0 || h <= 0) return
    val scale = awt.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
    val dpW = (w / scale).dp
    val dpH = (h / scale).dp
    if (windowState.size != DpSize(dpW, dpH)) {
        windowState.size = DpSize(dpW, dpH)
    }
}

private fun syncFromX11(
    awt: java.awt.Window,
    windowState: androidx.compose.ui.window.WindowState,
    title: String,
) {
    val geom = x11WindowSize(title) ?: return
    val (xw, xh) = geom
    if (xw <= 0 || xh <= 0) return
    if (awt.width == xw && awt.height == xh) {
        syncFromAwt(awt, windowState)
        return
    }
    SwingUtilities.invokeLater {
        // Accept the compositor's size so AWT/Compose reflow like a normal app.
        awt.setSize(xw, xh)
        syncFromAwt(awt, windowState)
    }
}

private interface X11Lib : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XDefaultRootWindow(dpy: Pointer?): Int
    fun XFetchName(dpy: Pointer?, w: Int, name: PointerByReference?): Int
    fun XFree(data: Pointer?): Int
    fun XQueryTree(
        dpy: Pointer?,
        w: Int,
        rootReturn: IntByReference?,
        parentReturn: IntByReference?,
        childrenReturn: PointerByReference?,
        nchildrenReturn: IntByReference?,
    ): Int

    fun XGetGeometry(
        dpy: Pointer?,
        drawable: Int,
        rootReturn: IntByReference?,
        xReturn: IntByReference?,
        yReturn: IntByReference?,
        widthReturn: IntByReference?,
        heightReturn: IntByReference?,
        borderWidthReturn: IntByReference?,
        depthReturn: IntByReference?,
    ): Int

    fun XCloseDisplay(dpy: Pointer?): Int
}

private val x11: X11Lib by lazy { Native.load("X11", X11Lib::class.java) }

/** First viewable X window whose WM_NAME equals [title]. Size in pixels. */
private fun x11WindowSize(title: String): Pair<Int, Int>? = runCatching {
    val dpy = x11.XOpenDisplay(null) ?: return null
    try {
        val root = x11.XDefaultRootWindow(dpy)
        val queue = ArrayDeque<Int>()
        queue.add(root)
        val seen = HashSet<Int>()
        var steps = 0
        while (queue.isNotEmpty() && steps < 4000) {
            steps++
            val wid = queue.removeFirst()
            if (!seen.add(wid)) continue
            val nameRef = PointerByReference()
            if (x11.XFetchName(dpy, wid, nameRef) != 0) {
                val ptr = nameRef.value
                if (ptr != null) {
                    try {
                        val name = ptr.getString(0)
                        if (name == title) {
                            val g = IntByReference()
                            val x = IntByReference()
                            val y = IntByReference()
                            val w = IntByReference()
                            val h = IntByReference()
                            val bw = IntByReference()
                            val depth = IntByReference()
                            val rootRet = IntByReference()
                            val ok = x11.XGetGeometry(
                                dpy, wid, rootRet, x, y, w, h, bw, depth,
                            )
                            if (ok != 0 && w.value > 0 && h.value > 0) {
                                return w.value to h.value
                            }
                        }
                    } finally {
                        x11.XFree(ptr)
                    }
                }
            }
            val rootRet = IntByReference()
            val parent = IntByReference()
            val children = PointerByReference()
            val n = IntByReference()
            if (x11.XQueryTree(dpy, wid, rootRet, parent, children, n) != 0) {
                val arr = children.value
                if (arr != null && n.value > 0) {
                    try {
                        val ids = arr.getIntArray(0, n.value)
                        for (id in ids) queue.add(id)
                    } finally {
                        x11.XFree(arr)
                    }
                }
            }
        }
        null
    } finally {
        x11.XCloseDisplay(dpy)
    }
}.getOrNull()

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
