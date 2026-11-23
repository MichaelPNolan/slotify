package com.slotify.app.library

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverArtReaderDesktopTest {

    private val reader = createCoverArtReader()

    private fun fixture(name: String): String =
        File(checkNotNull(javaClass.getResource("/art/$name")) { "fixture $name missing" }.toURI()).path

    @Test
    fun opusWithArtReturnsPictureBytes() = runBlocking {
        val bytes = reader.readArt(fixture("cover-art.opus"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8)
    }

    @Test
    fun mp3WithArtReturnsPictureBytes() = runBlocking {
        val bytes = reader.readArt(fixture("cover-art.mp3"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8)
    }

    @Test
    fun artlessFilesReturnNull() = runBlocking {
        assertNull(reader.readArt(fixture("no-art.opus")))
        assertNull(reader.readArt(fixture("no-art.mp3")))
    }

    @Test
    fun missingPathReturnsNull() = runBlocking {
        assertNull(reader.readArt("/definitely/missing/file.mp3"))
    }
}
