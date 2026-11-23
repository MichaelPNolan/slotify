package com.slotify.app.playback

import kotlin.random.Random

/** One playable track reduced to what artist ordering needs.
 *  [artist] is the grouping key (primary artist); [allArtists] also lists
 *  collab partners so a block can fall back to them (e.g. "Buddy Guy, Bonnie Raitt"). */
data class ArtistSourceItem(
    val key: String,
    val artist: String,
    val allArtists: List<String> = listOf(artist),
)

/** Split a raw artist tag/list on collaboration indicators. */
fun splitArtistList(raw: String): List<String> =
    raw.split(";", ",", " & ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(raw.trim()).filter { it.isNotEmpty() } }

/** First artist of a collab string — "Buddy Guy, Bonnie Raitt" → "Buddy Guy". */
fun primaryArtistName(raw: String): String =
    splitArtistList(raw).firstOrNull() ?: raw.trim()

/**
 * Random artist-block ordering for ARTIST_2 / ARTIST_ALL: shuffle artists,
 * hand out each artist's unplayed songs (up to [songsPerArtist] per block),
 * and report pass exhaustion so the caller can reshuffle + clear played keys.
 * Never returns a key already in [played] — repeats are strongly avoided.
 */
class ArtistSession(
    items: List<ArtistSourceItem>,
    private val songsPerArtist: Int,
    private val random: Random = Random.Default,
) {
    private val allItems = items
    private val byArtist: Map<String, List<String>> = items.groupBy({ it.artist }, { it.key })
    private val allArtistsByKey: Map<String, Set<String>> =
        items.associate { it.key to it.allArtists.map { a -> a.trim() }.toSet() }
    private var artistOrder: List<String> = emptyList()
    private var artistIdx = 0
    private var playedThisArtist = 0
    private var anchorFallbacks: Set<String> = emptySet()

    var isPassComplete = false
        private set

    init {
        reshuffle()
    }

    /** Fresh random pass over every artist (init and pass completion). */
    fun reshuffle() {
        artistOrder = byArtist.keys.shuffled(random)
        artistIdx = 0
        playedThisArtist = 0
        anchorFallbacks = emptySet()
        isPassComplete = false
    }

    /**
     * Next key to play given everything already [played], or null when this
     * pass has no block left — caller then [reshuffle]s and clears played keys.
     * Never returns a key contained in [played].
     */
    fun next(played: Set<String>): String? {
        if (isPassComplete) return null
        while (artistIdx < artistOrder.size) {
            val artist = artistOrder[artistIdx]
            val candidates = byArtist[artist].orEmpty().filter { it !in played }
            if (candidates.isNotEmpty() && playedThisArtist < songsPerArtist) {
                playedThisArtist++
                return candidates.random(random)
            }
            // Anchored block dry (one collab and nothing else) → try partners.
            if (artistIdx == 0 && anchorFallbacks.isNotEmpty() && playedThisArtist < songsPerArtist) {
                val fallbacks = allItems.filter { item ->
                    item.key !in played &&
                        allArtistsByKey[item.key].orEmpty().any { name ->
                            anchorFallbacks.any { it.equals(name, ignoreCase = true) }
                        }
                }
                if (fallbacks.isNotEmpty()) {
                    playedThisArtist++
                    return fallbacks.random(random).key
                }
            }
            artistIdx++
            playedThisArtist = 0
        }
        isPassComplete = true
        return null
    }

    /**
     * Rebuild the pass around [artist] after a manual pick or at playback
     * start: that artist is the first block (the picked song already counts
     * as one). [collabPartners] are the picked song's other artists — used
     * when the primary block runs dry before the quota is spent.
     */
    fun restartAround(artist: String, collabPartners: Collection<String> = emptyList()) {
        if (artist !in byArtist) {
            reshuffle()
            return
        }
        artistOrder = listOf(artist) + (byArtist.keys - artist).shuffled(random)
        artistIdx = 0
        playedThisArtist = 1
        anchorFallbacks = collabPartners
            .filterNot { it.equals(artist, ignoreCase = true) }
            .toSet()
        isPassComplete = false
    }

    /** Which artist owns [key] (tests + restartAround callers). */
    fun artistOf(key: String): String? =
        byArtist.entries.firstOrNull { key in it.value }?.key
}
