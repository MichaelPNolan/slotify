package com.slotify.app.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpusArtworkTest {

    private fun fixture(name: String): String =
        File(checkNotNull(javaClass.getResource("/art/$name")) { "fixture $name missing" }.toURI()).path

    @Test
    fun opusWithEmbeddedArtReturnsPngBytes() {
        val bytes = readOpusEmbeddedPicture(fixture("cover-art.opus"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8, "expected a real picture block")
        // PNG magic: 89 50 4E 47
        assertTrue(
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte(),
            "expected PNG bytes, got ${bytes.take(4).toList()}",
        )
    }

    @Test
    fun artlessOpusReturnsNull() {
        assertNull(readOpusEmbeddedPicture(fixture("no-art.opus")))
    }

    @Test
    fun nonOpusFileReturnsNull() {
        // Parser must bail on non-Ogg bytes, never throw.
        assertNull(readOpusEmbeddedPicture(fixture("cover-art.mp3")))
    }

    @Test
    fun missingFileReturnsNull() {
        assertNull(readOpusEmbeddedPicture("/definitely/missing/file.opus"))
    }
}
