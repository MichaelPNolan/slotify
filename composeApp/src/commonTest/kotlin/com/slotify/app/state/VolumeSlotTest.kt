package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeSlotTest {

    @Test
    fun buttonWhenPausedWithActivePlaylist() {
        assertTrue(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = true,
                loudnessSupported = true,
            ),
        )
    }

    @Test
    fun statusWhilePlaying() {
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = true,
                hasActivePlaylist = true,
                loudnessSupported = true,
            ),
        )
    }

    @Test
    fun noButtonWithoutPlaylistOrSupport() {
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = false,
                loudnessSupported = true,
            ),
        )
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = true,
                loudnessSupported = false,
            ),
        )
    }
}
