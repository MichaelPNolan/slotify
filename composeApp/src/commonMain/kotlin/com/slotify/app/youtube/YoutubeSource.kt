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
): SearchAttempt = searchUntilScore(
    youtube = youtube,
    baseQuery = baseQuery,
    track = track,
    max = max,
    minScore = null,
)

/**
 * Like [searchWithFallbacks], but when [minScore] is set keeps trying fallback
 * phrasings and merging unique hits until the best ranked score clears the
 * threshold (or every variant is exhausted). First non-empty query alone often
 * ranks below the bar even when a later phrasing would have matched.
 */
suspend fun searchUntilScore(
    youtube: YoutubeSource,
    baseQuery: String,
    track: RemoteTrack,
    max: Int = 8,
    minScore: Double? = null,
): SearchAttempt {
    val queries = YoutubeQuery.fallbacks(baseQuery, track)
    var lastError: Throwable? = null
    var lastQuery = baseQuery
    val merged = LinkedHashMap<String, YoutubeSearchResult>()
    for (q in queries) {
        lastQuery = q
        try {
            val hits = youtube.search(q, max = max)
            for (h in hits) merged[h.videoId] = h
            if (merged.isNotEmpty()) {
                if (minScore == null) {
                    return SearchAttempt(query = q, results = merged.values.toList(), error = null)
                }
                val best = YoutubeRanking.rank(merged.values.toList(), track).firstOrNull()
                if (best != null && best.score >= minScore) {
                    return SearchAttempt(query = q, results = merged.values.toList(), error = null)
                }
            }
        } catch (e: Throwable) {
            lastError = e
            // Keep trying other phrasings — one bad query shouldn’t abort.
        }
    }
    return SearchAttempt(query = lastQuery, results = merged.values.toList(), error = lastError)
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
     * Tokens that never identify a recording. Stripped from both sides before
     * title scoring so “(2011 Remaster) Official Audio” still equals the bare
     * Spotify title. **“live” is kept** — it distinguishes the version.
     */
    private val NOISE_TOKENS = setOf(
        "remaster", "remastered", "remastering",
        "mastered", "mastering", "master",
        "official", "video", "audio", "lyrics", "lyric",
        "music", "hd", "4k", "1080p", "720p", "visualizer",
        "version", "edit", "radio", "single", "album",
    )

    /** Drop noise words + standalone years from a title before comparison. */
    fun stripNoise(title: String): String {
        val tokens = title
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { t ->
                if (t.isEmpty()) return@filter false
                val lt = t.lowercase()
                if (lt in NOISE_TOKENS) return@filter false
                // Standalone year (1900–2099) — date/version marker, not the song.
                if (t.length == 4 && t.all { it.isDigit() }) {
                    val y = t.toIntOrNull()
                    if (y != null && y in 1900..2099) return@filter false
                }
                true
            }
        return tokens.joinToString(" ").trim()
    }

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
     *
     * Channel ≈ artist boosts the score (even slightly lower title scores win
     * when the uploader is the artist). Near-exact titles are floored so a
     * moderate duration mismatch alone cannot drop below the batch bar.
     */
    fun rank(
        results: List<YoutubeSearchResult>,
        target: RemoteTrack,
    ): List<ScoredYoutubeResult> = results
        .map { r ->
            val titleScore = Matcher.matchYouTubeTitleScore(
                stripNoise(target.name),
                stripNoise(r.title),
            )
            val durationScore = r.durationMs?.let { Matcher.durationPart(target.durationMs, it) }
            var score = when (durationScore) {
                null -> titleScore * 0.9 // no duration signal — slight discount, still rankable
                else -> titleScore * 0.65 + durationScore * 0.35
            }
            // Exact-enough title: duration noise (intro/outro, ads) must not
            // push a true hit under a ~85% batch threshold.
            if (titleScore >= 0.97) score = maxOf(score, 0.92)
            score += channelArtistBoost(r, target)
            ScoredYoutubeResult(r, score.coerceIn(0.0, 1.0))
        }
        .sortedWith(
            compareByDescending<ScoredYoutubeResult> { it.score }
                .thenByDescending { channelArtistBoost(it.result, target) },
        )

    /**
     * +0.12 when the channel is (or contains) the track artist — user prefers
     * the official/artist upload even if another hit scores slightly higher.
     */
    private fun channelArtistBoost(result: YoutubeSearchResult, target: RemoteTrack): Double {
        val channel = result.channel?.trim().orEmpty()
        if (channel.isEmpty()) return 0.0
        val chLower = channel.lowercase()
        val artists = target.artists.map { it.name.trim() }.filter { it.isNotEmpty() }
        if (artists.isEmpty()) return 0.0
        val hit = artists.any { a ->
            val al = a.lowercase()
            al.length >= 3 && (chLower == al || chLower.contains(al))
        }
        return if (hit) 0.12 else 0.0
    }
}
