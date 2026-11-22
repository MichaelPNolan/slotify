package com.slotify.app.library

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistinctLibraryRootsTest {

    private fun tempDir(name: String): File {
        val base = Files.createTempDirectory("slotify-roots-").toFile()
        val dir = base.resolve(name)
        dir.mkdirs()
        return dir
    }

    @Test
    fun dropsNestedRoot() {
        val music = tempDir("Music")
        val slotify = music.resolve("Slotify").apply { mkdirs() }
        try {
            val out = distinctLibraryRoots(listOf(music.absolutePath, slotify.absolutePath))
            assertEquals(listOf(music.canonicalFile.absolutePath), out)
        } finally {
            music.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun dropsDuplicatePaths() {
        val music = tempDir("Music")
        try {
            val out = distinctLibraryRoots(
                listOf(music.absolutePath, music.absolutePath, music.absolutePath + File.separator),
            )
            assertEquals(1, out.size, out.toString())
        } finally {
            music.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun keepsUnrelatedRoots() {
        val a = tempDir("MusicA")
        val b = tempDir("MusicB")
        try {
            val out = distinctLibraryRoots(listOf(a.absolutePath, b.absolutePath))
            assertEquals(2, out.size, out.toString())
        } finally {
            a.parentFile?.deleteRecursively()
            b.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun emptyAndMissingAreDropped() {
        val out = distinctLibraryRoots(listOf("", "/no/such/dir-xyz-slotify", "   "))
        assertTrue(out.isEmpty(), out.toString())
    }
}
