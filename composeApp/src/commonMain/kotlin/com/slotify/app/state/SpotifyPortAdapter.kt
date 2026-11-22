package com.slotify.app.state

import com.slotify.app.model.AppPlaylist
import com.slotify.app.net.createHttpClient
import com.slotify.app.spotify.SpotifyClient
import com.slotify.app.spotify.SpotifyConnectionState
import com.slotify.app.spotify.SpotifySession
import com.slotify.app.spotify.StorageAuthTokenProvider
import com.slotify.app.spotify.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Adapts the concrete [SpotifySession] to the UI-facing [SpotifyPort]. */
class SpotifySessionAdapter(
    private val session: SpotifySession,
    scope: CoroutineScope,
) : SpotifyPort {

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override val status: StateFlow<SpotifyStatus> = session.connectionState
        .map { it.toPortStatus() }
        .stateIn(scope, SharingStarted.Eagerly, SpotifyStatus.DISCONNECTED)

    override val displayName: StateFlow<String?> = session.displayName

    override suspend fun connectWithOAuth(
        clientId: String,
        awaitCode: suspend (String) -> String,
    ): Result<Unit> = session.connectWithOAuth(clientId, awaitCode)
        .onSuccess { _lastError.value = null }
        .onFailure {
            _lastError.value = it.message ?: it.toString()
            // Also mirror to the console — run.sh shows this in the terminal.
            println("Slotify Spotify sign-in failed: ${it.stackTraceToString()}")
        }

    override suspend fun connectWithCookie(spDc: String): Result<Unit> =
        session.connectWithCookie(spDc)
            .onSuccess { _lastError.value = null }
            .onFailure {
                _lastError.value = it.message ?: it.toString()
                println("Slotify Spotify cookie sign-in failed: ${it.stackTraceToString()}")
            }

    override suspend fun disconnect() {
        session.disconnect()
        _lastError.value = null
    }

    override suspend fun importAllPlaylists(onProgress: (String) -> Unit): List<AppPlaylist> {
        _lastError.value = null
        return runCatching { session.importAllPlaylists(onProgress) }
            .onFailure {
                _lastError.value = it.message ?: it.toString()
                println("Slotify Spotify import failed: ${it.stackTraceToString()}")
            }
            .getOrThrow()
    }

    override suspend fun importPlaylist(playlistId: String, onProgress: (String) -> Unit): AppPlaylist {
        _lastError.value = null
        return runCatching { session.importPlaylist(playlistId, onProgress) }
            .onFailure {
                _lastError.value = it.message ?: it.toString()
                println("Slotify Spotify playlist import failed: ${it.stackTraceToString()}")
            }
            .getOrThrow()
    }
}

private fun SpotifyConnectionState.toPortStatus(): SpotifyStatus = when (this) {
    SpotifyConnectionState.DISCONNECTED -> SpotifyStatus.DISCONNECTED
    SpotifyConnectionState.CONNECTING -> SpotifyStatus.CONNECTING
    SpotifyConnectionState.CONNECTED -> SpotifyStatus.CONNECTED
    SpotifyConnectionState.ERROR -> SpotifyStatus.ERROR
}

/**
 * Build the full Spotify graph: token storage → auth provider → API client →
 * session → UI adapter. Platform entry points supply the [TokenStorage].
 */
fun createSpotifyPort(storage: TokenStorage, scope: CoroutineScope): SpotifyPort {
    val http = createHttpClient()
    val provider = StorageAuthTokenProvider(storage, http)
    val client = SpotifyClient(http, provider, refresher = provider)
    val session = SpotifySession(client, storage, http)
    return SpotifySessionAdapter(session, scope)
}
