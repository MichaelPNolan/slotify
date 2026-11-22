// Ported from Parachord (MIT License), © Jason Herskowitz.
// https://github.com/Parachord/parachord-mobile — shared/api/RateLimitGate.kt
// Adaptations for Slotify:
//  - Dropped `platform.Log` calls (Slotify has no logging dependency).
//  - `currentTimeMillis()` now resolves via this package's expect fun
//    (com.slotify.app.spotify.currentTimeMillis).
//  - KDoc trimmed where it referenced Parachord-only issues/components.

package com.slotify.app.spotify

import io.ktor.client.statement.HttpResponse
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Per-client rate-limit gate. Provides a single source of truth for
 *   1. **Cooldown** — when the upstream service has signaled a 429 (or 503
 *      for some APIs), all in-flight callers fail fast against this gate
 *      until the suggested `Retry-After` window elapses.
 *   2. **Concurrency limit** — a [Semaphore] caps simultaneous calls to a
 *      conservative number (default 2). This prevents the post-cooldown
 *      burst that would otherwise re-trip the throttle as soon as queued
 *      tasks all fire at once.
 *   3. **Inter-request delay** — a 150ms sleep before each call (after
 *      acquiring the permit).
 *   4. **Optional proactive rolling-window cap** — delay requests *before* a
 *      429 would occur so a cold browse stays under the service's published
 *      ceiling ([maxRequestsPerWindow] / [requestWindowMs]).
 *
 * **Design choices:**
 *  - `maxCooldownMs` default = 1 hour: respect the upstream's `Retry-After`
 *    even when it's 30+ minutes. Capping below it is actively
 *    counterproductive — if Spotify says wait 1997s and we cap at 120s, the
 *    next call after 120s trips a fresh 429 and we loop indefinitely.
 *  - Single global cooldown (not per-method): rate limits are usually
 *    account/IP-wide, so one endpoint's 429 means all calls 429.
 *
 * @param tag diagnostic tag (typically the client name).
 * @param maxConcurrent simultaneous in-flight permit count.
 * @param interRequestDelayMs sleep between successive calls inside the permit.
 * @param defaultCooldownSec used when the response has no `Retry-After`.
 * @param maxCooldownMs hard cap (defense against misbehaving servers).
 * @param minCooldownMs minimum backoff floor: when the response has no
 *   `Retry-After` OR an explicit `Retry-After: 0`, floor the computed backoff
 *   at this value so the short-circuit window is never 0. A larger
 *   server-supplied `Retry-After` always wins (the floor is a MINIMUM).
 * @param loadCooldownEpochMs optional callback invoked once at construction
 *   to rehydrate the cooldown across process restarts.
 * @param saveCooldownEpochMs optional callback fired (fire-and-forget) on
 *   every cooldown write. Paired with [loadCooldownEpochMs].
 * @param escalateOnRepeat when true, each CONSECUTIVE rate-limited response
 *   doubles the backoff (`base * 2^(strikes-1)`, capped at [maxCooldownMs]);
 *   the first non-rate-limited response resets the counter. Spotify opts in:
 *   its abuse-mode ban routinely outlasts a flat 1h cooldown.
 * @param loadStrikes persist the consecutive-strike count across restarts
 *   (paired with [saveStrikes]).
 * @param maxRequestsPerWindow optional proactive rolling-window cap.
 * @param nowMs clock source (epoch ms). Injectable so tests can drive the
 *   cooldown deterministically.
 */
