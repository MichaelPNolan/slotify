package com.slotify.app.data

import java.io.File
import kotlinx.serialization.Serializable

/** A playback-failed file held out of the library index until fixed or re-linked. */
@Serializable
data class QuarantinedFile(
    val path: String,
    /** On-disk fingerprint at quarantine time — a changed file is rehabilitated. */
    val mtimeMs: Long = 0L,
    val sizeBytes: Long = 0L,
)

/**
 * Corrupt-file registry (spec: failed playback → reset rows to *To be
 * sourced*). Quarantined files are dropped from the index and skipped by
 * scans so fuzzy rematch cannot resurrect them; the audio file itself is
 * never deleted. An entry clears when the on-disk file changes (replaced /
 * fixed copy) or the user explicitly re-links it.
 */
class Quarantine(initial: List<QuarantinedFile> = emptyList()) {

    private val entries = initial.toMutableList()

    val all: List<QuarantinedFile> get() = entries.toList()

    fun contains(path: String): Boolean = entries.any { referSame(it.path, path) }

    /** Records [path] with its current on-disk fingerprint (best-effort). */
    fun add(path: String) {
        if (contains(path)) return
        val file = File(path)
        entries.add(
            QuarantinedFile(
                path = path,
                mtimeMs = runCatching { file.lastModified() }.getOrDefault(0L),
                sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            ),
        )
    }

    /** Explicit rehabilitation (user chose this file deliberately). */
    fun remove(path: String) {
        entries.removeAll { referSame(it.path, path) }
    }

    /**
     * True while [path] should stay out of the index. A quarantined file
     * whose size/mtime changed since quarantine was rehabilitated (removed)
     * and returns false — the replacement copy indexes normally.
     */
    fun shouldSkip(path: String): Boolean {
        val entry = entries.firstOrNull { referSame(it.path, path) } ?: return false
        val file = File(path)
        if (!file.isFile) return true
        val unchanged = runCatching { file.lastModified() == entry.mtimeMs && file.length() == entry.sizeBytes }
            .getOrDefault(true)
        if (!unchanged) {
            remove(path)
            return false
        }
        return true
    }

    private fun referSame(a: String, b: String): Boolean {
        if (a == b) return true
        val ca = runCatching { File(a).canonicalPath }.getOrNull() ?: return false
        val cb = runCatching { File(b).canonicalPath }.getOrNull() ?: return false
        return ca == cb
    }
}
