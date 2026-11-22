// Ported/adapted from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/auth/AuthTokenProvider.kt
// (request-time lookup semantics) + OAuthTokenRefresher (refresh semantics).
// Adaptations for Slotify: TokenStorage / InMemoryTokenStorage / the
// expect JsonFileTokenStorage and the combined StorageAuthTokenProvider are
// written fresh — Parachord read tokens from Android SecureTokenStore /
// iOS Keychain via platform-specific stores.

package com.slotify.app.spotify

import com.slotify.app.net.createHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the [AuthCredential] JSON lives. All implementations persist (or
 * hold) exactly one credential — Slotify's single-Spotify-account model.
 */
interface TokenStorage {
    suspend fun load(): AuthCredential?
    suspend fun save(credential: AuthCredential)
    suspend fun clear()
}

/** In-memory storage for tests and UI wiring until platform storage lands. */
class InMemoryTokenStorage : TokenStorage {
    private val mutex = Mutex()
    private var credential: AuthCredential? = null

    override suspend fun load(): AuthCredential? = mutex.withLock { credential }

    override suspend fun save(credential: AuthCredential) {
        mutex.withLock { this.credential = credential }
    }

    override suspend fun clear() {
        mutex.withLock { credential = null }
    }
}

/**
 * JSON file-backed storage under a caller-supplied directory.
 * desktopMain writes `spotify_auth.json` via java.io.File; androidMain is an
 * in-memory fallback until SharedPreferences is wired (T1.6).
 */
expect class JsonFileTokenStorage(directory: String) : TokenStorage

/**
 * The [AuthTokenProvider] + [OAuthTokenRefresher] pair that backs
 * [SpotifyClient]:
 *
 *  - [tokenFor] reads [TokenStorage] and proactively runs the
 *    `refresh_token` grant when the stored OAuth credential is expired
 *    ([credentialNeedsRefresh]) — a transient refresh failure keeps the old
 *    credential (the server may still accept it); a terminal failure clears
 *    storage so a dead grant is never re-poked.
 *  - [refresh] is the single-flight response-time refresher used by
 *    SpotifyClient's 401 handling. Cookie credentials have no refresh grant
 *    and always return null (→ invalidate + [ReauthRequiredException]).
 *  - [invalidate] clears storage.
 */
class StorageAuthTokenProvider(
    private val storage: TokenStorage,
    private val httpClient: HttpClient = createHttpClient(),
    private val nowMs: () -> Long = { currentTimeMillis() },
) : AuthTokenProvider, OAuthTokenRefresher {

    /** Single-flight: concurrent 401s (or expiry races) trigger ONE refresh POST. */
    private val refreshMutex = Mutex()

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? {
        val credential = storage.load() ?: return null
        if (credentialNeedsRefresh(credential, nowMs())) {
            // Best-effort: transient failure keeps the stored credential below;
            // terminal failure clears storage so the re-read returns null.
            runCatching { refresh(realm) }
            return storage.load()
        }
        return credential
    }

    override suspend fun invalidate(realm: AuthRealm) {
        storage.clear()
    }

    override suspend fun refresh(realm: AuthRealm): AuthCredential? = refreshMutex.withLock {
        val credential = storage.load() ?: return@withLock null
        if (credential.clientType != AuthCredential.ClientType.OAUTH) return@withLock null
        val refreshToken = credential.refreshToken ?: return@withLock null
        val clientId = credential.clientId ?: return@withLock null

        val result = try {
            refreshAccessToken(httpClient, clientId, refreshToken, nowMs())
        } catch (_: Exception) {
            // Transport failure — transient by definition. Keep the credential.
            return@withLock null
        }
        when (result.outcome) {
            SpotifyRefreshOutcome.OK -> result.credential.also { saved ->
                if (saved != null) storage.save(saved)
            }
            SpotifyRefreshOutcome.TERMINAL -> {
                storage.clear()
                null
            }
            SpotifyRefreshOutcome.TRANSIENT -> null
        }
    }
}
