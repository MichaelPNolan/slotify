package com.slotify.app.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end desktop scanner test: generates small MP3s with ffmpeg
 * (present on the dev machine), then reads them back via jaudiotagger.
 * Skips silently when ffmpeg is unavailable.
 */
class JaudiotaggerScannerTest {

    private fun ffmpegAvailable(): Boolean = try {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().let { p ->
            p.waitFor() == 0
        }
    } catch (_: Exception) {
        false
    }

    private fun runFfmpeg(args: List<String>): Int =
        ProcessBuilder(args).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes() // drain
            p.waitFor()
        }

    @Test
    fun readsTagsFromGeneratedMp3() {
        if (!ffmpegAvailable()) return
        val dir = Files.createTempDirectory("slotify-scan").toFile()
        try {
            val tagged = File(dir, "tagged.mp3")
            val rc = runFfmpeg(
                listOf(
                    "ffmpeg", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-metadata", "title=Blue Moon",
                    "-metadata", "artist=Billie Holiday",
                    "-metadata", "album=Billie Holiday Sings",
                    tagged.absolutePath,
                )
            )
            assertEquals(0, rc, "ffmpeg failed to generate fixture")

            val tracks = runBlocking { JaudiotaggerScanner().scan(listOf(dir.absolutePath)) }
            assertEquals(1, tracks.size)
            val t = tracks[0]
            assertEquals("Blue Moon", t.title)
            assertEquals("Billie Holiday", t.artist)
            assertEquals("Billie Holiday Sings", t.album)
            val dur = assertNotNull(t.durationMs)
            assertTrue(dur in 1_000..4_000, "duration ~2s expected, got $dur")
            assertEquals("mp3", t.fileFormat)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToFilenameWhenNoTags() {
        if (!ffmpegAvailable()) return
        val dir = Files.createTempDirectory("slotify-scan2").toFile()
        try {
            val untagged = File(dir, "Ella Fitzgerald - Blue Moon.mp3")
            val rc = runFfmpeg(
                listOf(
                    "ffmpeg", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=220:duration=1",
                    untagged.absolutePath,
                )
            )
            assertEquals(0, rc)

            val tracks = runBlocking { JaudiotaggerScanner().scan(listOf(dir.absolutePath)) }
            assertEquals(1, tracks.size)
            val t = tracks[0]
            // ffmpeg may or may not write empty tags; filename parse is the fallback.
            assertEquals("Blue Moon", t.displayTitle)
            assertEquals("Ella Fitzgerald", t.displayArtist)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresNonAudioFiles() {
        val dir = Files.createTempDirectory("slotify-scan3").toFile()
        try {
            File(dir, "notes.txt").writeText("hello")
            val tracks = runBlocking { JaudiotaggerScanner().scan(listOf(dir.absolutePath)) }
            assertTrue(tracks.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun skipsHiddenDotfilesAndUnlistedExtensions() {
        if (!ffmpegAvailable()) return
        val dir = Files.createTempDirectory("slotify-scan4").toFile()
        try {
            // One legitimate file that must be kept.
            val real = File(dir, "real.mp3")
            assertEquals(0, runFfmpeg(listOf("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", real.absolutePath)))

            // Hidden AppleDouble-style sidecar with an audio extension → skipped.
            File(dir, "._real.mp3").writeBytes(byteArrayOf(0, 1, 2, 3))
            // Hidden directory with audio inside → whole subtree skipped.
            val hiddenDir = File(dir, ".hidden").apply { mkdirs() }
            File(hiddenDir, "secret.mp3").writeBytes(byteArrayOf(0, 1, 2, 3))
            // Extensions outside the approved list → skipped even if valid-looking.
            // (opus IS approved — see AUDIO_EXTENSIONS — so use wma/ogg/m4a here.)
            File(dir, "track.ogg").writeText("not really vorbis")
            File(dir, "track.m4a").writeText("not really alac")
            File(dir, "track.wma").writeText("not really wma")

            val tracks = runBlocking { JaudiotaggerScanner().scan(listOf(dir.absolutePath)) }
            assertEquals(listOf(real.absolutePath), tracks.map { it.filePath })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun approvedExtensionListIsExactlyTheUserSpecifiedSet() {
        // aac/mp3/mp4/flac/wav/aif/aiff per user spec; opus added later for
        // no-re-encode YouTube downloads (user-sanctioned).
        assertEquals(
            setOf("aac", "mp3", "mp4", "flac", "wav", "aif", "aiff", "opus"),
            AUDIO_EXTENSIONS,
        )
    }
}
