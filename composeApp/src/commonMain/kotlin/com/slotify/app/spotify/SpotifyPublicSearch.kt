// Written fresh for Slotify — public playlist discovery without Spotify Web API.
//
// api.spotify.com search is unavailable here: free/dev tokens hit 403/429,
// and anonymous embed tokens burn quota almost immediately. We discover
// public `open.spotify.com/playlist/…` URLs via DuckDuckGo’s HTML endpoint
// (no JS), then import tracks with the existing embed-page path.

package com.slotify.app.spotify

import com.slotify.app.net.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/** One public playlist hit from web search (id + display title). */
data class PublicPlaylistHit(
    val id: String,
    val title: String,
    val url: String,
) {
    val spotifyUrl: String get() = "https://open.spotify.com/playlist/$id"
}

/**
 * Search public Spotify playlists by free-text query (artist, genre, …).
 * Metadata only — never audio.
 */
class SpotifyPublicSearch(
    private val httpClient: HttpClient = createHttpClient(),
) {
    /**
     * Query DuckDuckGo HTML for `site:open.spotify.com/playlist <query>`
     * and return unique playlist hits (title from the result anchor).
     *
     * Throws on HTTP failure; returns empty list when nothing matches.
     */
    suspend fun searchPlaylists(query: String, limit: Int = 12): List<PublicPlaylistHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val response = httpClient.get("https://html.duckduckgo.com/html/") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            parameter("q", "site:open.spotify.com/playlist $q")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Search failed: HTTP ${response.status.value}")
        }
        return parseDuckDuckGoPlaylistHits(body, limit)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
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
        val bare = Regex("""https?://open\.spotify\.com/playlist/([A-Za-z0-9]{22})""")
        for (m in bare.findAll(html)) {
            if (out.size >= limit) break
            val id = m.groupValues[1]
            if (!seen.add(id)) continue
            out += PublicPlaylistHit(id, "Playlist $id", "https://open.spotify.com/playlist/$id")
        }
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
