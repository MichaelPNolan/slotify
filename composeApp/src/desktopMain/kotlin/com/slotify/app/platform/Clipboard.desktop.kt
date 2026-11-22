package com.slotify.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.TimeUnit

/**
 * Desktop clipboard read. On Wayland/XWayland, AWT's X11 clipboard often
 * reports zero flavors (UnsupportedFlavorException) even when the selection
 * has text — prefer `wl-paste`, then AWT, then xclip/xsel.
 */
actual suspend fun readClipboardText(): String? = withContext(Dispatchers.IO) {
    readViaWlPaste()
        ?: readViaAwt()
        ?: readViaXclip()
        ?: readViaXsel()
}

private fun readViaWlPaste(): String? {
    val out = runProcess(
        listOf("wl-paste", "--no-newline", "--type", "text/plain;charset=utf-8"),
    ) ?: runProcess(listOf("wl-paste", "--no-newline", "--type", "text/plain"))
        ?: runProcess(listOf("wl-paste", "--no-newline"))
        ?: return null
    return out.trim().takeIf { it.isNotEmpty() }
}

private fun readViaAwt(): String? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val contents = clipboard.getContents(null) ?: return null
    // Prefer any CharSequence/String flavor actually advertised.
    for (flavor in contents.transferDataFlavors) {
        val data = runCatching { contents.getTransferData(flavor) }.getOrNull() ?: continue
        val text = when (data) {
            is CharSequence -> data.toString()
            is String -> data
            else -> null
        } ?: continue
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) return trimmed
    }
    // Last resort: classic stringFlavor even if the flavor list looked empty.
    val s = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
    s?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

private fun readViaXclip(): String? =
    runProcess(listOf("xclip", "-selection", "clipboard", "-out"))
        ?.trim()?.takeIf { it.isNotEmpty() }

private fun readViaXsel(): String? =
    runProcess(listOf("xsel", "--clipboard", "--output"))
        ?.trim()?.takeIf { it.isNotEmpty() }

/** Run a short-lived CLI; return stdout (empty string allowed) or null on failure. */
private fun runProcess(command: List<String>): String? {
    val binary = command.first()
    // Skip quickly when the tool is not installed.
    if (which(binary) == null) return null
    return runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        // Clipboard tools must not hang the UI thread forever.
        val finished = process.waitFor(800, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        val text = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (process.exitValue() != 0) {
            // wl-paste exits non-zero when clipboard is empty — not an error to surface.
            if (err.contains("No selection", ignoreCase = true) ||
                err.contains("Nothing is copied", ignoreCase = true)
            ) {
                return ""
            }
            return null
        }
        text
    }.getOrNull()
}

private fun which(binary: String): String? {
    val path = System.getenv("PATH") ?: return null
    for (dir in path.split(':')) {
        if (dir.isBlank()) continue
        val f = java.io.File(dir, binary)
        if (f.isFile && f.canExecute()) return f.absolutePath
    }
    return null
}
