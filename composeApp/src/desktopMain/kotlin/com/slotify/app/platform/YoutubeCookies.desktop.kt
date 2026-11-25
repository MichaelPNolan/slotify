package com.slotify.app.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

/**
 * Desktop cookies.txt: AWT picker → copy into `~/.local/share/slotify/secrets/`
 * (same private-store idea as Android). System `yt-dlp` gets `--cookies <path>`
 * when configured. Unrelated to Floorp Spotify `sp_dc`.
 */
actual fun pickYoutubeCookiesFile(): String? {
    var chosen: String? = null
    val runnable = Runnable {
        val dialog = FileDialog(null as Frame?, "Import YouTube cookies.txt", FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.setFilenameFilter { _, name ->
            name.endsWith(".txt", ignoreCase = true) || name.equals("cookies", true)
        }
        dialog.isVisible = true
        val file = dialog.file ?: return@Runnable
        val dir = dialog.directory ?: return@Runnable
        chosen = File(dir, file).absolutePath
    }
    if (SwingUtilities.isEventDispatchThread()) runnable.run()
    else SwingUtilities.invokeAndWait(runnable)
    return chosen
}

private fun desktopSecretsDir(): File {
    val home = System.getProperty("user.home") ?: "."
    return File(home, ".local/share/slotify/secrets").apply { mkdirs() }
}

private fun desktopCookiesFile(): File = File(desktopSecretsDir(), "youtube_cookies.txt")

actual fun youtubeCookiesPathOrNull(): String? {
    val f = desktopCookiesFile()
    return if (f.isFile && f.canRead() && f.length() > 0L) f.absolutePath else null
}

actual fun clearYoutubeCookies() {
    runCatching { desktopCookiesFile().delete() }
}

actual fun importYoutubeCookies(source: String): YoutubeCookiesImport {
    if (source.isBlank()) return YoutubeCookiesImport.Cancelled
    val src = File(source.removePrefix("file://"))
    if (!src.isFile) return YoutubeCookiesImport.Failed("File not found")
    val bytes = runCatching { src.readBytes() }
        .getOrElse { e -> return YoutubeCookiesImport.Failed("Read failed: ${e.message}") }
    if (bytes.isEmpty()) return YoutubeCookiesImport.Failed("File is empty")
    val body = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        ?: return YoutubeCookiesImport.Failed("Not text")
    if (!looksLikeNetscapeCookies(body)) {
        return YoutubeCookiesImport.Failed("Not a Netscape cookies.txt")
    }
    return runCatching {
        val dest = desktopCookiesFile()
        val tmp = File(dest.parentFile, "youtube_cookies.txt.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
        if (!dest.isFile || dest.length() == 0L) {
            YoutubeCookiesImport.Failed("Could not save private copy")
        } else {
            YoutubeCookiesImport.Ok(dest.length())
        }
    }.getOrElse { e ->
        YoutubeCookiesImport.Failed("Save failed: ${e.message}")
    }
}
