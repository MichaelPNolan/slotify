package com.slotify.app.data

import java.io.File

private fun dataDir(): File {
    val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { File(it) } ?: File(System.getProperty("user.home"), ".local/share")
    return File(base, "slotify").apply { mkdirs() }
}

actual object AppFiles {
    actual fun readTextOrNull(name: String): String? {
        val f = File(dataDir(), name)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    actual fun writeText(name: String, content: String) {
        val f = File(dataDir(), name)
        f.parentFile?.mkdirs()
        // Atomic replace: write temp then rename, so concurrent writers can't
        // interleave into a torn file (observed corrupting settings.json).
        val tmp = File(f.parentFile, "$name.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            f.writeText(content)
            tmp.delete()
        }
    }

    actual fun dataDirPath(): String = dataDir().absolutePath
}

actual fun readExternalTextOrNull(path: String): String? {
    val f = File(path)
    return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
}

actual fun defaultLibraryRoots(): List<String> =
    listOf(File(System.getProperty("user.home"), "Music").absolutePath)

actual fun defaultSlotifyMusicDir(): String =
    File(File(System.getProperty("user.home"), "Music"), "Slotify").absolutePath

actual fun supportsSymlinkStaging(): Boolean = true
