// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/auth/AuthCredential.kt
// Adaptations for Slotify:
//  - @Serializable so credentials persist as JSON via TokenStorage.
//  - Replaced Parachord's multi-subtype sealed hierarchy with a single flat
//    Spotify credential carrying accessToken / refreshToken /
//    expiresAtEpochMs / clientType (oauth|cookie) — Slotify is metadata-only
//    and stores exactly one Spotify credential. clientId is retained so the
//    refresh_token grant can be replayed after an app restart.
//  - AuthRealm trimmed to the single Spotify entry.

package com.slotify.app.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Logical authentication realm — used by [AuthTokenProvider.tokenFor] to
 * disambiguate which credentials to return.
 */
enum class AuthRealm {
    Spotify,
}

/**
 * The persisted Spotify credential (JSON via [TokenStorage]).
 *
 * [clientType] is the discriminator: [ClientType.OAUTH] credentials carry a
 * [refreshToken] and can be renewed via the `refresh_token` grant;
 * [ClientType.COOKIE] credentials come from the `sp_dc` web-cookie flow and
 * have NO refresh — on 401 they must be discarded (see SpotifyClient's
 * 401 handling / [ReauthRequiredException]).
 */
@Serializable
data class AuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch-ms expiry of [accessToken]; null = unknown (treat as unexpired). */
    val expiresAtEpochMs: Long? = null,
    val clientType: ClientType = ClientType.OAUTH,
    /** Spotify requires client_id on the refresh grant; stored at connect time. */
    val clientId: String? = null,
) {
    @Serializable
    enum class ClientType {
        @SerialName("oauth") OAUTH,
        @SerialName("cookie") COOKIE,
    }
}
