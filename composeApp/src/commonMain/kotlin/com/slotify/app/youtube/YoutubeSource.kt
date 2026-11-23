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
 * Platform YouTube source.
 * Desktop: system `yt-dlp` + ffmpeg (unchanged).
 * Android: bundled yt-dlp via Chaquopy (`yt-dlp-android`) + ffmpeg-kit remux.
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
     *
     * [baseName] is the on-disk basename (no extension) when the caller knows
     * the desired `Artist - Title` (Spotify row). Null → platform default from
     * the YouTube result (title/channel — often wrong for matching).
     */
    suspend fun download(
        result: YoutubeSearchResult,
        targetDir: String,
        onProgress: (String) -> Unit,
        baseName: String? = null,
    ): String
}

expect fun createYoutubeSource(): YoutubeSource

// ── Pure helpers (commonMain, unit-tested) ───────────────────────────────

/**
 * On-disk basename for a YouTube fetch when the Spotify row is known.
 * Prefer Spotify artist/title so filename + tags match what the matcher scores
 * against — YouTube titles/channels often differ enough to fail fuzzy match.
 */
fun preferredDownloadBaseName(remote: com.slotify.app.model.RemoteTrack): String {
    val artist = remote.artistNames.trim()
    val title = remote.name.trim()
    val joined = listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" - ")
    return com.slotify.app.data.sanitizeSlotifyBaseName(
        joined.ifBlank { title.ifBlank { artist } },
    ).take(180)
}

object YoutubeQuery {
    /**
     * Default search query for one unmatched track.
     *
     * Uses **only** that track’s Spotify metadata (title + its own artists).
     * The playlist name is never an artist source — even when one artist
     * dominates the playlist (e.g. “Tai Orathai Mix”).
     *
     * Artist first often works better for non-English catalogs on YouTube.
     */
    fun forTrack(track: RemoteTrack): String {
        val title = track.name.trim()
        val artist = track.primaryArtist.trim()
        // Reject a track artist that is literally blank after trim only —
        // never substitute playlist/owner/context strings here.
        return when {
            artist.isNotEmpty() && title.isNotEmpty() -> "$artist $title"
            title.isNotEmpty() -> title
            else -> artist
        }.trim()
    }

    /**
     * Ordered query variants for [baseQuery]: YouTube’s search API sometimes
     * returns **zero** hits for a phrase that works fine on youtube.com
     * (e.g. “Hoochie Coochie Man”). Retry with light edits before giving up.
     * Variants are derived from [track] only — never from a playlist title.
     */
    fun fallbacks(baseQuery: String, track: RemoteTrack): List<String> {
        val seen = LinkedHashSet<String>()
        fun add(raw: String) {
            val q = raw.trim().replace(Regex("\\s+"), " ")
            if (q.isNotEmpty()) seen += q
        }

        val base = baseQuery.trim()
        add(base)
        // Apostrophes / curly quotes can zero out the API response.
        add(base.replace("'", "").replace("’", ""))
        add(base.replace(Regex("[^\\p{L}\\p{N}\\s]"), " "))

        val title = track.name.trim()
        val artist = track.primaryArtist.trim()
        if (artist.isNotEmpty() && title.isNotEmpty()) {
            add("$artist $title")
            add("$artist $title audio")
            add("$artist $title song")
            add("$title $artist")
            add("$title $artist audio")
        }
        if (title.isNotEmpty()) {
            add("$title audio")
            add("$title song")
            add("$title official audio")
            // Title-only without any artist string (never playlist name).
            add(title)
        }
        if (artist.isNotEmpty()) {
            add("$artist $title".trim())
        }
        // If the user already edited the query, still try title-only + audio.
        if (base.isNotEmpty() && base != title && base != artist) {
            add("$base audio")
            add("$base song")
        }
        return seen.toList()
    }
}

/**
 * Search YouTube with [baseQuery], then [YoutubeQuery.fallbacks] until one
 * query returns results. Returns the query that worked (or the last tried)
 * and the raw hits (empty if every variant was empty).
 */
suspend fun searchWithFallbacks(
    youtube: YoutubeSource,
    baseQuery: String,
    track: RemoteTrack,
    max: Int = 8,
): SearchAttempt {
    val queries = YoutubeQuery.fallbacks(baseQuery, track)
    var lastError: Throwable? = null
    var lastQuery = baseQuery
    for (q in queries) {
        lastQuery = q
        try {
            val hits = youtube.search(q, max = max)
            if (hits.isNotEmpty()) {
                return SearchAttempt(query = q, results = hits, error = null)
            }
        } catch (e: Throwable) {
            lastError = e
            // Keep trying other phrasings — one bad query shouldn’t abort.
        }
    }
    return SearchAttempt(query = lastQuery, results = emptyList(), error = lastError)
}

data class SearchAttempt(
    /** Query that produced [results], or the last variant tried when empty. */
    val query: String,
    val results: List<YoutubeSearchResult>,
    val error: Throwable?,
)

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
