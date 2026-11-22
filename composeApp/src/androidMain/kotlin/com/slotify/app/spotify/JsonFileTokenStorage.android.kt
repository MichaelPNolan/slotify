// Android actual of the commonMain expect JsonFileTokenStorage.
// Written fresh for Slotify (no Parachord equivalent — see desktop actual).

package com.slotify.app.spotify

actual class JsonFileTokenStorage actual constructor(directory: String) : TokenStorage {
    // TODO(T1.6): persist to SharedPreferences (another workstream wires the
    // real platform storage); until then this is an in-memory fallback, so a
    // reconnect is required per process.
    private val delegate = InMemoryTokenStorage()

    override suspend fun load(): AuthCredential? = delegate.load()

    override suspend fun save(credential: AuthCredential) = delegate.save(credential)

    override suspend fun clear() = delegate.clear()
}
