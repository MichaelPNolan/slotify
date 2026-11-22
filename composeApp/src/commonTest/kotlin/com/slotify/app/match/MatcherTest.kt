package com.slotify.app.match

import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatcherTest {

    private fun remote(
        name: String,
        vararg artists: String,
        durationMs: Long? = null,
        isrc: String? = null,
    ) = RemoteTrack(
        name = name,
        artists = artists.map { RemoteArtist(name = it) },
        durationMs = durationMs,
        isrc = isrc,
    )

    private fun local(
        title: String,
        artist: String? = null,
        durationMs: Long? = null,
        isrc: String? = null,
        path: String = "/music/${title.hashCode()}.mp3",
    ) = LocalTrack(filePath = path, title = title, artist = artist, durationMs = durationMs, isrc = isrc)

    // ── matchConfidence ──────────────────────────────────────────────

    @Test
    fun exactTitleAndArtistScoresAtLeastPoint95() {
        val conf = Matcher.matchConfidence(
            remote("Wonderwall", "Oasis", durationMs = 258_000),
            local("Wonderwall", "Oasis", durationMs = 257_500),
        )
        assertTrue(conf >= 0.95, "expected >=0.95, got $conf")
    }

    @Test
    fun equalIsrcIsAuthoritative() {
        val conf = Matcher.matchConfidence(
            remote("Anything", "Anyone", isrc = "GBAYE0601498"),
            local("Totally Different Title", "Someone Else", isrc = "gbaye0601498"),
        )
        assertEquals(0.99, conf)
    }

    @Test
    fun remasterSuffixStillMatchesViaContainment() {
        val conf = Matcher.matchConfidence(
            remote("Bohemian Rhapsody - 2011 Remaster", "Queen"),
            local("Bohemian Rhapsody", "Queen"),
        )
        assertTrue(conf >= 0.95, "expected >=0.95, got $conf")
    }

    @Test
    fun leadingTrackNumberInFilenameStillMatches() {
        val conf = Matcher.matchConfidence(
            remote("Blue Moon", "Billie Holiday"),
            local("01 - Blue Moon", "Billie Holiday", path = "/music/01 - Blue Moon.mp3"),
        )
        assertTrue(conf >= 0.95, "expected >=0.95, got $conf")
    }

    @Test
    fun wrongArtistIsRejected() {
        val conf = Matcher.matchConfidence(
            local = local("Mariana", "Some Other Band"),
            remote = remote("Mariana", "Correct Band"),
        )
        assertTrue(conf < Matcher.DEFAULT_THRESHOLD, "expected <${
            Matcher.DEFAULT_THRESHOLD
        }, got $conf")
    }

    @Test
    fun unrelatedTrackIsRejected() {
        val conf = Matcher.matchConfidence(
            remote("Take Five", "Dave Brubeck"),
            local("Summertime", "Ella Fitzgerald"),
        )
        assertTrue(conf < Matcher.DEFAULT_THRESHOLD, "expected rejection, got $conf")
    }

    @Test
    fun unknownLocalArtistStillMatchesOnExactTitle() {
        val conf = Matcher.matchConfidence(
            remote("Georgia On My Mind", "Ray Charles"),
            local("Georgia On My Mind", artist = null),
        )
        assertTrue(conf >= Matcher.DEFAULT_THRESHOLD, "expected >=${Matcher.DEFAULT_THRESHOLD}, got $conf")
    }

    @Test
    fun absurdDurationGapCapsAtPoint80() {
        val conf = Matcher.matchConfidence(
            remote("Stairway", "Someone", durationMs = 180_000),
            local("Stairway", "Someone", durationMs = 600_000),
        )
        assertTrue(conf <= 0.81, "expected cap <=0.80, got $conf")
    }

    @Test
    fun closeDurationAddsSmallBonus() {
        val conf = Matcher.matchConfidence(
            remote("Song", "Artist", durationMs = 100_000),
            local("Song", "Artist", durationMs = 101_500),
        )
        assertTrue(conf >= 0.95, "expected >=0.95, got $conf")
    }

    @Test
    fun reorderedWordsMatchViaTokenOverlap() {
        val conf = Matcher.matchConfidence(
            remote("The Night Has a Thousand Eyes", "Bud Powell"),
            local("Night Thousand Eyes The Has A", "Bud Powell"),
        )
        assertTrue(conf >= Matcher.DEFAULT_THRESHOLD, "expected >=${Matcher.DEFAULT_THRESHOLD}, got $conf")
    }

    @Test
    fun accentedTitlesFoldTogether() {
        val conf = Matcher.matchConfidence(
            remote("Bésame Mucho", "Consuelo Velázquez"),
            local("Besame Mucho", "Consuelo Velazquez"),
        )
        assertTrue(conf >= 0.95, "expected >=0.95, got $conf")
    }

    @Test
    fun wrongTagStillMatchesWhenFilenameAgrees() {
        // Android MediaMetadataRetriever sometimes yields a junk title while the
        // on-disk name is identical to desktop — filename axis must still score.
        val conf = Matcher.matchConfidence(
            remote = remote("Blue Moon", "Billie Holiday"),
            local = local(
                title = "unknown",
                artist = "unknown",
                path = "/music/Billie Holiday - Blue Moon.mp3",
            ),
        )
        assertTrue(conf >= Matcher.DEFAULT_THRESHOLD, "expected >=${Matcher.DEFAULT_THRESHOLD}, got $conf")
    }

    @Test
    fun bestMatchFindsFileWhenTagsAreWrongButNameIsRight() {
        val result = Matcher.bestMatch(
            remote("Blue Moon", "Billie Holiday"),
            locals = listOf(
                local(
                    title = "Track 01",
                    artist = "Various Artists",
                    path = "/music/Billie Holiday - Blue Moon.mp3",
                ),
                local("Totally Different", "Nobody", path = "/other.mp3"),
            ),
        )
        assertNotNull(result)
        assertEquals("/music/Billie Holiday - Blue Moon.mp3", result.local.filePath)
        assertTrue(result.confidence >= Matcher.DEFAULT_THRESHOLD)
    }

    // ── bestMatch ────────────────────────────────────────────────────

    @Test
    fun bestMatchPicksHighestScoringLocal() {
        val result = Matcher.bestMatch(
            remote("Moonlight", "Alice"),
            locals = listOf(
                local("Moonlight Sonata", "Bob", path = "/a.mp3"),
                local("Moonlight", "Alice", path = "/b.mp3"),
                local("Moonlight", "Alice Trio", path = "/c.mp3"),
            ),
        )
        assertNotNull(result)
        assertEquals("/b.mp3", result.local.filePath)
        assertTrue(result.confidence >= 0.95)
    }

    @Test
    fun bestMatchReturnsNullBelowThreshold() {
        val result = Matcher.bestMatch(
            remote("Completely Different Song", "Whomever"),
            locals = listOf(local("Moonlight", "Alice")),
        )
        assertNull(result)
    }

    @Test
    fun bestMatchOnEmptyLibraryReturnsNull() {
        assertNull(Matcher.bestMatch(remote("Anything", "Anyone"), emptyList()))
    }

    // ── matchPlaylist ────────────────────────────────────────────────

    @Test
    fun matchPlaylistMarksMatchedAndUnmatchedRows() {
        val playlist = com.slotify.app.model.RemotePlaylist(
            id = "p1",
            name = "Test",
            tracks = listOf(
                remote("Blue Moon", "Billie Holiday"),
                remote("Nonexistent Song", "Nobody"),
            ),
        )
        val locals = listOf(local("01 - Blue Moon", "Billie Holiday", path = "/bm.mp3"))

        val rows = Matcher.matchPlaylist(playlist, locals)
        assertEquals(2, rows.size)
        assertTrue(rows[0].isMatched)
        assertEquals("/bm.mp3", rows[0].match!!.local.filePath)
        assertTrue(!rows[1].isMatched)
    }

    @Test
    fun matchPlaylistRepeatedRowsMayShareFile() {
        val playlist = com.slotify.app.model.RemotePlaylist(
            id = "p2",
            name = "Dupes",
            tracks = listOf(
                remote("Blue Moon", "Billie Holiday"),
                remote("Blue Moon", "Billie Holiday"),
            ),
        )
        val rows = Matcher.matchPlaylist(playlist, listOf(local("Blue Moon", "Billie Holiday")))
        assertTrue(rows.all { it.isMatched })
    }

    // ── primitives ───────────────────────────────────────────────────

    @Test
    fun levenshteinRatioIsOneForEqualStrings() {
        assertEquals(1.0, Matcher.levenshteinRatio("abc", "abc"))
        assertEquals(0.0, Matcher.levenshteinRatio("abc", "xyz"))
    }

    @Test
    fun jaccardOverlap() {
        assertEquals(1.0, Matcher.jaccard(setOf("a", "b"), setOf("b", "a")))
        assertEquals(0.0, Matcher.jaccard(setOf("a"), setOf("b")))
    }

    @Test
    fun tokenSetSplitsAndFolds() {
        assertEquals(setOf("besame", "mucho"), Matcher.tokenSet("Besame, Mucho!"))
    }
}
