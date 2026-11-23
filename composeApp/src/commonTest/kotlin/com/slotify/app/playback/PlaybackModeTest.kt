package com.slotify.app.playback

import com.slotify.app.data.PlaybackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackModeTest {
    @Test
    fun cycleWrapsThroughAllFourModesInOrder() {
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.LINEAR.cycle())
        assertEquals(PlaybackMode.ARTIST_2, PlaybackMode.SHUFFLE.cycle())
        assertEquals(PlaybackMode.ARTIST_ALL, PlaybackMode.ARTIST_2.cycle())
        assertEquals(PlaybackMode.LINEAR, PlaybackMode.ARTIST_ALL.cycle())
    }

    @Test
    fun artistModesFlag() {
        assertTrue(PlaybackMode.ARTIST_2.isArtistMode())
        assertTrue(PlaybackMode.ARTIST_ALL.isArtistMode())
        assertFalse(PlaybackMode.SHUFFLE.isArtistMode())
        assertFalse(PlaybackMode.LINEAR.isArtistMode())
    }

    @Test
    fun storedModeWinsOverLegacyShuffleFlag() {
        assertEquals(
            PlaybackMode.ARTIST_ALL,
            PlaybackStore(shuffle = false, mode = "ARTIST_ALL").initialMode(),
        )
        assertEquals(PlaybackMode.SHUFFLE, PlaybackStore(shuffle = true, mode = "").initialMode())
        assertEquals(PlaybackMode.LINEAR, PlaybackStore(shuffle = false, mode = "").initialMode())
        // Unreadable names fall back to the legacy flag.
        assertEquals(PlaybackMode.SHUFFLE, PlaybackStore(shuffle = true, mode = "GARBAGE").initialMode())
    }
}
