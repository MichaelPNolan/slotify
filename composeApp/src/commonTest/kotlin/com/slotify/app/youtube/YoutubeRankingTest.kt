package com.slotify.app.youtube

import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoutubeRankingTest {

    private val target = RemoteTrack(
        name = "Take Five",
        artists = listOf(RemoteArtist(name = "Dave Brubeck")),
        durationMs = 325_000,
    )

    // ── parseSearchLine ──────────────────────────────────────────────

    @Test
    fun parsesFlatSearchLineWithFloatDuration() {
        val line = """{"_type":"url","id":"jvPdM8EBVcE","title":"Take Five","duration":325.0,"channel":"Jazz Archive"}"""
        val r = YoutubeRanking.parseSearchLine(line)
        assertNotNull(r)
        assertEquals("jvPdM8EBVcE", r.videoId)
        assertEquals("Take Five", r.title)
        assertEquals(325L, r.durationSec)
        assertEquals("Jazz Archive", r.channel)
    }

    @Test
    fun parsesUploaderFallbackAndIntDuration() {
        val line = """{"id":"abc123","title":"Blue Moon","duration":180,"uploader":"Oldies Channel"}"""
        val r = YoutubeRanking.parseSearchLine(line)
        assertNotNull(r)
        assertEquals(180L, r.durationSec)
        assertEquals("Oldies Channel", r.channel)
    }

    @Test
    fun rejectsGarbageAndMissingTitle() {
        assertNull(YoutubeRanking.parseSearchLine("WARNING: something"))
        assertNull(YoutubeRanking.parseSearchLine("""{"id":"x"}"""))
        assertNull(YoutubeRanking.parseSearchLine(""))
    }

    // ── YoutubeQuery ─────────────────────────────────────────────────

    @Test
    fun queryUsesTitleAndPrimaryArtist() {
        assertEquals("Take Five Dave Brubeck", YoutubeQuery.forTrack(target))
        assertEquals("Solo Track", YoutubeQuery.forTrack(RemoteTrack(name = "Solo Track")))
    }

    // ── rank ─────────────────────────────────────────────────────────

    @Test
    fun durationAlignedMatchOutranksSameTitleWrongLength() {
        val good = YoutubeSearchResult("good", "Take Five - Dave Brubeck (1959)", durationSec = 326)
        val bad = YoutubeSearchResult("bad", "Take Five - Dave Brubeck (1959)", durationSec = 600)
        val ranked = YoutubeRanking.rank(listOf(bad, good), target)
        assertEquals("good", ranked.first().result.videoId)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun exactMatchScoresHighAndJunkScoresLow() {
        val exact = YoutubeSearchResult("e", "Take Five", durationSec = 325)
        val junk = YoutubeSearchResult("j", "Minecraft speedrun montage", durationSec = 95)
        val ranked = YoutubeRanking.rank(listOf(junk, exact), target)
        assertEquals("e", ranked.first().result.videoId)
        assertTrue(ranked.first().score >= 0.85, "exact+duration should score high, got ${ranked.first().score}")
        assertTrue(ranked.last().score < 0.4, "junk should score low, got ${ranked.last().score}")
    }

    @Test
    fun missingDurationStillRanksByTitle() {
        val noDur = YoutubeSearchResult("nd", "Take Five Dave Brubeck audio")
        val ranked = YoutubeRanking.rank(listOf(noDur), target)
        assertTrue(ranked.first().score > 0.5, "title-only should remain rankable, got ${ranked.first().score}")
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(YoutubeRanking.rank(emptyList(), target).isEmpty())
    }
}
