package com.slotify.app.data

/** App-private file storage (settings/playlists/library index live here). */
expect object AppFiles {
    fun readTextOrNull(name: String): String?
    fun writeText(name: String, content: String)
    fun dataDirPath(): String
}

/** Read an arbitrary filesystem path (e.g. a pasted playlist JSON file). */
expect fun readExternalTextOrNull(path: String): String?

/** Suggested music library roots for this platform. */
expect fun defaultLibraryRoots(): List<String>

/**
 * Symlink staging (link playlist audio into Slotify for a desktop→phone copy)
 * is desktop-only — Android storage typically forbids symlinks.
 */
expect fun supportsSymlinkStaging(): Boolean

/**
 * Platform “managed” directory for files Slotify downloads/exports:
 * a subdirectory of the OS music folder (e.g. `~/Music/Slotify`).
 * Movable with the library; not a hashed store — plain named audio files.
 *
 * Layout under that root:
 * - final named audio files live at the root
 * - `downloads/` and `conversions/` are **staging only** (temp until promoted);
 *   purge manually to reclaim space
 */
expect fun defaultSlotifyMusicDir(): String

/** Staging subdirs under [defaultSlotifyMusicDir] — temp; user may purge. */
val SLOTIFY_STAGING_DIRS = listOf("downloads", "conversions")

fun slotifyDownloadsDir(root: String = defaultSlotifyMusicDir()): String =
    root.trimEnd('/') + "/downloads"

fun slotifyConversionsDir(root: String = defaultSlotifyMusicDir()): String =
    root.trimEnd('/') + "/conversions"

/**
 * Directories searched for portable JSON (`playlists.json`, `manual_links.json`).
 * **Only the Music library roots** (e.g. `/sdcard/Music` on Android) — not the
 * Slotify subdirectory. App-private data is loaded separately via AppFiles.
 */
fun portableJsonSearchDirs(): List<String> =
    defaultLibraryRoots()
        .map { it.trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()

/** Export location for portable JSON: first Music library root. */
fun portableJsonExportDir(): String =
    portableJsonSearchDirs().firstOrNull()
        ?: defaultSlotifyMusicDir().trimEnd('/')

/** True when [path] is inside the managed Slotify root (not staging). */
fun isUnderSlotifyRoot(path: String, root: String = defaultSlotifyMusicDir()): Boolean {
    val p = path.replace('\\', '/').trimEnd('/')
    val r = root.replace('\\', '/').trimEnd('/')
    return p == r || p.startsWith("$r/")
}

/**
 * Sanitize a display name into an on-disk basename (no extension).
 * Collapses whitespace and strips path-illegal characters.
 */
fun sanitizeSlotifyBaseName(raw: String): String {
    val cleaned = raw
        .replace(Regex("""[/\\?%*:|"<>\x00-\x1f]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('.')
    return cleaned.ifBlank { "track" }
}

/**
 * If [path] is a symlink, return its target (absolute, normalized);
 * otherwise return [path] unchanged. Used so the playlist UI can show the
 * original library source even when the match points at a Slotify link.
 */
fun resolveSymlinkTarget(path: String): String = try {
    val p = java.nio.file.Paths.get(path)
    if (java.nio.file.Files.isSymbolicLink(p)) {
        val link = java.nio.file.Files.readSymbolicLink(p)
        val abs = if (link.isAbsolute) link else p.parent?.resolve(link) ?: link
        abs.normalize().toString()
    } else {
        path
    }
} catch (_: Exception) {
    path
}
