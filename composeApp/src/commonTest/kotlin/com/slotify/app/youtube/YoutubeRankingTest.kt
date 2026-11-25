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
        // Artist first, then title — from track metadata only.
        assertEquals("Dave Brubeck Take Five", YoutubeQuery.forTrack(target))
        assertEquals("Solo Track", YoutubeQuery.forTrack(RemoteTrack(name = "Solo Track")))
    }

    @Test
    fun queryNeverUsesPlaylistNameAsArtist() {
        // Simulates “Tai Orathai Mix” playlist with a guest track — the
        // playlist title must not appear in the search query.
        val playlistName = "Tai Orathai Mix"
        val guest = RemoteTrack(
            name = "ฝากใบลา",
            artists = listOf(RemoteArtist(name = "เนย ภัสวรรณ")),
        )
        val q = YoutubeQuery.forTrack(guest)
        assertEquals("เนย ภัสวรรณ ฝากใบลา", q)
        assertTrue(!q.contains(playlistName), "playlist name leaked into query: $q")
        assertTrue(!q.contains("Mix"), "playlist suffix leaked into query: $q")

        val variants = YoutubeQuery.fallbacks(q, guest)
        assertTrue(variants.none { it.contains(playlistName) })
        assertTrue(variants.none { it.contains("Mix") })
        // Artist from the track still appears in at least one variant.
        assertTrue(variants.any { it.contains("เนย ภัสวรรณ") })
    }

    @Test
    fun queryWithoutArtistIsTitleOnly() {
        val noArtist = RemoteTrack(name = "Only Title")
        assertEquals("Only Title", YoutubeQuery.forTrack(noArtist))
    }

    @Test
    fun fallbacksAlwaysIncludeEditedBaseAndAudioVariants() {
        val track = RemoteTrack(
            name = "I'm Your Hoochie Coochie Man",
            artists = listOf(RemoteArtist(name = "Muddy Waters")),
        )
        val variants = YoutubeQuery.fallbacks("I'm Your Hoochie Coochie Man Muddy Waters", track)
        assertTrue(variants.isNotEmpty())
        assertTrue(
            variants.any { it.contains("Hoochie Coochie Man") && it.contains("audio") },
            "should retry with audio suffix, got $variants",
        )
        assertTrue(
            variants.any { !it.contains("'") },
            "should try apostrophe-stripped query, got $variants",
        )
        // Deduped
        assertEquals(variants.toSet().size, variants.size)
    }

    @Test
    fun fallbacksFromUserEditedQueryStillAddHelpfulVariants() {
        val track = RemoteTrack(
            name = "I'm Your Hoochie Coochie Man",
            artists = listOf(RemoteArtist(name = "Muddy Waters")),
        )
        val variants = YoutubeQuery.fallbacks("Hoochie Coochie Man", track)
        assertTrue(variants.first() == "Hoochie Coochie Man")
        assertTrue(variants.any { it == "Hoochie Coochie Man audio" })
        assertTrue(variants.any { it.contains("Muddy Waters") && it.contains("Hoochie") })
    }

    // ── stripNoise ─────────────────────────────────────────────────────

    @Test
    fun stripNoiseDropsRemasterYearAndOfficialButKeepsLive() {
        assertEquals(
            "Take Five",
            YoutubeRanking.stripNoise("Take Five (2011 Remaster) Official Audio"),
        )
        assertEquals(
            "Hallelujah",
            YoutubeRanking.stripNoise("Hallelujah 1984 radio version"),
        )
        // “live” is meaningful — must survive.
        assertEquals(
            "Take Five live",
            YoutubeRanking.stripNoise("Take Five (live) Remastered"),
        )
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
    fun noisyTitleWithDurationStillClearsEightyFive() {
        // Pre-fix: remaster/year/official tokens + duration drag often landed ~0.70–0.84.
        val noisy = YoutubeSearchResult(
            "n",
            "Dave Brubeck Take Five (2011 Remaster) Official Audio",
            durationSec = 325,
            channel = "Craft Recordings",
        )
        val ranked = YoutubeRanking.rank(listOf(noisy), target)
        assertTrue(
            ranked.first().score >= 0.85,
            "noise-stripped exact title should clear 85%, got ${ranked.first().score}",
        )
    }

    @Test
    fun exactTitleWithWeakDurationStillClearsEightyFive() {
        // title exact, duration 60s off → old formula 1.0*0.65+0.2*0.35=0.72
        val drifted = YoutubeSearchResult("d", "Take Five", durationSec = 385)
        val ranked = YoutubeRanking.rank(listOf(drifted), target)
        assertTrue(
            ranked.first().score >= 0.85,
            "exact title floor should clear 85% despite duration drift, got ${ranked.first().score}",
        )
    }

    @Test
    fun artistChannelBeatsSlightlyHigherStrangerChannel() {
        val stranger = YoutubeSearchResult(
            "s",
            "Take Five Dave Brubeck",
            durationSec = 325,
            channel = "Jazz Compilations 24/7",
        )
        val official = YoutubeSearchResult(
            "o",
            "Take Five",
            durationSec = 325,
            channel = "Dave Brubeck",
        )
        val ranked = YoutubeRanking.rank(listOf(stranger, official), target)
        assertEquals("o", ranked.first().result.videoId)
        assertTrue(
            ranked.first().score >= ranked.last().score,
            "artist channel must not rank below a stranger upload",
        )
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
