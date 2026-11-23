package com.slotify.app.match

import com.slotify.app.library.parseFilename
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.TrackMatch
import com.slotify.app.platform.nfkd
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fuzzy matcher: Spotify track metadata → local audio file.
 *
 * Confidence model (0.0–1.0):
 * - ISRC equal on both sides            → 0.99 (authoritative)
 * - title AND artist containment        → at least 0.95 (Parachord both-axes gate)
 * - otherwise graded blend of title / artist / duration similarity
 * - tiny bonus (+0.02, capped at 1.0) when durations are within 3s
 *
 * Old-vinyl transfers vary in length, so duration is a soft signal:
 * wide gaps lower the score but never hard-cap it, except absurd gaps
 * (> 120s) which cap at 0.80 — almost surely a different recording.
 *
 * Default acceptance threshold: [DEFAULT_THRESHOLD] (0.75).
 */
object Matcher {

    const val DEFAULT_THRESHOLD = 0.75

    /**
     * When both sides have artist metadata and containment fails, graded artist
     * similarity below this caps overall confidence at 0.70 (rejected by default).
     * Short unrelated strings can still levenshtein to ~0.45, hence 0.60.
     */
    const val MIN_ARTIST_SIMILARITY = 0.60

    // ── Public API ───────────────────────────────────────────────────

    fun matchConfidence(remote: RemoteTrack, local: LocalTrack): Double {
        validateIsrc(remote.isrc)?.let { rI ->
            validateIsrc(local.isrc)?.let { lI -> if (rI == lI) return 0.99 }
        }

        val tagScore = scoreTitleArtistDuration(remote, local)

        // Filename axis: Android/desktop tag readers can disagree while the
        // on-disk name is identical — take the better of tag vs filename score.
        // Content URIs (SAF Link file) use displayPath as the human-readable name.
        val parsed = parseFilename(local.nameForFilenameParse)
        val filenameLocal = local.copy(
            title = parsed.title,
            artist = parsed.artist,
        )
        if (filenameLocal.title == local.title && filenameLocal.artist == local.artist) {
            return tagScore
        }
        val filenameScore = scoreTitleArtistDuration(remote, filenameLocal)
        return max(tagScore, filenameScore)
    }

    private fun scoreTitleArtistDuration(remote: RemoteTrack, local: LocalTrack): Double {
        val nRTitle = normalizeStr(remote.name)
        val nLTitle = normalizeStr(local.displayTitle)
        val titleKnown = nRTitle.isNotEmpty() && nLTitle.isNotEmpty()

        val rArtists = remote.artists
            .mapNotNull { a ->
                normalizeStr(a.name).takeIf { s -> s.isNotEmpty() }?.let { norm -> norm to a.name }
            }
        val nLArtist = normalizeStr(local.artist)
        val artistKnown = rArtists.isNotEmpty() && nLArtist.isNotEmpty()

        val titleContain = titleKnown && (nRTitle.contains(nLTitle) || nLTitle.contains(nRTitle))
        val artistContain = artistKnown &&
            rArtists.any { (norm, _) -> nLArtist.contains(norm) || norm.contains(nLArtist) }

        val titlePart: Double? = when {
            !titleKnown -> null
            titleContain -> 1.0
            else -> gradedSimilarity(nRTitle, nLTitle, remote.name, local.displayTitle)
        }

        val artistPart: Double = when {
            artistContain -> 1.0
            artistKnown -> rArtists.maxOf { (norm, raw) ->
                gradedSimilarity(norm, nLArtist, raw, local.artist ?: "")
            }
            else -> 0.5 // unknown side → neutral, never blocks a title-driven match
        }

        val durationPart = durationPart(remote.durationMs, local.durationMs)

        var num = 0.0
        var den = 0.0
        titlePart?.let { num += 0.55 * it; den += 0.55 }
        num += 0.30 * artistPart; den += 0.30
        durationPart?.let { num += 0.15 * it; den += 0.15 }
        var conf = if (den > 0) num / den else 0.0

        if (titleContain && artistContain && conf < 0.95) conf = 0.95

        // Wrong-artist gate: both artists known, no containment, and weak graded
        // similarity → almost certainly a different artist's song with the same
        // title. Cap below the acceptance threshold (Parachord's single-axis rule,
        // softened to still allow close artist-name variants like
        // "St. Louis" vs "Saint Louis", which clear the 0.60 bar).
        if (artistKnown && !artistContain && artistPart < MIN_ARTIST_SIMILARITY) {
            conf = min(conf, 0.70)
        }

        val dR = remote.durationMs
        val dL = local.durationMs
        if (dR != null && dL != null) {
            val diff = abs(dR - dL)
            when {
                diff <= 3_000 -> conf = min(1.0, conf + 0.02)
                diff > 120_000 -> conf = min(conf, 0.80)
            }
        }
        return conf
    }

