// Written fresh for Slotify. Uses the RFC 7636 Appendix B test vector; the
// PKCE primitives themselves are ported from Parachord's OAuthManager
// (MIT License), © Jason Herskowitz.

package com.slotify.app.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {

    /** RFC 7636 Appendix B — the canonical S256 test vector. */
    @Test
    fun rfc7636TestVectorChallenge() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceSha256Base64Url(verifier),
        )
    }

    @Test
    fun verifierHasRequestedLengthAndUrlSafeAlphabet() {
        val verifier = pkceRandomVerifier(64)
        assertEquals(64, verifier.length)
        // RFC 7636 §4.1 — [A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~"
        assertTrue(
            verifier.all { it.isLetterOrDigit() || it in "-._~" },
            "verifier contains non-URL-safe chars: $verifier",
        )
        // Varying lengths stay within the RFC's 43..128 bounds when asked for.
        assertEquals(43, pkceRandomVerifier(43).length)
        assertEquals(128, pkceRandomVerifier(128).length)
    }

    @Test
    fun challengeIsDeterministicPerVerifier() {
        val verifier = pkceRandomVerifier(64)
        assertEquals(pkceSha256Base64Url(verifier), pkceSha256Base64Url(verifier))
    }
}
