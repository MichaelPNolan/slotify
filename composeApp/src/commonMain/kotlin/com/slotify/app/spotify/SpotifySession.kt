// Written fresh for Slotify. PKCE pieces follow Parachord's OAuthManager
// (MIT License), © Jason Herskowitz — https://github.com/Parachord/parachord-mobile
// (state/verifier generation + token exchange), but the commonMain
// `awaitCode` callback contract replaces their Android Custom-Tab flow.

package com.slotify.app.spotify

import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.PlaylistSource
import com.slotify.app.net.createHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpotifyConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR,
}

/**
 * High-level Spotify connection: PKCE or `sp_dc` cookie login, credential
 * persistence via [TokenStorage], and metadata-only playlist import.
 *
 * Note: [client] must be constructed over the SAME [TokenStorage] (via
 * [StorageAuthTokenProvider]) so API calls see credentials this session
 * writes.
 */
class SpotifySession(
    private val client: SpotifyClient,
    private val storage: TokenStorage,
    private val httpClient: HttpClient = createHttpClient(),
) {
    private val _connectionState = MutableStateFlow(SpotifyConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SpotifyConnectionState> = _connectionState.asStateFlow()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    /**
     * PKCE flow. Builds the authorize URL, invokes [awaitCode] with it (the
     * platform opens the browser and returns the `code` query param from the
     * loopback redirect), exchanges code + verifier for tokens, persists the
     * credential, then verifies it against `/v1/me`.
     */
    suspend fun connectWithOAuth(
        clientId: String,
        awaitCode: suspend (authorizeUrl: String) -> String,
    ): Result<Unit> {
        _connectionState.value = SpotifyConnectionState.CONNECTING
        return runCatching {
            val verifier = pkceRandomVerifier(64)
            val challenge = pkceSha256Base64Url(verifier)
            val state = pkceRandomVerifier(32)
            val authorizeUrl = buildAuthorizeUrl(clientId, challenge, state)
            val code = awaitCode(authorizeUrl)
            val credential = exchangeAuthorizationCode(
                httpClient = httpClient,
                clientId = clientId,
                code = code,
                redirectUri = SPOTIFY_REDIRECT_URI,
                codeVerifier = verifier,
            )
            storage.save(credential)
            val user = client.getCurrentUser()
            _displayName.value = user.displayName ?: user.id
        }.onSuccess {
            _connectionState.value = SpotifyConnectionState.CONNECTED
        }.onFailure {
            _connectionState.value = SpotifyConnectionState.ERROR
        }
    }

    /**
     * Cookie fallback: exchange an `sp_dc` cookie value for a web token,
     * persist it, verify via `/v1/me`. Cookie credentials never refresh —
     * a later 401 invalidates them and surfaces reauth.
     */
    suspend fun connectWithCookie(spDc: String): Result<Unit> {
        _connectionState.value = SpotifyConnectionState.CONNECTING
        return runCatching {
            val credential = fetchCookieToken(httpClient, spDc)
            storage.save(credential)
            val user = client.getCurrentUser()
            _displayName.value = user.displayName ?: user.id
        }.onSuccess {
            _connectionState.value = SpotifyConnectionState.CONNECTED
        }.onFailure {
            _connectionState.value = SpotifyConnectionState.ERROR
        }
    }

    /** Drop the stored credential and return to [SpotifyConnectionState.DISCONNECTED]. */
    suspend fun disconnect() {
        storage.clear()
        _displayName.value = null
        _connectionState.value = SpotifyConnectionState.DISCONNECTED
    }

    /**
     * Import every playlist visible to the connected account, paginating
     * playlists (50/page) and each playlist's tracks (100/page), skipping
     * null/local track entries, and mapping to [AppPlaylist]s.
     *
     * [onProgress] receives per-playlist progress like
     * `"Importing 'Road Trip' (43/120)"` after every tracks page.
     * The source is COOKIE when the stored credential came from the `sp_dc`
     * flow, OAUTH otherwise. Requires an authenticated connection (401s
     * escalate to [ReauthRequiredException]).
     */
    suspend fun importAllPlaylists(onProgress: (String) -> Unit = {}): List<AppPlaylist> {
        val source = when (storage.load()?.clientType) {
            AuthCredential.ClientType.COOKIE -> PlaylistSource.COOKIE
            else -> PlaylistSource.OAUTH
        }
        val imported = mutableListOf<AppPlaylist>()
        var playlistOffset = 0
        while (true) {
            val playlistPage = client.getUserPlaylists(limit = 50, offset = playlistOffset)
            if (playlistPage.items.isEmpty()) break
            for (playlist in playlistPage.items) {
                val id = playlist.id ?: continue
                val label = playlist.name ?: id
                val items = mutableListOf<SpPlaylistTrackItem>()
                var tracksOffset = 0
                while (true) {
                    val trackPage = client.getPlaylistTracks(id, limit = 100, offset = tracksOffset)
                    items.addAll(trackPage.items)
                    val fetched = items.size.coerceAtMost(trackPage.total)
                    onProgress("Importing '$label' ($fetched/${trackPage.total})")
                    if (trackPage.next == null || trackPage.items.isEmpty()) break
                    tracksOffset += trackPage.items.size
                }
                imported += mapAppPlaylist(
                    playlist = playlist,
                    items = items,
                    importedAtEpochMs = currentTimeMillis(),
                    source = source,
                )
            }
            playlistOffset += playlistPage.items.size
            if (playlistPage.next == null || playlistOffset >= playlistPage.total) break
        }
        return imported
    }

    /**
     * Import a single playlist by id (metadata only). Used for pasted
     * open.spotify.com playlist URLs — does not require the playlist to be
     * in the user's library, only that the connected account can read it.
     *
     * [onProgress] receives the same `"Importing '…' (n/m)"` lines as
     * [importAllPlaylists].
     */
    suspend fun importPlaylist(
        playlistId: String,
        onProgress: (String) -> Unit = {},
    ): AppPlaylist {
        val source = when (storage.load()?.clientType) {
            AuthCredential.ClientType.COOKIE -> PlaylistSource.COOKIE
            else -> PlaylistSource.OAUTH
        }
        val full = client.getPlaylist(playlistId)
        val simple = SpPlaylistSimple(
            id = full.id ?: playlistId,
            name = full.name,
            description = full.description,
            images = full.images,
            owner = full.owner,
            snapshotId = full.snapshotId,
            tracks = full.tracks?.let { SpPlaylistTracksRef(total = it.total) },
        )
        val label = simple.name ?: playlistId
        val items = mutableListOf<SpPlaylistTrackItem>()
        var tracksOffset = 0
        while (true) {
            val trackPage = client.getPlaylistTracks(playlistId, limit = 100, offset = tracksOffset)
            items.addAll(trackPage.items)
            val fetched = items.size.coerceAtMost(trackPage.total)
            onProgress("Importing '$label' ($fetched/${trackPage.total})")
            if (trackPage.next == null || trackPage.items.isEmpty()) break
            tracksOffset += trackPage.items.size
        }
        return mapAppPlaylist(
            playlist = simple,
            items = items,
            importedAtEpochMs = currentTimeMillis(),
            source = source,
        )
    }
}