    /** Best local file for [remote] at or above [threshold], or null. */
    fun bestMatch(
        remote: RemoteTrack,
        locals: List<LocalTrack>,
        threshold: Double = DEFAULT_THRESHOLD,
    ): TrackMatch? = LocalTrackIndex(locals).bestMatch(remote, threshold)

    /**
     * Stable key for manual file links across rematches / re-imports.
     * Prefers Spotify id, then URI, then title|artists.
     */
    fun trackKey(track: RemoteTrack): String =
        track.id?.takeIf { it.isNotBlank() }
            ?: track.uri?.takeIf { it.isNotBlank() }
            ?: "${track.name}|${track.artistNames}"

    /**
     * Match every track of a playlist. Each row is scored independently
     * (a file may serve repeated rows). [manualLinks] maps [trackKey] →
     * absolute file path and pins those rows with confidence 1.0.
     */
    fun matchPlaylist(
        remote: RemotePlaylist,
        locals: List<LocalTrack>,
        threshold: Double = DEFAULT_THRESHOLD,
        manualLinks: Map<String, String> = emptyMap(),
    ): List<ResolvedPlaylistTrack> {
        val index = LocalTrackIndex(locals)
        val byPath = locals.associateBy { it.filePath }
        return remote.tracks.map { track ->
            val key = trackKey(track)
            val manualPath = manualLinks[key]
            // Pin wins even when the index lacks the file (scan dropped it) —
            // synthesize a minimal row so the link never degrades to fuzzy.
            val manualLocal = manualPath?.let { byPath[it] ?: LocalTrack(filePath = it) }
            if (manualLocal != null) {
                ResolvedPlaylistTrack(track, TrackMatch(manualLocal, confidence = 1.0, isManual = true))
            } else {
                ResolvedPlaylistTrack(track, index.bestMatch(track, threshold))
            }
        }
    }

    /**
     * Force [heldTrackKeys] rows to unsourced after a corrupt-file reset —
     * fuzzy rematch must not reclaim them. A pinned match wins (the pin IS
     * the manual sourcing that clears the hold).
     */
    fun applyHolds(
        rows: List<ResolvedPlaylistTrack>,
        heldTrackKeys: Set<String>,
    ): List<ResolvedPlaylistTrack> {
        if (heldTrackKeys.isEmpty()) return rows
        return rows.map { row ->
            if (trackKey(row.remote) in heldTrackKeys && row.match?.isManual != true && row.match != null) {
                row.copy(match = null)
            } else {
                row
            }
        }
    }

    // ── Similarity internals ─────────────────────────────────────────

    /**
     * Title-only similarity for ranking third-party search results (YouTube):
     * no artist axis — video titles embed the artist themselves, so we rely on
     * containment + graded string similarity of the two titles.
     */
    internal fun matchYouTubeTitleScore(targetTitle: String, candidateTitle: String): Double {
        val nA = normalizeStr(targetTitle)
        val nB = normalizeStr(candidateTitle)
        if (nA.isEmpty() || nB.isEmpty()) return 0.0
        return gradedSimilarity(nA, nB, targetTitle, candidateTitle)
    }

    /** Soft duration similarity; null when either side is unknown. */
    internal fun durationPart(aMs: Long?, bMs: Long?): Double? {
        if (aMs == null || bMs == null) return null
        val d = abs(aMs - bMs)
        return when {
            d <= 3_000 -> 1.0
            d <= 10_000 -> 0.8
            d <= 30_000 -> 0.5
            d <= 90_000 -> 0.2
            else -> 0.0
        }
    }

    /** Graded similarity of two normalized strings plus raw forms (for token overlap). */
    internal fun gradedSimilarity(nA: String, nB: String, rawA: String, rawB: String): Double {        if (nA.isEmpty() || nB.isEmpty()) return 0.0
        if (nA == nB) return 1.0
        val lev = levenshteinRatio(nA, nB)
        val contain = if (nA.contains(nB) || nB.contains(nA)) {
            0.75 + 0.25 * (min(nA.length, nB.length).toDouble() / max(nA.length, nB.length))
        } else {
            0.0
        }
        val jac = jaccard(tokenSet(rawA), tokenSet(rawB))
        return maxOf(lev, contain, 0.9 * jac)
    }

