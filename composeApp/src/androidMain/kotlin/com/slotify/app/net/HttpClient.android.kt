// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/HttpClientFactory.kt (android actual)
// Adaptations for Slotify: OkHttp engine + [installSharedConfig] only.

package com.slotify.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    installSharedConfig()
}
