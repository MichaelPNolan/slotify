package com.slotify.app.library

import android.media.MediaMetadataRetriever
import com.slotify.app.model.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun createLibraryScanner(): LibraryScanner = AndroidLibraryScanner()

/**
 * Android scanner: same filesystem walk as desktop, but tags come from
 * MediaMetadataRetriever (built-in, no dependency). Requires the caller
 * to have been granted READ_MEDIA_AUDIO (or legacy READ_EXTERNAL_STORAGE).
 */
class AndroidLibraryScanner(
    private val audioExtensions: Set<String> = AUDIO_EXTENSIONS,
) : LibraryScanner {

    override suspend fun scan(roots: List<String>, onProgress: (String) -> Unit): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            val files = roots.flatMap { root ->
                val dir = File(root)
                if (!dir.isDirectory) return@flatMap emptyList()
                dir.walkTopDown()
                    .onEnter { it.canRead() }
                    .filter { it.isFile && it.extension.lowercase() in audioExtensions }
                    // After a desktop copy, Slotify may still contain links —
                    // resolve to the real path when possible.
                    .map { f -> runCatching { f.canonicalFile }.getOrDefault(f) }
                    .distinctBy { it.absolutePath }
                    .toList()
            }
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
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(canonical.absolutePath)
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
