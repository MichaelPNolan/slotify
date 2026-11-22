// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/SpotifyClient.kt
// Adaptations for Slotify:
//  - Metadata-only surface: kept the playlist-import + /v1/me methods
//    (getUserPlaylists, getPlaylistTracks, getPlaylist, getCurrentUser),
//    the RateLimitGate integration and the Sp* playlist DTOs. Dropped search,
//    playback-control, library and playlist-WRITE methods (Slotify never
//    mutates Spotify — metadata only, never audio).
//  - 401 handling inlined here (Parachord installs a global Ktor
//    OAuthRefreshPlugin; Slotify's client receives the refresher directly and
//    applies the same two-strikes logic: refresh + retry once, then
//    invalidate + [ReauthRequiredException]).
//  - AuthCredential is Slotify's flat @Serializable type, so applyAuth reads
//    `.accessToken` directly instead of casting to BearerToken.
//  - SpPlaylistTrackItem gained `is_local`, SpTrack gained
//    uri/track_number/disc_number/explicit for RemoteTrack mapping.

package com.slotify.app.spotify

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thrown when Spotify responds with HTTP 429 (or when the [RateLimitGate]
 * short-circuits during an active cooldown). Carries the `Retry-After`
 * header when Spotify sent one so callers can size their cooldown.
 */
class SpotifyRateLimitedException(val retryAfterSeconds: Long? = null) : Exception(
    "Spotify returned HTTP 429" +
        (retryAfterSeconds?.let { " (Retry-After: ${it}s)" } ?: "")
)

/** A non-2xx Web API response, carrying Spotify's own error message when present. */
class SpotifyApiException(message: String) : Exception(message)

/**
 * Extract the human-readable message from a Spotify error body: their error
 * shape is either JSON `{"error":{"status":…,"message":"…"}}` or plain text
 * (the development-mode premium gate returns plain text with no content-type).
 */
internal fun spotifyErrorMessage(bodyText: String): String {
    val trimmed = bodyText.trim()
    if (trimmed.isEmpty()) return "(empty response body)"
    val jsonMessage = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(trimmed)?.groupValues?.get(1)
    return (jsonMessage ?: trimmed).take(400)
}

/**
 * Spotify Web API client (metadata only). Cross-platform (commonMain).
 *
 * Auth: per-request `Authorization: Bearer <access_token>` resolved via
 * [AuthTokenProvider.tokenFor] for [AuthRealm.Spotify].
 *
 * 401 handling (Parachord's OAuthRefreshPlugin pattern, inlined): on 401 the
 * client single-flights a token refresh via [OAuthTokenRefresher], retries
 * once with the new bearer, and invalidates the credential + throws
 * [ReauthRequiredException] on two-strikes failure or when no refresh grant
 * is available (cookie credentials never refresh).
 *
 * Every GET routes through a shared [RateLimitGate] — Spotify's 429s are
 * account-wide, so one gate covers all endpoints. See [RateLimitGate] for the
 * escalation / proactive-window design notes.
 */
