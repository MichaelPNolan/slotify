// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/auth/ReauthRequiredException.kt
// Adaptations for Slotify: package rename only.

package com.slotify.app.spotify

/**
 * Thrown when a 401-refresh-401 sequence (or a 401 with no usable refresh
 * grant) indicates the user must re-authenticate. Call sites that care can
 * catch and prompt re-login; otherwise it propagates as a fatal error with a
 * clear realm.
 */
class ReauthRequiredException(val realm: AuthRealm) : Exception("Re-authentication required for $realm")
