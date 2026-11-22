// Written fresh for Slotify — desktop actual of the commonMain
// expect JsonFileTokenStorage (no Parachord equivalent; their Android store
// was SecureTokenStore-backed).

package com.slotify.app.spotify

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

actual class JsonFileTokenStorage actual constructor(directory: String) : TokenStorage {
    private val file = File(directory, "spotify_auth.json")
    private val mutex = Mutex()

    override suspend fun load(): AuthCredential? = mutex.withLock {
        if (!file.exists()) return@withLock null
        runCatching { spotifyJson.decodeFromString<AuthCredential>(file.readText()) }.getOrNull()
    }

    override suspend fun save(credential: AuthCredential) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            file.writeText(spotifyJson.encodeToString(credential))
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            if (file.exists()) file.delete()
        }
    }
}
