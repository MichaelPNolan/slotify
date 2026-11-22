package com.slotify.app.state

import com.slotify.app.model.AppPlaylist
import kotlinx.coroutines.flow.StateFlow

/** Connection lifecycle as seen by the UI. */
enum class SpotifyStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR,
}

/**
 * UI-facing Spotify boundary. The concrete implementation adapts
 * `com.slotify.app.spotify.SpotifySession` (see SpotifyPortAdapter), keeping
 * the state/UI layer independent of that package's internals.
 */
interface SpotifyPort {
    val status: StateFlow<SpotifyStatus>
    val displayName: StateFlow<String?>
    /** Last error message, if any. */
    val lastError: StateFlow<String?>

    /** PKCE login: [awaitCode] receives the authorize URL and returns the `code` query param. */
    suspend fun connectWithOAuth(clientId: String, awaitCode: suspend (String) -> String): Result<Unit>

    /** Cookie fallback: exchange an sp_dc cookie value. */
    suspend fun connectWithCookie(spDc: String): Result<Unit>

    suspend fun disconnect()

    /** Fetch all playlists (paginated). [onProgress] receives status lines. */
    suspend fun importAllPlaylists(onProgress: (String) -> Unit): List<AppPlaylist>

    /** Fetch one playlist by Spotify id (metadata only). */
    suspend fun importPlaylist(playlistId: String, onProgress: (String) -> Unit): AppPlaylist
}
