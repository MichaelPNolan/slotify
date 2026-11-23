package com.slotify.app.youtube

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.slotify.app.data.androidAppContext
import com.slotify.app.library.AUDIO_EXTENSIONS
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android YouTube source: real yt-dlp inside Chaquopy (`yt-dlp-android`).
 *
 * Desktop keeps the system `yt-dlp` binary — this file is the Android fallback only.
 *
 * Search/download go through a small Python helper (`slotify_ytdlp.py`) because the
 * library's stock runner omits `--dump-json` / `--flat-playlist` / full format control.
 * Audio is downloaded as a stream (251/140/bestaudio), then remuxed with ffmpeg-kit
 * into a whitelist extension — **copy, never re-encode**.
 */

@Volatile
private var youtubeRuntimeReady = false

@Volatile
private var youtubeRuntimeFailed = false

/** Start Chaquopy + bundled yt-dlp. Safe to call more than once; no-op on failure. */
fun initYoutubeRuntime(context: Context?) {
    if (youtubeRuntimeReady || youtubeRuntimeFailed) return
    synchronized(Unit) {
        if (youtubeRuntimeReady || youtubeRuntimeFailed) return
        val ctx = context?.applicationContext ?: return
        try {
            YtDlp.init(ctx)
            youtubeRuntimeReady = true
        } catch (e: Throwable) {
            youtubeRuntimeFailed = true
            android.util.Log.e("SlotifyYtDlp", "YtDlp.init failed", e)
        }
    }
}

actual fun createYoutubeSource(): YoutubeSource = ChaquopyYoutubeSource

object ChaquopyYoutubeSource : YoutubeSource {

    override val isSupported: Boolean
        get() {
            if (youtubeRuntimeReady) return true
            if (youtubeRuntimeFailed) return false
            initYoutubeRuntime(androidAppContext)
            return youtubeRuntimeReady
        }

    override suspend fun search(query: String, max: Int): List<YoutubeSearchResult> =
        withContext(Dispatchers.IO) {
            require(isSupported) { "yt-dlp runtime not available" }
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val mod = loadHelper()
            val json = mod.callAttr("search", q, max).toString()
            parseSearchResults(json)
        }

    override suspend fun download(
        result: YoutubeSearchResult,
        targetDir: String,
        onProgress: (String) -> Unit,
        baseName: String?,
    ): String = withContext(Dispatchers.IO) {
        require(isSupported) { "yt-dlp runtime not available" }
        val dir = File(targetDir)
        dir.mkdirs()

        onProgress("Starting download…")
        val name = baseName?.takeIf { it.isNotBlank() } ?: preferredBaseName(result)
        val mod = loadHelper()

        val progress = dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback { pct, eta, _ ->
            val rounded = pct.toInt()
            if (rounded >= 0) {
                onProgress(
                    if (eta > 0) "Downloading… $rounded% (ETA ${eta}s)"
                    else "Downloading… $rounded%",
                )
            }
        }

        val rawPath = mod.callAttr(
            "download",
            result.url,
            dir.absolutePath,
            name,
            progress,
        ).toString()
        val raw = File(rawPath)
        if (!raw.isFile) {
            throw IllegalStateException("yt-dlp finished but produced no file in $targetDir")
        }

        onProgress("Remuxing…")
        val saved = remuxToWhitelisted(raw)
        if (saved.extension.lowercase() !in AUDIO_EXTENSIONS) {
            throw IllegalStateException(
                "Downloaded audio extension .${saved.extension} is not in the Slotify whitelist",
            )
        }
        onProgress("Saved ${saved.name}")
        saved.absolutePath
    }

    // ── Python helper (written once into app filesDir) ───────────────

    @Volatile
    private var helper: PyObject? = null

    private fun loadHelper(): PyObject {
        helper?.let { return it }
        val ctx = androidAppContext
            ?: error("Android context not ready — call initYoutubeRuntime first")
        val py = Python.getInstance()
        val dir = File(ctx.filesDir, "py").apply { mkdirs() }
        val file = File(dir, "slotify_ytdlp.py")
        val source = HELPER_PY
        if (!file.exists() || file.readText() != source) {
            file.writeText(source)
            // Drop a stale import so the next getModule sees the new file.
            val modules = py.getModule("sys").get("modules")!!
            if (modules.containsKey("slotify_ytdlp")) {
                runCatching {
                    py.getModule("importlib").callAttr(
                        "reload",
                        py.getModule("slotify_ytdlp"),
                    )
                }
            }
        }
        val path = py.getModule("sys").get("path")!!
        val entries = path.toJava(List::class.java)
        val dirPath = dir.absolutePath
        if (entries.none { it.toString() == dirPath }) {
            path.callAttr("insert", 0, dirPath)
        }
        val mod = py.getModule("slotify_ytdlp")
        helper = mod
        return mod
    }

    // ── Remux → whitelist extension (stream copy only) ────────────────

