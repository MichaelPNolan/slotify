// Written fresh for Slotify — network-free tests for the ported OAuth pieces
// (authorize URL, form bodies, refresh classification). The classification
// expectations mirror Parachord's OAuthManagerSpotifyRefreshTest
// (MIT License), © Jason Herskowitz, rewritten as pure-function tests since
// their original is coupled to mockk/MockWebServer/Android.

package com.slotify.app.spotify

import io.ktor.http.encodeURLParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthFlowTest {

    // ── authorize URL builder ─────────────────────────────────────────

    @Test
    fun authorizeUrlCarriesEveryRequiredParameter() {
        val url = buildAuthorizeUrl(
            clientId = "testclient123",
            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            state = "csrf-state-1",
        )
        assertTrue(url.startsWith("$SPOTIFY_AUTHORIZE_URL?"), "got: $url")
        assertTrue(url.contains("client_id=testclient123"), "got: $url")
        assertTrue(url.contains("response_type=code"), "got: $url")
        assertTrue(url.contains("redirect_uri=" + SPOTIFY_REDIRECT_URI.encodeURLParameter()), "got: $url")
        assertTrue(url.contains("code_challenge_method=S256"), "got: $url")
        assertTrue(url.contains("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"), "got: $url")
        assertTrue(url.contains("scope=" + SPOTIFY_SCOPES.encodeURLParameter()), "got: $url")
        assertTrue(url.contains("state=csrf-state-1"), "got: $url")
    }

    @Test
    fun redirectUriIsTheFixedLoopback() {
        assertEquals("http://127.0.0.1:43821/callback", SPOTIFY_REDIRECT_URI)
    }

    // ── form encoding ─────────────────────────────────────────────────

    @Test
    fun refreshFormEncodesGrantTypeRefreshTokenAndClientId() {
        val form = refreshForm(clientId = "cid-1", refreshToken = "rt-1")
        assertEquals(
            listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "rt-1",
                "client_id" to "cid-1",
            ),
            form,
        )
        val params = form.toFormParameters()
        assertEquals("refresh_token", params["grant_type"])
        assertEquals("rt-1", params["refresh_token"])
        assertEquals("cid-1", params["client_id"])
        assertEquals(setOf("grant_type", "refresh_token", "client_id"), params.names())
    }

    @Test
    fun authorizationCodeFormEncodesPkceExchangeFields() {
        val form = authorizationCodeForm(
            clientId = "cid-1",
            code = "auth-code",
            redirectUri = SPOTIFY_REDIRECT_URI,
            codeVerifier = "verifier-abc",
        )
        assertEquals(
            listOf(
                "grant_type" to "authorization_code",
                "code" to "auth-code",
                "redirect_uri" to SPOTIFY_REDIRECT_URI,
                "client_id" to "cid-1",
                "code_verifier" to "verifier-abc",
            ),
            form,
        )
    }

    // ── refresh classification (port of OAuthManagerSpotifyRefreshTest) ─

    @Test
    fun successIsOk() {
        assertEquals(SpotifyRefreshOutcome.OK, classifySpotifyRefresh(200, null))
        assertEquals(SpotifyRefreshOutcome.OK, classifySpotifyRefresh(204, null))
    }

    @Test
    fun invalidGrantOn400IsTerminal() {
        assertEquals(
            SpotifyRefreshOutcome.TERMINAL,
            classifySpotifyRefresh(400, "invalid_grant"),
        )
    }

    @Test
    fun invalidClientOn401IsTerminal() {
        assertEquals(
            SpotifyRefreshOutcome.TERMINAL,
            classifySpotifyRefresh(401, "invalid_client"),
        )
    }

    @Test
    fun serverOutageIsTransient() {
        assertEquals(
            SpotifyRefreshOutcome.TRANSIENT,
            classifySpotifyRefresh(503, "server_error"),
        )
        // A non-grant 400 must NOT discard the token (OAuthManager test: same expectation).
        assertEquals(
            SpotifyRefreshOutcome.TRANSIENT,
            classifySpotifyRefresh(400, "unsupported_grant_type"),
        )
    }

    @Test
    fun unparseableErrorBodyIsTransient() {
        assertNull(parseSpotifyErrorCode(""))
        assertNull(parseSpotifyErrorCode(null))
        assertNull(parseSpotifyErrorCode("<html>proxy error</html>"))
        assertEquals("invalid_grant", parseSpotifyErrorCode("""{"error":"invalid_grant"}"""))
    }

    // ── proactive-refresh decision ────────────────────────────────────

    @Test
    fun expiredOauthCredentialNeedsRefresh() {
        val cred = AuthCredential(
            accessToken = "a",
            refreshToken = "r",
            expiresAtEpochMs = 1_000_000L,
            clientType = AuthCredential.ClientType.OAUTH,
        )
        assertTrue(credentialNeedsRefresh(cred, nowMs = 990_000L), "inside the 30s skew window")
        assertTrue(credentialNeedsRefresh(cred, nowMs = 1_500_000L))
        assertEquals(false, credentialNeedsRefresh(cred, nowMs = 500_000L))
    }

    @Test
    fun cookieCredentialNeverNeedsRefresh() {
        val cred = AuthCredential(
            accessToken = "a",
            expiresAtEpochMs = 1_000_000L,
            clientType = AuthCredential.ClientType.COOKIE,
        )
        assertEquals(false, credentialNeedsRefresh(cred, nowMs = 2_000_000L))
    }
}
