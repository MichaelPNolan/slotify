// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/src/commonTest/.../api/RateLimitGateTest.kt
// Adaptations for Slotify:
//  - package rename (com.parachord.shared.api → com.slotify.app.spotify).
//  - `kotlinx-coroutines-test` (runTest / testScheduler virtual clock) and
//    `ktor-client-mock` (MockEngine) are NOT dependencies of Slotify, so:
//      * runTest → kotlinx.coroutines.runBlocking,
//      * MockEngine-built responses → hand-constructed DefaultHttpResponse
//        (public Ktor constructors; status/headers are all the gate reads),
//      * the two virtual-clock rolling-window tests were reworked onto the
//        real clock (currentTimeMillis) with tolerance-based assertions;
//        everything else keeps the injected manual `nowMs` clock verbatim.

package com.slotify.app.spotify

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [RateLimitGate]'s escalating circuit breaker (opt-in),
 * rolling window, and persistence callbacks.
 *
 * Background: Spotify's abuse-mode ban routinely outlasts the 1h maxCooldown
 * cap. With a flat cooldown, every time our local cooldown lapses the next
 * call re-pokes the still-banned account and re-extends Spotify's window.
 * Escalation doubles the cooldown on each consecutive 429 (reset on the first
 * success) so a persistent ban quickly pushes our local cooldown past
 * Spotify's window and we stop poking.
 *
 * [InternalAPI] opt-in covers the hand-built HttpResponse/HttpClientCall
 * constructors (the MockEngine replacement — see the file header).
 */
@OptIn(InternalAPI::class)
class RateLimitGateTest {

    private class TestRateLimited(val retryAfter: Long?) : Exception()

    /** Per-test client — the gate never sends through it; HttpClientCall only needs a non-null owner. Closed after each test to release engine threads. */
    private lateinit var client: HttpClient

    @BeforeTest
    fun createClient() {
        client = HttpClient(CIO)
    }

    @AfterTest
    fun closeClient() {
        client.close()
    }

    /**
     * Hand-built response (MockEngine replacement): status + headers are the
     * only fields [RateLimitGate] reads; the body is never touched.
     */
    private fun response(status: HttpStatusCode, retryAfterSec: Long?): HttpResponse {
        val responseData = HttpResponseData(
            status,
            GMTDate(),
            if (retryAfterSec != null) headersOf("Retry-After", retryAfterSec.toString()) else headersOf(),
            HttpProtocolVersion.HTTP_1_1,
            "",
            Job(),
        )
        val requestData = HttpRequestData(
            url = Url("http://example.test/"),
            method = HttpMethod.Get,
            headers = Headers.Empty,
            body = TextContent("", ContentType.Text.Plain),
            executionContext = Job(),
            attributes = io.ktor.util.Attributes(),
        )
        return HttpClientCall(client, requestData, responseData).response
    }

    private fun gate(escalate: Boolean, now: () -> Long) = RateLimitGate(
        tag = "test",
        defaultCooldownSec = 60L,
        maxCooldownMs = 6L * 60L * 60L * 1000L, // 6h cap
        escalateOnRepeat = escalate,
        nowMs = now,
    )

    /** Gate matching MusicBrainz's opt-in: a positive [minCooldown] floor and a non-escalating, low default. */
    private fun flooredGate(minCooldown: Long, now: () -> Long) = RateLimitGate(
        tag = "test",
        defaultCooldownSec = 30L,
        minCooldownMs = minCooldown,
        nowMs = now,
    )

    private fun RateLimitGate.feed429(resp: HttpResponse) {
        assertFailsWith<TestRateLimited> {
            handleResponse(resp, exceptionFactory = { TestRateLimited(it) })
        }
    }