    private fun remuxToWhitelisted(src: File): File {
        val ext = src.extension.lowercase()
        if (ext in AUDIO_EXTENSIONS) return src

        return when (ext) {
            // YouTube audio-only 251 → webm/opus → .opus (copy)
            "webm", "ogg", "mkv" -> {
                val dest = File(src.parentFile, src.nameWithoutExtension + ".opus")
                runFfmpegCopy(src, dest, container = "opus")
            }
            // 140 → m4a/aac → .mp4 (AAC in MP4, copy; .m4a is not whitelisted)
            "m4a" -> {
                val dest = File(src.parentFile, src.nameWithoutExtension + ".mp4")
                runFfmpegCopy(src, dest, container = null)
            }
            else -> src
        }
    }

    private fun runFfmpegCopy(src: File, dest: File, container: String?): File {
        if (dest.exists()) dest.delete()
        val args = buildList {
            add("-y")
            add("-i"); add(src.absolutePath)
            add("-c:a"); add("copy")
            if (container != null) {
                add("-f"); add(container)
            }
            if (container == null) {
                add("-movflags"); add("+faststart")
            }
            add(dest.absolutePath)
        }
        // FFmpegKit.execute takes a single command string (not varargs).
        val command = args.joinToString(" ") { a ->
            if (a.isEmpty() || a.any { it.isWhitespace() }) "\"$a\"" else a
        }
        val session = FFmpegKit.execute(command)
        val code = session.returnCode
        if (!ReturnCode.isSuccess(code) || !dest.isFile || dest.length() == 0L) {
            val log = session.allLogsAsString?.takeLast(400).orEmpty()
            src.delete()
            dest.delete()
            throw IllegalStateException(
                "ffmpeg remux failed (code=$code) for ${src.name}:\n$log",
            )
        }
        src.delete()
        return dest
    }

    // ── Naming / JSON ─────────────────────────────────────────────────

    private fun preferredBaseName(result: YoutubeSearchResult): String {
        val title = sanitizeFileComponent(result.title)
        val channel = result.channel?.takeIf { it.isNotBlank() }?.let(::sanitizeFileComponent)
        val artist = channel?.takeIf {
            !it.contains(" - Topic", ignoreCase = true) &&
                !it.contains("Official", ignoreCase = true) &&
                !it.equals(title, ignoreCase = true)
        }
        val base = when {
            artist != null && !title.contains(artist, ignoreCase = true) -> "$artist - $title"
            else -> title
        }
        return base.take(180).trim().ifBlank { result.videoId }
    }

    private fun sanitizeFileComponent(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[/\\?%*:|"<>\x00-\x1f]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return cleaned.ifBlank { "unknown" }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Hit(
        val id: String,
        val title: String? = null,
        val duration: Double? = null,
        val channel: String? = null,
    )

    private fun parseSearchResults(body: String): List<YoutubeSearchResult> =
        runCatching {
            json.decodeFromString<List<Hit>>(body)
        }.getOrDefault(emptyList()).mapNotNull { h ->
            if (h.id.isBlank() || h.title.isNullOrBlank()) null
            else YoutubeSearchResult(
                videoId = h.id,
                title = h.title,
                durationSec = h.duration?.toLong(),
                channel = h.channel,
            )
        }
}

/**
 * Loaded into Chaquopy after `YtDlp.init` — full yt_dlp API (stock runner is flag-limited).
 * Download uses stream formats only; remux to whitelist ext happens in Kotlin.
 */
private val HELPER_PY: String = """
import glob
import json
import os


def search(query, max_n=8):
    import yt_dlp
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "noplaylist": True,
        "playlistend": int(max_n),
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info("ytsearch%d:%s" % (int(max_n), str(query)), download=False)
    entries = (info or {}).get("entries") or []
    out = []
    for e in entries:
        if not e:
            continue
        out.append({
            "id": e.get("id") or "",
            "title": e.get("title") or "",
            "duration": e.get("duration"),
            "channel": e.get("channel") or e.get("uploader"),
        })
    return json.dumps(out)


def download(url, out_dir, base_name, progress_cb=None):
    import yt_dlp
    os.makedirs(out_dir, exist_ok=True)
    outtmpl = os.path.join(out_dir, base_name + ".%(ext)s")
    opts = {
        # Prefer opus (251), then AAC (140) — stream download, no transcode here.
        "format": "251/140/bestaudio",
        "outtmpl": outtmpl,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "retries": 3,
    }
    if progress_cb is not None:
        def hook(d):
            if d.get("status") != "downloading":
                return
            try:
                pct_s = d.get("_percent_str") or "0%"
                pct = float(str(pct_s).replace("%", "").strip() or 0)
                eta = int(d.get("eta") or 0)
                line = str(d.get("_default_template") or "")
                progress_cb.onProgressUpdate(float(pct), int(eta), line)
            except Exception:
                pass
        opts["progress_hooks"] = [hook]

    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download([url])

    pattern = os.path.join(out_dir, base_name + ".*")
    matches = [
        p for p in glob.glob(pattern)
        if os.path.isfile(p) and not p.endswith(".part")
    ]
    if not matches:
        raise RuntimeError("yt-dlp produced no file for %s" % base_name)

    def score(p):
        ext = p.rsplit(".", 1)[-1].lower()
        order = {"opus": 5, "m4a": 4, "mp3": 4, "mp4": 3, "webm": 2, "ogg": 2}
        return order.get(ext, 1)

    matches.sort(key=score, reverse=True)
    return matches[0]
""".trimIndent()
