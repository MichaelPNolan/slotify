package com.slotify.app.state

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinNeedsIndexTest {

    // Regression: pins are stored under one spelling (alias/symlink, e.g.
    // /sdcard/…) while the index holds the canonical path. The raw-only check
    // re-appended the pin on every rematch; the duplicate rows then crashed
    // the Library LazyColumn key uniqueness check.
    @Test
    fun pinBehindSymlinkAliasAlreadyIndexedIsSkipped() {
        val dir = Files.createTempDirectory("slotify-pin-test")
        try {
            val real = Files.createDirectory(dir.resolve("real"))
            val song = Files.write(real.resolve("Song.opus"), byteArrayOf(1))
            val alias = Files.createSymbolicLink(dir.resolve("alias"), real)

            val canonical = song.toFile().canonicalPath
            val aliased = alias.resolve("Song.opus").toString()
            assertFalse(canonical == aliased, "symlink alias must differ from canonical path")

            assertFalse(pinNeedsIndex(aliased, setOf(canonical)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pinPathAlreadyIndexedIsSkipped() {
        val path = "/storage/emulated/0/Music/x.opus"
        assertFalse(pinNeedsIndex(path, setOf(path)))
    }

    @Test
    fun pinMissingFromIndexNeedsIndexing() {
        assertTrue(pinNeedsIndex("/storage/emulated/0/Music/x.opus", emptySet()))
        assertTrue(
            pinNeedsIndex(
                "content://media/external/audio/media/42",
                setOf("/storage/emulated/0/Music/x.opus"),
            ),
        )
    }
}
