// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — app/.../auth/OAuthManager.kt
// (PKCE helpers; identical JVM body duplicated in desktopMain. Android no
// longer needs android.util.Base64 — java.util.Base64 is available on API 26+.)

package com.slotify.app.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual fun pkceSha256Base64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

actual fun pkceRandomVerifier(length: Int): String {
    // 4 base64 chars encode 3 bytes; over-provision then trim to [length].
    val bytes = ByteArray((length * 3 + 3) / 4)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(length)
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
