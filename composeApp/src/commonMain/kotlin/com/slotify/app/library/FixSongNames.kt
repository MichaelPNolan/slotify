package com.slotify.app.library

/** Characters that are illegal in file names on Android/desktop → '_'. */
private val ILLEGAL = Regex("""[/\\:*?"<>|]""")

/** Strip/replace illegal filename characters and trim stray spaces/dots. */
fun sanitizeFileNamePart(name: String): String =
    name.replace(ILLEGAL, "_").trim().trim('.').trim()

/** True when [fileNameNoExt] lacks the sanitized "<artist> - " prefix. */
fun isSongFileNameMisnamed(fileNameNoExt: String, artist: String): Boolean {
    val prefix = sanitizeFileNamePart(artist) + " - "
    return !fileNameNoExt.startsWith(prefix, ignoreCase = true)
}

/** Proposed "<artist> - <title>.<extension>" (sanitized) for a track. */
fun proposeSongFileName(artist: String, title: String, extension: String): String {
    val base = "${sanitizeFileNamePart(artist)} - ${sanitizeFileNamePart(title)}"
    return if (extension.isBlank()) base else "$base.$extension"
}
