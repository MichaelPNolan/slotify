package com.slotify.app.library

/** Embedded cover-picture bytes for a local audio file. Null = no art / unreadable. */
interface CoverArtReader {
    suspend fun readArt(path: String): ByteArray?
}

/** Platform reader factory: desktop actual in this task, Android actual in Task 4. */
expect fun createCoverArtReader(): CoverArtReader
