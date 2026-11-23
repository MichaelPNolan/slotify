package com.slotify.app.youtube

import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferredDownloadBaseNameTest {

    @Test
    fun usesSpotifyArtistAndTitle() {
        val remote = RemoteTrack(
            name = "Blue Moon",
            artists = listOf(RemoteArtist(name = "Billie Holiday")),
        )
        assertEquals("Billie Holiday - Blue Moon", preferredDownloadBaseName(remote))
    }

    @Test
    fun multipleArtistsJoined() {
        val remote = RemoteTrack(
            name = "Under Pressure",
            artists = listOf(RemoteArtist(name = "Queen"), RemoteArtist(name = "David Bowie")),
        )
        assertEquals("Queen; David Bowie - Under Pressure", preferredDownloadBaseName(remote))
    }

    @Test
    fun titleOnlyWhenNoArtist() {
        val remote = RemoteTrack(name = "Ambient Piece", artists = emptyList())
        assertEquals("Ambient Piece", preferredDownloadBaseName(remote))
    }

    @Test
    fun sanitizesPathIllegalCharacters() {
        val remote = RemoteTrack(
            name = "What/If: Live?",
            artists = listOf(RemoteArtist(name = "A/B")),
        )
        assertEquals("A B - What If Live", preferredDownloadBaseName(remote))
    }
}
