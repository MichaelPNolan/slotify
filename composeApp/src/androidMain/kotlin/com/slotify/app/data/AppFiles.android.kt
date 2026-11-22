package com.slotify.app.data

import java.io.File

/** Set by MainActivity before any UI/composition runs. */
internal var androidBaseDir: String = ""

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
    if (path.startsWith("content://")) return null // SAF paths unsupported for now
    val f = File(path)
    return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
}

actual fun defaultLibraryRoots(): List<String> =
    listOf("/storage/emulated/0/Music", "/sdcard/Music")
