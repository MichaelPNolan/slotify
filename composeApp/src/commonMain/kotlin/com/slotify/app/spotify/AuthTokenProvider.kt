// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/auth/AuthTokenProvider.kt
// Adaptations for Slotify: package rename only.

package com.slotify.app.spotify

/**
 * Request-time authentication credential lookup. Cheap; no network when the
 * stored token is still fresh — implementations refresh proactively only when
 * the stored OAuth token has expired (see [StorageAuthTokenProvider]).
 *
 * @see OAuthTokenRefresher for response-time token refresh on 401.
 */
interface AuthTokenProvider {
    /** Returns the cached credential for [realm], or null if not authenticated. */
    suspend fun tokenFor(realm: AuthRealm): AuthCredential?

    /** Marks the [realm]'s credential as invalid (e.g., after a confirmed reauth requirement). */
    suspend fun invalidate(realm: AuthRealm)
}
