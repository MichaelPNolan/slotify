// Ported/adapted from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — app/.../auth/OAuthManager.kt
// (authorize-URL construction L213–252, token exchange L297–341, refresh
// form L138–151) and shared/api/auth/SpotifyRefreshClassification.kt.
// Adaptations for Slotify: extracted as pure, network-free functions so they
// are unit-testable without ktor-client-mock; Android Custom-Tab / deep-link
// glue replaced by the common `awaitCode` callback contract.

package com.slotify.app.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter

/** Fixed app-wide loopback redirect for the PKCE flow (desktop opens a tiny loopback server here). */
const val SPOTIFY_REDIRECT_URI = "http://127.0.0.1:43821/callback"

/** Spotify scopes Slotify needs — metadata import only. */
const val SPOTIFY_SCOPES = "playlist-read-private playlist-read-collaborative"

const val SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
const val SPOTIFY_COOKIE_TOKEN_URL =
    "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"

/** Shared JSON config — mirrors createHttpClient's ContentNegotiation (ignoreUnknownKeys + lenient). */
internal val spotifyJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Build the Spotify authorize URL for the PKCE flow
 * (RFC 7636 + `state` CSRF protection, ported from OAuthManager.launchSpotifyAuth).
 *
 * @param codeChallenge BASE64URL(SHA-256(verifier)), no padding — see [pkceSha256Base64Url].
 * @param state random per-flow CSRF token; the callback's `state` must match.
 */
fun buildAuthorizeUrl(
    clientId: String,
    codeChallenge: String,
    state: String,
    redirectUri: String = SPOTIFY_REDIRECT_URI,
): String = buildString {
    append(SPOTIFY_AUTHORIZE_URL)
    append("?client_id=").append(clientId.encodeURLParameter())
    append("&response_type=code")
    append("&redirect_uri=").append(redirectUri.encodeURLParameter())
    append("&code_challenge_method=S256")
    append("&code_challenge=").append(codeChallenge.encodeURLParameter())
    append("&scope=").append(SPOTIFY_SCOPES.encodeURLParameter())
    append("&state=").append(state.encodeURLParameter())
}

/** `grant_type=authorization_code` form body for `POST /api/token` (PKCE — no client secret). */
fun authorizationCodeForm(
    clientId: String,
    code: String,
    redirectUri: String,
    codeVerifier: String,
): List<Pair<String, String>> = listOf(
    "grant_type" to "authorization_code",
    "code" to code,
    "redirect_uri" to redirectUri,
    "client_id" to clientId,
    "code_verifier" to codeVerifier,
)

/** `grant_type=refresh_token` form body for `POST /api/token`. */
fun refreshForm(clientId: String, refreshToken: String): List<Pair<String, String>> = listOf(
    "grant_type" to "refresh_token",
    "refresh_token" to refreshToken,
    "client_id" to clientId,
)

/** Convert an encoded form pair list to Ktor [Parameters] for submitForm. */
fun List<Pair<String, String>>.toFormParameters(): Parameters = Parameters.build {
    this@toFormParameters.forEach { (k, v) -> append(k, v) }
}

/** Parsed `POST /api/token` success body. */
@Serializable
data class TokenEndpointResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

/** Decode a token-endpoint success body. Throws on malformed JSON. */
fun parseTokenEndpointSuccess(body: String): TokenEndpointResponse =
    spotifyJson.decodeFromString(body)

/**
 * Decode the `sp_dc` cookie exchange body from
 * `GET open.spotify.com/get_access_token` — `accessToken`,
 * `expirationTimestampMs`, `isAnonymous`.
 *
 * Throws [IllegalStateException] when Spotify reports `isAnonymous: true`
 * (the cookie belongs to a logged-out session) or when `accessToken` is
 * missing — those credentials can never authenticate api.spotify.com.
 */
fun parseCookieTokenResponse(body: String, nowMs: Long = currentTimeMillis()): AuthCredential {
    val obj = spotifyJson.parseToJsonElement(body).jsonObject
    val anonymous = obj["isAnonymous"]?.jsonPrimitive?.booleanOrNull ?: false
    check(!anonymous) { "sp_dc cookie exchange returned an anonymous token — use a logged-in sp_dc value" }
    val accessToken = obj["accessToken"]?.jsonPrimitive?.contentOrNull
        ?: error("sp_dc cookie exchange response missing accessToken")
    val expiresAt = obj["expirationTimestampMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: (nowMs + 3_600_000L)
    return AuthCredential(
        accessToken = accessToken,
        refreshToken = null,
        expiresAtEpochMs = expiresAt,
        clientType = AuthCredential.ClientType.COOKIE,
        clientId = null,
    )
}

/**
 * Should the stored OAuth credential be proactively refreshed before a
 * request? Cookie credentials NEVER refresh (no grant) — they ride until a
 * 401 invalidates them. [skewMs] refreshes slightly before actual expiry so
 * an in-flight request doesn't race the deadline.
 */
fun credentialNeedsRefresh(
    credential: AuthCredential,
    nowMs: Long,
    skewMs: Long = 30_000L,
): Boolean = credential.clientType == AuthCredential.ClientType.OAUTH &&
    credential.expiresAtEpochMs != null &&
    nowMs >= credential.expiresAtEpochMs - skewMs
