package com.slotify.app.match

import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.TrackMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyHoldsTest {

    private val track = RemoteTrack(
        id = "abc123",
        name = "Just A Girl",
        artists = listOf(RemoteArtist(name = "No Doubt")),
    )

    private fun fuzzyRow(): ResolvedPlaylistTrack = ResolvedPlaylistTrack(
        remote = track,
        match = TrackMatch(LocalTrack(filePath = "/music/corrupt.opus"), confidence = 0.9),
    )

    private fun pinnedRow(): ResolvedPlaylistTrack = ResolvedPlaylistTrack(
        remote = track,
        match = TrackMatch(
            LocalTrack(filePath = "/music/replacement.opus"),
            confidence = 1.0,
            isManual = true,
        ),
    )

    private fun playlist(rows: List<ResolvedPlaylistTrack>) = RemotePlaylist(
        id = "pl1",
        name = "Sweet",
        tracks = rows.map { it.remote },
    )

    @Test
    fun heldFuzzyRowIsForcedUnsourced() {
        val held = Matcher.trackKey(track)
        val out = Matcher.applyHolds(listOf(fuzzyRow()), setOf(held))
        assertNull(out.single().match, "held row must stay To be sourced")
    }

    @Test
    fun pinnedRowWinsOverHold() {
        val held = Matcher.trackKey(track)
        val out = Matcher.applyHolds(listOf(pinnedRow()), setOf(held))
        assertNotNull(out.single().match, "pin is the manual sourcing that clears a hold")
        assertTrue(out.single().match!!.isManual)
    }

    @Test
    fun unheldRowsAreUntouched() {
        val row = fuzzyRow()
        val out = Matcher.applyHolds(listOf(row), emptySet())
        assertEquals(row, out.single())
    }

    @Test
    fun alreadyUnsourcedHeldRowStaysUnsourced() {
        val held = Matcher.trackKey(track)
        val out = Matcher.applyHolds(listOf(ResolvedPlaylistTrack(track, null)), setOf(held))
        assertNull(out.single().match)
    }

    @Test
    fun holdSurvivesRematchCycleLikeAppStateAppliesIt() {
        // Simulates matchPlaylist: the corrupt file WOULD fuzzy-match by filename…
        val held = Matcher.trackKey(track)
        val corruptFile = LocalTrack(filePath = "/music/No Doubt - Just A Girl.opus")
        val rematched = Matcher.matchPlaylist(
            playlist(listOf(fuzzyRow())),
            listOf(corruptFile),
            manualLinks = emptyMap(),
        )
        val fuzzy = assertNotNull(rematched.single().match, "sanity: fuzzy would match the filename")
        assertTrue(fuzzy.confidence >= 0.75, "expected a real fuzzy match, got ${fuzzy.confidence}")
        // …but the hold re-clears it, so it stays To be sourced.
        val applied = Matcher.applyHolds(rematched, setOf(held))
        assertNull(applied.single().match)
    }
}
