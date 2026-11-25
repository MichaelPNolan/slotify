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
 * geometry (JNA) and push it into AWT only.
 *
 * One-way (X11 → AWT): never write AWT size back into [rememberWindowState].
 * Compose re-applies `windowState.size` onto the AWT window, so a two-way
 * sync fights itself and can shrink the window stepwise.
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
            val job = CoroutineScope(Dispatchers.IO).launch {
                // A few seconds of polling: map + first tile assign often race listeners.
                repeat(40) {
                    delay(250)
                    if (!isActive) return@launch
                    syncFromX11(awt, tick = "fast")
                }
                // Keep a slow heartbeat in case the user retiles later and AWT still misses it.
                while (isActive) {
                    delay(1000)
                    syncFromX11(awt, tick = "hb")
                }
            }
            onDispose {
                awt.removeComponentListener(listener)
                job.cancel()
            }
        }
        App(state = state)
    }
}

/**
 * Adopt the compositor's pixel size on AWT so Compose reflows. Do not touch windowState.
 * Every poll logs — silent early-returns hid “X11 never disagrees” failures.
 */
private fun syncFromX11(awt: java.awt.Window, tick: String) {
    val title = "Slotify"
    val lookup = x11WindowGeometry(title)
    if (lookup == null) {
        println("RESIZE[$tick] x11=none title=\"$title\" awt=${awt.width}x${awt.height}")
        return
    }
    val (xw, xh, wid) = lookup
    if (xw <= 0 || xh <= 0) {
        println("RESIZE[$tick] x11=bad ${xw}x$xh wid=$wid awt=${awt.width}x${awt.height}")
        return
    }
    if (awt.width == xw && awt.height == xh) return // healthy — no log
    println(
        "RESIZE[$tick] x11=${xw}x$xh wid=$wid awt=${awt.width}x${awt.height} -> setSize",
    )
    SwingUtilities.invokeLater {
        if (awt.width != xw || awt.height != xh) {
            awt.setSize(xw, xh)
            println("RESIZE[$tick] setSize done awt=${awt.width}x${awt.height}")
        }
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

    fun XGetWindowProperty(
        dpy: Pointer?,
        w: Int,
        property: Int,
        longOffset: Long,
        longLength: Long,
        delete: Int,
        reqType: Int,
        actualTypeReturn: IntByReference?,
        actualFormatReturn: IntByReference?,
        nitemsReturn: IntByReference?,
        bytesAfterReturn: IntByReference?,
        propReturn: PointerByReference?,
    ): Int

    // C: Atom XInternAtom(Display *, const char *atom_name, Bool only_if_exists)
    fun XInternAtom(dpy: Pointer?, name: String, onlyIfExists: Int): Int

    fun XCloseDisplay(dpy: Pointer?): Int
}

private val x11: X11Lib by lazy { Native.load("X11", X11Lib::class.java) }

private data class X11Geom(val w: Int, val h: Int, val wid: Int)

/**
 * Locate *our* top-level window and read its size in pixels.
 *
 * Prefer `_NET_WM_PID == getpid()` (stable across title changes / stale
 * “Slotify” windows). Fall back to the **largest** WM_NAME match so a stray
 * 1100×740 window cannot win over the tiled one.
 */
private fun x11WindowGeometry(expectedTitle: String): X11Geom? = runCatching {
    val dpy = x11.XOpenDisplay(null) ?: return null
    try {
        val root = x11.XDefaultRootWindow(dpy)
        val pidAtom = x11.XInternAtom(dpy, "_NET_WM_PID", 1)
        val ourPid = ProcessHandle.current().pid().toInt()
        val titleHits = ArrayList<X11Geom>()
        var pidHit: X11Geom? = null

        val queue = ArrayDeque<Int>()
        queue.add(root)
        val seen = HashSet<Int>()
        var steps = 0
        while (queue.isNotEmpty() && steps < 4000) {
            steps++
            val wid = queue.removeFirst()
            if (!seen.add(wid)) continue

            // Children first (BFS) so we still discover the tree when names differ.
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

            val geom = readGeom(dpy, wid) ?: continue
            val pid = readNetWmPid(dpy, wid, pidAtom)
            if (pid != null && pid == ourPid) {
                // Keep the largest pid-matched toplevel (dialogs can be smaller).
                if (pidHit == null || geom.w * geom.h > pidHit.w * pidHit.h) {
                    pidHit = geom
                }
            }
            val name = readName(dpy, wid)
            if (name != null && name == expectedTitle) {
                titleHits += geom
            }
        }
        pidHit ?: titleHits.maxByOrNull { it.w.toLong() * it.h }
    } finally {
        x11.XCloseDisplay(dpy)
    }
}.getOrNull()

private fun readGeom(dpy: Pointer?, wid: Int): X11Geom? {
    val g = IntByReference()
    val x = IntByReference()
    val y = IntByReference()
    val w = IntByReference()
    val h = IntByReference()
    val bw = IntByReference()
    val depth = IntByReference()
    val rootRet = IntByReference()
    val ok = x11.XGetGeometry(dpy, wid, rootRet, x, y, w, h, bw, depth)
    if (ok == 0 || w.value <= 0 || h.value <= 0) return null
    return X11Geom(w.value, h.value, wid)
}

private fun readName(dpy: Pointer?, wid: Int): String? {
    val nameRef = PointerByReference()
    if (x11.XFetchName(dpy, wid, nameRef) == 0) return null
    val ptr = nameRef.value ?: return null
    return try {
        ptr.getString(0)
    } finally {
        x11.XFree(ptr)
    }
}

private fun readNetWmPid(dpy: Pointer?, wid: Int, pidAtom: Int): Int? {
    if (pidAtom == 0) return null
    val actualType = IntByReference()
    val actualFormat = IntByReference()
    val nitems = IntByReference()
    val bytesAfter = IntByReference()
    val prop = PointerByReference()
    // XA_CARDINAL = 6
    val status = x11.XGetWindowProperty(
        dpy, wid, pidAtom, 0, 1, 0, 6,
        actualType, actualFormat, nitems, bytesAfter, prop,
    )
    if (status != 0) return null
    val p = prop.value ?: return null
    return try {
        if (nitems.value >= 1 && actualFormat.value == 32) p.getInt(0) else null
    } finally {
        x11.XFree(p)
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
