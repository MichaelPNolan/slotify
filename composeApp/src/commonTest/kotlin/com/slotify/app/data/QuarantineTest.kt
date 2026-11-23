package com.slotify.app.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuarantineTest {

    private fun tempDir(): File = File.createTempFile("quarantine", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun file(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun unknownPathIsNotSkipped() {
        val q = Quarantine()
        assertFalse(q.shouldSkip("/nope.opus"))
        assertFalse(q.contains("/nope.opus"))
    }

    @Test
    fun addedFileIsSkippedWhileUnchanged() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(64) { 1 })
        val q = Quarantine()
        q.add(f.path)
        assertTrue(q.contains(f.path))
        assertTrue(q.shouldSkip(f.path))
        assertEquals(1, q.all.size)
    }

    @Test
    fun replacedFileRehabilitatesAndIndexesAgain() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(64) { 1 })
        val q = Quarantine()
        q.add(f.path)
        // A different copy lands at the same path (new size → fingerprint miss).
        f.writeBytes(ByteArray(4096) { 2 })
        assertFalse(q.shouldSkip(f.path), "changed file must re-enter the index")
        assertFalse(q.contains(f.path), "entry must drop once rehabilitated")
    }

    @Test
    fun missingFileStaysSkipped() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(8) { 1 })
        val q = Quarantine()
        q.add(f.path)
        f.delete()
        assertTrue(q.shouldSkip(f.path))
    }

    @Test
    fun explicitRemoveRehabilitates() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(8) { 1 })
        val q = Quarantine()
        q.add(f.path)
        q.remove(f.path)
        assertFalse(q.contains(f.path))
        assertFalse(q.shouldSkip(f.path))
    }

    @Test
    fun symlinkAliasMatchesQuarantinedPath() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(8) { 1 })
        val alias = File(dir, "alias.opus")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), f.toPath())
        val q = Quarantine()
        q.add(f.path)
        assertTrue(q.contains(alias.path), "canonical comparison must see the alias")
        assertTrue(q.shouldSkip(alias.path))
    }

    @Test
    fun initialEntriesAreRestored() {
        val dir = tempDir()
        val f = file(dir, "bad.opus", ByteArray(16) { 3 })
        val saved = QuarantinedFile(f.path, f.lastModified(), f.length())
        val q = Quarantine(listOf(saved))
        assertTrue(q.shouldSkip(f.path))
    }
}
