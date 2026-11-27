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
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Soft open size only — the WM owns geometry after map. */
private val OPEN_WIDTH_DP = 1100.dp
private val OPEN_HEIGHT_DP = 740.dp

/**
 * Normal desktop window: soft open size; WM owns geometry after map.
 *
 * **Event-driven only — no timer / heartbeat / geometry poll** (user 2026-11-27:
 * “heartbeats sound like a loop sounds like a way for the bug to return”).
 *
 * Two event sources, both one-way into AWT — **never write `windowState`
 * from a side channel** (Compose re-applies `windowState.size` onto AWT;
 * two-way sync fights itself and can shrink the window stepwise):
 *
 * 1. **AWT `ComponentEvent.RESIZE`** — works on KWin when Java delivers
 *    ConfigureNotify; Compose updates `windowState` itself.
 * 2. **X11 `ConfigureNotify` on our own Display connection** — niri/XWayland
 *    resizes the X window but AWT often never delivers a ComponentEvent.
 *    A daemon thread blocks in `XNextEvent` (no polling). Each real WM resize
 *    → one `awt.setSize(w, h)` (grow **and** shrink).
 *
 * **Coordinate spaces (rule 14 / root cause 2026-11-27):**
 * - AWT `setSize` / `width` = **outer** (includes titlebar insets).
 * - `ConfigureNotify` w/h = often **client** (outer − insets). Adopting that
 *   raw shrinks niri by exactly the insets (user: one iteration = still the bug).
 * - **XGetGeometry is the source of truth** for the real window size. On each
 *   ConfigureNotify, re-read geometry; only `setSize` when it differs from AWT
 *   by more than 2px. Never nudge by ±insets. No timer/heartbeat. Never write
 *   `windowState` from a side channel. After our `setSize`, briefly ignore cfg.
 *   Healthy match: silent.
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
                    // Diagnostic only — insets are NEVER used in setSize / adopt logic.
                    println("RESIZE awt=${c.width}x${c.height} state=${windowState.size}")
                }
            }
            awt.addComponentListener(listener)
            val x11Thread = startConfigureNotifyLoop(awt)
            onDispose {
                awt.removeComponentListener(listener)
                x11Thread.interrupt()
                cachedX11Wid = 0
            }
        }
        App(state = state)
    }
}

/** Epoch ms until which X ConfigureNotify is ignored (breaks setSize feedback). */
@Volatile private var ignoreXUntilMs: Long = 0

/**
 * Block on X11 ConfigureNotify (event-driven — not a poll).
 *
 * One-shot geometry adopt after map (AWT may still be at soft-open with no
 * event yet), then only react to real WM ConfigureNotify.
 */
private fun startConfigureNotifyLoop(awt: java.awt.Window): Thread {
    val thread = Thread({
        val lib = x11
        val dpy = runCatching { lib.XOpenDisplay(null) }.getOrNull()
        if (dpy == null) {
            println("RESIZE x11=display-none (AWT-only path)")
            return@Thread
        }
        try {
            // Map/tile race: retry *window discovery* until the toplevel exists
            // (not a geometry poll — find once, then event-driven only).
            var wid = 0
            repeat(40) {
                if (Thread.currentThread().isInterrupted) return@Thread
                wid = findOurWid(dpy) ?: 0
                if (wid > 0) return@repeat
                Thread.sleep(100)
            }
            if (wid <= 0) {
                println("RESIZE x11=no-window")
                return@Thread
            }
            cachedX11Wid = wid

            // One-shot unstick: AWT may still be at soft-open with no ComponentEvent.
            readGeom(dpy, wid)?.let { applyAwtSize(awt, it.w, it.h, source = "map") }

            // StructureNotifyMask — our Display connection; does not steal AWT’s mask.
            lib.XSelectInput(dpy, wid, X11.StructureNotifyMask.toLong())
            lib.XFlush(dpy)
            println("RESIZE x11-listen wid=$wid")

            val event = X11.XEvent()
            var events = 0
            while (!Thread.currentThread().isInterrupted) {
                // Blocks until the compositor resizes our window — no timer.
                lib.XNextEvent(dpy, event)
                event.read()
                val type = event.type
                events++
                if (type == X11.ConfigureNotify) {
                    // JNA Union: width/height at x86_64 offsets 56/60 (diagnostic only).
                    val buf = event.pointer
                    val cfgW = buf.getInt(56)
                    val cfgH = buf.getInt(60)
                    if (System.currentTimeMillis() < ignoreXUntilMs) {
                        println("RESIZE[x11-cfg] ${cfgW}x${cfgH} (ignore echo)")
                        continue
                    }
                    // Truth = XGetGeometry (same space as niri window_size), not cfg
                    // fields (often client = outer − insets).
                    val geom = readGeom(dpy, wid)
                    val realW = geom?.w ?: cfgW
                    val realH = geom?.h ?: cfgH
                    println(
                        "RESIZE[x11-cfg] cfg=${cfgW}x${cfgH} xgeom=${realW}x${realH} " +
                            "awt=${awt.width}x${awt.height}",
                    )
                    if (realW <= 0 || realH <= 0) continue
                    applyAwtSize(awt, realW, realH, source = "x11-cfg")
                } else if (events <= 8) {
                    println("RESIZE[x11-evt] type=$type n=$events")
                }
            }
        } catch (e: InterruptedException) {
            // normal shutdown
        } catch (e: Throwable) {
            println("RESIZE x11-loop error: $e")
        } finally {
            runCatching { lib.XCloseDisplay(dpy) }
        }
    }, "slotify-x11-configure")
    thread.isDaemon = true
    thread.start()
    return thread
}

