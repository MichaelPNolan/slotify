package com.slotify.app.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

actual fun createYoutubeSource(): YoutubeSource = YoutubeDlpSource()

/**
 * Desktop implementation over the system `yt-dlp` binary + ffmpeg.
 *
 * Quality policy (user requirement): **stream copy, never re-encode.**
 * `-f 251/140/bestaudio -x --audio-format best` keeps YouTube's delivered
 * codec (opus ~160k preferred) untouched; `--embed-metadata/--embed-thumbnail`
 * write title/artwork tags into the container without touching audio
 * (requires the `mutagen` python module — installed user-site).
 */
class YoutubeDlpSource(
    private val binary: String = "yt-dlp",
) : YoutubeSource {

    @Volatile
    private var toolchainChecked = false

    @Volatile
    private var toolchainAvailable = false

    override val isSupported: Boolean
        get() {
            if (!toolchainChecked) {
                toolchainAvailable = runCatching {
                    ProcessBuilder(binary, "--version")
                        .redirectErrorStream(true)
                        .start()
                        .let { p -> p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0 }
                }.getOrDefault(false)
                toolchainChecked = true
            }
            return toolchainAvailable
        }

    override suspend fun search(query: String, max: Int): List<YoutubeSearchResult> =
        withContext(Dispatchers.IO) {
            require(isSupported) { "yt-dlp not found on this machine" }
            val process = ProcessBuilder(
                binary,
                "--skip-download",
                "--dump-json",
                "--flat-playlist",
                "--playlist-end", max.toString(),
                "--no-warnings",
                "ytsearch$max:$query",
            ).redirectErrorStream(false).start()

            val lines = process.inputStream.bufferedReader().readLines()
            val err = process.errorStream.bufferedReader().readText()
            val exit = waitOrKill(process, 60_000)
            if (exit != 0 && lines.isEmpty()) {
                throw IllegalStateException("yt-dlp search failed (exit $exit): ${err.take(300)}")
            }
            lines.mapNotNull { YoutubeRanking.parseSearchLine(it) }
        }

    override suspend fun download(
        result: YoutubeSearchResult,
        targetDir: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(isSupported) { "yt-dlp not found on this machine" }
        val dir = File(targetDir)
        dir.mkdirs()

        onProgress("Starting download…")
        val process = ProcessBuilder(
            binary,
            "-f", "251/140/bestaudio",
            "-x", "--audio-format", "best", // stream copy — NO re-encode
            "--embed-metadata", "--embed-thumbnail",
            "--newline",
            "--no-warnings",
            "-o", "${dir.absolutePath}/%(title).180s [%(id)s].%(ext)s",
            result.url,
        ).redirectErrorStream(true).start()

        var lastPct = -1
        process.inputStream.bufferedReader().forEachLine { line ->
            val pct = Regex("""\[download]\s+(\d+)\.""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (pct != null && pct != lastPct && pct % 10 == 0) {
                lastPct = pct
                onProgress("Downloading… $pct%")
            }
        }
        val exit = waitOrKill(process, 15 * 60_000)
        if (exit != 0) {
            throw IllegalStateException("yt-dlp download failed (exit $exit)")
        }

        // Locate the produced audio file by its [videoId] tag in the name.
        val audioFile = dir.listFiles()
            ?.filter { f -> f.isFile && f.name.contains("[${result.videoId}]") && f.extension in AUDIO_LIKE }
            ?.maxByOrNull { f -> if (f.extension == "opus") 2 else if (f.extension == "m4a") 1 else 0 }
            ?: throw IllegalStateException("Download finished but no audio file found in $targetDir")

        // Best-effort cleanup of stray thumbnail intermediates from failed embeds.
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.contains("[${result.videoId}]") &&
                f.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg")
            ) {
                runCatching { f.delete() }
            }
        }
        onProgress("Saved ${audioFile.name}")
        audioFile.absolutePath
    }

    private fun waitOrKill(process: Process, timeoutMs: Long): Int {
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    companion object {
        /** Extensions an extracted download may legitimately land as. */
        private val AUDIO_LIKE = setOf("opus", "m4a", "mp3", "webm", "ogg", "mp4")
    }
}
