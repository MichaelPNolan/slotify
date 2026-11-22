package com.slotify.app.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live VLC playback test: generates a short MP3 and plays it through vlcj.
 * Skips silently when libvlc cannot be discovered or ffmpeg is unavailable.
 */
class VlcAudioPlayerTest {

    private fun toolAvailable(cmd: String): Boolean = try {
        ProcessBuilder(cmd, "-version").redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor() == 0
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Audible playback is OPT-IN: these tests generate tones and play them
     * through the system speakers. Enable with SLOTIFY_AUDIO_TESTS=1.
     */
    private fun audioTestsEnabled(): Boolean = System.getenv("SLOTIFY_AUDIO_TESTS") == "1"

    @Test
    fun playsPauseSeeksGeneratedMp3() = runBlocking {
        if (!audioTestsEnabled()) return@runBlocking
        if (!toolAvailable("ffmpeg")) return@runBlocking
        if (!NativeDiscovery().discover()) return@runBlocking

        val dir = Files.createTempDirectory("slotify-play").toFile()
        try {
            val file = File(dir, "tone.mp3")
            val rc = ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                file.absolutePath,
            ).redirectErrorStream(true).start().let { p -> p.inputStream.readBytes(); p.waitFor() }
            assertEquals(0, rc)

            val player = createAudioPlayer()
            try {
                player.load(file.absolutePath)

                val playing = withTimeoutOrNull(15_000) {
                    player.snapshot.first { it.isPlaying && it.durationMs > 0 }
                }
                assertNotNull(playing, "never reached playing state: ${player.snapshot.value}")
                assertTrue(playing.durationMs in 3_000..7_000, "duration ~5s, got ${playing.durationMs}")

                delay(1_200)
                assertTrue(player.snapshot.value.positionMs > 300, "position should advance, got ${player.snapshot.value.positionMs}")

                player.pause()
                delay(700)
                assertTrue(!player.snapshot.value.isPlaying, "should be paused")

                player.play()
                delay(500)
                assertTrue(player.snapshot.value.isPlaying, "should resume")

                player.seekTo(3_500)
                delay(700)
                assertTrue(
                    player.snapshot.value.positionMs > 2_500,
                    "seek should move position, got ${player.snapshot.value.positionMs}",
                )
            } finally {
                player.release()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownFileEmitsFailure() = runBlocking {
        if (!NativeDiscovery().discover()) return@runBlocking
        val player = createAudioPlayer()
        try {
            player.load("/nonexistent/definitely-missing.mp3")
            // Assert on snapshot state, not the event flow: VLC's error event can
            // race the collector subscription (SharedFlow has no replay), while
            // the error text persists in the snapshot.
            val errored = withTimeoutOrNull(15_000) {
                player.snapshot.first { it.error != null }
            }
            assertNotNull(errored, "expected player error state for a missing file")
        } finally {
            player.release()
        }
    }
}