/**
 * Adopt compositor size on AWT so Compose reflows (real grow **and** shrink).
 * [w]/[h] must already be in AWT’s space (XGetGeometry / map path).
 * Never write windowState. Skip if already matching (±2px).
 */
private fun applyAwtSize(awt: java.awt.Window, w: Int, h: Int, source: String) {
    SwingUtilities.invokeLater {
        if (abs(awt.width - w) <= 2 && abs(awt.height - h) <= 2) return@invokeLater
        ignoreXUntilMs = System.currentTimeMillis() + 250
        println("RESIZE[$source] ${w}x${h} awt=${awt.width}x${awt.height} -> setSize")
        awt.setSize(w, h)
        println("RESIZE[$source] setSize done awt=${awt.width}x${awt.height}")
    }
}

private fun findOurWid(dpy: Pointer?): Int? {
    if (dpy == null) return null
    val cached = cachedX11Wid
    if (cached > 0 && readGeom(dpy, cached) != null) return cached
    val found = runCatching { walkForTopLevel(dpy, "Slotify") }.getOrElse { e ->
        println("RESIZE x11 exception: $e")
        null
    }
    if (found != null) cachedX11Wid = found.wid
    return found?.wid
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

    fun XSelectInput(dpy: Pointer?, w: Int, eventMask: Long): Int

    fun XFlush(dpy: Pointer?): Int

    fun XNextEvent(dpy: Pointer?, event: X11.XEvent): Int
}

private val x11: X11Lib by lazy { Native.load("X11", X11Lib::class.java) }

private data class X11Geom(val w: Int, val h: Int, val wid: Int)

/** Set once a toplevel is found — avoids a full tree walk on every event. */
@Volatile private var cachedX11Wid: Int = 0

private fun walkForTopLevel(dpy: Pointer?, expectedTitle: String): X11Geom? {
    if (dpy == null) return null
    val root = x11.XDefaultRootWindow(dpy)
    if (root <= 0) return null
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
        if (wid <= 0 || !seen.add(wid)) continue

        val rootRet = IntByReference()
        val parent = IntByReference()
        val children = PointerByReference()
        val n = IntByReference()
        if (x11.XQueryTree(dpy, wid, rootRet, parent, children, n) != 0) {
            val arr = children.value
            if (arr != null && n.value > 0) {
                try {
                    val ids = arr.getIntArray(0, n.value)
                    for (id in ids) if (id > 0) queue.add(id)
                } finally {
                    x11.XFree(arr)
                }
            }
        }

        val geom = readGeom(dpy, wid) ?: continue
        val pid = readNetWmPid(dpy, wid, pidAtom)
        if (pid != null && pid == ourPid) {
            if (pidHit == null || geom.w * geom.h > pidHit.w * pidHit.h) {
                pidHit = geom
            }
        }
        val name = readName(dpy, wid)
        if (name != null && name == expectedTitle) {
            titleHits += geom
        }
    }
    return pidHit ?: titleHits.maxByOrNull { it.w.toLong() * it.h }
}

private fun readGeom(dpy: Pointer?, wid: Int): X11Geom? {
    if (wid <= 0) return null
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
