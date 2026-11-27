package com.slotify.app.loudness

import com.slotify.app.data.AppFiles
import com.slotify.app.data.resolveSymlinkTarget
import com.slotify.app.platform.nowEpochMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOUDNESS_FILE = "loudness.json"

/**
 * One key for write (backfill/download) and read (chip UI / play):
 * resolve symlinks the same way the playlist row does, so a match that points
 * at a Slotify link and the library file it targets share one entry.
 */
fun loudnessKey(path: String): String = resolveSymlinkTarget(path).replace('\\', '/')

@Serializable
enum class CorrectionMode { RECOMMENDED, BYPASS, CUSTOM }

@Serializable
data class TrackLoudness(
    val integratedLufs: Double,
    val loudnessRange: Double,
    val truePeakDbtp: Double,
    val chip0_99: Int,
    val correction: CorrectionMode = CorrectionMode.RECOMMENDED,
    val customGainDb: Double = 0.0,
    /**
     * Custom mode only: limiter threshold in dBFS (0 = full scale).
     * Negative = start cutting earlier, below max volume (e.g. −6 dB).
     */
    val customLimitThresholdDb: Double = 0.0,
    val analysedAt: Long = 0L,
)

@Serializable
data class PlaylistLoudnessProfile(
    val meanLufs: Double,
    val analysedCount: Int,
    val totalMembers: Int,
    val updatedAt: Long = 0L,
)

@Serializable
data class LoudnessDocument(
    val tracks: Map<String, TrackLoudness> = emptyMap(),
    val playlists: Map<String, PlaylistLoudnessProfile> = emptyMap(),
)

/** App-private `loudness.json` — never mirrored to portable music-dir JSON. */
class LoudnessStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var doc: LoudnessDocument? = null

    private val _revision = MutableStateFlow(0)
    /** Bumped on every track write so Compose rows re-read chips after analysis. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    fun load(): LoudnessDocument {
        doc?.let { return it }
        val parsed = read()?.let { text ->
            runCatching { json.decodeFromString<LoudnessDocument>(text) }.getOrNull()
        } ?: LoudnessDocument()
        // Re-key legacy raw paths (e.g. symlink rows stored before loudnessKey).
        val migrated = parsed.copy(
            tracks = parsed.tracks.entries.associate { (k, v) -> loudnessKey(k) to v },
        )
        doc = migrated
        return migrated
    }

    fun track(path: String): TrackLoudness? = load().tracks[loudnessKey(path)]

    fun putTrack(path: String, result: LoudnessResult) {
        val d = load()
        val entry = TrackLoudness(
            integratedLufs = result.integratedLufs,
            loudnessRange = result.loudnessRange,
            truePeakDbtp = result.truePeakDbtp,
            chip0_99 = loudnessChip(result.integratedLufs),
            analysedAt = now(),
        )
        doc = d.copy(tracks = d.tracks + (loudnessKey(path) to entry))
        persist()
        bumpRevision()
    }

    fun setCorrection(
        path: String,
        mode: CorrectionMode,
        customGainDb: Double,
        customLimitThresholdDb: Double = 0.0,
    ) {
        val d = load()
        val key = loudnessKey(path)
        val cur = d.tracks[key] ?: return
        doc = d.copy(tracks = d.tracks + (key to cur.copy(
            correction = mode,
            customGainDb = customGainDb,
            customLimitThresholdDb = customLimitThresholdDb.coerceIn(-24.0, 0.0),
        )))
        persist()
        bumpRevision()
    }

    fun playlist(id: String): PlaylistLoudnessProfile? = load().playlists[id]

    fun putPlaylist(id: String, meanLufs: Double, analysedCount: Int, totalMembers: Int) {
        val d = load()
        doc = d.copy(playlists = d.playlists + (id to PlaylistLoudnessProfile(
            meanLufs = meanLufs,
            analysedCount = analysedCount,
            totalMembers = totalMembers,
            updatedAt = now(),
        )))
        persist()
    }

    fun clear() {
        doc = LoudnessDocument()
        persist()
    }

    private fun persist() {
        val d = load()
        runCatching { write(json.encodeToString(LoudnessDocument.serializer(), d)) }
    }
}

/** Production store bound to [AppFiles] (`loudness.json`). */
fun appLoudnessStore(): LoudnessStore = LoudnessStore(
    read = { AppFiles.readTextOrNull(LOUDNESS_FILE) },
    write = { AppFiles.writeText(LOUDNESS_FILE, it) },
    now = { nowEpochMs() },
)
