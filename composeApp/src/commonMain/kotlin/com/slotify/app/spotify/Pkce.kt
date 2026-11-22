// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — app/.../auth/OAuthManager.kt
// (generateCodeVerifier / generateCodeChallenge / generateOAuthState, L541–574)
// Adaptations for Slotify:
//  - Split into expect declarations: SHA-256 + SecureRandom are java.* and not
//    visible in commonMain; desktopMain and androidMain provide identical JVM
//    actuals (android no longer uses android.util.Base64 — java.util.Base64
//    gives the same URL-safe/no-padding output).

package com.slotify.app.spotify

/**
 * BASE64URL(SHA-256(input)) without `=` padding — the RFC 7636 S256
 * code_challenge transformation. Test vector (RFC 7636 Appendix B):
 * `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` →
 * `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
 */
expect fun pkceSha256Base64Url(input: String): String

/** Random URL-safe Base64 (no padding) of roughly [length] chars — RFC 7636 code_verifier / OAuth state. */
expect fun pkceRandomVerifier(length: Int): String
