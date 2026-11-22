package com.slotify.app.library

import kotlin.test.Test
import kotlin.test.assertEquals

class FilenameMetadataTest {

    @Test
    fun artistTitleSplit() {
        assertEquals(
            FilenameParts("Billie Holiday", "Blue Moon"),
            parseFilename("Billie Holiday - Blue Moon.mp3"),
        )
    }

    @Test
    fun leadingTrackNumberStripped() {
        assertEquals(FilenameParts("Billie Holiday", "Blue Moon"), parseFilename("01 - Billie Holiday - Blue Moon.mp3"))
        assertEquals(FilenameParts(null, "Blue Moon"), parseFilename("01 - Blue Moon.mp3"))
        assertEquals(FilenameParts(null, "Blue Moon"), parseFilename("01. Blue Moon.flac"))
    }

    @Test
    fun plainTitle() {
        assertEquals(FilenameParts(null, "Blue Moon"), parseFilename("Blue Moon.mp3"))
    }

    @Test
    fun pathAndMultiDotNames() {
        assertEquals(FilenameParts("Artist", "The Best.Takes"), parseFilename("/music/Artist - The Best.Takes.mp3"))
        assertEquals(FilenameParts(null, "song"), parseFilename("C:\\music\\song.wma"))
    }

    @Test
    fun noArtistWhenNumberOnly() {
        assertEquals(FilenameParts(null, "12"), parseFilename("12.mp3"))
    }
}
