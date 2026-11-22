// Written fresh for Slotify.
//
// Spotify's web-player token exchange (`GET open.spotify.com/get_access_token`)
// is CDN-blocked (Fastly "403 URL Blocked") from this network, and the embed
// access token is not accepted by api.spotify.com for free accounts. The public
// embed page for a playlist still SSRs the full track list into
// `__NEXT_DATA__` — metadata only (title/artists/duration/uri), never audio.

package com.slotify.app.spotify

import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.PlaylistSource
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.net.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val embedJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Playlist import via the public embed page. Works without a Spotify login
 * for public playlists; pass the user's `sp_dc` cookie when available so
 * private / follower playlists resolve too.
 */
class SpotifyEmbedSource(
    private val httpClient: HttpClient = createHttpClient(),
) {
    /**
     * Fetch `https://open.spotify.com/embed/playlist/{id}` and map the SSR
     * track list into an [AppPlaylist]. Throws on HTTP failure or when the
     * page has no playlist state (login wall / unknown id).
     */
    suspend fun importPlaylist(
        playlistId: String,
        spDcCookie: String? = null,
        onProgress: (String) -> Unit = {},
    ): AppPlaylist {
        onProgress("Fetching playlist $playlistId…")
        val response = httpClient.get("https://open.spotify.com/embed/playlist/$playlistId") {
            header(HttpHeaders.UserAgent, EMBED_USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            if (!spDcCookie.isNullOrBlank()) {
                header(HttpHeaders.Cookie, "sp_dc=$spDcCookie")
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Embed fetch failed: HTTP ${response.status.value}")
        }
        onProgress("Parsing playlist…")
        val html = extractNextDataHtml(body)
            ?: throw IllegalStateException(
                "No playlist data on embed page (private playlist? try Connect with browser session)",
            )
        return parseEmbedPlaylistJson(html, playlistId)
    }

    companion object {
        // Floorp/Firefox UA — the embed page is a normal web response either way.
        private const val EMBED_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}

/** Pull the `__NEXT_DATA__` JSON blob out of embed HTML. */
internal fun extractNextDataHtml(html: String): String? {
    val startTag = Regex("""<script id="__NEXT_DATA__"[^>]*>""")
        .find(html)?.range?.last?.plus(1)
        ?: return null
    val end = html.indexOf("</script>", startIndex = startTag)
    if (end < 0) return null
    return html.substring(startTag, end).trim().takeIf { it.isNotEmpty() }
}

/**
 * Decode one embed `__NEXT_DATA__` document into an [AppPlaylist].
 * Pure / network-free — unit-tested against a captured fixture.
 */
fun parseEmbedPlaylistJson(nextDataJson: String, expectedId: String? = null): AppPlaylist {
    val root = embedJson.parseToJsonElement(nextDataJson).jsonObject
    val state = root["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
        ?: error("embed page missing pageProps.state")
    val entity = state["data"]?.jsonObject?.get("entity")?.jsonObject
        ?: error("embed page missing data.entity")
    val id = entity.string("id")
        ?: entity.string("uri")?.substringAfterLast(':')
        ?: expectedId
        ?: error("embed entity missing playlist id")
    expectedId?.let { check(it == id) { "embed returned playlist $id, expected $expectedId" } }

    val name = entity.string("title") ?: entity.string("name") ?: id
    val description = entity.string("description")
    val owner = entity.string("subtitle")?.takeIf { it.isNotBlank() }
    val cover = entity["coverArt"]?.jsonObject
        ?.get("sources")
        ?.largestCoverUrl()

    val trackList = entity["trackList"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
    val tracks = trackList.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val kind = obj["entityType"]?.jsonPrimitive?.contentOrNull
        if (kind != null && kind != "track") return@mapNotNull null
        val uri = obj.string("uri")
        val title = obj.string("title") ?: return@mapNotNull null
        if (title.isBlank()) return@mapNotNull null
        val artists = (obj.string("subtitle") ?: "")
            .split(',', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { RemoteArtist(name = it) }
        RemoteTrack(
            id = uri?.substringAfterLast(':'),
            uri = uri,
            name = title,
            artists = artists,
            durationMs = obj["duration"]?.jsonPrimitive?.longOrNull,
            explicit = obj["isExplicit"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    return AppPlaylist(
        remote = RemotePlaylist(
            id = id,
            name = name,
            description = description,
            ownerName = owner,
            coverUrl = cover,
            trackCount = tracks.size,
            tracks = tracks,
        ),
        resolvedTracks = tracks.map { ResolvedPlaylistTrack(remote = it) },
        importedAtEpochMs = currentTimeMillis(),
        source = PlaylistSource.COOKIE,
    )
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** coverArt.sources is a JSON array of {height,width,url}. */
private fun kotlinx.serialization.json.JsonElement.largestCoverUrl(): String? {
    val arr = this as? kotlinx.serialization.json.JsonArray ?: return null
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val url = o.string("url") ?: return@mapNotNull null
        val h = o["height"]?.jsonPrimitive?.longOrNull ?: 0L
        h to url
    }.maxByOrNull { it.first }?.second
}
