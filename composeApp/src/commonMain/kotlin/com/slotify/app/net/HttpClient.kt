// Shared HTTP client factory for Slotify.
// JSON/timeout configuration mirrors Parachord's HttpClientFactory
// (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/HttpClientFactory.kt
// Adaptations for Slotify: no logging plugin (no ktor-logging dependency),
// no retry plugin, no auth plugins — auth is wired per-client instead.

package com.slotify.app.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Platform HTTP client factory.
 * desktopMain uses the CIO engine; androidMain uses the OkHttp engine.
 * Both apply [installSharedConfig].
 */
expect fun createHttpClient(): HttpClient

/**
 * JSON + timeout configuration shared by every platform actual of
 * [createHttpClient]. Mirrors Parachord's `installSharedPlugins` minus the
 * plugins Slotify has no dependency for (logging) or no need for (retry,
 * auth — auth headers are applied per-request by `SpotifyClient`).
 *
 * `expectSuccess = false` so callers (e.g. the Spotify token endpoints and
 * the rate-limit gate) can inspect non-2xx statuses instead of having Ktor
 * throw before they classify the response.
 */
fun HttpClientConfig<*>.installSharedConfig() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
