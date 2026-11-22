package com.slotify.app.library

import com.slotify.app.model.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

actual fun createLibraryScanner(): LibraryScanner = JaudiotaggerScanner()

/** Desktop scanner: walks directories and reads tags with jaudiotagger. */
class JaudiotaggerScanner(
    private val audioExtensions: Set<String> = AUDIO_EXTENSIONS,
) : LibraryScanner {

    override suspend fun scan(roots: List<String>, onProgress: (String) -> Unit): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            // Normalize roots + resolve symlinks + dedupe across the whole set
            // so overlapping roots can’t double-count (200% reports).
            val files = collectAudioFiles(roots, audioExtensions, skipHidden = true)
            files.mapIndexed { i, file ->
                if (i % 25 == 0 || i == files.lastIndex) {
                    onProgress("Reading tags (${i + 1}/${files.size}): ${file.name}")
                }
                readTrack(file)
            }
        }

    override suspend fun readTrack(path: String): LocalTrack? = withContext(Dispatchers.IO) {
        val file = java.io.File(path)
        if (!file.isFile || file.name.startsWith(".")) return@withContext null
        if (file.extension.lowercase() !in audioExtensions) return@withContext null
        readTrack(file)
    }

    private fun readTrack(file: File): LocalTrack {
        // Canonical path: playlist rows point at the real source file even if
        // the walk entered via a Slotify symlink.
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val fallback = LocalTrack(
            filePath = canonical.absolutePath,
            fileFormat = canonical.extension.lowercase(),
            fileSizeBytes = canonical.length(),
        )
        return try {
            val audio = AudioFileIO.read(canonical)
            val tag = audio.tag
            val header = audio.audioHeader
            val parsed = parseFilename(canonical.name)
            fallback.copy(
                title = tag?.getFirst(FieldKey.TITLE)?.ifBlank { null } ?: parsed.title,
                artist = tag?.getFirst(FieldKey.ARTIST)?.ifBlank { null } ?: parsed.artist,
                album = tag?.getFirst(FieldKey.ALBUM)?.ifBlank { null },
                durationMs = header?.trackLength?.let { it * 1000L },
                trackNumber = tag?.getFirst(FieldKey.TRACK)?.filter { it.isDigit() }?.ifBlank { null }?.toIntOrNull(),
                isrc = runCatching { tag?.getFirst(FieldKey.ISRC)?.ifBlank { null } }.getOrNull(),
            )
        } catch (_: Exception) {
            // Unreadable/corrupt tags → fall back to filename-derived metadata.
            val parsed = parseFilename(canonical.name)
            fallback.copy(title = parsed.title, artist = parsed.artist)
        }
    }
}
