package com.slotify.app.library

import com.slotify.app.playback.primaryArtistName

/** Characters that are illegal in file names on Android/desktop → '_'. */
private val ILLEGAL = Regex("""[/\\:*?"<>|]""")

/** Dash separators: hyphen, en dash, em dash, optional surrounding spaces. */
private val DASH = Regex("""\s*[-–—]\s*""")

/** Strip/replace illegal filename characters and trim stray spaces/dots. */
fun sanitizeFileNamePart(name: String): String =
    name.replace(ILLEGAL, "_").trim().trim('.').trim()

/**
 * True only when we're CONFIDENT the file isn't in artist-song shape:
 * no dash separator at all ("Piano Man", "01 Piano Man"), or a leading
 * track number ("01 - Song"). Any other "X - Y" name is left alone —
 * we can't tell song-first from artist-first, and a leading tag-artist
 * match means the file is already right (multi-artist tags compare by
 * their primary artist only).
 */
fun isSongFileNameMisnamed(fileNameNoExt: String, artist: String): Boolean {
    if (fileNameNoExt.isBlank()) return false
    val primary = sanitizeFileNamePart(primaryArtistName(artist))
    if (primary.isEmpty()) return false
    val parts = DASH.split(fileNameNoExt, limit = 2)
    if (parts.size == 2 && parts[0].isNotBlank()) {
        val first = parts[0].trim()
        if (first.equals(primary, ignoreCase = true)) return false
        if (first.contains(primary, ignoreCase = true)) return false
        // Leading track number — confidently not an artist.
        if (first.trim('.').trim().toIntOrNull() != null) return true
        // Unknown leading text (song-first, other artist, album words…) —
        // can't judge, so don't touch it.
        return false
    }
    // No dash shape at all.
    return true
}

/** Proposed "<primary artist> - <title>.<extension>" (sanitized). */
fun proposeSongFileName(artist: String, title: String, extension: String): String {
    val base = "${sanitizeFileNamePart(primaryArtistName(artist))} - ${sanitizeFileNamePart(title)}"
    return if (extension.isBlank()) base else "$base.$extension"
}
