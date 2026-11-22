// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/auth/OAuthTokenRefresher.kt
// Adaptations for Slotify: return type widened from AuthCredential.BearerToken
// to the flat AuthCredential; package rename.

package com.slotify.app.spotify

/**
 * Response-time OAuth refresh. Invoked when a 401 confirms the cached token
 * is dead. Performs a network refresh against the provider's /api/token
 * endpoint, persists the new token via [TokenStorage], and returns the new
 * credential.
 *
 * Returns null if refresh is impossible or failed (cookie credential with no
 * refresh grant, refresh token revoked, transient server error) — caller
 * should escalate to [ReauthRequiredException].
 */
interface OAuthTokenRefresher {
    suspend fun refresh(realm: AuthRealm): AuthCredential?
}
