// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/HttpClientFactory.kt (desktop actual)
// Adaptations for Slotify: CIO engine + [installSharedConfig] only.

package com.slotify.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    installSharedConfig()
}