    /** Feeds a response through the MusicBrainZ-style 429-OR-503 predicate (the floor tests exercise 503). */
    private fun RateLimitGate.feed503(resp: HttpResponse) {
        assertFailsWith<TestRateLimited> {
            handleResponse(
                resp,
                isRateLimited = { it.status.value == 429 || it.status.value == 503 },
                exceptionFactory = { TestRateLimited(it) },
            )
        }
    }

    // ── rolling window (real-clock adaptation; see header) ─────────────

    @Test
    fun rolling_window_delays_calls_over_budget() = runBlocking {
        // Budget: 3 requests per 1000ms window. The 4th must wait until the
        // oldest entry ages out — asserted with real-time tolerances.
        val gate = RateLimitGate(
            tag = "test",
            interRequestDelayMs = 0L,            // isolate the window from the inter-request gap
            maxRequestsPerWindow = 3,
            requestWindowMs = 1000L,
        )
        val start = currentTimeMillis()
        val ranAt = mutableListOf<Long>()
        repeat(4) {
            gate.withPermit(exceptionFactory = { TestRateLimited(it) }) { ranAt.add(currentTimeMillis() - start) }
        }
        assertEquals(4, ranAt.size)
        // First 3 fit at once; the 4th waited for the window.
        ranAt.take(3).forEach { assertTrue(it < 500L, "first three must not be delayed, got $ranAt") }
        assertTrue(ranAt[3] >= 900L, "fourth call must wait for the window, got ${ranAt[3]}ms")
    }

    @Test
    fun rolling_window_off_by_default_no_delay() = runBlocking {
        // No maxRequestsPerWindow → the window cap is inert; many calls, no wait.
        val gate = RateLimitGate(tag = "test", interRequestDelayMs = 0L)
        val start = currentTimeMillis()
        var count = 0
        repeat(50) { gate.withPermit(exceptionFactory = { TestRateLimited(it) }) { count++ } }
        assertEquals(50, count)
        assertTrue(currentTimeMillis() - start < 5_000L, "no call should be throttled")
    }

    // ── escalation across restarts (manual clock) ─────────────────────

    @Test
    fun escalation_resumes_after_a_restart() = runBlocking {
        // A previous process hit 2 consecutive 429s; its cooldown just lapsed.
        // The next 429 is strike 3 (base × 4), not a fresh strike 1.
        val now = 10_000_000L
        val restarted = RateLimitGate(
            tag = "test", defaultCooldownSec = 60L, maxCooldownMs = 6L * 60L * 60L * 1000L,
            escalateOnRepeat = true,
            loadCooldownEpochMs = { now - 1_000L },
            loadStrikes = { 2 },
            nowMs = { now },
        )
        restarted.feed429(response(HttpStatusCode.TooManyRequests, 60L))
        assertEquals(240_000L, restarted.remainingCooldownMs())
    }

    @Test
    fun a_streak_older_than_the_max_cooldown_is_forgotten() = runBlocking {
        val max = 6L * 60L * 60L * 1000L
        val now = 100_000_000L
        val restarted = RateLimitGate(
            tag = "test", defaultCooldownSec = 60L, maxCooldownMs = max,
            escalateOnRepeat = true,
            loadCooldownEpochMs = { now - max - 1_000L }, // ended > 6h ago
            loadStrikes = { 3 },
            nowMs = { now },
        )
        restarted.feed429(response(HttpStatusCode.TooManyRequests, 60L))
        assertEquals(60_000L, restarted.remainingCooldownMs())
    }

    @Test
    fun strikes_are_saved_on_a_429_and_cleared_on_success() = runBlocking {
        val saved = mutableListOf<Int>()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var now = 0L
        val g = RateLimitGate(
            tag = "test", defaultCooldownSec = 60L, escalateOnRepeat = true,
            saveStrikes = { v -> saved += v; if (!first.isCompleted) first.complete(Unit) else second.complete(Unit) },
            nowMs = { now },
        )
        g.feed429(response(HttpStatusCode.TooManyRequests, 60L))
        first.await()
        now += 61_000L
        g.handleResponse(response(HttpStatusCode.OK, null), exceptionFactory = { TestRateLimited(it) })
        second.await()
        assertEquals(listOf(1, 0), saved)
    }

