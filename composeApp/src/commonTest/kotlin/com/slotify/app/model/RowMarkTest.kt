package com.slotify.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowMarkTest {
    @Test
    fun cycleMarkWalksNoneSkipDeleteNone() {
        val none: RowMark? = null
        assertEquals(RowMark.SKIP, none.cycleMark())
        assertEquals(RowMark.DELETE, RowMark.SKIP.cycleMark())
        assertNull(RowMark.DELETE.cycleMark())
    }

    @Test
    fun localTrackSerializesMarkWithDefaultNull() {
        val track = LocalTrack(filePath = "/m/a.mp3", title = "A")
        assertNull(track.mark)
        assertEquals(RowMark.SKIP, track.copy(mark = RowMark.SKIP).mark)
    }
}
