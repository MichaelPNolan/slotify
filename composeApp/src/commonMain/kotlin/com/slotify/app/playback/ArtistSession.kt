package com.slotify.app.playback

import kotlin.random.Random

/** One playable track reduced to what artist ordering needs. */
data class ArtistSourceItem(val key: String, val artist: String)

/**
 * Random artist-block ordering for ARTIST_2 / ARTIST_ALL: shuffle artists,
 * hand out each artist's unplayed songs (up to [songsPerArtist] per block),
 * and report pass exhaustion so the caller can reshuffle + clear played keys.
 */
class ArtistSession(
    items: List<ArtistSourceItem>,
    private val songsPerArtist: Int,
    private val random: Random = Random.Default,
) {
    private val byArtist: Map<String, List<String>> = items.groupBy({ it.artist }, { it.key })
    private var artistOrder: List<String> = emptyList()
    private var artistIdx = 0
    private var playedThisArtist = 0

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
        isPassComplete = false
    }

    /**
     * Next key to play given everything already [played], or null when this
     * pass has no block left — caller then [reshuffle]s and clears played keys.
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
            artistIdx++
            playedThisArtist = 0
        }
        isPassComplete = true
        return null
    }

    /**
     * Rebuild the pass around [artist] after a manual pick: that artist is the
     * first block (the picked song already counts as one) and everyone else
     * follows in random order.
     */
    fun restartAround(artist: String) {
        if (artist !in byArtist) {
            reshuffle()
            return
        }
        artistOrder = listOf(artist) + (byArtist.keys - artist).shuffled(random)
        artistIdx = 0
        playedThisArtist = 1
        isPassComplete = false
    }

    /** Which artist owns [key] (tests + restartAround callers). */
    fun artistOf(key: String): String? =
        byArtist.entries.firstOrNull { key in it.value }?.key
}
