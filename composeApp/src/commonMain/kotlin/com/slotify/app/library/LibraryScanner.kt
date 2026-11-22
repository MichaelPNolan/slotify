package com.slotify.app.library

import com.slotify.app.model.LocalTrack
import java.io.File

/** Scans filesystem roots for audio files and reads their metadata tags. */
interface LibraryScanner {
    /**
     * Recursively scan [roots] for audio files. Suspends while doing IO.
     * [onProgress] receives human-readable status lines (called from the IO thread).
     */
    suspend fun scan(roots: List<String>, onProgress: (String) -> Unit = {}): List<LocalTrack>

    /**
     * Read tags for a single audio file (manual link / single-file index).
     * Returns null when the path is missing or not an accepted audio extension.
     */
    suspend fun readTrack(path: String): LocalTrack?
}

/** Platform scanner: jaudiotagger on desktop, MediaMetadataRetriever on Android. */
expect fun createLibraryScanner(): LibraryScanner

/**
 * Audio file extensions both platform scanners accept (user-approved list).
 * `opus` added 2026-10-27 for quality-preserving YouTube downloads
 * (stream copy, no re-encode) — user-sanctioned change.
 */
val AUDIO_EXTENSIONS: Set<String> = setOf(
    "aac", "mp3", "mp4", "flac", "wav", "aif", "aiff", "opus",
)

/**
 * Collapse library roots so the same tree is never walked twice.
 *
 * - Canonicalize each path (fixes `/sdcard/Music` vs `/storage/emulated/0/Music`).
 * - Drop duplicates and any root nested under another kept root
 *   (e.g. `Music` + `Music/Slotify` → only `Music`).
 *
 * Without this, overlapping roots index every file ~2× (“200% of actual files”).
 */
fun distinctLibraryRoots(roots: List<String>): List<String> {
    val canonical = roots
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { raw -> runCatching { File(raw).canonicalFile }.getOrNull() }
        .filter { it.isDirectory }
        .distinctBy { it.absolutePath }
    val kept = mutableListOf<File>()
    // Shorter paths first so parents are kept and children dropped.
    for (dir in canonical.sortedBy { it.absolutePath.length }) {
        val nested = kept.any { parent ->
            dir.absolutePath == parent.absolutePath ||
                dir.absolutePath.startsWith(parent.absolutePath + File.separator)
        }
        if (!nested) kept.add(dir)
    }
    return kept.map { it.absolutePath }
}

/**
 * Collect unique audio files under [roots]: walk each normalized root once,
 * resolve symlinks to canonical paths, then dedupe across the whole set.
 */
fun collectAudioFiles(
    roots: List<String>,
    audioExtensions: Set<String> = AUDIO_EXTENSIONS,
    skipHidden: Boolean = true,
): List<File> {
    val byPath = LinkedHashMap<String, File>()
    for (root in distinctLibraryRoots(roots)) {
        val dir = File(root)
        if (!dir.isDirectory) continue
        dir.walkTopDown()
            .onEnter { entry ->
                if (skipHidden && entry.name.startsWith(".")) return@onEnter false
                entry.canRead()
            }
            .filter { f ->
                f.isFile &&
                    (!skipHidden || !f.name.startsWith(".")) &&
                    f.extension.lowercase() in audioExtensions
            }
            .forEach { f ->
                val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
                byPath.putIfAbsent(canonical.absolutePath, canonical)
            }
    }
    return byPath.values.toList()
}
