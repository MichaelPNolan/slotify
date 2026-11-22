package com.slotify.app.youtube

import com.slotify.app.match.Matcher
import com.slotify.app.model.RemoteTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One YouTube search result (metadata only — nothing downloaded yet). */
data class YoutubeSearchResult(
    val videoId: String,
    val title: String,
    val durationSec: Long? = null,
    val channel: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
    val durationMs: Long? get() = durationSec?.let { it * 1000 }
}

/**
 * Platform YouTube source. Desktop: yt-dlp (search + quality-preserving
 * audio extraction). Android: unsupported for now (no yt-dlp runtime).
 */
interface YoutubeSource {
    /** False on platforms without the yt-dlp toolchain. */
    val isSupported: Boolean

    /** Search [max] results for [query]. Suspends; IO-bound. */
    suspend fun search(query: String, max: Int = 8): List<YoutubeSearchResult>

    /**
     * Download [result]'s audio into [targetDir] as a **stream copy**
     * (no re-encode). Returns the resulting file path, or throws.
     * [onProgress] receives status lines from the IO thread.
     */
    suspend fun download(result: YoutubeSearchResult, targetDir: String, onProgress: (String) -> Unit): String
}

expect fun createYoutubeSource(): YoutubeSource

// ── Pure helpers (commonMain, unit-tested) ───────────────────────────────

object YoutubeQuery {
    /** Default search query for an unmatched track — user can edit it in the dialog. */
    fun forTrack(track: RemoteTrack): String =
        listOf(track.name, track.primaryArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
}

data class ScoredYoutubeResult(
    val result: YoutubeSearchResult,
    /** 0..1 combined title-similarity + duration-proximity score. */
    val score: Double,
)

object YoutubeRanking {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse one `yt-dlp --dump-json --flat-playlist` line.
     * Returns null for unparseable lines (warnings, blanks).
     */
    fun parseSearchLine(line: String): YoutubeSearchResult? = runCatching {
        val obj = json.parseToJsonElement(line).jsonObject
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: return null
        // yt-dlp emits duration as a float (e.g. 325.0) — accept both shapes.
        val duration = obj["duration"]?.jsonPrimitive?.let { p ->
            p.longOrNull ?: p.doubleOrNull?.toLong()
        }
        YoutubeSearchResult(
            videoId = id,
            title = title,
            durationSec = duration,
            channel = (obj["channel"] ?: obj["uploader"])?.jsonPrimitive?.content,
        )
    }.getOrNull()

    /**
     * Rank results against the target track: title similarity (our ported
     * graded matcher) weighted against duration proximity (YouTube matches
     * are far more trustworthy when the length lines up — e.g. 78rpm
     * transfers share near-identical durations).
     */
    fun rank(
        results: List<YoutubeSearchResult>,
        target: RemoteTrack,
    ): List<ScoredYoutubeResult> = results
        .map { r ->
            val titleScore = Matcher.matchYouTubeTitleScore(target.name, r.title)
            val durationScore = r.durationMs?.let { Matcher.durationPart(target.durationMs, it) }
            val score = when (durationScore) {
                null -> titleScore * 0.9 // no duration signal — slight discount, still rankable
                else -> titleScore * 0.65 + durationScore * 0.35
            }
            ScoredYoutubeResult(r, score.coerceIn(0.0, 1.0))
        }
        .sortedByDescending { it.score }
}
