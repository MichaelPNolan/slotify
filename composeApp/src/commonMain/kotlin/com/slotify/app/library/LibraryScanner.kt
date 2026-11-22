package com.slotify.app.library

import com.slotify.app.model.LocalTrack

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
