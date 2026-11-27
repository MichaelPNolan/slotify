package com.slotify.app.loudness

import com.slotify.app.data.AppFiles
import com.slotify.app.platform.nowEpochMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOUDNESS_FILE = "loudness.json"

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

    fun load(): LoudnessDocument {
        doc?.let { return it }
        val parsed = read()?.let { text ->
            runCatching { json.decodeFromString<LoudnessDocument>(text) }.getOrNull()
        } ?: LoudnessDocument()
        doc = parsed
        return parsed
    }

    fun track(path: String): TrackLoudness? = load().tracks[path]

    fun putTrack(path: String, result: LoudnessResult) {
        val d = load()
        val entry = TrackLoudness(
            integratedLufs = result.integratedLufs,
            loudnessRange = result.loudnessRange,
            truePeakDbtp = result.truePeakDbtp,
            chip0_99 = loudnessChip(result.integratedLufs),
            analysedAt = now(),
        )
        doc = d.copy(tracks = d.tracks + (path to entry))
        persist()
    }

    fun setCorrection(path: String, mode: CorrectionMode, customGainDb: Double) {
        val d = load()
        val cur = d.tracks[path] ?: return
        doc = d.copy(tracks = d.tracks + (path to cur.copy(
            correction = mode,
            customGainDb = customGainDb,
        )))
        persist()
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