class SpotifyClient(
    private val httpClient: HttpClient,
    private val tokens: AuthTokenProvider,
    /**
     * Read/write the persisted cooldown epoch-ms across process restarts.
     * Pass null on tests / surfaces where persistence isn't desired.
     */
    loadCooldownEpochMs: (() -> Long)? = null,
    saveCooldownEpochMs: (suspend (Long) -> Unit)? = null,
    loadStrikes: (() -> Int)? = null,
    saveStrikes: (suspend (Int) -> Unit)? = null,
    /**
     * Response-time refresher used by the inlined 401 handling. When null,
     * every 401 escalates straight to invalidate + [ReauthRequiredException].
     */
    private val refresher: OAuthTokenRefresher? = null,
) {

    companion object {
        private const val BASE = "https://api.spotify.com"
    }

    /**
     * Per-client rate-limit gate. Covers ALL Spotify GET endpoints below —
     * Spotify's 429s are account-wide, so a single shared gate is the right
     * shape. Escalates its cooldown on consecutive 429s (1h→2h→4h→6h cap) so
     * a persistent abuse ban pushes the local cooldown past Spotify's window
     * and we stop poking; proactive 75-requests/30s cap stays under the
     * Developer-Mode rolling ceiling (~180–200 req/min) with headroom.
     */
    private val gate = RateLimitGate(
        tag = "SpotifyClient",
        loadCooldownEpochMs = loadCooldownEpochMs,
        saveCooldownEpochMs = saveCooldownEpochMs,
        loadStrikes = loadStrikes,
        saveStrikes = saveStrikes,
        escalateOnRepeat = true,
        maxCooldownMs = 6L * 60L * 60L * 1000L,
        maxRequestsPerWindow = 75,
        requestWindowMs = 30_000L,
    )

    /** Milliseconds remaining on the Spotify rate-limit cooldown (0 = clear). */
    fun rateLimitRemainingMs(): Long = gate.remainingCooldownMs()

    /**
     * Apply the Spotify bearer token from [AuthTokenProvider]. If no token is
     * currently stored, the call falls through with no Authorization header —
     * the server returns 401, and the [refresher] flow takes over.
     */
    private suspend fun applyAuth(builder: HttpRequestBuilder) {
        val token = tokens.tokenFor(AuthRealm.Spotify)?.accessToken
        if (token != null) builder.header(HttpHeaders.Authorization, "Bearer $token")
    }

    /**
     * Parachord's two-strikes 401 flow (their OAuthRefreshPlugin), inlined
     * into [gatedGet] so the crossinline configure lambda stays in scope:
     * refresh once (single-flight via the refresher's own mutex), retry the
     * request with the fresh bearer — configure re-reads the token, so the
     * retry automatically picks it up — and on a second 401 (or no refresh
     * grant available, e.g. cookie credentials) invalidate + escalate.
     */

    /**
     * Wraps every GET in the rate-limit gate. Reads response status BEFORE
     * `.body()` deserialization so a 429 surfaces as a typed
     * [SpotifyRateLimitedException] (instead of a `NoTransformationFoundException`
     * from Ktor's body parser trying to deserialize an empty 429 body), then
     * applies the 401 refresh flow. Other non-success responses fall through
     * to `.body()` — Ktor throws, preserving per-call exception handling.
     */
    private suspend inline fun <reified T> gatedGet(
        url: String,
        crossinline configure: suspend HttpRequestBuilder.() -> Unit,
    ): T = gate.withPermit(exceptionFactory = { SpotifyRateLimitedException(it) }) {
        var response: HttpResponse = httpClient.get(url) { configure() }
        gate.handleResponse(response) { SpotifyRateLimitedException(it) }
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = runCatching { refresher?.refresh(AuthRealm.Spotify) }.getOrNull()
            if (refreshed == null) {
                tokens.invalidate(AuthRealm.Spotify)
                throw ReauthRequiredException(AuthRealm.Spotify)
            }
            response = httpClient.get(url) { configure() }
            gate.handleResponse(response) { SpotifyRateLimitedException(it) }
            if (response.status == HttpStatusCode.Unauthorized) {
                tokens.invalidate(AuthRealm.Spotify)
                throw ReauthRequiredException(AuthRealm.Spotify)
            }
        }
        if (!response.status.isSuccess()) {
            // Surface Spotify's own message (e.g. the free-account development-mode
            // 403 "Active premium subscription required for the owner of the app…")
            // instead of a Ktor NoTransformationFoundException from trying to
            // deserialize an empty/non-JSON error body into the success DTO.
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            throw SpotifyApiException(
                "Spotify API ${response.status.value} ${response.status.description}: " +
                    spotifyErrorMessage(bodyText),
            )
        }
        response.body()
    }

    // ── Playlist import (metadata only) ─────────────────────────────────

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): SpPaginatedPlaylists =
        gatedGet("$BASE/v1/me/playlists") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset)
        }

    suspend fun getPlaylistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
        market: String = "from_token",
    ): SpPlaylistTracksResponse =
        gatedGet("$BASE/v1/playlists/$playlistId/tracks") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }

    suspend fun getPlaylist(playlistId: String, fields: String? = null): SpPlaylistFull =
        gatedGet("$BASE/v1/playlists/$playlistId") {
            applyAuth(this); if (fields != null) parameter("fields", fields)
        }

    suspend fun getCurrentUser(): SpUser =
        gatedGet("$BASE/v1/me") { applyAuth(this) }
}

// ── Response Models (ported subset — playlist import + /v1/me) ──────────

@Serializable
data class SpTrack(
    val id: String? = null,
    val name: String? = null,
    val uri: String? = null,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    @SerialName("external_ids") val externalIds: SpExternalIds? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    val explicit: Boolean = false,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

/** Spotify `external_ids` — carries the ISRC (present on full track objects). */
@Serializable
data class SpExternalIds(val isrc: String? = null)

@Serializable
data class SpArtistRef(val id: String? = null, val name: String? = null)

@Serializable
data class SpAlbumRef(
    val id: String? = null, val name: String? = null, val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpImage(val url: String? = null, val height: Int? = null, val width: Int? = null)

fun List<SpImage>?.bestImageUrl(): String? =
    this?.filter { it.url != null }?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: this?.firstOrNull { it.url != null }?.url

@Serializable
data class SpUser(val id: String, @SerialName("display_name") val displayName: String? = null, val country: String? = null)

@Serializable
data class SpPlaylistSimple(
    val id: String? = null, val name: String? = null, val description: String? = null,
    val images: List<SpImage>? = null, val owner: SpUser? = null,
    val collaborative: Boolean = false,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksRef? = null,
)

@Serializable
data class SpPlaylistTracksRef(val total: Int = 0)

@Serializable
data class SpPaginatedPlaylists(
    val items: List<SpPlaylistSimple> = emptyList(), val total: Int = 0,
    val offset: Int = 0, val limit: Int = 50, val next: String? = null,
)

@Serializable
data class SpPlaylistTrackItem(
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
    val track: SpTrack? = null,
)

@Serializable
data class SpPlaylistTracksResponse(
    val items: List<SpPlaylistTrackItem> = emptyList(), val total: Int = 0,
    val offset: Int = 0, val limit: Int = 100, val next: String? = null,
)

@Serializable
data class SpPlaylistFull(
    val id: String? = null, val name: String? = null, val description: String? = null,
    val images: List<SpImage>? = null, val owner: SpUser? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksResponse? = null,
)
