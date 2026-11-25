package com.slotify.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.slotify.app.data.androidAppContext
import java.io.File

/**
 * YouTube cookies.txt — private copy under `filesDir/secrets/` (never public
 * storage, never logged). Unrelated to Spotify Floorp `sp_dc` (`BrowserSession`).
 */
internal var youtubeCookiesPicker: (() -> String?)? = null

actual fun pickYoutubeCookiesFile(): String? = youtubeCookiesPicker?.invoke()

private fun secretsDir(ctx: Context): File =
    File(ctx.filesDir, "secrets").apply { mkdirs() }

private fun cookiesFile(ctx: Context): File =
    File(secretsDir(ctx), "youtube_cookies.txt")

actual fun youtubeCookiesPathOrNull(): String? {
    val ctx = androidAppContext ?: return null
    val f = cookiesFile(ctx)
    return if (f.isFile && f.canRead() && f.length() > 0L) f.absolutePath else null
}

actual fun clearYoutubeCookies() {
    val ctx = androidAppContext ?: return
    runCatching { cookiesFile(ctx).delete() }
}

actual fun importYoutubeCookies(source: String): YoutubeCookiesImport {
    val ctx = androidAppContext
        ?: return YoutubeCookiesImport.Failed("App context not ready")
    if (source.isBlank()) return YoutubeCookiesImport.Cancelled

    val bytes = runCatching {
        when {
            source.startsWith("content://") -> {
                val uri = Uri.parse(source)
                // Persist read so re-import/replace works after process death.
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return YoutubeCookiesImport.Failed("Cannot read selected file")
            }
            else -> {
                val f = File(source.removePrefix("file://"))
                if (!f.isFile) return YoutubeCookiesImport.Failed("File not found")
                f.readBytes()
            }
        }
    }.getOrElse { e ->
        return YoutubeCookiesImport.Failed("Read failed: ${e.message ?: e::class.java.simpleName}")
    }

    if (bytes.isEmpty()) return YoutubeCookiesImport.Failed("File is empty")
    val body = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        ?: return YoutubeCookiesImport.Failed("Not text")
    if (!looksLikeNetscapeCookies(body)) {
        return YoutubeCookiesImport.Failed(
            "Not a Netscape cookies.txt (export cookies from your browser as “cookies.txt”)",
        )
    }

    return runCatching {
        val dest = cookiesFile(ctx)
        dest.parentFile?.mkdirs()
        // Atomic replace so a partial write never leaves a half file.
        val tmp = File(dest.parentFile, "youtube_cookies.txt.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
        // Restrict perms as far as the FS allows (app-private dir already isolates).
        runCatching {
            dest.setReadable(false, false)
            dest.setReadable(true, true)
            dest.setWritable(false, false)
            dest.setWritable(true, true)
        }
        if (!dest.isFile || dest.length() == 0L) {
            YoutubeCookiesImport.Failed("Could not save private copy")
        } else {
            YoutubeCookiesImport.Ok(dest.length())
        }
    }.getOrElse { e ->
        YoutubeCookiesImport.Failed("Save failed: ${e.message ?: e::class.java.simpleName}")
    }
}
