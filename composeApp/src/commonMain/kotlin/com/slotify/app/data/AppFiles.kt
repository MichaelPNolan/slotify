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
