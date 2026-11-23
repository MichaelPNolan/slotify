package com.slotify.app.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixSongNamesTest {

    @Test
    fun misnamedWhenArtistPrefixMissing() {
        assertTrue(isSongFileNameMisnamed("01 - bohemian", "Queen"))
        assertTrue(isSongFileNameMisnamed("bohemian rhapsody", "Queen"))
        // Prefix is case-insensitive — "queen - Song" satisfies "Queen - ".
        assertFalse(isSongFileNameMisnamed("queen - Song", "Queen"))
        assertFalse(isSongFileNameMisnamed("Queen - Song", "Queen"))
        // Suffixes after the prefix are fine (remasters etc.).
        assertFalse(isSongFileNameMisnamed("Queen - Song (remastered)", "Queen"))
    }

    @Test
    fun misnamedComparesAgainstSanitizedArtist() {
        // Artist tag "AC/DC" can't appear raw in a filename — sanitized to AC_DC.
        assertFalse(isSongFileNameMisnamed("AC_DC - Song", "AC/DC"))
        assertTrue(isSongFileNameMisnamed("01 - Song", "AC/DC"))
    }

    @Test
    fun proposedNameIsArtistDashTitleWithExtension() {
        assertEquals(
            "Queen - Bohemian Rhapsody.mp3",
            proposeSongFileName("Queen", "Bohemian Rhapsody", "mp3"),
        )
        assertEquals(
            "AC_DC - Back in Black.flac",
            proposeSongFileName("AC/DC", "Back in Black", "flac"),
        )
    }

    @Test
    fun proposedNameStripsIllegalCharacters() {
        assertEquals(
            "Artist - What_ is this_.mp3",
            proposeSongFileName("Artist", "What? is this|", "mp3"),
        )
        // No extension → no trailing dot.
        assertEquals("Artist - Song", proposeSongFileName("Artist", "Song", ""))
    }

    @Test
    fun sanitizeTrimsTrailingSpacesAndDots() {
        assertEquals("Name", sanitizeFileNamePart("Name . "))
        assertEquals("A_B", sanitizeFileNamePart("A/B"))
    }

    // ── Conservative detection (no false positives on good names) ───

    @Test
    fun multiArtistTagComparesByPrimaryArtistOnly() {
        // Tag "Queen; David Bowie" must not flag "Queen - Song".
        assertFalse(isSongFileNameMisnamed("Queen - Song", "Queen; David Bowie"))
        assertEquals(
            "Queen - Song.mp3",
            proposeSongFileName("Queen; David Bowie", "Song", "mp3"),
        )
    }

    @Test
    fun alreadyShapedNamesAreLeftAlone() {
        // Song-first / unknown leading text — we can't judge, so don't touch.
        assertFalse(isSongFileNameMisnamed("Piano Man - Billy Joel", "Billy Joel"))
        assertFalse(isSongFileNameMisnamed("The Very Best of Billy Joel - Hits", "Billy Joel"))
        // Artist-first with a no-space dash is still the right shape.
        assertFalse(isSongFileNameMisnamed("Billy Joel-Piano Man", "Billy Joel"))
        // En dash counts as the separator.
        assertFalse(isSongFileNameMisnamed("Queen – Song", "Queen"))
    }

    @Test
    fun onlyNoShapeOrLeadingTrackNumberIsFlagged() {
        // No dash at all → clearly not artist-song.
        assertTrue(isSongFileNameMisnamed("Piano Man", "Billy Joel"))
        assertTrue(isSongFileNameMisnamed("01 Piano Man", "Billy Joel"))
        // Leading track number → confidently not an artist.
        assertTrue(isSongFileNameMisnamed("01 - Piano Man", "Billy Joel"))
    }
}
