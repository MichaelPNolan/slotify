package com.slotify.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.slotify.app.data.androidAppContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO

actual fun createCoverArtReader(): CoverArtReader = AndroidCoverArtReader()

/** jaudiotagger first for parity with the scanner — mp3/flac/m4a/aiff, never opus. */
private fun readTagArtwork(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    AudioFileIO.read(file).tag?.firstArtwork?.binaryData
}.getOrNull()

/**
 * Chain per spec S4: content:// → retriever only; .opus → common parser then
 * retriever; everything else → jaudiotagger then retriever. Any failure or
 * missing picture yields null — never a substitute image.
 */
class AndroidCoverArtReader : CoverArtReader {
    override suspend fun readArt(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                path.startsWith("content://") -> retrieverPicture(path)
                path.endsWith(".opus", ignoreCase = true) ->
                    readOpusEmbeddedPicture(path) ?: retrieverPicture(path)
                else -> readTagArtwork(path) ?: retrieverPicture(path)
            }
        }.getOrNull()
    }

    private fun retrieverPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) {
                val ctx: Context = androidAppContext ?: return null
                retriever.setDataSource(ctx, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
