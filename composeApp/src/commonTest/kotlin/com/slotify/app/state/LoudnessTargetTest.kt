package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoudnessTargetTest {

    @Test
    fun openDetailWinsOverPlaybackActive() {
        assertEquals(
            "aus-cock-rock",
            loudnessTargetPlaylistId(
                openDetailPlaylistId = "aus-cock-rock",
                activePlaybackPlaylistId = "thai-orathai",
            ),
        )
    }

    @Test
    fun fallsBackToPlaybackWhenNoDetail() {
        assertEquals(
            "thai-orathai",
            loudnessTargetPlaylistId(
                openDetailPlaylistId = null,
                activePlaybackPlaylistId = "thai-orathai",
            ),
        )
    }

    @Test
    fun nullWhenNothingOpenAndNothingPlaying() {
        assertNull(loudnessTargetPlaylistId(null, null))
    }
}
