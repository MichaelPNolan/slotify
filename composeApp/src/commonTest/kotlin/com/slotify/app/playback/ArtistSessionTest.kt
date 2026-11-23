package com.slotify.app.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSessionTest {

    private fun items() = listOf(
        ArtistSourceItem("a1", "Artist A"), ArtistSourceItem("a2", "Artist A"), ArtistSourceItem("a3", "Artist A"),
        ArtistSourceItem("b1", "Artist B"), ArtistSourceItem("b2", "Artist B"), ArtistSourceItem("b3", "Artist B"),
        ArtistSourceItem("c1", "Artist C"), ArtistSourceItem("c2", "Artist C"), ArtistSourceItem("c3", "Artist C"),
    )

    /** Drain one pass: every pick is fed back into [played] like the app does. */
    private fun drainPass(session: ArtistSession, played: MutableSet<String>): List<String> {
        val picks = mutableListOf<String>()
        while (true) {
            val key = session.next(played) ?: break
            assertTrue(key !in played, "repeated $key within a pass")
            picks += key
            played += key
        }
        return picks
    }

    @Test
    fun artistTwoPlaysTwoPerArtistThenMovesOn() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(1))
        val played = mutableSetOf<String>()
        val picks = drainPass(session, played)
        assertEquals(6, picks.size, "3 artists × 2 songs")
        // Contiguous runs of 2, one artist per run.
        val runs = picks.chunked(2)
        assertEquals(3, runs.size)
        assertEquals(3, runs.map { session.artistOf(it.first()) }.distinct().size, "each run a different artist")
        runs.forEach { run -> run.forEach { assertEquals(session.artistOf(run.first()), session.artistOf(it)) } }
        assertTrue(session.isPassComplete)
        assertNull(session.next(played), "pass exhausted")
    }

    @Test
    fun artistAllPlaysWholeBlockBeforeNextArtist() {
        val session = ArtistSession(items(), songsPerArtist = Int.MAX_VALUE, random = Random(2))
        val played = mutableSetOf<String>()
        val picks = drainPass(session, played)
        assertEquals(9, picks.size, "all songs in one pass")
        // Blocks of 3, one artist each.
        picks.chunked(3).forEach { block ->
            block.forEach { assertEquals(session.artistOf(block.first()), session.artistOf(it)) }
        }
    }

    @Test
    fun reshuffleAfterPassAllowsFullSecondPass() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(3))
        val played = mutableSetOf<String>()
        drainPass(session, played)
        assertTrue(session.isPassComplete)
        played.clear() // app clears playedKeys on pass completion (S5)
        session.reshuffle()
        assertFalse(session.isPassComplete)
        val second = drainPass(session, played)
        assertEquals(6, second.size)
    }

    @Test
    fun restartAroundPutsThatArtistFirstAndCountsCurrentTowardQuota() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(4))
        // User manually picked b1 (already played); block restarts around B.
        val played = mutableSetOf("b1")
        session.restartAround("Artist B")
        // playedThisArtist = 1 (b1) → exactly one more B pick before moving on.
        val first = assertNotNull(session.next(played))
        assertEquals("Artist B", session.artistOf(first), "B block comes first")
        played += first
        val second = assertNotNull(session.next(played))
        assertTrue(session.artistOf(second) != "Artist B", "quota 2 = b1 + one more, block moves on")
    }

    @Test
    fun restartAroundUnknownArtistFallsBackToFullReshuffle() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(5))
        session.restartAround("Nobody")
        assertNotNull(session.next(emptySet()))
    }
}
