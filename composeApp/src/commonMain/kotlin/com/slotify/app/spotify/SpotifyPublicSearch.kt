// Written fresh for Slotify — public playlist discovery without Spotify Web API.
//
// api.spotify.com search is unavailable here: free/dev tokens hit 403/429,
// and anonymous embed tokens burn quota almost immediately. We discover
// public `open.spotify.com/playlist/…` URLs via free HTML search endpoints
// (DuckDuckGo primary, Bing fallback), then import with the embed-page path.

package com.slotify.app.spotify

import com.slotify.app.net.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout

/** One public playlist hit from web search (id + display title). */
data class PublicPlaylistHit(
    val id: String,
    val title: String,
    val url: String,
) {
    val spotifyUrl: String get() = "https://open.spotify.com/playlist/$id"
}

/**
 * Outcome of [SpotifyPublicSearch.searchPlaylists].
 * [engineNotes] always records what each endpoint did — empty hits vs HTTP
 * failure vs likely block — so the UI can say *where* search stopped.
 */
data class PublicSearchOutcome(
    val hits: List<PublicPlaylistHit>,
    val engineNotes: List<String>,
    /** Endpoints that returned HTTP success (even if HTML had no playlist links). */
    val enginesOk: Int,
    /** Endpoints that threw (timeout, HTTP error, …). */
    val enginesFailed: Int,
) {
    val allFailed: Boolean get() = enginesFailed > 0 && enginesOk == 0 && hits.isEmpty()
}

/**
 * Search public Spotify playlists by free-text query (artist, genre, …).
 * Metadata only — never audio.
 *
 * Tries DuckDuckGo HTML, then DDG Lite, then Bing — Thai/non-ASCII queries
 * and mobile networks often fail the first endpoint (timeouts / empty).
 */
class SpotifyPublicSearch(
    private val httpClient: HttpClient = createHttpClient(),
) {
    /**
     * Playlist hits for [query] plus per-engine diagnostics.
     * Never throws for “no hits” or “engines blocked” — callers read
     * [PublicSearchOutcome.engineNotes] / [PublicSearchOutcome.allFailed].
     * Only unexpected cancellation propagates.
     */
    suspend fun searchPlaylists(query: String, limit: Int = 20): PublicSearchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return PublicSearchOutcome(emptyList(), emptyList(), 0, 0)
        // Ktor `parameter` percent-encodes UTF-8 — required for Thai etc.
        val siteQuery = "site:open.spotify.com/playlist $q"
        val notes = mutableListOf<String>()
        var ok = 0
        var failed = 0

        for (endpoint in ENDPOINTS) {
            val host = endpoint.host
            try {
                val body = endpoint.fetch(httpClient, siteQuery, q)
                ok++
                val hits = when (endpoint.parser) {
                    Parser.Ddg -> parseDuckDuckGoPlaylistHits(body, limit)
                    Parser.Bing -> parseBingPlaylistHits(body, limit)
                }
                if (hits.isNotEmpty()) {
                    notes += "$host: ${hits.size} hit(s)"
                    // Keep earlier failures in the note trail for diagnostics.
                    return PublicSearchOutcome(hits, notes, ok, failed)
                }
                notes += "$host: 0 hits${blockHint(body)}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed++
                val msg = e.message ?: e::class.simpleName ?: "error"
                notes += "$host: $msg"
            }
        }
        return PublicSearchOutcome(emptyList(), notes, ok, failed)
    }

    /** Heuristic: engines that CAPTCHA / anomaly-page us return HTML with no SERP. */
    private fun blockHint(body: String): String {
        val lower = body.lowercase()
        return when {
            "captcha" in lower || "unusual traffic" in lower ||
                "anomaly" in lower || "challenge-platform" in lower ->
                " (blocked/captcha?)"
            body.length < 400 -> " (empty/short page)"
            else -> ""
        }
    }

    private enum class Parser { Ddg, Bing }

    private class Endpoint(
        val url: String,
        val host: String,
        val useSiteQuery: Boolean,
        val parser: Parser,
        val extraParams: Map<String, String> = emptyMap(),
    ) {
        suspend fun fetch(client: HttpClient, siteQuery: String, rawQuery: String): String {
            // Per-endpoint budget: shared client default is 30s; mobile DDG stalls.
            val body = withTimeout(45_000L) {
                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,th;q=0.8")
                    val q = if (useSiteQuery) siteQuery else rawQuery
                    parameter("q", q)
                    extraParams.forEach { (k, v) -> parameter(k, v) }
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value}")
                }
                response.bodyAsText()
            }
            return body
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val ENDPOINTS = listOf(
            Endpoint(
                url = "https://html.duckduckgo.com/html/",
                host = "DDG HTML",
                useSiteQuery = true,
                parser = Parser.Ddg,
            ),
            Endpoint(
                url = "https://lite.duckduckgo.com/lite/",
                host = "DDG Lite",
                useSiteQuery = true,
                parser = Parser.Ddg,
            ),
            Endpoint(
                url = "https://www.bing.com/search",
                host = "Bing",
                useSiteQuery = true,
                parser = Parser.Bing,
                extraParams = mapOf("setlang" to "en", "count" to "20"),
            ),
        )
    }
}

