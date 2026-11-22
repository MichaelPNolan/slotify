// Written fresh for Slotify — Sp* DTO → domain mapping tests.

package com.slotify.app.spotify

import com.slotify.app.model.PlaylistSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistMappingTest {

    /** Representative /v1/playlists/{id}/tracks page: null track, is_local track, normal track. */
    private val tracksFixture = """
        {
          "items": [
            {"added_at": "2024-01-01T00:00:00Z", "track": null},
            {
              "added_at": "2024-01-02T00:00:00Z",
              "is_local": true,
              "track": {
                "id": "local-1",
                "name": "Local File Song",
                "uri": "spotify:local:user:Local%20File%20Song",
                "artists": [{"name": "Some Artist"}],
                "duration_ms": 1000
              }
            },
            {
              "added_at": "2024-01-03T00:00:00Z",
              "is_local": false,
              "track": {
                "id": "t1",
                "name": "Bohemian Rhapsody",
                "uri": "spotify:track:t1",
                "artists": [
                  {"id": "q", "name": "Queen"},
                  {"id": "b", "name": "David Bowie"}
                ],
                "album": {"id": "a1", "name": "A Night at the Opera"},
                "duration_ms": 354000,
                "external_ids": {"isrc": "GBUMZ1600535"},
                "track_number": 11,
                "disc_number": 1,
                "explicit": false
              }
            }
          ],
          "total": 3,
          "offset": 0,
          "limit": 100,
          "next": null
        }
    """.trimIndent()

    private val playlist = SpPlaylistSimple(
        id = "pl1",
        name = "Road Trip",
        description = "sunshine and highways",
        images = listOf(SpImage(url = "https://i.scdn.co/image/small", width = 64)),
        owner = SpUser(id = "u1", displayName = "Alice"),
        snapshotId = "snap-1",
        tracks = SpPlaylistTracksRef(total = 3),
    )

    private fun decodeFixture(): SpPlaylistTracksResponse =
        spotifyJson.decodeFromString(SpPlaylistTracksResponse.serializer(), tracksFixture)

    @Test
    fun mapsNullAndLocalTracksOutOfThePlaylist() {
        val page = decodeFixture()
        assertEquals(3, page.items.size)
        val tracks = page.items.toRemoteTracks()
        assertEquals(1, tracks.size, "null track + is_local track must be skipped")
        val track = tracks.single()
        assertEquals("t1", track.id)
        assertEquals("Bohemian Rhapsody", track.name)
        assertEquals("spotify:track:t1", track.uri)
        assertEquals("Queen; David Bowie", track.artistNames, "artist join uses the RemoteTrack separator")
        assertEquals(listOf("Queen", "David Bowie"), track.artists.map { it.name })
        assertEquals("A Night at the Opera", track.albumName)
        assertEquals(354000L, track.durationMs)
        assertEquals("GBUMZ1600535", track.isrc)
        assertEquals(11, track.trackNumber)
        assertEquals(1, track.discNumber)
        assertEquals(false, track.explicit)
    }

    @Test
    fun mapAppPlaylistBuildsDomainModelWithCorrectCounts() {
        val page = decodeFixture()
        val app = mapAppPlaylist(
            playlist = playlist,
            items = page.items,
            importedAtEpochMs = 1234L,
            source = PlaylistSource.OAUTH,
        )
        assertEquals("pl1", app.id)
        assertEquals("Road Trip", app.name)
        assertEquals(1, app.remote.trackCount, "only importable tracks count")
        assertEquals(1, app.resolvedTracks.size)
        assertEquals(0, app.matchedCount, "import starts unmatched")
        assertNull(app.resolvedTracks.single().match)
        assertEquals("sunshine and highways", app.remote.description)
        assertEquals("Alice", app.remote.ownerName)
        assertEquals("snap-1", app.remote.snapshotId)
        assertEquals("https://i.scdn.co/image/small", app.remote.coverUrl)
        assertEquals(1234L, app.importedAtEpochMs)
        assertEquals(PlaylistSource.OAUTH, app.source)
    }

    @Test
    fun cookieSourceIsPreserved() {
        val app = mapAppPlaylist(playlist, decodeFixture().items, 0L, PlaylistSource.COOKIE)
        assertEquals(PlaylistSource.COOKIE, app.source)
    }

    @Test
    fun localTrackMapsToNullDirectly() {
        val localItem = SpPlaylistTrackItem(
            isLocal = true,
            track = SpTrack(id = "l1", name = "Local", artists = listOf(SpArtistRef(name = "X"))),
        )
        assertNull(localItem.toRemoteTrackOrNull())
        val removedItem = SpPlaylistTrackItem(track = null)
        assertNull(removedItem.toRemoteTrackOrNull())
    }

    @Test
    fun trackUriFallsBackToBuiltSpotifyUri() {
        val item = SpPlaylistTrackItem(
            track = SpTrack(id = "t9", name = "No Uri", artists = listOf(SpArtistRef(name = "Y"))),
        )
        assertEquals("spotify:track:t9", item.toRemoteTrackOrNull()?.uri)
    }

    @Test
    fun malformedIsrcIsDroppedNotCarried() {
        val item = SpPlaylistTrackItem(
            track = SpTrack(
                id = "t8",
                name = "Bad ISRC",
                artists = listOf(SpArtistRef(name = "Z")),
                externalIds = SpExternalIds(isrc = "not-an-isrc"),
            ),
        )
        assertNull(item.toRemoteTrackOrNull()?.isrc)
        assertTrue(item.toRemoteTracksSafe().isNotEmpty())
    }

    private fun SpPlaylistTrackItem.toRemoteTracksSafe(): List<com.slotify.app.model.RemoteTrack> =
        listOfNotNull(toRemoteTrackOrNull())
}
