package com.slotify.app.library

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoverArtCacheTest {

    private fun bmp() = ImageBitmap(width = 1, height = 1)

    @Test
    fun putThenGetReturnsArt() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        assertNotNull(cache["/a.mp3"])
        assertEquals(1, cache.size)
    }

    @Test
    fun unknownPathIsNull() {
        assertNull(CoverArtCache()["/nope.mp3"])
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCap() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        cache.put("/b.mp3", bmp())
        cache.put("/c.mp3", bmp())
        assertNull(cache["/a.mp3"], "oldest entry must be evicted")
        assertNotNull(cache["/b.mp3"])
        assertNotNull(cache["/c.mp3"])
        assertEquals(2, cache.size)
    }

    @Test
    fun readingAnEntryRefreshesItsRecency() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        cache.put("/b.mp3", bmp())
        assertNotNull(cache["/a.mp3"])   // refresh /a
        cache.put("/c.mp3", bmp())       // evicts /b, not /a
        assertNotNull(cache["/a.mp3"])
        assertNull(cache["/b.mp3"])
    }
}
