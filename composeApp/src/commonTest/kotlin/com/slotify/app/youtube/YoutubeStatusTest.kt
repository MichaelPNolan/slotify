package com.slotify.app.youtube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YoutubeStatusTest {

    @Test
    fun batchStoppedDoesNotBlockManualDownload() {
        assertFalse(youtubeStatusBlocksDownload(null))
        assertFalse(youtubeStatusBlocksDownload("Batch stopped"))
        assertFalse(youtubeStatusBlocksDownload("Batch done — 3 fetched"))
        assertFalse(youtubeStatusBlocksDownload("Done — added to playlist"))
        assertFalse(youtubeStatusBlocksDownload("Download failed: oops"))
        assertTrue(youtubeStatusBlocksDownload("Batch 3/10: download “x” (90%)…"))
        assertTrue(youtubeStatusBlocksDownload("Starting download…"))
        assertTrue(youtubeStatusBlocksDownload("Indexing new file…"))
    }

    @Test
    fun batchStoppedIsClearableWhenFindOpens() {
        assertTrue(youtubeStatusClearableWhenIdle("Batch stopped"))
        assertTrue(youtubeStatusClearableWhenIdle("Done — added to library"))
        assertFalse(youtubeStatusClearableWhenIdle("Download failed: oops"))
        assertTrue(youtubeStatusClearableWhenIdle("Batch 3/10: download “x” (90%)…").not())
        assertFalse(youtubeStatusClearableWhenIdle("HTTP 403"))
    }
}
