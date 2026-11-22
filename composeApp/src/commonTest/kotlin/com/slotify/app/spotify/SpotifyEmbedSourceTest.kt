// Written fresh for Slotify — embed __NEXT_DATA__ playlist decoder.

package com.slotify.app.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyEmbedSourceTest {

    // Captured shape from open.spotify.com/embed/playlist/2IVlvNaXDsLD62TVqC7yUA
    // (trimmed to the fields Slotify reads).
    private val fixture = """
        {
          "props": {
            "pageProps": {
              "state": {
                "data": {
                  "entity": {
                    "type": "playlist",
                    "name": "Deep Peace out",
                    "uri": "spotify:playlist:2IVlvNaXDsLD62TVqC7yUA",
                    "id": "2IVlvNaXDsLD62TVqC7yUA",
                    "title": "Deep Peace out",
                    "subtitle": "elguini7",
                    "coverArt": {
                      "sources": [
                        {"height": 60, "width": 60, "url": "https://i.scdn.co/image/small"},
                        {"height": 640, "width": 640, "url": "https://i.scdn.co/image/large"}
                      ]
                    },
                    "trackList": [
                      {
                        "uri": "spotify:track:2SLJSg5MvfoHdHPPoHQDTx",
                        "title": "Peace Somehow",
                        "subtitle": "Avi Kaplan",
                        "isExplicit": false,
                        "duration": 211800,
                        "isPlayable": true,
                        "entityType": "track"
                      },
                      {
                        "uri": "spotify:track:abc",
                        "title": "Two Artists",
                        "subtitle": "Foo Bar, Baz Qux",
                        "isExplicit": true,
                        "duration": 1000,
                        "entityType": "track"
                      },
                      {
                        "uri": "spotify:episode:zzz",
                        "title": "Not a track",
                        "subtitle": "Podcast",
                        "entityType": "episode"
                      }
                    ]
                  }
                },
                "settings": {
                  "session": {"accessToken": "x", "isAnonymous": false}
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun extractNextDataFindsScriptBlob() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"a":1}</script></html>"""
        assertEquals("""{"a":1}""", extractNextDataHtml(html))
        assertNull(extractNextDataHtml("<html>no data</html>"))
    }

    @Test
    fun parseEmbedPlaylistMapsTracksAndSkipsEpisodes() {
        val playlist = parseEmbedPlaylistJson(fixture, expectedId = "2IVlvNaXDsLD62TVqC7yUA")
        assertEquals("2IVlvNaXDsLD62TVqC7yUA", playlist.id)
        assertEquals("Deep Peace out", playlist.name)
        assertEquals("elguini7", playlist.remote.ownerName)
        assertEquals("https://i.scdn.co/image/large", playlist.remote.coverUrl)
        assertEquals(2, playlist.remote.tracks.size)

        val first = playlist.remote.tracks[0]
        assertEquals("Peace Somehow", first.name)
        assertEquals("2SLJSg5MvfoHdHPPoHQDTx", first.id)
        assertEquals(211800L, first.durationMs)
        assertEquals(listOf("Avi Kaplan"), first.artists.map { it.name })
        assertTrue(first.uri!!.startsWith("spotify:track:"))

        val second = playlist.remote.tracks[1]
        assertEquals(listOf("Foo Bar", "Baz Qux"), second.artists.map { it.name })
        assertTrue(second.explicit)

        assertEquals(2, playlist.resolvedTracks.size)
        assertTrue(playlist.resolvedTracks.all { it.match == null })
    }

    @Test
    fun parseEmbedRejectsWrongPlaylistId() {
        assertFailsWith<IllegalStateException> {
            parseEmbedPlaylistJson(fixture, expectedId = "otherId")
        }
    }
}
