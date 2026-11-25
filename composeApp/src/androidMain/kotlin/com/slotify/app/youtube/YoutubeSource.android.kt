package com.slotify.app.youtube

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.slotify.app.data.androidAppContext
import com.slotify.app.library.AUDIO_EXTENSIONS
import com.slotify.app.library.buildFlacPictureBlock
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
            // Python returns a JSON *string* — never a list object (Chaquopy
            // cannot convert a raw Python list into a Java List here).
            val raw = runCatching { mod.callAttr("search", q, max) }
                .getOrElse { e -> throw IllegalStateException(searchFailureMessage(q, e), e) }
            // PyObject for a Python str → toString() is the JSON body.
            val json = raw.toString()
            if (json.isBlank()) return@withContext emptyList()
            parseSearchResults(json)
        }

    private fun searchFailureMessage(query: String, e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.joinToString(" → ") {
            it::class.java.simpleName + ": " + (it.message ?: "")
        }
        return "YouTube search failed for “$query”: $cause"
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

        val name = baseName?.takeIf { it.isNotBlank() } ?: preferredBaseName(result)
        // targetDir comes from Settings music root (youtubeDownloadDir) —
        // e.g. /sdcard/Music/downloads — not a hardcoded Slotify path.
        android.util.Log.i("SlotifyYtDlp", "download → $targetDir ($name) url=${result.url}")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException(
                "Cannot create download directory (settings music folder?): $targetDir",
            )
        }
        val probe = File(dir, ".slotify-write-test")
        val canWrite = runCatching {
            probe.writeText("ok"); probe.delete(); true
        }.getOrDefault(false)
        if (!canWrite) {
            throw IllegalStateException(
                "Download directory not writable: $targetDir — " +
                    "grant Slotify “All files access” (the Settings page should have opened), then retry.",
            )
        }
        val mod = loadHelper()

        var startReported = false
        val progress = dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback { pct, eta, line ->
            // yt-dlp hook may pass the real path (with extension) as `line`.
            if (!startReported && !line.isNullOrBlank()) {
                val looksLikePath = line.contains('/') || line.contains('\\') ||
                    Regex("""\.\w{2,5}$""").containsMatchIn(line)
                if (looksLikePath && !line.startsWith("Downloading")) {
                    startReported = true
                    val base = line.substringAfterLast('/').substringAfterLast('\\')
                    onProgress("Starting download of $base…")
                }
            }
            val rounded = pct.toInt()
            if (rounded >= 0) {
                if (!startReported) {
                    startReported = true
                    onProgress("Starting download of $name…")
                }
                onProgress(
                    if (eta > 0) "Downloading… $rounded% (ETA ${eta}s)"
                    else "Downloading… $rounded%",
                )
            }
        }

        val rawPathObj = runCatching {
            mod.callAttr(
                "download",
                result.url,
                dir.absolutePath,
                name,
                progress,
            )
        }.getOrElse { e ->
            throw IllegalStateException(downloadFailureMessage(e), e)
        }
        // Chaquopy: pass a Python str path (never convert lists on this edge).
        val pyStr = Python.getInstance().getModule("builtins").callAttr("str", rawPathObj)
        val rawPath = pyStr.toString()
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
        // Cover art must be injected AFTER the remux — remuxing drops it (the
        // opus muxer rejects the attached-picture stream; see spec follow-up).
        embedCoverArt(saved, dir, name)
        onProgress("Saved ${saved.name}")
        saved.absolutePath
    }

    private fun downloadFailureMessage(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }
            .map { it.message ?: it::class.java.simpleName }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        // Keep HTTP status visible — YouTube 403 is the common Android case.
        val http = Regex("""HTTP Error \d+""").find(chain)?.value
        return when {
            http != null -> "yt-dlp $http — ${chain.take(300)}"
            else -> "yt-dlp download failed: ${chain.take(300)}"
        }
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
            // Stay on Python dict APIs — Chaquopy toJava(List) on sys.path
            // throws TypeError: Cannot convert list object to java.util.list.
            val modules = py.getModule("sys").get("modules")!!
            if (modules.callAttr("__contains__", "slotify_ytdlp").toBoolean()) {
                runCatching {
                    py.getModule("importlib").callAttr(
                        "reload",
                        py.getModule("slotify_ytdlp"),
                    )
                }
            }
        }
        // sys.path membership via Python `in` — no Java List conversion.
        val path = py.getModule("sys").get("path")!!
        val dirPath = dir.absolutePath
        if (!path.callAttr("__contains__", dirPath).toBoolean()) {
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

    // ── Cover art (post-remux inject — the remux itself drops covers) ─

    /**
     * Best-effort: inject the yt-dlp thumbnail into [audio] after remuxing.
     * mp4 gets an attached-picture stream; opus gets a METADATA_BLOCK_PICTURE
     * comment via an ffmetadata sidecar (a CLI `-metadata` arg would exceed
     * Android's argument limits for real cover sizes). Failures never fail
     * the download — the file just keeps no art. The thumbnail file is always
     * removed afterwards.
     */
    private fun embedCoverArt(audio: File, dir: File, baseName: String) {
        val thumb = listOf("jpg", "jpeg", "png", "webp")
            .map { File(dir, "$baseName.$it") }
            .firstOrNull { it.isFile } ?: return
        runCatching {
            when (audio.extension.lowercase()) {
                "mp4" -> injectCoverMp4(audio, thumb)
                "opus" -> injectCoverOpus(audio, thumb, dir)
                else -> Unit
            }
        }.onFailure { e ->
            android.util.Log.w("SlotifyYtDlp", "cover embed failed for ${audio.name}: $e")
        }
        thumb.delete()
    }

    /** mp4 cover: audio + JPEG cover as attached_pic (video is never rendered by the app). */
    private fun injectCoverMp4(audio: File, thumb: File) {
        val tmp = File(audio.parentFile, "${audio.name}.cover.tmp.mp4")
        tmp.delete()
        val ok = runFfmpegCommand(
            listOf(
                "-y",
                "-i", audio.path,
                "-i", thumb.path,
                "-map", "0:a", "-map", "1:v",
                "-c:a", "copy", "-c:v", "mjpeg",
                "-disposition:v:0", "attached_pic",
                tmp.path,
            ),
        )
        if (!ok || !tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("mp4 cover inject failed")
        }
        replaceFile(tmp, audio)
    }

    /** opus cover: JPEG thumb → FLAC picture block → ffmetadata sidecar → inject. */
    private fun injectCoverOpus(audio: File, thumb: File, dir: File) {
        val jpg = File(dir, "${audio.name}.cover.tmp.jpg")
        val meta = File(dir, "${audio.name}.cover.tmp.meta")
        val tmp = File(audio.parentFile, "${audio.name}.cover.tmp.opus")
        try {
            jpg.delete(); meta.delete(); tmp.delete()
            if (!runFfmpegCommand(listOf("-y", "-i", thumb.path, "-c:v", "mjpeg", jpg.path)) || !jpg.isFile) {
                error("thumbnail conversion failed")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpg.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("thumbnail decode failed")
            val block = buildFlacPictureBlock(jpg.readBytes(), "image/jpeg", bounds.outWidth, bounds.outHeight)
            meta.writeText(
                ";FFMETADATA1\nMETADATA_BLOCK_PICTURE=" +
                    Base64.encodeToString(block, Base64.NO_WRAP) + "\n",
            )
            val ok = runFfmpegCommand(
                listOf(
                    "-y",
                    "-f", "ffmetadata", "-i", meta.path,
                    "-i", audio.path,
                    "-map", "1:a", "-map_metadata", "0",
                    "-c", "copy", "-f", "opus",
                    tmp.path,
                ),
            )
            if (!ok || !tmp.isFile || tmp.length() == 0L) error("opus cover inject failed")
            replaceFile(tmp, audio)
        } finally {
            jpg.delete(); meta.delete(); tmp.delete()
        }
    }

    private fun runFfmpegCommand(args: List<String>): Boolean {
        val command = args.joinToString(" ") { a ->
            if (a.isEmpty() || a.any { it.isWhitespace() }) "\"$a\"" else a
        }
        return ReturnCode.isSuccess(FFmpegKit.execute(command).returnCode)
    }

    private fun replaceFile(tmp: File, dest: File) {
        if (tmp.renameTo(dest)) return
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
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

    private fun parseSearchResults(body: String): List<YoutubeSearchResult> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        // Helper may return {"error": "..."} — surface as empty (caller shows note).
        if (trimmed.startsWith("{") && !trimmed.startsWith("[[")) {
            runCatching {
                val obj = json.parseToJsonElement(trimmed)
                if (obj is kotlinx.serialization.json.JsonObject && obj.containsKey("error")) {
                    android.util.Log.w("SlotifyYtDlp", "search error: ${obj["error"]}")
                    return emptyList()
                }
            }
        }
        return runCatching {
            json.decodeFromString<List<Hit>>(trimmed)
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
    # Return a JSON *string* (never a Python list) for Chaquopy callAttr.
    import yt_dlp
    n = max(1, min(int(max_n), 25))
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "noplaylist": True,
        "playlistend": n,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info("ytsearch%d:%s" % (n, str(query)), download=False)
    except Exception as e:
        # Still a JSON string so Kotlin parse path stays uniform.
        return json.dumps({"error": "%s: %s" % (type(e).__name__, e)})
    entries = (info or {}).get("entries") or []
    out = []
    for e in entries:
        if not e:
            continue
        # flat entries may nest under 'url'/'id' only — keep defensive gets.
        vid = e.get("id") or e.get("url") or ""
        if isinstance(vid, str) and "watch?v=" in vid:
            vid = vid.split("watch?v=")[-1].split("&")[0]
        out.append({
            "id": str(vid or ""),
            "title": e.get("title") or "",
            "duration": e.get("duration"),
            "channel": e.get("channel") or e.get("uploader"),
        })
    return json.dumps(out)


def download(url, out_dir, base_name, progress_cb=None):
    # out_dir is an absolute Android path (e.g. /sdcard/Music/Slotify/downloads).
    # yt-dlp only needs normal filesystem write access there — not SAF.
    import yt_dlp
    os.makedirs(out_dir, exist_ok=True)
    if not os.access(out_dir, os.W_OK):
        raise RuntimeError("Not writable: %s (storage permission?)" % out_dir)
    outtmpl = os.path.join(out_dir, base_name + ".%(ext)s")

    # YouTube media URLs 403 without matching player-client / UA on some
    # networks. Try known-good client sets before giving up (no re-encode).
    client_attempts = [
        ["android", "web"],
        ["ios", "tv"],
        ["web_safari", "mweb"],
        [],  # yt-dlp default
    ]
    format_attempts = [
        "251/140/bestaudio/best",
        "bestaudio/best",
    ]
    last_err = None
    for clients in client_attempts:
        for fmt in format_attempts:
            opts = {
                "format": fmt,
                "outtmpl": outtmpl,
                "quiet": True,
                "no_warnings": True,
                "noprogress": True,
                "noplaylist": True,
                # Save the thumbnail beside the audio; Kotlin embeds it AFTER
                # the remux (remuxing strips covers, so embed-here would be lost).
                "writethumbnail": True,
                "retries": 2,
                "fragment_retries": 3,
                # Keep android app from looking like a blank UA.
                "http_headers": {
                    "User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                    "Referer": "https://www.youtube.com/",
                },
            }
            if clients:
                opts["extractor_args"] = {"youtube": {"player_client": clients}}
            if progress_cb is not None:
                def hook(d, _cb=progress_cb):
                    if d.get("status") != "downloading":
                        return
                    try:
                        pct_s = d.get("_percent_str") or "0%"
                        pct = float(str(pct_s).replace("%", "").strip() or 0)
                        eta = int(d.get("eta") or 0)
                        # Prefer real path (includes extension) so UI can show filename.
                        fn = str(d.get("filename") or "")
                        line = fn if fn else str(d.get("_default_template") or "")
                        _cb.onProgressUpdate(float(pct), int(eta), line)
                    except Exception:
                        pass
                opts["progress_hooks"] = [hook]
            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    ydl.download([url])
                last_err = None
                break
            except Exception as e:
                last_err = e
                msg = str(e)
                # Only retry on auth/URL rejection — not on missing-file bugs.
                if "403" not in msg and "401" not in msg and "Forbidden" not in msg:
                    if "HTTP Error" not in msg:
                        raise
                continue
        if last_err is None:
            break
    if last_err is not None:
        raise RuntimeError("HTTP download failed after client retries: %s" % last_err)

    pattern = os.path.join(out_dir, base_name + ".*")
    matches = [
        p for p in glob.glob(pattern)
        if os.path.isfile(p) and not p.endswith(".part")
        and p.rsplit(".", 1)[-1].lower() not in ("jpg", "jpeg", "png", "webp")
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
