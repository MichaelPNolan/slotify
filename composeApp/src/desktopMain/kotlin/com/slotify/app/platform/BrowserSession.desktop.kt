package com.slotify.app.platform

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Pull `sp_dc` for `.spotify.com` out of Firefox-family cookie DBs
 * (Floorp, Firefox, LibreWolf, Waterfox, Zen). Profiles may be locked while
 * the browser is running — each candidate is copied to a temp file first.
 *
 * Chromium-family cookies are AES-encrypted and are intentionally skipped.
 */
actual fun readBrowserSpDcCookie(): String? {
    val candidates = buildList {
        addAll(floorpProfileDirs())
        addAll(firefoxFamilyProfileDirs("firefox"))
        addAll(firefoxFamilyProfileDirs("librewolf"))
        addAll(firefoxFamilyProfileDirs("waterfox"))
        addAll(firefoxFamilyProfileDirs("zen"))
    }
    for (profile in candidates) {
        val db = File(profile, "cookies.sqlite")
        if (!db.isFile) continue
        readSpDcFromCookiesSqlite(db)?.let { return it }
    }
    return null
}

/** Floorp stores profiles under ~/.floorp and ~/.config/floorp. */
private fun floorpProfileDirs(): List<File> = buildList {
    val home = File(System.getProperty("user.home") ?: return@buildList)
    for (root in listOf(File(home, ".floorp"), File(home, ".config/floorp"))) {
        if (!root.isDirectory) continue
        // profiles.ini Path= entries
        val ini = File(root, "profiles.ini")
        if (ini.isFile) {
            for (path in iniProfilePaths(ini)) {
                add(File(root, path))
            }
        }
        // Fallback: every child dir that looks like a profile
        root.listFiles()?.filter { it.isDirectory && File(it, "cookies.sqlite").isFile }?.forEach { add(it) }
    }
}

private fun firefoxFamilyProfileDirs(browser: String): List<File> {
    val home = File(System.getProperty("user.home") ?: return emptyList())
    val roots = listOf(
        File(home, ".$browser"),
        File(home, ".config/$browser"),
        // Flatpak-style
        File(home, ".var/app/org.mozilla.$browser/.mozilla/$browser"),
        File(home, ".var/app/org.librewolf.$browser/.librewolf"),
        File(home, ".var/app/net.waterfox.$browser/.waterfox"),
    )
    return roots.filter { it.isDirectory }.flatMap { root ->
        val ini = File(root, "profiles.ini")
        val fromIni = if (ini.isFile) iniProfilePaths(ini).map { File(root, it) } else emptyList()
        val scanned = root.listFiles()
            ?.filter { it.isDirectory && File(it, "cookies.sqlite").isFile }
            .orEmpty()
        fromIni + scanned
    }
}

/** Parse `Path=` lines from a Firefox profiles.ini (relative or absolute). */
private fun iniProfilePaths(ini: File): List<String> = runCatching {
    ini.readLines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("Path=", ignoreCase = true)) return@mapNotNull null
            trimmed.substringAfter('=').trim().takeIf { it.isNotEmpty() }
        }
        .distinct()
}.getOrDefault(emptyList())

private fun readSpDcFromCookiesSqlite(db: File): String? {
    return runCatching {
        Class.forName("org.sqlite.JDBC")
        val tmp = Files.createTempFile("slotify-cookies-", ".sqlite").toFile()
        try {
            db.copyTo(tmp, overwrite = true)
            // WAL may still hold recent writes; copy sidecars if present.
            for (suffix in listOf("-wal", "-shm")) {
                val side = File(db.parentFile, db.name + suffix)
                if (side.isFile) side.copyTo(File(tmp.parentFile, tmp.name + suffix), overwrite = true)
            }
            DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT value FROM moz_cookies
                        WHERE name = 'sp_dc'
                          AND (host = '.spotify.com' OR host = 'open.spotify.com' OR host = 'accounts.spotify.com')
                        ORDER BY lastAccessed DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { rs ->
                        if (rs.next()) {
                            val value = rs.getString(1)?.trim().orEmpty()
                            if (value.isNotEmpty() && !looksEncryptedFirefoxCookie(value)) return value
                        }
                    }
                }
            }
            null
        } finally {
            tmp.delete()
            File(tmp.parentFile, tmp.name + "-wal").delete()
            File(tmp.parentFile, tmp.name + "-shm").delete()
        }
    }.getOrNull()
}

/**
 * Firefox stores NSS-encrypted cookies as binary; when the driver still
 * exposes them via `value` they often start with a version prefix octet
 * (v10/v11) rather than printable JWT-ish text. Real sp_dc values are ASCII.
 */
private fun looksEncryptedFirefoxCookie(value: String): Boolean {
    if (value.startsWith("v10") || value.startsWith("v11") || value.startsWith("v20")) return true
    val printable = value.count { it.code in 32..126 }
    return printable < value.length * 0.9
}
