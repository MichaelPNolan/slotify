package com.slotify.app.loudness

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoudnessStoreTest {

    private fun storeOn(memory: MutableList<String>): LoudnessStore =
        LoudnessStore(
            read = { memory.firstOrNull() },
            write = { s -> memory.clear(); memory.add(s) },
            now = { 1_700_000_000_000L },
        )

    @Test
    fun putTrackRoundTripsWithChip() {
        val mem = mutableListOf<String>()
        storeOn(mem).putTrack("/sdcard/Music/a.opus", LoudnessResult(-18.5, 4.0, -3.2))
        val t = storeOn(mem).track("/sdcard/Music/a.opus")!!
        assertEquals(-18.5, t.integratedLufs, 1e-9)
        assertEquals(4.0, t.loudnessRange, 1e-9)
        assertEquals(-3.2, t.truePeakDbtp, 1e-9)
        assertEquals(loudnessChip(-18.5), t.chip0_99)
        assertEquals(CorrectionMode.RECOMMENDED, t.correction)
        assertEquals(1_700_000_000_000L, t.analysedAt)
    }

    @Test
    fun setCorrectionPersistsModeAndThreshold() {
        val mem = mutableListOf<String>()
        val store = storeOn(mem)
        store.putTrack("/x.mp3", LoudnessResult(-20.0, 3.0, -1.0))
        store.setCorrection("/x.mp3", CorrectionMode.CUSTOM, customGainDb = 5.5, customLimitThresholdDb = -6.0)
        val t = storeOn(mem).track("/x.mp3")!!
        assertEquals(CorrectionMode.CUSTOM, t.correction)
        assertEquals(5.5, t.customGainDb, 1e-9)
        assertEquals(-6.0, t.customLimitThresholdDb, 1e-9)
        // Threshold cannot go above full scale
        store.setCorrection("/x.mp3", CorrectionMode.CUSTOM, 0.0, customLimitThresholdDb = 3.0)
        assertEquals(0.0, storeOn(mem).track("/x.mp3")!!.customLimitThresholdDb, 1e-9)
    }

    @Test
    fun playlistProfileRoundTrips() {
        val mem = mutableListOf<String>()
        storeOn(mem).putPlaylist("pl1", -14.25, 10, 12)
        val p = storeOn(mem).playlist("pl1")!!
        assertEquals(-14.25, p.meanLufs, 1e-9)
        assertEquals(10, p.analysedCount)
        assertEquals(12, p.totalMembers)
    }

    @Test
    fun corruptJsonLoadsEmpty() {
        val store = LoudnessStore(read = { "{not json" }, write = {}, now = { 0 })
        assertEquals(0, store.load().tracks.size)
        assertNull(store.track("/a"))
    }

    /**
     * Chip UI reads resolveSymlinkTarget(path); backfill writes match.local.filePath.
     * If those differ (symlink row), analysis under the link path must still hit the chip.
     */
    @Test
    fun symlinkPathAndResolvedTargetShareOneKey() {
        val mem = mutableListOf<String>()
        val store = storeOn(mem)
        val dir = Files.createTempDirectory("loudness-symlink")
        val target = dir.resolve("real.opus")
        val link = dir.resolve("link.opus")
        Files.write(target, byteArrayOf(1, 2, 3))
        Files.createSymbolicLink(link, target)
        try {
            store.putTrack(link.toString(), LoudnessResult(-16.0, 3.0, -1.0))
            assertNotNull(store.track(link.toString()), "link key miss")
            assertNotNull(store.track(target.toString()), "resolved target key miss")
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dir)
        }
    }
}