    @Test
    fun escalating_gate_doubles_cooldown_on_consecutive_429s() = runBlocking {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 60L) // 60s base

        g.feed429(r)
        val c1 = g.remainingCooldownMs()
        now += c1 + 1 // let the cooldown lapse, as a real re-poke would

        g.feed429(r)
        val c2 = g.remainingCooldownMs()
        now += c2 + 1

        g.feed429(r)
        val c3 = g.remainingCooldownMs()

        assertEquals(60_000L, c1)
        assertEquals(120_000L, c2)
        assertEquals(240_000L, c3)
        assertTrue(c3 > c2 && c2 > c1)
    }

    @Test
    fun success_resets_the_escalation() = runBlocking {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r429 = response(HttpStatusCode.TooManyRequests, 60L)

        g.feed429(r429)
        now += g.remainingCooldownMs() + 1
        g.feed429(r429) // strike 2 → 120s
        assertEquals(120_000L, g.remainingCooldownMs())
        now += g.remainingCooldownMs() + 1

        // A successful (non-rate-limited) response resets the strike counter.
        g.handleResponse(response(HttpStatusCode.OK, null), exceptionFactory = { TestRateLimited(it) })

        g.feed429(r429) // back to base
        assertEquals(60_000L, g.remainingCooldownMs())
    }

    @Test
    fun non_escalating_gate_stays_flat() = runBlocking {
        var now = 0L
        val g = gate(escalate = false, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 60L)

        g.feed429(r)
        val c1 = g.remainingCooldownMs()
        now += c1 + 1
        g.feed429(r)
        val c2 = g.remainingCooldownMs()

        assertEquals(60_000L, c1)
        assertEquals(60_000L, c2) // unchanged — default behavior preserved
    }

    // ── Minimum backoff floor (#273) ──────────────────────────────────

    @Test
    fun floor_applies_when_503_has_no_retry_after() = runBlocking {
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = null))
        assertTrue(
            g.remainingCooldownMs() >= 1_000L,
            "a no-Retry-After 503 must still arm a window (got ${g.remainingCooldownMs()}ms)",
        )
    }

    @Test
    fun floor_rescues_an_explicit_zero_retry_after() = runBlocking {
        // The on-device bug (#273): `Retry-After: 0` → 0ms backoff → window
        // defeated → tight-loop of `Backing off 0s`. The floor lifts it to 1s.
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 0L))
        assertEquals(
            1_000L,
            g.remainingCooldownMs(),
            "Retry-After: 0 must be floored to the 1s minimum, not 0",
        )
    }

    @Test
    fun zero_retry_after_without_floor_yields_zero_window() = runBlocking {
        // Documents the pre-fix behavior the floor protects against: with NO
        // floor (default), `Retry-After: 0` produces a 0ms window.
        var now = 0L
        val g = flooredGate(minCooldown = 0L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 0L))
        assertEquals(0L, g.remainingCooldownMs())
    }

    @Test
    fun larger_server_retry_after_still_wins_over_floor() = runBlocking {
        // The floor is a MINIMUM, not a cap: a generous server window is honored.
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 45L))
        assertEquals(45_000L, g.remainingCooldownMs())
    }

    @Test
    fun escalation_is_capped_at_maxCooldown() = runBlocking {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 3600L) // 1h base
        // 1h → 2h → 4h → 6h(cap) → 6h(cap)
        val seen = mutableListOf<Long>()
        repeat(5) {
            g.feed429(r)
            val rem = g.remainingCooldownMs()
            seen.add(rem)
            now += rem + 1
        }
        assertEquals(6L * 60L * 60L * 1000L, seen.last())
        assertTrue(seen.all { it <= 6L * 60L * 60L * 1000L })
    }
}
