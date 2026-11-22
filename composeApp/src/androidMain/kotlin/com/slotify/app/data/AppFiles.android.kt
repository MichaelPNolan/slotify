package com.slotify.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Set by MainActivity before any UI/composition runs. */
internal var androidBaseDir: String = ""

/** App context for content:// SAF reads (JSON import). */
@Volatile
internal var androidAppContext: Context? = null

actual object AppFiles {
    actual fun readTextOrNull(name: String): String? {
        if (androidBaseDir.isEmpty()) return null
        val f = File(androidBaseDir, name)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    actual fun writeText(name: String, content: String) {
        if (androidBaseDir.isEmpty()) return
        val f = File(androidBaseDir, name)
        f.parentFile?.mkdirs()
        // Atomic replace: write temp then rename (see desktop actual).
        val tmp = File(f.parentFile, "$name.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            f.writeText(content)
            tmp.delete()
        }
    }

    actual fun dataDirPath(): String = androidBaseDir
}

actual fun readExternalTextOrNull(path: String): String? {
    val p = path.trim()
    if (p.isEmpty()) return null
    // SAF document picker result — the only reliable way to read non-media
    // files under /sdcard on scoped storage.
    if (p.startsWith("content://")) {
        val ctx = androidAppContext ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(Uri.parse(p))?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
    val cleaned = p.removePrefix("file://")
    val f = File(cleaned)
    if (!f.isFile) return null
    return runCatching { f.readText() }.getOrNull()
}

actual fun defaultLibraryRoots(): List<String> =
    // /sdcard is what this device’s permissions actually allow for Music.
    // Single path only — listing both /sdcard and /storage/emulated/0 double-counts.
    listOf("/sdcard/Music")

actual fun defaultSlotifyMusicDir(): String =
    "/sdcard/Music/Slotify"

actual fun supportsSymlinkStaging(): Boolean = false
