// Written fresh for Slotify — fixture-driven tests for the sp_dc cookie
// token parsing (the cookie flow itself is a Slotify feature; Parachord has
// no sp_dc path).

package com.slotify.app.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieTokenTest {

    private val validFixture = """
        {
          "accessToken": "BQC-token-fixture",
          "expirationTimestampMs": 1750000000123,
          "isAnonymous": false
        }
    """.trimIndent()

    @Test
    fun parsesRealisticCookieResponse() {
        val credential = parseCookieTokenResponse(validFixture, nowMs = 0L)
        assertEquals("BQC-token-fixture", credential.accessToken)
        assertEquals(1750000000123L, credential.expiresAtEpochMs)
        assertEquals(AuthCredential.ClientType.COOKIE, credential.clientType)
        assertNull(credential.refreshToken, "cookie tokens have no refresh grant")
        assertNull(credential.clientId)
    }

    @Test
    fun anonymousTokenIsRejected() {
        val anonymous = """
            {"accessToken": null, "expirationTimestampMs": 0, "isAnonymous": true}
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            parseCookieTokenResponse(anonymous, nowMs = 0L)
        }
    }

    @Test
    fun missingAccessTokenIsRejected() {
        val broken = """{"expirationTimestampMs": 1750000000123, "isAnonymous": false}"""
        assertFailsWith<IllegalStateException> {
            parseCookieTokenResponse(broken, nowMs = 0L)
        }
    }

    @Test
    fun missingExpirationFallsBackToOneHourFromNow() {
        val noExpiry = """{"accessToken": "tok", "isAnonymous": false}"""
        val credential = parseCookieTokenResponse(noExpiry, nowMs = 1_000L)
        assertEquals(1_000L + 3_600_000L, credential.expiresAtEpochMs)
    }

    @Test
    fun credentialSerializesToJsonAndBack() {
        val original = parseCookieTokenResponse(validFixture, nowMs = 0L)
        val json = spotifyJson.encodeToString(AuthCredential.serializer(), original)
        assertTrue(json.contains("\"clientType\":\"cookie\""), "got: $json")
        assertTrue(json.contains("\"accessToken\":\"BQC-token-fixture\""), "got: $json")
        val decoded = spotifyJson.decodeFromString(AuthCredential.serializer(), json)
        assertEquals(original, decoded)
    }
}
