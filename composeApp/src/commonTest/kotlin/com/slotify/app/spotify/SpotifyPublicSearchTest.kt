package com.slotify.app.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotifyPublicSearchTest {

    private val sampleHtml = """
        <html><body>
        <div class="result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fopen.spotify.com%2Fplaylist%2F37i9dQZF1DZ06evO2FgIak%3Fsi%3Dabc">
            This Is Muddy Waters | Spotify Playlist
          </a>
          <a class="result__snippet" href="#">snippet…</a>
        </div>
        <div class="result">
          <a rel="nofollow" class="result__a" href="https://open.spotify.com/playlist/6NQ6lsBqY3B327PbegJgl8">
            The Best Of Muddy Waters - playlist by Sean
          </a>
        </div>
        <div class="result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fopen.spotify.com%2Fplaylist%2F37i9dQZF1DZ06evO2FgIak">
            Duplicate should drop
          </a>
        </div>
        <a class="result__a" href="https://example.com/not-spotify">Other</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesUniquePlaylistHitsWithTitles() {
        val hits = parseDuckDuckGoPlaylistHits(sampleHtml)
        assertEquals(2, hits.size, hits.toString())
        assertEquals("37i9dQZF1DZ06evO2FgIak", hits[0].id)
        assertTrue(hits[0].title.contains("Muddy Waters"))
        assertEquals("6NQ6lsBqY3B327PbegJgl8", hits[1].id)
        assertTrue(hits[1].title.contains("Best Of"))
        assertTrue(hits.all { it.url.startsWith("https://open.spotify.com/playlist/") })
    }

    @Test
    fun respectsLimit() {
        val hits = parseDuckDuckGoPlaylistHits(sampleHtml, limit = 1)
        assertEquals(1, hits.size)
    }

    @Test
    fun resolveDdgRedirectAndBareUrls() {
        val redirected = resolveDdgHref(
            "//duckduckgo.com/l/?uddg=https%3A%2F%2Fopen.spotify.com%2Fplaylist%2Fabc",
        )
        assertEquals("https://open.spotify.com/playlist/abc", redirected)
        assertEquals(
            "https://open.spotify.com/playlist/xyz",
            resolveDdgHref("https://open.spotify.com/playlist/xyz"),
        )
    }

    @Test
    fun emptyHtmlYieldsEmptyList() {
        assertTrue(parseDuckDuckGoPlaylistHits("<html></html>").isEmpty())
    }

    @Test
    fun parsesBingPlaylistAnchors() {
        val html = """
            <html><body>
            <li class="b_algo">
              <h2><a href="https://open.spotify.com/playlist/37i9dQZF1DZ06evO2FgIak?si=xyz">Yinglee Hits | Spotify</a></h2>
            </li>
            <li class="b_algo">
              <h2><a href="https://open.spotify.com/playlist/6NQ6lsBqY3B327PbegJgl8">Thai Pop Mix</a></h2>
            </li>
            </body></html>
        """.trimIndent()
        val hits = parseBingPlaylistHits(html)
        assertEquals(2, hits.size, hits.toString())
        assertEquals("37i9dQZF1DZ06evO2FgIak", hits[0].id)
        assertTrue(hits[0].title.contains("Yinglee"))
        assertEquals("6NQ6lsBqY3B327PbegJgl8", hits[1].id)
    }

    @Test
    fun bingFallsBackToBareUrls() {
        val html = """See also https://open.spotify.com/playlist/6NQ6lsBqY3B327PbegJgl8 ok"""
        val hits = parseBingPlaylistHits(html)
        assertEquals(1, hits.size)
        assertEquals("6NQ6lsBqY3B327PbegJgl8", hits[0].id)
    }

    @Test
    fun allFailedOutcomeFlagsWhenEveryEngineThrew() {
        val outcome = PublicSearchOutcome(
            hits = emptyList(),
            engineNotes = listOf("DDG HTML: HTTP 202", "DDG Lite: timed out", "Bing: HTTP 403"),
            enginesOk = 0,
            enginesFailed = 3,
        )
        assertTrue(outcome.allFailed)
        assertTrue(outcome.engineNotes.all { it.contains(":") })
    }

    @Test
    fun emptyHitsFromLiveEnginesIsNotAllFailed() {
        val outcome = PublicSearchOutcome(
            hits = emptyList(),
            engineNotes = listOf("DDG HTML: 0 hits (blocked/captcha?)"),
            enginesOk = 1,
            enginesFailed = 0,
        )
        assertTrue(!outcome.allFailed)
        assertTrue(outcome.engineNotes.first().contains("blocked"))
    }
}
