// Ported/adapted from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — app/.../auth/OAuthManager.kt
// (Spotify token exchange L297–341, refresh L138–213) + their 401/refresh
// classification.
// Adaptations for Slotify: OkHttp calls replaced with the shared Ktor
// HttpClient; error handling uses classifySpotifyRefresh / ReauthRequired
// patterns instead of Android's session kill-switch.

package com.slotify.app.spotify

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/** Outcome of [refreshAccessToken]: which classification the response earned, plus the new credential when OK. */
data class RefreshResult(
    val outcome: SpotifyRefreshOutcome,
    val credential: AuthCredential?,
)

/**
 * `POST /api/token` with `grant_type=authorization_code` (PKCE exchange).
 * Returns the persisted [AuthCredential] (OAUTH) or throws when Spotify
 * rejects the exchange.
 */
suspend fun exchangeAuthorizationCode(
    httpClient: HttpClient,
    clientId: String,
    code: String,
    redirectUri: String,
    codeVerifier: String,
    nowMs: Long = currentTimeMillis(),
): AuthCredential {
    val response = httpClient.submitForm(
        url = SPOTIFY_TOKEN_URL,
        formParameters = authorizationCodeForm(clientId, code, redirectUri, codeVerifier).toFormParameters(),
    )
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) {
        val errorCode = parseSpotifyErrorCode(body)
        throw IllegalStateException(
            "Spotify authorization_code exchange failed: HTTP ${response.status.value}" +
                (errorCode?.let { " ($it)" } ?: ""),
        )
    }
    val parsed = parseTokenEndpointSuccess(body)
    return AuthCredential(
        accessToken = parsed.accessToken,
        refreshToken = parsed.refreshToken,
        expiresAtEpochMs = parsed.expiresIn?.let { nowMs + it * 1000L },
        clientType = AuthCredential.ClientType.OAUTH,
        clientId = clientId,
    )
}

/**
 * `POST /api/token` with `grant_type=refresh_token`.
 *
 * Never throws for a classified token-endpoint response — the caller switches
 * on [RefreshResult.outcome]:
 *  - [SpotifyRefreshOutcome.OK] → [RefreshResult.credential] holds the new
 *    credential (refresh token is kept from the old grant when Spotify omits it).
 *  - [SpotifyRefreshOutcome.TERMINAL] → the grant is dead; caller must
 *    [TokenStorage.clear].
 *  - [SpotifyRefreshOutcome.TRANSIENT] → keep the stored credential, fail
 *    only this attempt.
 *
 * Transport exceptions propagate to the caller (treated as transient there).
 */
suspend fun refreshAccessToken(
    httpClient: HttpClient,
    clientId: String,
    refreshToken: String,
    nowMs: Long = currentTimeMillis(),
): RefreshResult {
    val response = httpClient.submitForm(
        url = SPOTIFY_TOKEN_URL,
        formParameters = refreshForm(clientId, refreshToken).toFormParameters(),
    )
    val body = response.bodyAsText()
    val outcome = classifySpotifyRefresh(response.status.value, parseSpotifyErrorCode(body))
    if (outcome != SpotifyRefreshOutcome.OK) {
        return RefreshResult(outcome, null)
    }
    val parsed = parseTokenEndpointSuccess(body)
    return RefreshResult(
        outcome,
        AuthCredential(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken ?: refreshToken,
            expiresAtEpochMs = parsed.expiresIn?.let { nowMs + it * 1000L },
            clientType = AuthCredential.ClientType.OAUTH,
            clientId = clientId,
        ),
    )
}

/**
 * Cookie fallback: exchange an `sp_dc` cookie value for a web access token.
 *
 * `GET open.spotify.com/get_access_token` with `Cookie: sp_dc=<value>` — the
 * JSON body carries accessToken / expirationTimestampMs / isAnonymous
 * (anonymous responses are rejected by [parseCookieTokenResponse]). The
 * bearer works against api.spotify.com/v1; there is NO refresh for cookie
 * tokens — on 401 the client invalidates and surfaces reauth.
 */
suspend fun fetchCookieToken(
    httpClient: HttpClient,
    spDc: String,
    nowMs: Long = currentTimeMillis(),
): AuthCredential {
    val response = httpClient.get(SPOTIFY_COOKIE_TOKEN_URL) {
        header(HttpHeaders.Cookie, "sp_dc=$spDc")
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) {
        throw IllegalStateException("sp_dc cookie exchange failed: HTTP ${response.status.value}")
    }
    return parseCookieTokenResponse(body, nowMs)
}
