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
}