/**
 * Extract unique Spotify playlist hits from DuckDuckGo HTML.
 * Pure — unit-tested. Handles `uddg=` redirect links and absolute hrefs.
 */
fun parseDuckDuckGoPlaylistHits(html: String, limit: Int = 12): List<PublicPlaylistHit> {
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<PublicPlaylistHit>()

    // result__a anchors: href then inner title text (titles often span lines).
    val anchor = Regex(
        pattern = """class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    for (match in anchor.findAll(html)) {
        if (out.size >= limit) break
        val rawHref = match.groupValues[1]
        val title = match.groupValues[2]
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
        val url = resolveDdgHref(rawHref)
        val id = parseSpotifyPlaylistId(url) ?: continue
        if (!seen.add(id)) continue
        out += PublicPlaylistHit(
            id = id,
            title = title.ifBlank { "Playlist $id" },
            url = "https://open.spotify.com/playlist/$id",
        )
    }

    // Fallback: bare playlist URLs if anchors were blocked.
    if (out.isEmpty()) {
        out += collectBarePlaylistHits(html, limit, seen)
    }
    return out
}

/**
 * Extract Spotify playlist hits from Bing SERP HTML (`<a href="https://open.spotify.com/…">`).
 * Pure — unit-tested.
 */
fun parseBingPlaylistHits(html: String, limit: Int = 12): List<PublicPlaylistHit> {
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<PublicPlaylistHit>()
    // Bing wraps results in cite/h2 anchors; bare URL scan is enough for ids.
    val href = Regex(
        pattern = """href="(https?://open\.spotify\.com/playlist/[A-Za-z0-9]{22}[^"]*)\"""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    for (match in href.findAll(html)) {
        if (out.size >= limit) break
        val url = match.groupValues[1].replace("&amp;", "&")
        val id = parseSpotifyPlaylistId(url) ?: continue
        if (!seen.add(id)) continue
        // Prefer nearby title text: take a simple <a …>title</a> around the href if present.
        val title = Regex(
            pattern = """<a[^>]+href="${Regex.escape(url)}"[^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&amp;", "&")
            ?.trim()
            .orEmpty()
            .ifBlank { "Playlist $id" }
        out += PublicPlaylistHit(id, title, "https://open.spotify.com/playlist/$id")
    }
    if (out.isEmpty()) {
        out += collectBarePlaylistHits(html, limit, seen)
    }
    return out
}

private fun collectBarePlaylistHits(
    html: String,
    limit: Int,
    seen: MutableSet<String>,
): List<PublicPlaylistHit> {
    val out = mutableListOf<PublicPlaylistHit>()
    val bare = Regex("""https?://open\.spotify\.com/playlist/([A-Za-z0-9]{22})""")
    for (m in bare.findAll(html)) {
        if (out.size >= limit) break
        val id = m.groupValues[1]
        if (!seen.add(id)) continue
        out += PublicPlaylistHit(id, "Playlist $id", "https://open.spotify.com/playlist/$id")
    }
    return out
}

/** DDG may wrap destinations as `//duckduckgo.com/l/?uddg=<encoded>`. */
internal fun resolveDdgHref(href: String): String {
    val h = href.trim().replace("&amp;", "&")
    if (h.contains("uddg=")) {
        val q = h.substringAfter("uddg=").substringBefore('&')
        return try {
            URLDecoderCompat.decode(q)
        } catch (_: Exception) {
            q
        }
    }
    if (h.startsWith("//")) return "https:$h"
    return h
}

/** URL-decode for DDG redirect targets (JVM commonMain). */
private object URLDecoderCompat {
    fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
