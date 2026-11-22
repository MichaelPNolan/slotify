// Written fresh for Slotify — manual pin survives rematch.

package com.slotify.app.match

import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualLinkMatchTest {

    private val remote = RemoteTrack(
        id = "spotify-id-1",
        name = "Peace Somehow",
        artists = listOf(RemoteArtist(name = "Avi Kaplan")),
        durationMs = 211800,
    )
    private val playlist = RemotePlaylist(
        id = "pl1",
        name = "Test",
        tracks = listOf(remote),
    )
    private val local = LocalTrack(
        filePath = "/music/custom/peace.opus",
        title = "Totally Different Tags",
        artist = "Someone Else Entirely",
        durationMs = 100,
    )

    @Test
    fun trackKeyPrefersSpotifyId() {
        assertEquals("spotify-id-1", Matcher.trackKey(remote))
        assertEquals(
            "spotify:track:abc",
            Matcher.trackKey(RemoteTrack(id = null, uri = "spotify:track:abc", name = "x")),
        )
        assertEquals(
            "x|Artist",
            Matcher.trackKey(RemoteTrack(name = "x", artists = listOf(RemoteArtist(name = "Artist")))),
        )
    }

    @Test
    fun manualLinkPinsEvenWhenFuzzyWouldReject() {
        // Tags intentionally unrelated — fuzzy match would fail.
        val fuzzy = Matcher.matchPlaylist(playlist, listOf(local), threshold = 0.75)
        assertNull(fuzzy[0].match, "sanity: unrelated file should not auto-match")

        val pinned = Matcher.matchPlaylist(
            playlist,
            listOf(local),
            threshold = 0.75,
            manualLinks = mapOf(Matcher.trackKey(remote) to local.filePath),
        )
        val match = assertNotNull(pinned[0].match)
        assertTrue(match.isManual)
        assertEquals(1.0, match.confidence)
        assertEquals("/music/custom/peace.opus", match.local.filePath)
    }

    @Test
    fun missingManualPathFallsBackToFuzzy() {
        val resolved = Matcher.matchPlaylist(
            playlist,
            listOf(local),
            threshold = 0.75,
            manualLinks = mapOf(Matcher.trackKey(remote) to "/gone/missing.opus"),
        )
        assertNull(resolved[0].match)
    }
}
