// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/resolver/ResolverModels.kt
// Adapted for Slotify: package rename, nfkd indirection.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.slotify.app.match

import com.slotify.app.platform.nfkd
import kotlinx.serialization.Serializable

@Serializable
data class ResolverInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ResolvedSource(
    val url: String,
    val sourceType: String,
    val resolver: String,
    val quality: Int? = null,
    val headers: Map<String, String>? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    /** ISRC of the matched recording, when the source carries one. */
    val isrc: String? = null,
    /** Match confidence from the source (0.0–1.0). */
    val confidence: Double? = null,
    /** Whether the source explicitly couldn't match this track. */
    val noMatch: Boolean = false,
    /** The title returned by the source's search result (for confidence scoring). */
    val matchedTitle: String? = null,
    /** The artist returned by the source's search result (for confidence scoring). */
    val matchedArtist: String? = null,
    /** Duration in ms from the source's result (for confidence scoring). */
    val matchedDurationMs: Long? = null,
    /** Album/track artwork URL surfaced by the source, when available. */
    val artworkUrl: String? = null,
)

// ── Confidence Scoring ──────────────────────────────────────────────

/**
 * Unicode letters + numbers as a regex class body.
 *
 * `\p{N}` is spelled out as `\p{Nd}\p{Nl}\p{No}` for engine portability; N is by
 * definition the union of Nd ∪ Nl ∪ No, so the match set is identical.
 */
internal const val LETTER_OR_NUMBER_CLASS = "\\p{L}\\p{Nd}\\p{Nl}\\p{No}"

private val COMBINING_MARKS = Regex("\\p{M}")
private val NOT_LETTER_OR_DIGIT = Regex("[^$LETTER_OR_NUMBER_CLASS]")

/**
 * Normalize a string for comparison:
 * lowercase → NFKD → strip combining marks (\p{M}) → keep only \p{L}\p{N}.
 * Unicode-aware: accented letters fold ("Beyoncé" → "beyonce") and non-Latin
 * scripts are preserved instead of being deleted.
 */
fun normalizeStr(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    return nfkd(s.lowercase())
        .replace(COMBINING_MARKS, "")
        .replace(NOT_LETTER_OR_DIGIT, "")
}

/**
 * Check if two normalized strings match via containment (either direction).
 */
private fun stringsMatch(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Pick the best-matching result for a target track: the first whose title AND
 * artist BOTH bidirectionally substring-match. Returns null when nothing matches.
 */
fun <T> selectBestMatch(
    results: List<T>,
    targetTitle: String,
    targetArtist: String,
    titleOf: (T) -> String?,
    artistOf: (T) -> String?,
): T? {
    val nt = normalizeStr(targetTitle)
    val na = normalizeStr(targetArtist)
    if (nt.isEmpty() || na.isEmpty()) return null
    return results.firstOrNull {
        stringsMatch(nt, normalizeStr(titleOf(it))) && stringsMatch(na, normalizeStr(artistOf(it)))
    }
}

/**
 * Calculate match confidence by comparing a source's result against the target:
 *
 * - Title + artist match → 0.95   (validated)
 * - Title match only     → 0.50   (artist mismatch — wrong-song candidate)
 * - Artist match only    → 0.50   (wrong song by the right artist)
 * - No match             → 0.50
 *
 * Single-axis matches collapse to 0.50 so a 0.60 floor filters them out.
 */
fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
): Double {
    val normTarget = normalizeStr(targetTitle)
    val normArtist = normalizeStr(targetArtist)
    val normMatchTitle = normalizeStr(matchedTitle)
    val normMatchArtist = normalizeStr(matchedArtist)

    val titleMatch = stringsMatch(normTarget, normMatchTitle)
    val artistMatch = stringsMatch(normArtist, normMatchArtist)

    return if (titleMatch && artistMatch) 0.95 else 0.50
}

// ── Apple Music ids ─────────────────────────────────────────────────

/** Is [id] an Apple Music CATALOG id (all digits)? Library ids (`i.…` etc.) are not. */
fun isAppleMusicCatalogId(id: String?): Boolean =
    !id.isNullOrBlank() && id.all { it.isDigit() }

// ── ISRC capture / walk ─────────────────────────────────────────────

/** Canonical ISRC: 2-letter country, 3-char alphanumeric registrant, 7 digits. */
private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

/**
 * Normalize + validate a raw ISRC: trim, uppercase, require canonical shape.
 * Returns null for malformed/blank input.
 */
fun validateIsrc(raw: String?): String? =
    raw?.trim()?.uppercase()?.takeIf { ISRC_REGEX.matches(it) }

/**
 * Pick the best ISRC for a track: the track's own (carried) ISRC first, then
 * walk the resolved sources in order, skipping `noMatch`. Always canonical or null.
 */
fun pickTrackIsrc(topLevelIsrc: String?, sources: List<ResolvedSource>): String? {
    validateIsrc(topLevelIsrc)?.let { return it }
    for (source in sources) {
        if (source.noMatch) continue
        validateIsrc(source.isrc)?.let { return it }
    }
    return null
}
