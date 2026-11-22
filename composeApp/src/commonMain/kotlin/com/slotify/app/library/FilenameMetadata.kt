package com.slotify.app.library

/**
 * Derive (artist, title) from a filename when tags are missing.
 *
 * Handles the common conventions:
 * - `Artist - Title.mp3`        → ("Artist", "Title")
 * - `01 - Title.mp3`            → (null, "Title")
 * - `01. Artist - Title.mp3`    → ("Artist", "Title")
 * - `Title.mp3`                 → (null, "Title")
 */
fun parseFilename(fileName: String): FilenameParts {
    var name = fileName.substringAfterLast('/').substringAfterLast('\\')
    name = name.substringBeforeLast('.', name)

    // Strip a leading track number: "01 - ", "01. ", "01_", "1 "
    val numberPrefix = Regex("""^(\d{1,3})\s*[-._)]\s*""")
    name = name.replace(numberPrefix, "")
    if (name.matches(Regex("""^\d{1,3}$"""))) return FilenameParts(null, name)

    val dash = name.indexOf(" - ")
    return if (dash > 0) {
        FilenameParts(
            artist = name.substring(0, dash).trim().ifBlank { null },
            title = name.substring(dash + 3).trim().ifBlank { null },
        )
    } else {
        FilenameParts(artist = null, title = name.trim().ifBlank { null })
    }
}

data class FilenameParts(
    val artist: String?,
    val title: String?,
)
