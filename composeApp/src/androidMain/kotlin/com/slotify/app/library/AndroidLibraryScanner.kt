package com.slotify.app.library

import com.slotify.app.model.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import android.media.MediaMetadataRetriever
import java.io.File

actual fun createLibraryScanner(): LibraryScanner = AndroidLibraryScanner()

/**
 * Android scanner: same filesystem walk as desktop. Tags are read with
 * **jaudiotagger first** (parity with desktop — same titles/artists/ISRC for
 * matching), then MediaMetadataRetriever as a fallback for formats
 * jaudiotagger cannot open. Requires READ_MEDIA_AUDIO (or legacy
 * READ_EXTERNAL_STORAGE) for files outside app-private storage.
 */
class AndroidLibraryScanner(
    private val audioExtensions: Set<String> = AUDIO_EXTENSIONS,
) : LibraryScanner {

    override suspend fun scan(roots: List<String>, onProgress: (String) -> Unit): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            // Same normalization as desktop: overlapping roots (/sdcard vs
            // /storage/emulated/0, Music + Music/Slotify) must not double-count.
            val files = collectAudioFiles(roots, audioExtensions, skipHidden = false)
            files.mapIndexed { i, file ->
                if (i % 25 == 0 || i == files.lastIndex) {
                    onProgress("Reading tags (${i + 1}/${files.size}): ${file.name}")
                }
                readFileTags(file)
            }
        }

    override suspend fun readTrack(path: String): LocalTrack? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.extension.lowercase() !in audioExtensions) return@withContext null
        readFileTags(file)
    }

    private fun readFileTags(file: File): LocalTrack {
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val parsed = parseFilename(canonical.name)
        val base = LocalTrack(
            filePath = canonical.absolutePath,
            fileFormat = canonical.extension.lowercase(),
            fileSizeBytes = canonical.length(),
        )
        // Prefer jaudiotagger so Android scores match desktop for the same files.
        try {
            val audio = AudioFileIO.read(canonical)
            val tag = audio.tag
            val header = audio.audioHeader
            val title = tag?.getFirst(FieldKey.TITLE)?.ifBlank { null } ?: parsed.title
            val artist = tag?.getFirst(FieldKey.ARTIST)?.ifBlank { null } ?: parsed.artist
            val album = tag?.getFirst(FieldKey.ALBUM)?.ifBlank { null }
            val duration = header?.trackLength?.let { it * 1000L }
            val trackNumber = tag?.getFirst(FieldKey.TRACK)
                ?.filter { it.isDigit() }?.ifBlank { null }?.toIntOrNull()
            val isrc = runCatching { tag?.getFirst(FieldKey.ISRC)?.ifBlank { null } }.getOrNull()
            if (title != null || artist != null || duration != null || isrc != null) {
                return base.copy(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    trackNumber = trackNumber,
                    isrc = isrc,
                )
            }
        } catch (_: Exception) {
            // Fall through to MediaMetadataRetriever.
        }
        return readWithRetriever(canonical, base, parsed)
    }

    private fun readWithRetriever(
        file: File,
        base: LocalTrack,
        parsed: FilenameParts,
    ): LocalTrack {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            base.copy(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.ifBlank { null } ?: parsed.title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.ifBlank { null } ?: parsed.artist,
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.ifBlank { null },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.filter { it.isDigit() }?.ifBlank { null }?.toIntOrNull(),
            )
        } catch (_: Exception) {
            base.copy(title = parsed.title, artist = parsed.artist)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