class RateLimitGate(
    private val tag: String,
    maxConcurrent: Int = 2,
    private val interRequestDelayMs: Long = 150L,
    private val defaultCooldownSec: Long = 30L,
    private val maxCooldownMs: Long = 60L * 60L * 1000L,
    private val minCooldownMs: Long = 0L,
    loadCooldownEpochMs: (() -> Long)? = null,
    private val saveCooldownEpochMs: (suspend (Long) -> Unit)? = null,
    private val escalateOnRepeat: Boolean = false,
    loadStrikes: (() -> Int)? = null,
    private val saveStrikes: (suspend (Int) -> Unit)? = null,
    private val maxRequestsPerWindow: Int? = null,
    private val requestWindowMs: Long = 30_000L,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    private val limiter = Semaphore(maxConcurrent)

    /** Sliding window of recent request epoch-ms timestamps for the proactive
     *  [maxRequestsPerWindow] cap. Guarded by [windowMutex]. */
    private val windowMutex = Mutex()
    private val requestTimestamps = mutableListOf<Long>()

    /** Background scope for fire-and-forget cooldown persistence writes. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Epoch-ms timestamp at which calls may resume. Hydrated from disk via
     *  [loadCooldownEpochMs] at construction so process restarts inherit any
     *  active server-side cooldown rather than cold-probing into a fresh
     *  punishment cycle. */
    @Volatile
    private var cooldownUntilMs: Long = loadCooldownEpochMs?.invoke() ?: 0L

    /** Consecutive rate-limited responses with no intervening success.
     *  Drives [escalateOnRepeat]. Restored from [loadStrikes] — unless the
     *  last cooldown ended more than [maxCooldownMs] ago, in which case the
     *  streak is too old to count. */
    @Volatile
    private var consecutiveStrikes: Int =
        if (cooldownUntilMs + maxCooldownMs < nowMs()) 0 else (loadStrikes?.invoke() ?: 0)

    /**
     * Run [block] under the gate. Throws via [exceptionFactory] without
     * making a network call if the cooldown is active. Otherwise acquires a
     * permit, sleeps the inter-request delay, runs [block], and on a
     * rate-limited response (per [isRateLimited]) sets the cooldown + throws.
     *
     * @param isRateLimited matches 429 by default; pass a different predicate
     *   for APIs that signal throttling differently (e.g. 503 + `Retry-After`).
     * @param exceptionFactory builds the typed exception to throw. Receives the
     *   `Retry-After` value in seconds (or null if absent).
     */
    suspend fun <T> withPermit(
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
        block: suspend () -> T,
    ): T {
        checkCooldown(exceptionFactory)
        return limiter.withPermit {
            checkCooldown(exceptionFactory)
            awaitWindowSlot()
            delay(interRequestDelayMs)
            block()
        }
    }

    /**
     * Proactive rolling-window throttle (opt-in via [maxRequestsPerWindow]).
     * Reserves a slot in the sliding [requestWindowMs] window, DELAYING until
     * the window has room so the request rate stays under the cap. No-op when
     * [maxRequestsPerWindow] is null. The window-full wait is held under
     * [windowMutex] so concurrent callers serialize on the same budget.
     */
    private suspend fun awaitWindowSlot() {
        val max = maxRequestsPerWindow ?: return
        windowMutex.withLock {
            while (true) {
                val now = nowMs()
                val cutoff = now - requestWindowMs
                while (requestTimestamps.isNotEmpty() && requestTimestamps[0] <= cutoff) {
                    requestTimestamps.removeAt(0)
                }
                if (requestTimestamps.size < max) {
                    requestTimestamps.add(now)
                    return
                }
                // Window full — wait until the oldest entry ages out, then recheck.
                val waitMs = requestTimestamps[0] + requestWindowMs - now
                if (waitMs > 0) delay(waitMs)
            }
        }
    }

    /**
     * Check if the most recent response indicates rate-limiting, and if
     * so set the cooldown. Call this AFTER the request returns (inside the
     * [withPermit] block), passing the response. Returns normally if NOT
     * rate-limited (caller proceeds) or throws if rate-limited (caller
     * doesn't reach the `body()` decode step).
     */
    fun handleResponse(
        response: HttpResponse,
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
    ) {
        if (!isRateLimited(response)) {
            // A clean response ends any consecutive-strike streak. Critical for
            // the escalating breaker: a recovered call must drop the backoff
            // back to base so a single later 429 doesn't inherit a long cooldown.
            if (consecutiveStrikes != 0) {
                consecutiveStrikes = 0
                persistStrikes(0)
            }
            return
        }
        consecutiveStrikes++
        persistStrikes(consecutiveStrikes)
        val retryAfterSec = response.headers["Retry-After"]?.toLongOrNull()
        val baseMs = (retryAfterSec ?: defaultCooldownSec) * 1000L
        // Escalate: double per consecutive strike (base * 2^(strikes-1)), capped.
        // Shift is bounded so the Long can't overflow before the cap clamps it.
        val computedMs = if (escalateOnRepeat) {
            val shift = (consecutiveStrikes - 1).coerceIn(0, 32)
            (baseMs shl shift).coerceAtMost(maxCooldownMs)
        } else {
            baseMs.coerceAtMost(maxCooldownMs)
        }
        // Floor the window: a no-/zero-`Retry-After` response yields a 0ms
        // backoff, defeating the short-circuit. A larger server value still
        // wins (this is a MINIMUM).
        val backoffMs = max(computedMs, minCooldownMs)
        val wasAlreadyLimited = nowMs() < cooldownUntilMs
        val newCooldown = nowMs() + backoffMs
        cooldownUntilMs = newCooldown
        // Persist (fire-and-forget) so the cooldown survives a process restart.
        // (Parachord logs the first 429 of each cycle here; Slotify has no
        // logging dependency, so the event is silent.)
        saveCooldownEpochMs?.let { save ->
            persistScope.launch { runCatching { save(newCooldown) } }
        }
        throw exceptionFactory(retryAfterSec)
    }

    private fun persistStrikes(value: Int) {
        saveStrikes?.let { save -> persistScope.launch { runCatching { save(value) } } }
    }

    private fun checkCooldown(exceptionFactory: (retryAfterSeconds: Long?) -> Exception) {
        val now = nowMs()
        if (now < cooldownUntilMs) {
            throw exceptionFactory(((cooldownUntilMs - now + 999L) / 1000L).coerceAtLeast(1L))
        }
    }

    /**
     * Milliseconds remaining on the active cooldown, or 0 when clear.
     *
     * Read-only — does NOT acquire a permit or make a call. Lets ungated call
     * paths honor the same cooldown the gated path enforces.
     */
    fun remainingCooldownMs(): Long = (cooldownUntilMs - nowMs()).coerceAtLeast(0L)
}
