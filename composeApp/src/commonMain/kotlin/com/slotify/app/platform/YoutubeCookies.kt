package com.slotify.app.platform

/**
 * YouTube **Netscape `cookies.txt`** for yt-dlp (Android primary; desktop optional).
 *
 * This is **not** the Spotify Floorp `sp_dc` browser-cookie path (`BrowserSession`) —
 * different product, different file, different privacy story.
 *
 * The private copy lives under app storage (`filesDir/secrets/` on Android) so the
 * embedded Python runtime can read a normal filesystem path. Never log contents.
 */
expect fun pickYoutubeCookiesFile(): String?

/** Result of importing a picked cookies file into private storage. */
sealed class YoutubeCookiesImport {
    data class Ok(val byteCount: Long) : YoutubeCookiesImport()
    data class Failed(val reason: String) : YoutubeCookiesImport()
    object Cancelled : YoutubeCookiesImport()
}

/**
 * Copy [source] (SAF `content://` or absolute path) into the private cookies store.
 * Replaces any previous file. Fails on empty/non-Netscape-looking content.
 */
expect fun importYoutubeCookies(source: String): YoutubeCookiesImport

/** Delete the private cookies file if present. Safe when none exists. */
expect fun clearYoutubeCookies()

/** Absolute path of the private cookies file when configured and readable; else null. */
expect fun youtubeCookiesPathOrNull(): String?

/** True when a private cookies file exists (status only — never show contents). */
fun youtubeCookiesConfigured(): Boolean = youtubeCookiesPathOrNull() != null

/**
 * Human status for Settings — path/size only, never cookie values.
 */
fun youtubeCookiesStatusLabel(): String {
    val path = youtubeCookiesPathOrNull() ?: return "Not configured"
    val bytes = runCatching { java.io.File(path).length() }.getOrDefault(0L)
    return "Configured · ${bytes} bytes (contents hidden)"
}

/**
 * True when [body] looks like a Netscape/Mozilla cookies.txt export.
 * Heuristic only — rejects HTML login pages and empty files.
 */
fun looksLikeNetscapeCookies(body: String): Boolean {
    val t = body.trim()
    if (t.isEmpty()) return false
    // Common export headers / first-party markers.
    if (t.startsWith("# Netscape HTTP Cookie File") ||
        t.startsWith("# HTTP Cookie File") ||
        t.contains("#HttpOnly_") ||
        t.startsWith(".youtube.com") ||
        t.startsWith(".google.com")
    ) {
        return true
    }
    // Tab-separated domain lines without a header (some exporters strip comments).
    val lines = t.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.take(5)
    return lines.any { line ->
        val parts = line.split('\t')
        parts.size >= 7 && parts[0].contains('.')
    }
}

/**
 * User-facing YouTube auth failure (bot check). [hasCookies] = private file present.
 * Does not include cookie contents or the private path.
 */
fun youtubeAuthFailureMessage(hasCookies: Boolean): String = when {
    !hasCookies ->
        "YouTube requires sign-in (“not a bot”). " +
            "Import a Netscape cookies.txt in Settings → YouTube cookies, then retry."
    else ->
        "YouTube rejected the session cookies (expired or invalid?). " +
            "Export a fresh cookies.txt from your browser and import it again in Settings → YouTube cookies."
}

/** True when [chain] is the classic YouTube bot-check / sign-in challenge. */
fun isYoutubeAuthFailure(chain: String): Boolean {
    val c = chain
    return c.contains("Sign in to confirm", ignoreCase = true) ||
        c.contains("not a bot", ignoreCase = true) ||
        c.contains("confirm you're not a bot", ignoreCase = true) ||
        c.contains("confirm you are not a bot", ignoreCase = true) ||
        // yt-dlp sometimes wraps as LoginRequired / Sign-in required
        c.contains("Use --cookies", ignoreCase = true)
}
