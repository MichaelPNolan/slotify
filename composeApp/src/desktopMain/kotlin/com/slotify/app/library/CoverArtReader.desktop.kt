package com.slotify.app.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO

actual fun createCoverArtReader(): CoverArtReader = DesktopCoverArtReader()

/**
 * jaudiotagger: mp3/flac/m4a/aiff — NOT opus (this build has no opus reader;
 * .opus is routed to [readOpusEmbeddedPicture] first). Null on any failure.
 */
private fun readTagArtwork(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    AudioFileIO.read(file).tag?.firstArtwork?.binaryData
}.getOrNull()

class DesktopCoverArtReader : CoverArtReader {
    override suspend fun readArt(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                path.startsWith("content://") -> null // SAF links are Android-side only
                path.endsWith(".opus", ignoreCase = true) -> readOpusEmbeddedPicture(path)
                else -> readTagArtwork(path)
            }
        }.getOrNull()
    }
}
