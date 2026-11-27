// Written fresh for Slotify — Link file same-folder sibling fill.

package com.slotify.app.match

import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiblingLinkMatchTest {

    private fun remote(name: String, vararg artists: String) = RemoteTrack(
        name = name,
        artists = artists.map { RemoteArtist(name = it) },
    )

    private fun local(title: String?, artist: String?, path: String) = LocalTrack(
        filePath = path,
        title = title,
        artist = artist,
    )

    // ── siblingMatchConfidence gates ─────────────────────────────────

    @Test
    fun sameArtistAloneIsNotEnough() {
        // Artist matches, title does not — must not sibling-match.
        val conf = Matcher.siblingMatchConfidence(
            remote = remote("Blue Moon", "Billie Holiday"),
            local = local("Some Other Song", "Billie Holiday", "/m/01 - Some Other Song.mp3"),
        )
        assertNull(conf, "artist-only must be rejected for sibling fill")
    }

    @Test
    fun titleWordsPlusArtistMatch() {
        // Only 1–2 title words shared — still enough when artist agrees.
        val conf = Matcher.siblingMatchConfidence(
            remote = remote("The Girl from Ipanema", "Stan Getz"),
            local = local("Girl from Ipanema", "Stan Getz", "/m/girl.opus"),
        )
        assertNotNull(conf)
        assertTrue(conf >= Matcher.SIBLING_THRESHOLD, "got $conf")
    }

    @Test
    fun filenameTitleCountsWhenTagsAreEmpty() {
        val conf = Matcher.siblingMatchConfidence(
            remote = remote("Peace Somehow", "Avi Kaplan"),
            local = local(
                title = null,
                artist = null,
                path = "/album/01 - Peace Somehow.opus",
            ),
        )
        assertNotNull(conf, "filename-parsed title+artist should pass")
    }

    @Test
    fun wrongArtistWithSharedTitleWordFails() {
        val conf = Matcher.siblingMatchConfidence(
            remote = remote("Hello", "Adele"),
            local = local("Hello", "Lionel Richie", "/m/hello.mp3"),
        )
        assertNull(conf, "shared title alone must not override a wrong artist")
    }

    @Test
    fun unknownLocalArtistStillMatchesOnTitle() {
        val conf = Matcher.siblingMatchConfidence(
            remote = remote("Georgia On My Mind", "Ray Charles"),
            local = local("Georgia On My Mind", artist = null, "/m/g.opus"),
        )
        assertNotNull(conf)
    }

    // ── assignSiblingLinks ───────────────────────────────────────────

    @Test
    fun assignsOnlyProvidedOpenTracks() {
        // Caller filters unmatched rows — assignment never invents targets.
        val open = listOf(remote("Blue Moon", "Billie Holiday"))
        val siblings = listOf(
            local("Blue Moon", "Billie Holiday", "/album/01 - Blue Moon.mp3"),
            local("Summertime", "Ella Fitzgerald", "/album/02 - Summertime.mp3"),
        )
        val pins = Matcher.assignSiblingLinks(open, siblings)
        assertEquals(1, pins.size)
        assertEquals("/album/01 - Blue Moon.mp3", pins[Matcher.trackKey(open[0])])
    }

    @Test
    fun exclusiveOneFileOneTrack() {
        val a = remote("Blue Moon", "Billie Holiday")
        val b = remote("Blue Moon (Reprise)", "Billie Holiday")
        val file = local("Blue Moon", "Billie Holiday", "/album/bm.mp3")
        val pins = Matcher.assignSiblingLinks(listOf(a, b), listOf(file))
        assertEquals(1, pins.size, "one file must pin only one row")
    }

    @Test
    fun emptyOpenListYieldsNoPins() {
        val pins = Matcher.assignSiblingLinks(
            openRemotes = emptyList(),
            siblingLocals = listOf(local("Blue Moon", "Billie Holiday", "/x.mp3")),
        )
        assertTrue(pins.isEmpty())
    }

    @Test
    fun noReasonablePairYieldsNoPins() {
        val pins = Matcher.assignSiblingLinks(
            openRemotes = listOf(remote("Blue Moon", "Billie Holiday")),
            siblingLocals = listOf(local("Completely Different", "Nobody", "/x.mp3")),
        )
        assertTrue(pins.isEmpty())
    }
}