    /** 1.0 - levenshteinDistance / maxLen. */
    internal fun levenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshteinDistance(a, b).toDouble() / maxLen
    }

    internal fun levenshteinDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Case/diacritic-folded word tokens from a raw string. */
    internal fun tokenSet(raw: String): Set<String> {
        val folded = nfkd(raw.lowercase()).replace(Regex("\\p{M}"), "")
        return folded.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()
    }

    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        return inter.toDouble() / (a.size + b.size - inter)
    }

    // ── Index for batch matching ─────────────────────────────────────

    /**
     * Pre-normalizes the local library once, then ranks candidates cheaply
     * (containment + token overlap) before running full [matchConfidence]
     * on the top candidates — keeps large libraries (10k files) responsive.
     */
    internal class LocalTrackIndex(locals: List<LocalTrack>) {

        private class Entry(
            val local: LocalTrack,
            val nTitle: String,
            val nArtist: String,
            val titleTokens: Set<String>,
            val artistTokens: Set<String>,
            /** Same fields derived from the filename — ranks when tags disagree. */
            val nFileTitle: String,
            val nFileArtist: String,
            val fileTitleTokens: Set<String>,
            val fileArtistTokens: Set<String>,
        )

        private val entries: List<Entry> = locals.map { l ->
            val parsed = parseFilename(l.nameForFilenameParse)
            Entry(
                local = l,
                nTitle = normalizeStr(l.displayTitle),
                nArtist = normalizeStr(l.artist),
                titleTokens = tokenSet(l.displayTitle),
                artistTokens = tokenSet(l.artist ?: ""),
                nFileTitle = normalizeStr(parsed.title ?: ""),
                nFileArtist = normalizeStr(parsed.artist ?: ""),
                fileTitleTokens = tokenSet(parsed.title ?: ""),
                fileArtistTokens = tokenSet(parsed.artist ?: ""),
            )
        }

        fun bestMatch(remote: RemoteTrack, threshold: Double): TrackMatch? {
            if (entries.isEmpty()) return null
            val rTitle = normalizeStr(remote.name)
            val rTokens = tokenSet(remote.name)
            val rArtistTokens = remote.artists.flatMap { tokenSet(it.name) }.toSet()
            val nRArtists = remote.artists.map { normalizeStr(it.name) }

            val candidates = entries
                .map { e -> e to cheapRank(rTitle, rTokens, rArtistTokens, nRArtists, e) }
                .sortedByDescending { it.second }
                .take(CANDIDATE_LIMIT)
                .map { it.first.local }

            var bestLocal: LocalTrack? = null
            var bestScore = -1.0
            for (local in candidates) {
                val score = matchConfidence(remote, local)
                if (score > bestScore) {
                    bestScore = score
                    bestLocal = local
                }
            }
            val local = bestLocal ?: return null
            return if (bestScore >= threshold) TrackMatch(local, bestScore) else null
        }

        private fun cheapRank(
            nTitle: String,
            titleTokens: Set<String>,
            artistTokens: Set<String>,
            nRArtists: List<String>,
            e: Entry,
        ): Double = max(
            cheapRankAxis(nTitle, titleTokens, artistTokens, nRArtists, e.nTitle, e.nArtist, e.titleTokens, e.artistTokens),
            cheapRankAxis(
                nTitle,
                titleTokens,
                artistTokens,
                nRArtists,
                e.nFileTitle,
                e.nFileArtist,
                e.fileTitleTokens,
                e.fileArtistTokens,
            ),
        )

        private fun cheapRankAxis(
            nTitle: String,
            titleTokens: Set<String>,
            artistTokens: Set<String>,
            nRArtists: List<String>,
            eTitle: String,
            eArtist: String,
            eTitleTokens: Set<String>,
            eArtistTokens: Set<String>,
        ): Double {
            var best = 0.0
            if (nTitle.isNotEmpty() && eTitle.isNotEmpty()) {
                if (nTitle.contains(eTitle) || eTitle.contains(nTitle)) {
                    best = max(best, 0.95)
                }
                best = max(best, jaccard(titleTokens, eTitleTokens))
            }
            if (artistTokens.isNotEmpty() && eArtistTokens.isNotEmpty()) {
                val contain = nRArtists.any { a ->
                    eArtist.isNotEmpty() && (eArtist.contains(a) || a.contains(eArtist))
                }
                if (contain) best = max(best, 0.85)
                best = max(best, 0.7 * jaccard(artistTokens, eArtistTokens))
            }
            return best
        }

        companion object {
            /** How many cheap-rank candidates get the full (expensive) score. */
            const val CANDIDATE_LIMIT = 12
        }
    }
}
