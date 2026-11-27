package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowTargetTest {

    @Test
    fun playlistWhenSongIsInActivePlaylist() {
        assertTrue(
            preferPlaylistFollow(
                hasNowPlaying = true,
                activePlaylistId = "pl1",
                nowPlayingInActivePlaylist = true,
            ),
        )
    }

    @Test
    fun libraryFallbackWhenNoPlaylistOrSongElsewhere() {
        assertFalse(
            preferPlaylistFollow(
                hasNowPlaying = true,
                activePlaylistId = null,
                nowPlayingInActivePlaylist = false,
            ),
        )
        assertFalse(
            preferPlaylistFollow(
                hasNowPlaying = true,
                activePlaylistId = "pl1",
                nowPlayingInActivePlaylist = false,
            ),
        )
        assertFalse(
            preferPlaylistFollow(
                hasNowPlaying = false,
                activePlaylistId = "pl1",
                nowPlayingInActivePlaylist = true,
            ),
        )
    }
}
