package com.slotify.app.loudness

import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.slotify.app.data.androidAppContext
import java.io.File
import java.io.RandomAccessFile

actual fun supportsLoudnessBalance(): Boolean = Build.VERSION.SDK_INT >= 28

actual fun analyseFileLoudness(path: String): LoudnessResult? = runCatching {
    val src = File(path)
    if (!src.isFile || src.length() == 0L) return null
    val ctx = androidAppContext ?: return null
    val pcm = File(ctx.cacheDir, "loudness_${src.nameWithoutExtension.hashCode()}.f32")
    try {
        if (pcm.exists()) pcm.delete()
        val args = listOf(
            "-y", "-v", "error",
            "-i", src.absolutePath,
            "-map", "a:0",
            "-ac", "2",
            "-f", "f32le",
            pcm.absolutePath,
        )
        val command = args.joinToString(" ") { a ->
            if (a.isEmpty() || a.any { it.isWhitespace() }) "\"$a\"" else a
        }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode) || !pcm.isFile || pcm.length() < 8L) {
            pcm.delete()
            return null
        }
        val sampleRate = probeSampleRate(src.absolutePath) ?: 48000
        val analyser = EbuR128(sampleRate, 2)
        RandomAccessFile(pcm, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                val usable = n - (n % 4)
                if (usable == 0) break
                val floats = FloatArray(usable / 4)
                for (i in floats.indices) {
                    val b0 = buf[i * 4].toInt() and 0xff
                    val b1 = buf[i * 4 + 1].toInt() and 0xff
                    val b2 = buf[i * 4 + 2].toInt() and 0xff
                    val b3 = buf[i * 4 + 3].toInt() and 0xff
                    floats[i] = Float.fromBits(b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
                }
                analyser.processInterleaved(floats, floats.size)
            }
        }
        analyser.finish()
    } finally {
        pcm.delete()
    }
}.getOrNull()

/**
 * Sample rate from ffmpeg `-i` stderr (`NNN Hz`). Default 48000 is fine for
 * LUFS within a fraction of a dB when the parse fails.
 */
private fun probeSampleRate(path: String): Int? {
    val session = FFmpegKit.execute("-i \"${path}\" -f null -")
    val text = session.allLogsAsString ?: return null
    val m = Regex("""(\d+) Hz""").find(text) ?: return null
    return m.groupValues[1].toIntOrNull()
}
