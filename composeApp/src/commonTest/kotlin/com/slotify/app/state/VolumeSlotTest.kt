package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeSlotTest {

    @Test
    fun buttonWhenIdleAndIncomplete() {
        assertTrue(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = true,
                playlistIncomplete = true,
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
                playlistIncomplete = true,
                loudnessSupported = true,
            ),
        )
    }

    @Test
    fun noButtonWhenCompleteOrUnsupportedOrNoPlaylist() {
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = true,
                playlistIncomplete = false,
                loudnessSupported = true,
            ),
        )
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = true,
                playlistIncomplete = true,
                loudnessSupported = false,
            ),
        )
        assertFalse(
            showLoudnessAnalyseButton(
                isPlaying = false,
                hasActivePlaylist = false,
                playlistIncomplete = true,
                loudnessSupported = true,
            ),
        )
    }
}
