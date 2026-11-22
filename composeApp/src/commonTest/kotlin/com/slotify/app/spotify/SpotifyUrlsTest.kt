// Written fresh for Slotify — pasted Spotify playlist URL → id extraction.

package com.slotify.app.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyUrlsTest {

    @Test
    fun parsesOpenSpotifyPlaylistUrl() {
        assertEquals(
            "2IVlvNaXDsLD62TVqC7yUA",
            parseSpotifyPlaylistId("https://open.spotify.com/playlist/2IVlvNaXDsLD62TVqC7yUA"),
        )
    }

    @Test
    fun parsesUrlWithTrackingQuery() {
        assertEquals(
            "2IVlvNaXDsLD62TVqC7yUA",
            parseSpotifyPlaylistId(
                "https://open.spotify.com/playlist/2IVlvNaXDsLD62TVqC7yUA?si=abc123&nd=1&dlsi=xyz",
            ),
        )
    }

    @Test
    fun parsesHttpAndTrailingSlash() {
        assertEquals(
            "4uLU6hMCjMI75M1A2tKUQC",
            parseSpotifyPlaylistId("http://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC/"),
        )
    }

    @Test
    fun parsesSpotifyUri() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            parseSpotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
        )
    }

    @Test
    fun parsesBareBase62Id() {
        assertEquals(
            "2IVlvNaXDsLD62TVqC7yUA",
            parseSpotifyPlaylistId("2IVlvNaXDsLD62TVqC7yUA"),
        )
    }

    @Test
    fun rejectsAlbumAndTrackUrls() {
        assertNull(parseSpotifyPlaylistId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertNull(parseSpotifyPlaylistId("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX"))
        assertNull(parseSpotifyPlaylistId("https://example.com/playlist/abc"))
        assertNull(parseSpotifyPlaylistId(""))
        assertNull(parseSpotifyPlaylistId("   "))
    }
}
