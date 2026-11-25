package com.slotify.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class YoutubeCookiesTest {

    @Test
    fun netscapeHeaderAccepted() {
        val body = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	0	CONSENT	YES
        """.trimIndent()
        assertTrue(looksLikeNetscapeCookies(body))
    }

    @Test
    fun tabSeparatedWithoutHeaderAccepted() {
        val body = ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc"
        assertTrue(looksLikeNetscapeCookies(body))
    }

    @Test
    fun htmlLoginPageRejected() {
        val body = "<html><body>Sign in to Google</body></html>"
        assertFalse(looksLikeNetscapeCookies(body))
    }

    @Test
    fun emptyRejected() {
        assertFalse(looksLikeNetscapeCookies("   \n  "))
    }

    @Test
    fun authFailureWithoutCookiesMentionsSettings() {
        val msg = youtubeAuthFailureMessage(hasCookies = false)
        assertTrue(msg.contains("Settings"))
        assertTrue(msg.contains("cookies.txt", ignoreCase = true))
        assertFalse(msg.contains("CONSENT=")) // no cookie values
    }

    @Test
    fun authFailureWithCookiesSuggestsRefresh() {
        val msg = youtubeAuthFailureMessage(hasCookies = true)
        assertTrue(msg.contains("expired", ignoreCase = true) || msg.contains("re-import", ignoreCase = true) || msg.contains("again", ignoreCase = true))
        assertFalse(msg.contains("/secrets/"))
    }

    @Test
    fun detectsBotCheckPhrases() {
        assertTrue(isYoutubeAuthFailure("ERROR: [youtube] abc: Sign in to confirm you're not a bot."))
        assertTrue(isYoutubeAuthFailure("Use --cookies-from-browser or --cookies for the authentication."))
        assertFalse(isYoutubeAuthFailure("HTTP Error 403: Forbidden"))
    }

    @Test
    fun statusLabelNeverIncludesPathOrContents() {
        // Without a configured file (desktop CI / clean env), label is fixed.
        val label = youtubeCookiesStatusLabel()
        assertTrue(label == "Not configured" || label.startsWith("Configured"))
        assertFalse(label.contains("CONSENT"))
        assertFalse(label.contains("SID="))
    }
}
