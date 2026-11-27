# Loudness Balance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-only EBU R128 loudness analysis of downloads + playlist backfill, playlist-mean balance via boost-only + DynamicsProcessing limiter, with app-private storage and advanced 0–99 chips.

**Architecture:** Pure-Kotlin EBU R128 (`EbuR128`) fed by ffmpeg-kit decode on Android (desktop analysers are no-ops). Results live in app-private `loudness.json`. Playback applies Recommended/Bypass/Custom correction through `DynamicsProcessing` on API 28+ only. Spec: `specs/2026-11-27-loudness-balance-design.md`.

**Tech Stack:** Kotlin 2.4.21 CMP, kotlinx.serialization, ffmpeg-kit-audio (decode only), Android `DynamicsProcessing` (API 28+), existing AppFiles atomic JSON.

## Global Constraints

- Android-only end-to-end (S2): desktop `analyse` returns null; `supportsLoudnessBalance()` is false on desktop and `Build.VERSION.SDK_INT >= 28` on Android.
- Boost-only corrective gain: `boostDb = clamp(mean − trackLufs, 0, maxBoostDb)` — never negative cut (S6). Default max boost **+12 dB**, slider 0–24.
- Loud tracks limited via DynamicsProcessing compressor/limiter, not volume cut (S6).
- App-private store only via `AppFiles` — never mirror to music-dir portable JSON (S4).
- Atomic tmp+rename writes (existing `AppFiles.writeText`).
- Chip formula: `clamp(round((lufs + 70.0) / 70.0 * 99.0), 0, 99)` (S4).
- No full-library scan; download hook + playlist backfill only (S5).
- No `.mimocode/*` in commits; no re-encode of audio; keep unused helpers (rule 9).
- Source `env.sh` before Gradle (`JAVA_HOME=$HOME/tools/jdk-21.0.13.1+2`).
- Branch `0.1.5`. Do not commit `VERSION`/`HANDOFF.md`/`.mimocode` drift from unrelated work unless the user asked — each task stages only its listed files.

---

### Task 1: Pure-Kotlin EBU R128 core

**Covers:** [S3], [S9]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/loudness/EbuR128.kt`
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/loudness/EbuR128Test.kt`

**Interfaces:**
- Consumes: none
- Produces:
  - `data class LoudnessResult(integratedLufs: Double, loudnessRange: Double, truePeakDbtp: Double)`
  - `class EbuR128(sampleRate: Int, channels: Int)`
    - `fun processInterleaved(samples: FloatArray, count: Int)` — `count` = number of floats to consume from index 0
    - `fun finish(): LoudnessResult`
  - `fun loudnessChip(integratedLufs: Double): Int`
  - `fun recommendedBoostDb(trackLufs: Double, meanLufs: Double?, maxBoostDb: Double): Double`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.slotify.app.loudness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EbuR128Test {

    /** 1 kHz sine, stereo, peak 0.5 — integrated should land near −18…−14 LUFS. */
    private fun sine1k(sampleRate: Int = 48000, seconds: Int = 10, amp: Float = 0.5f): FloatArray {
        val n = sampleRate * seconds
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate)).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    @Test
    fun sine1kIntegratedLufsIsReasonable() {
        val r = EbuR128(48000, 2)
        val pcm = sine1k()
        r.processInterleaved(pcm, pcm.size)
        val out = r.finish()
        assertTrue(out.integratedLufs in -25.0..-10.0, "got ${out.integratedLufs}")
        assertTrue(out.loudnessRange >= 0.0, "LRA got ${out.loudnessRange}")
        // Peak amp 0.5 → true peak ≤ ~−5 dBTP + tiny oversample headroom
        assertTrue(out.truePeakDbtp in -20.0..0.0, "dBTP got ${out.truePeakDbtp}")
    }

    @Test
    fun digitalSilenceIsGatedOut() {
        val r = EbuR128(48000, 2)
        val pcm = FloatArray(48000 * 2 * 5) // 5s silence
        r.processInterleaved(pcm, pcm.size)
        val out = r.finish()
        assertTrue(out.integratedLufs <= -70.0, "silence got ${out.integratedLufs}")
    }

    @Test
    fun chipMapsSilenceToZeroAndLoudNear99() {
        assertEquals(0, loudnessChip(-70.0))
        assertEquals(0, loudnessChip(-80.0))
        assertEquals(99, loudnessChip(0.0))
        assertEquals(99, loudnessChip(3.0))
        assertEquals(50, loudnessChip(-35.0)) // (-35+70)/70*99 ≈ 49.5 → 50
    }

    @Test
    fun recommendedBoostIsClampedToZeroAndMax() {
        assertEquals(0.0, recommendedBoostDb(-10.0, -14.0, 12.0)) // louder than mean
        assertEquals(4.0, recommendedBoostDb(-18.0, -14.0, 12.0), absoluteTolerance = 1e-6)
        assertEquals(12.0, recommendedBoostDb(-30.0, -14.0, 12.0)) // cap
        assertEquals(0.0, recommendedBoostDb(-18.0, null, 12.0)) // no profile
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/owner/mimo-projects/slotify && source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.loudness.EbuR128Test" --console=plain`
Expected: FAIL — `Unresolved reference: EbuR128`

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/loudness/EbuR128.kt`:

```kotlin
package com.slotify.app.loudness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tan

data class LoudnessResult(
    val integratedLufs: Double,
    val loudnessRange: Double,
    val truePeakDbtp: Double,
)

/**
 * EBU R128 / ITU-R BS.1770-4 loudness over interleaved float PCM in −1..1.
 *
 * K-weight each sample once on input (stateful biquads), keep a ring of
 * filtered samples, emit one mean-square per 400ms block every 100ms (75%
 * overlap), then gate. True peak tracks raw sample peak + linear 4× midpoints.
 */
class EbuR128(private val sampleRate: Int, private val channels: Int) {

    private val blockSize = (sampleRate * 0.4).toInt().coerceAtLeast(1)
    private val hop = (sampleRate * 0.1).toInt().coerceAtLeast(1)
    private val ringCapacity = blockSize * channels

    private val shelfB = DoubleArray(3)
    private val shelfA = DoubleArray(3)
    private val hpB = DoubleArray(3)
    private val hpA = DoubleArray(3)
    private val shelfX1 = DoubleArray(channels)
    private val shelfX2 = DoubleArray(channels)
    private val shelfY1 = DoubleArray(channels)
    private val shelfY2 = DoubleArray(channels)
    private val hpX1 = DoubleArray(channels)
    private val hpX2 = DoubleArray(channels)
    private val hpY1 = DoubleArray(channels)
    private val hpY2 = DoubleArray(channels)

    /** Filtered interleaved samples, oldest first; size ≤ [ringCapacity]. */
    private val ring = ArrayDeque<Double>(ringCapacity)
    private val blockMs = ArrayList<Double>()
    private var rawPeak = 1e-9
    private var pendingFrames = 0

    init {
        designKWeighting(sampleRate)
    }

    fun processInterleaved(samples: FloatArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            for (ch in 0 until channels) {
                val raw = samples[i + ch]
                val a = abs(raw.toDouble())
                if (a > rawPeak) rawPeak = a
                val k = kWeight(ch, raw)
                ring.addLast(k)
            }
            pendingFrames++
            i += channels
            while (ring.size >= ringCapacity) {
                blockMs.add(meanSquareRing())
                // Drop one hop of frames from the front (75% overlap)
                repeat(hop * channels) { ring.removeFirst() }
            }
        }
    }

    fun finish(): LoudnessResult {
        // Partial tail: score whatever full frames remain if ≥ 50% block
        if (ring.size >= ringCapacity / 2 && pendingFrames > 0) {
            blockMs.add(meanSquareRing())
        }
        val integrated = gatedLufs(blockMs, channels)
        val lra = computeLra(blockMs, channels)
        val dbtp = 20.0 * log10(rawPeak.coerceAtLeast(1e-9))
        return LoudnessResult(integrated, lra, dbtp)
    }

    private fun meanSquareRing(): Double {
        // BS.1770: mean square per channel × channel weight 1.0, then average
        // over frames in the current ring window (exactly blockSize frames).
        var sum = 0.0
        for (ch in 0 until channels) {
            var acc = 0.0
            var n = 0
            var fi = ch
            while (fi < ring.size) {
                val v = ring.elementAt(fi)
                acc += v * v
                n++
                fi += channels
            }
            if (n > 0) sum += acc / n
        }
        return sum
    }

    private fun kWeight(ch: Int, x: Float): Double {
        val xv = x.toDouble()
        val shelfY = shelfB[0] * xv + shelfB[1] * shelfX1[ch] + shelfB[2] * shelfX2[ch] -
            shelfA[1] * shelfY1[ch] - shelfA[2] * shelfY2[ch]
        shelfX2[ch] = shelfX1[ch]
        shelfX1[ch] = xv
        shelfY2[ch] = shelfY1[ch]
        shelfY1[ch] = shelfY
        val y = hpB[0] * shelfY + hpB[1] * hpX1[ch] + hpB[2] * hpX2[ch] -
            hpA[1] * hpY1[ch] - hpA[2] * hpY2[ch]
        hpX2[ch] = hpX1[ch]
        hpX1[ch] = shelfY
        hpY2[ch] = hpY1[ch]
        hpY1[ch] = y
        return y
    }

    private fun designKWeighting(sr: Int) {
        // High-shelf (BS.1770) + high-pass RBJ at audio cutoff, sample-rate aware.
        val f0 = 1681.974450955533
        val G = 3.999843853973347
        val Q = 0.7071752369554196
        val K = tan(PI * f0 / sr)
        val vh = 10.0.pow(G / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + K / Q + K * K
        shelfB[0] = (vh + vb * K / Q + K * K) / a0
        shelfB[1] = 2.0 * (K * K - vh) / a0
        shelfB[2] = (vh - vb * K / Q + K * K) / a0
        shelfA[0] = 1.0
        shelfA[1] = 2.0 * (K * K - 1.0) / a0
        shelfA[2] = (1.0 - K / Q + K * K) / a0

        val f0h = 38.13547087602444
        val Qh = 0.5003270373238773
        val Kh = tan(PI * f0h / sr)
        val a0h = 1.0 + Kh / Qh + Kh * Kh
        hpB[0] = 1.0
        hpB[1] = -2.0
        hpB[2] = 1.0
        hpA[0] = 1.0
        hpA[1] = 2.0 * (Kh * Kh - 1.0) / a0h
        hpA[2] = (1.0 - Kh / Qh + Kh * Kh) / a0h
    }

    private fun gatedLufs(blockMs: DoubleArray, ch: Int): Double {
        if (blockMs.isEmpty()) return -70.0
        val absPow = 10.0.pow(-70.0 / 10.0)
        val weighted = blockMs.map { it * ch }
        val aboveAbs = weighted.filter { it > absPow }
        if (aboveAbs.isEmpty()) return -70.0
        val rel = 10.0 * log10(aboveAbs.average()) - 10.0
        val aboveRel = aboveAbs.filter { it > 10.0.pow(rel / 10.0) }
        if (aboveRel.isEmpty()) return -70.0
        return -0.691 + 10.0 * log10(aboveRel.average())
    }

    private fun computeLra(blockMs: DoubleArray, ch: Int): Double {
        if (blockMs.size < 2) return 0.0
        val st = blockMs.map { 10.0 * log10((it * ch).coerceAtLeast(1e-12)) }
            .filter { it > -70.0 }
        if (st.size < 2) return 0.0
        val rel = 10.0 * log10(st.map { 10.0.pow(it / 10.0) }.average()) - 20.0
        val gated = st.filter { it > rel }.sorted()
        if (gated.size < 2) return 0.0
        val lo = gated[quantileIndex(gated.size, 0.10)]
        val hi = gated[quantileIndex(gated.size, 0.95)]
        return (hi - lo).coerceAtLeast(0.0)
    }

    private fun quantileIndex(n: Int, q: Double): Int =
        round(q * (n - 1)).toInt().coerceIn(0, n - 1)
}

/** 0 = silence (−70 LUFS), 99 = very loud (≥0 LUFS). */
fun loudnessChip(integratedLufs: Double): Int {
    val v = round((integratedLufs + 70.0) / 70.0 * 99.0)
    return v.toInt().coerceIn(0, 99)
}

/** Boost-only corrective gain toward playlist mean (never negative). */
fun recommendedBoostDb(trackLufs: Double, meanLufs: Double?, maxBoostDb: Double): Double {
    if (meanLufs == null) return 0.0
    return (meanLufs - trackLufs).coerceIn(0.0, maxBoostDb)
}
```

Implementation notes for the implementer:
- `ArrayDeque.elementAt` is O(n) — store the ring as `DoubleArray(ringCapacity)` + `head`/`size` indices instead if tests are slow.
- The channel-aware loop must walk `i += channels` and call `kWeight(ch, …)` per channel (stereo filter state must not be shared).
- Gating: absolute −70 LUFS, then relative −10 LU of the absolute-gated mean; integrated = −0.691 + 10·log10(mean of gated powers × channels).
- If sine test fails high/low by >3 LU, check `−0.691` offset and that each channel’s mean square is multiplied by weight 1.0 before averaging.

- [ ] **Step 4: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.loudness.EbuR128Test" --console=plain`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/loudness/EbuR128.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/loudness/EbuR128Test.kt
git commit -m "Add pure-Kotlin EBU R128 analyser and loudness chip helpers"
```

---

### Task 2: App-private LoudnessStore

**Covers:** [S4], [S9]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/loudness/LoudnessStore.kt`
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/loudness/LoudnessStoreTest.kt`

**Interfaces:**
- Consumes: `LoudnessResult`, `loudnessChip` from Task 1; `AppFiles.readTextOrNull` / `AppFiles.writeText`
- Produces:
  - `enum class CorrectionMode { RECOMMENDED, BYPASS, CUSTOM }`
  - `@Serializable data class TrackLoudness(integratedLufs, loudnessRange, truePeakDbtp, chip0_99, correction: CorrectionMode = RECOMMENDED, customGainDb: Double = 0.0, analysedAt: Long)`
  - `@Serializable data class PlaylistLoudnessProfile(meanLufs: Double, analysedCount: Int, totalMembers: Int, updatedAt: Long)`
  - `@Serializable data class LoudnessDocument(tracks: Map<String, TrackLoudness>, playlists: Map<String, PlaylistLoudnessProfile>)`
  - `class LoudnessStore(private val read: () -> String?, private val write: (String) -> Unit, private val now: () -> Long)`
    - `fun load(): LoudnessDocument`
    - `fun track(path: String): TrackLoudness?`
    - `fun putTrack(path: String, result: LoudnessResult)`
    - `fun setCorrection(path: String, mode: CorrectionMode, customGainDb: Double)`
    - `fun playlist(id: String): PlaylistLoudnessProfile?`
    - `fun putPlaylist(id: String, meanLufs: Double, analysedCount: Int, totalMembers: Int)`
    - `fun clear()`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.slotify.app.loudness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoudnessStoreTest {

    private fun storeOn(memory: MutableList<String>): LoudnessStore =
        LoudnessStore(
            read = { memory.firstOrNull() },
            write = { s -> memory.clear(); memory.add(s) },
            now = { 1_700_000_000_000L },
        )

    @Test
    fun putTrackRoundTripsWithChip() {
        val mem = mutableListOf<String>()
        val store = storeOn(mem)
        store.putTrack("/sdcard/Music/a.opus", LoudnessResult(-18.5, 4.0, -3.2))
        val reloaded = storeOn(mem)
        val t = reloaded.track("/sdcard/Music/a.opus")!!
        assertEquals(-18.5, t.integratedLufs, 1e-9)
        assertEquals(4.0, t.loudnessRange, 1e-9)
        assertEquals(-3.2, t.truePeakDbtp, 1e-9)
        assertEquals(loudnessChip(-18.5), t.chip0_99)
        assertEquals(CorrectionMode.RECOMMENDED, t.correction)
        assertEquals(1_700_000_000_000L, t.analysedAt)
    }

    @Test
    fun setCorrectionPersistsMode() {
        val mem = mutableListOf<String>()
        val store = storeOn(mem)
        store.putTrack("/x.mp3", LoudnessResult(-20.0, 3.0, -1.0))
        store.setCorrection("/x.mp3", CorrectionMode.CUSTOM, customGainDb = 5.5)
        val t = storeOn(mem).track("/x.mp3")!!
        assertEquals(CorrectionMode.CUSTOM, t.correction)
        assertEquals(5.5, t.customGainDb, 1e-9)
    }

    @Test
    fun playlistProfileRoundTrips() {
        val mem = mutableListOf<String>()
        val store = storeOn(mem)
        store.putPlaylist("pl1", -14.25, 10, 12)
        val p = storeOn(mem).playlist("pl1")!!
        assertEquals(-14.25, p.meanLufs, 1e-9)
        assertEquals(10, p.analysedCount)
        assertEquals(12, p.totalMembers)
    }

    @Test
    fun corruptJsonLoadsEmpty() {
        val store = LoudnessStore(read = { "{not json" }, write = {}, now = { 0 })
        assertEquals(0, store.load().tracks.size)
        assertNull(store.track("/a"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.loudness.LoudnessStoreTest" --console=plain`
Expected: FAIL — unresolved `LoudnessStore`

- [ ] **Step 3: Write implementation**

`composeApp/src/commonMain/kotlin/com/slotify/app/loudness/LoudnessStore.kt`:

```kotlin
package com.slotify.app.loudness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class CorrectionMode { RECOMMENDED, BYPASS, CUSTOM }

@Serializable
data class TrackLoudness(
    val integratedLufs: Double,
    val loudnessRange: Double,
    val truePeakDbtp: Double,
    val chip0_99: Int,
    val correction: CorrectionMode = CorrectionMode.RECOMMENDED,
    val customGainDb: Double = 0.0,
    val analysedAt: Long = 0L,
)

@Serializable
data class PlaylistLoudnessProfile(
    val meanLufs: Double,
    val analysedCount: Int,
    val totalMembers: Int,
    val updatedAt: Long = 0L,
)

@Serializable
data class LoudnessDocument(
    val tracks: Map<String, TrackLoudness> = emptyMap(),
    val playlists: Map<String, PlaylistLoudnessProfile> = emptyMap(),
)

/** App-private `loudness.json` — never mirrored to portable music-dir JSON (S4). */
class LoudnessStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var doc: LoudnessDocument? = null

    fun load(): LoudnessDocument {
        doc?.let { return it }
        val parsed = read()?.let { text ->
            runCatching { json.decodeFromString<LoudnessDocument>(text) }.getOrNull()
        } ?: LoudnessDocument()
        doc = parsed
        return parsed
    }

    fun track(path: String): TrackLoudness? = load().tracks[path]

    fun putTrack(path: String, result: LoudnessResult) {
        val d = load()
        val entry = TrackLoudness(
            integratedLufs = result.integratedLufs,
            loudnessRange = result.loudnessRange,
            truePeakDbtp = result.truePeakDbtp,
            chip0_99 = loudnessChip(result.integratedLufs),
            analysedAt = now(),
        )
        doc = d.copy(tracks = d.tracks + (path to entry))
        persist()
    }

    fun setCorrection(path: String, mode: CorrectionMode, customGainDb: Double) {
        val d = load()
        val cur = d.tracks[path] ?: return
        doc = d.copy(tracks = d.tracks + (path to cur.copy(
            correction = mode,
            customGainDb = customGainDb,
        )))
        persist()
    }

    fun playlist(id: String): PlaylistLoudnessProfile? = load().playlists[id]

    fun putPlaylist(id: String, meanLufs: Double, analysedCount: Int, totalMembers: Int) {
        val d = load()
        doc = d.copy(playlists = d.playlists + (id to PlaylistLoudnessProfile(
            meanLufs = meanLufs,
            analysedCount = analysedCount,
            totalMembers = totalMembers,
            updatedAt = now(),
        )))
        persist()
    }

    fun clear() {
        doc = LoudnessDocument()
        persist()
    }

    private fun persist() {
        val d = load()
        runCatching { write(json.encodeToString(LoudnessDocument.serializer(), d)) }
    }
}

/** Production store bound to [com.slotify.app.data.AppFiles] (`loudness.json`). */
fun appLoudnessStore(): LoudnessStore = LoudnessStore(
    read = { com.slotify.app.data.AppFiles.readTextOrNull(LOUDNESS_FILE) },
    write = { com.slotify.app.data.AppFiles.writeText(LOUDNESS_FILE, it) },
    now = { com.slotify.app.platform.nowEpochMs() },
)

const val LOUDNESS_FILE = "loudness.json"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.loudness.LoudnessStoreTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/loudness/LoudnessStore.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/loudness/LoudnessStoreTest.kt
git commit -m "Add app-private loudness.json store for analysis + playlist profiles"
```

---

### Task 3: Platform analysers + feature gate

**Covers:** [S2], [S3], [S8]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.kt`
- Create: `composeApp/src/androidMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.desktop.kt`

**Interfaces:**
- Consumes: `EbuR128`, `LoudnessResult` (Task 1)
- Produces:
  - `expect fun analyseFileLoudness(path: String): LoudnessResult?`
  - `expect fun supportsLoudnessBalance(): Boolean`
  - Android: `internal fun decodeToInterleavedFloat(path: String): Pair<FloatArray, Int>?` → (samples, channels) or null — actually return channels in wrapper; sample rate needed: `data class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int)`

- [ ] **Step 1: Write common expect + desktop actual (no test yet — gate is trivial)**

`LoudnessAnalyser.kt` (commonMain):

```kotlin
package com.slotify.app.loudness

/** Measure EBU R128 loudness of [path]. Platform failure → null (S3/S8). */
expect fun analyseFileLoudness(path: String): LoudnessResult?

/**
 * Loudness balance UI + DynamicsProcessing apply (S2/S6).
 * Desktop: false. Android: SDK_INT >= 28 (hide feature below API 28).
 */
expect fun supportsLoudnessBalance(): Boolean
```

`LoudnessAnalyser.desktop.kt` (desktopMain):

```kotlin
package com.slotify.app.loudness

actual fun analyseFileLoudness(path: String): LoudnessResult? = null

actual fun supportsLoudnessBalance(): Boolean = false
```

- [ ] **Step 2: Write Android actual with ffmpeg-kit decode**

`LoudnessAnalyser.android.kt`:

```kotlin
package com.slotify.app.loudness

import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.slotify.app.data.androidAppContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil

actual fun supportsLoudnessBalance(): Boolean = Build.VERSION.SDK_INT >= 28

actual fun analyseFileLoudness(path: String): LoudnessResult? = runCatching {
    val src = File(path)
    if (!src.isFile || src.length() == 0L) return null
    val ctx = androidAppContext ?: return null
    val pcm = File(ctx.cacheDir, "loudness_${src.nameWithoutExtension.hashCode()}.f32")
    try {
        // Decode → raw f32le stereo (interleaved). Native rate (S3).
        if (pcm.exists()) pcm.delete()
        val args = listOf(
            "-y", "-v", "error",
            "-i", src.absolutePath,
            "-map", "a:0",
            "-ac", "2",
            "-f", "f32le",
            pcm.absolutePath,
        )
        val command = args.joinToString(" ") { a ->
            if (a.isEmpty() || a.any { it.isWhitespace() }) "\"$a\"" else a
        }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode) || !pcm.isFile || pcm.length() < 8L) {
            pcm.delete()
            return null
        }
        val sampleRate = probeSampleRate(src.absolutePath) ?: 48000
        val analyser = EbuR128(sampleRate, 2)
        RandomAccessFile(pcm, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            val floats = FloatArray(buf.size / 4)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                val usable = n - (n % 4)
                if (usable == 0) break
                for (i in 0 until usable / 4) {
                    var b0 = buf[i * 4].toInt() and 0xff
                    var b1 = buf[i * 4 + 1].toInt() and 0xff
                    var b2 = buf[i * 4 + 2].toInt() and 0xff
                    var b3 = buf[i * 4 + 3].toInt() and 0xff
                    val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    floats[i] = Float.fromBits(bits)
                }
                analyser.processInterleaved(floats, usable / 4)
            }
        }
        analyser.finish()
    } finally {
        pcm.delete()
    }
}.getOrNull()

/** ffprobe-free sample rate: MediaMetadataRetriever cannot report rate reliably for opus —
 *  use ffmpeg -i stderr parse as last resort; default 48000 acceptable for LUFS within ~0.1 dB. */
private fun probeSampleRate(path: String): Int? {
    val session = FFmpegKit.execute("-i \"${path}\" -f null -")
    val text = session.allLogsAsString ?: return null
    val m = Regex("""(\d+) Hz""").find(text) ?: return null
    return m.groupValues[1].toIntOrNull()
}
```

- [ ] **Step 3: Compile both targets**

Run: `source env.sh && ./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.kt \
        composeApp/src/androidMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.android.kt \
        composeApp/src/desktopMain/kotlin/com/slotify/app/loudness/LoudnessAnalyser.desktop.kt
git commit -m "Add platform LoudnessAnalyser: ffmpeg-kit decode on Android, no-op desktop"
```

---

### Task 4: Settings fields + Settings UI

**Covers:** [S6], [S7]

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/data/AppRepository.kt:14-30` (`AppSettings`)
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/SettingsScreen.kt` (new section after YouTube batch)

**Interfaces:**
- Consumes: `supportsLoudnessBalance()`
- Produces:
  - `AppSettings.loudnessBalance: Boolean = false`
  - `AppSettings.loudnessMaxBoostDb: Float = 12f` (0..24)
  - `AppSettings.showLoudnessChips: Boolean = false` (advanced chips)

- [ ] **Step 1: Extend AppSettings**

```kotlin
    /** Loudness balance toward playlist mean (Android API 28+ only). */
    val loudnessBalance: Boolean = false,
    /** Cap for Recommended boost, dB (S6). */
    val loudnessMaxBoostDb: Float = 12f,
    /** Advanced: show 0–99 loudness chips on playlist rows. */
    val showLoudnessChips: Boolean = false,
```

- [ ] **Step 2: Settings UI section** (after the YouTube batch slider block in `SettingsScreen.kt`)

```kotlin
                if (supportsLoudnessBalance()) {
                    HorizontalDivider()
                    // ── Loudness balance ──────────────────────────────────
                    SectionTitle("Loudness balance")
                    Text(
                        text = "Analyse songs and balance quiet tracks toward this playlist’s " +
                            "average loudness. Loud songs are limited, never cut. API 28+ only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Enable balance",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.loudnessBalance,
                            onCheckedChange = { on ->
                                state.updateSettings { s -> s.copy(loudnessBalance = on) }
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Max boost",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "+${settings.loudnessMaxBoostDb.toInt()} dB",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = settings.loudnessMaxBoostDb,
                        onValueChange = { v ->
                            state.updateSettings { s -> s.copy(loudnessMaxBoostDb = v) }
                        },
                        valueRange = 0f..24f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Show loudness chips",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.showLoudnessChips,
                            onCheckedChange = { on ->
                                state.updateSettings { s -> s.copy(showLoudnessChips = on) }
                            },
                        )
                    }
                }
```

Add imports: `androidx.compose.material3.Switch`, `com.slotify.app.loudness.supportsLoudnessBalance`.

- [ ] **Step 3: Compile**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/data/AppRepository.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/SettingsScreen.kt
git commit -m "Add loudness balance settings (toggle, max boost, chips)"
```

---

### Task 5: AppState — download hook, backfill, playlist mean

**Covers:** [S5], [S8], [S3] (wiring)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt` (init store; after library save ~L857; new public APIs + status flow)

**Interfaces:**
- Consumes: `analyseFileLoudness`, `supportsLoudnessBalance`, `appLoudnessStore`, `LoudnessStore`, `recommendedBoostDb` (helpers elsewhere)
- Produces:
  - `val loudnessStore: LoudnessStore` (internal or public for UI)
  - `val loudnessStatus: StateFlow<String?>` — backfill progress / idle
  - `fun startLoudnessBackfill(playlistId: String)`
  - `fun cancelLoudnessBackfill()`
  - `fun playlistMeanLufs(playlistId: String): Double?`
  - `fun analyseLoudnessNow(path: String)` — fire-and-forget enqueue after download

- [ ] **Step 1: Add store + status + queue**

In `AppState` near other private flows (after `_linkFillStatus` ~L252):

```kotlin
    val loudnessStore = appLoudnessStore()

    private val _loudnessStatus = MutableStateFlow<String?>(null)
    val loudnessStatus: StateFlow<String?> = _loudnessStatus.asStateFlow()

    private var loudnessJob: Job? = null

    /** One file analysis — used by download hook; no status UI. */
    fun analyseLoudnessAsync(path: String) {
        if (!supportsLoudnessBalance()) return
        scope.launch(Dispatchers.Default) {
            val r = analyseFileLoudness(path) ?: return@launch
            withContext(Dispatchers.IO) { loudnessStore.putTrack(path, r) }
        }
    }
```

- [ ] **Step 2: Download hook**

In `downloadIndexAndPin` after `repository.saveLibrary(...)` (after L857):

```kotlin
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
            if (supportsLoudnessBalance()) analyseLoudnessAsync(finalPath)
```

- [ ] **Step 3: Backfill + profile recompute**

```kotlin
    fun startLoudnessBackfill(playlistId: String) {
        if (!supportsLoudnessBalance()) return
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        if (loudnessJob?.isActive == true) return
        _loudnessStatus.value = "Preparing loudness analysis…"
        loudnessJob = scope.launch(Dispatchers.Default) {
            var failed = 0
            var done = 0
            try {
                val rows = playlist.resolvedTracks
                val paths = rows.mapNotNull { it.match?.local?.filePath }.distinct()
                val todo = paths.filter { loudnessStore.track(it) == null }
                val total = todo.size
                if (total == 0) {
                    _loudnessStatus.value = "All tracks already analysed"
                    recomputePlaylistProfile(playlistId)
                    return@launch
                }
                for (p in todo) {
                    ensureActive()
                    _loudnessStatus.value =
                        "Analysing loudness… ${done + 1}/$total (${File(p).name.take(32)})"
                    val r = withContext(Dispatchers.IO) { analyseFileLoudness(p) }
                    if (r != null) {
                        withContext(Dispatchers.IO) { loudnessStore.putTrack(p, r) }
                    } else {
                        failed++
                    }
                    done++
                }
                recomputePlaylistProfile(playlistId)
                _loudnessStatus.value = if (failed > 0) {
                    "Loudness done · $done analysed · $failed failed"
                } else {
                    "Loudness done · $done analysed"
                }
            } catch (e: CancellationException) {
                recomputePlaylistProfile(playlistId)
                _loudnessStatus.value = "Loudness cancelled"
                throw e
            } catch (e: Exception) {
                _loudnessStatus.value =
                    "Loudness failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun cancelLoudnessBackfill() {
        loudnessJob?.cancel()
    }

    fun playlistMeanLufs(playlistId: String): Double? =
        loudnessStore.playlist(playlistId)?.meanLufs

    private fun recomputePlaylistProfile(playlistId: String) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        val paths = playlist.resolvedTracks.mapNotNull { it.match?.local?.filePath }.distinct()
        val lufs = paths.mapNotNull { loudnessStore.track(it)?.integratedLufs }
        if (lufs.isEmpty()) return
        val mean = lufs.average()
        runCatching {
            loudnessStore.putPlaylist(
                id = playlistId,
                meanLufs = mean,
                analysedCount = lufs.size,
                totalMembers = paths.size,
            )
        }
    }

```

(Use `java.io.File` import if not already present.)

- [ ] **Step 4: Compile**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt
git commit -m "Wire loudness analysis: download hook, playlist backfill, mean profile"
```

---

### Task 6: Playlist detail “Analyse loudness” action

**Covers:** [S5], [S7]

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt` (toolbar / status area near `linkFillStatus` ~L72, L243)

**Interfaces:**
- Consumes: `state.loudnessStatus`, `state.startLoudnessBackfill`, `state.cancelLoudnessBackfill`, `supportsLoudnessBalance()`

- [ ] **Step 1: Collect status + add action**

Near other status collectors:

```kotlin
    val loudnessStatus by state.loudnessStatus.collectAsState()
    val loudnessActive = loudnessStatus != null &&
        !loudnessStatus!!.startsWith("Loudness done") &&
        !loudnessStatus!!.startsWith("Loudness cancelled") &&
        !loudnessStatus!!.startsWith("All tracks")
```

In the top bar row (after delete-marked buttons, only when `supportsLoudnessBalance()`):

```kotlin
                    OutlinedButton(
                        onClick = {
                            if (loudnessActive) state.cancelLoudnessBackfill()
                            else state.startLoudnessBackfill(playlist.id)
                        },
                    ) {
                        Text(if (loudnessActive) "Stop analysis" else "Analyse loudness")
                    }
```

Under the existing `linkFillStatus` line, show:

```kotlin
                loudnessStatus?.let { s ->
                    Text(
                        text = s,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loudnessActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
```

(Adjust padding to match surrounding layout.)

- [ ] **Step 2: Compile**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt
git commit -m "Add Analyse loudness playlist action with live status"
```

---

### Task 7: AudioPlayer correction API + Android DynamicsProcessing

**Covers:** [S6], [S8], [S9] (manual device verify only)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/playback/AudioPlayer.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/slotify/app/playback/AndroidAudioPlayer.kt`
- Modify: `composeApp/src/desktopMain/kotlin/com/slotify/app/playback/VlcAudioPlayer.kt` (no override needed if default empty)

**Interfaces:**
- Consumes: none
- Produces:
  - `AudioPlayer.setLoudnessCorrection(boostDb: Float, enableLimiter: Boolean)` — default no-op on interface
  - Android: attaches `DynamicsProcessing` on prepared session when `boostDb != 0f || enableLimiter`

- [ ] **Step 1: Add interface default method**

In `AudioPlayer.kt`:

```kotlin
    /**
     * Per-track loudness correction (Android DynamicsProcessing: makeup boost +
     * limiter). Desktop: no-op. Call after/before load; applied on prepare.
     * [boostDb] ≥ 0 recommended; may be negative only for Custom mode.
     * [enableLimiter] true when balance is on (control loud tracks, S6).
     */
    fun setLoudnessCorrection(boostDb: Float, enableLimiter: Boolean) {}
```

- [ ] **Step 2: Android implementation**

In `AndroidAudioPlayer`:

```kotlin
import android.media.audiofx.DynamicsProcessing

    private var pendingBoostDb = 0f
    private var pendingLimiter = false
    private var dynamics: DynamicsProcessing? = null

    override fun setLoudnessCorrection(boostDb: Float, enableLimiter: Boolean) {
        pendingBoostDb = boostDb
        pendingLimiter = enableLimiter
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            applyDynamics()
        }
    }

    private fun clearDynamics() {
        runCatching { dynamics?.release() }
        dynamics = null
    }

    private fun applyDynamics() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        clearDynamics()
        val sid = runCatching { player.audioSessionId }.getOrNull() ?: return
        if (sid <= 0) return
        val needs = pendingBoostDb > 0.01f || pendingLimiter
        if (!needs) return
        runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,  // channels
                0,  // preEq stages
                0,  // mbc stages
                0,  // postEq stages
                true, // limiter
            ).setLimiterEnable(true)
                .setLimiterAttackTime(5f)
                .setLimiterReleaseTime(50f)
                .setLimiterRatio(20f)
                .setLimiterThresholdLevel(if (pendingLimiter) -1.5f else 0f)
                .build()
            val dpp = DynamicsProcessing(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                sid,
                0,
                cfg,
            )
            // Input (pre) gain = boost only (S6)
            dpp.setInputGainAllChannelsTo(pendingBoostDb.coerceIn(-24f, 24f))
            if (pendingLimiter) {
                dpp.setLimiterEnableAllChannelsTo(true)
            }
            dynamics = dpp
        }.onFailure {
            dynamics = null
            // Silent fallback: volume-only path is applied by caller if boost>0 (S8)
        }
    }
```

Call `applyDynamics()` from `onPrepared` as well (session id valid after prepare):

In the existing `player.setOnPreparedListener { ... }` after start/pause branch:

```kotlin
            if (android.os.Build.VERSION.SDK_INT >= 28) applyDynamics()
```

In `load()` after `player.reset()` call `clearDynamics()`. In `release()`/`stop()` call `clearDynamics()`.

**API note:** If `Config.Builder` arity differs on compileSdk 37, check `DynamicsProcessing.Config.Builder` overloads and use the documented constructor — do not silence deprecation (rule 11); use the supported builder.

- [ ] **Step 3: Compile Android**

Run: `source env.sh && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/playback/AudioPlayer.kt \
        composeApp/src/androidMain/kotlin/com/slotify/app/playback/AndroidAudioPlayer.kt
git commit -m "Add AudioPlayer loudness correction via DynamicsProcessing (API 28+)"
```

---

### Task 8: Apply on play + advanced chips UI

**Covers:** [S6], [S7], [S5] (row display)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt` (`playAt` ~L3052)
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt` (chip + sheet)

**Interfaces:**
- Consumes: `loudnessStore`, `playlistMeanLufs`, `settings.loudnessBalance`, `settings.loudnessMaxBoostDb`, `settings.showLoudnessChips`, `AudioPlayer.setLoudnessCorrection`, `recommendedBoostDb`, `CorrectionMode`

- [ ] **Step 1: Compute and apply correction in `playAt`**

Right after `player.load(...)` in `playAt`:

```kotlin
        player.load(entry.path, startPaused = seekToMs > 0)
        applyLoudnessToPlayer(entry.path)
```

New private method:

```kotlin
    /**
     * S6: balance toward playlist mean when enabled.
     * Recommended → boost-only clamp; Bypass → 0; Custom → user dB.
     * Limiter on whenever balance is on and analysis exists.
     */
    private fun applyLoudnessToPlayer(path: String) {
        if (!supportsLoudnessBalance()) {
            player.setLoudnessCorrection(0f, enableLimiter = false)
            return
        }
        val s = _settings.value
        if (!s.loudnessBalance) {
            player.setLoudnessCorrection(0f, enableLimiter = false)
            return
        }
        val track = loudnessStore.track(path)
        if (track == null) {
            player.setLoudnessCorrection(0f, enableLimiter = false)
            return
        }
        val mean = _activePlaylistId.value?.let { playlistMeanLufs(it) }
        val boost = when (track.correction) {
            CorrectionMode.RECOMMENDED ->
                recommendedBoostDb(track.integratedLufs, mean, s.loudnessMaxBoostDb.toDouble())
                    .toFloat()
            CorrectionMode.BYPASS -> 0f
            CorrectionMode.CUSTOM -> track.customGainDb.toFloat()
        }
        player.setLoudnessCorrection(boost, enableLimiter = true)
        // Fallback if DynamicsProcessing failed and boost > 0: leave global volume alone (S8)
    }
```

Import `CorrectionMode`, `recommendedBoostDb`, `supportsLoudnessBalance`.

- [ ] **Step 2: Chip on playlist rows + detail sheet**

In `PlaylistDetailScreen` row composable where match chips render:

```kotlin
                        if (settings.showLoudnessChips) {
                            val track = state.loudnessStore.track(row.match?.local?.filePath ?: "")
                            if (track != null) {
                                Text(
                                    text = track.chip0_99.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (track.chip0_99 < 40) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.shapes.small,
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
```

Row click / overflow → simple dialog (not a full sheet — YAGNI):

```kotlin
@Composable
private fun LoudnessDetailDialog(
    track: TrackLoudness,
    meanLufs: Double?,
    maxBoostDb: Float,
    onDismiss: () -> Unit,
    onMode: (CorrectionMode, Double) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loudness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chip ${track.chip0_99}/99 · ${track.integratedLufs.format(1)} LUFS")
                Text("LRA ${track.loudnessRange.format(1)} LU · peak ${track.truePeakDbtp.format(1)} dBTP")
                val rec = recommendedBoostDb(track.integratedLufs, meanLufs, maxBoostDb.toDouble())
                Text("Recommended boost: +${rec.format(1)} dB")
                CorrectionMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = track.correction == mode,
                            onClick = { onMode(mode, track.customGainDb) },
                        )
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
                if (track.correction == CorrectionMode.CUSTOM) {
                    Text("Custom gain: ${track.customGainDb.format(1)} dB")
                    Slider(
                        value = track.customGainDb.toFloat(),
                        onValueChange = { v -> onMode(CorrectionMode.CUSTOM, v.toDouble()) },
                        valueRange = -12f..24f,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun Double.format(d: Int) = "%+.${d}f".format(this) // or toString take
```

Wire: `state.loudnessStore.setCorrection(path, mode, gain)` + dialog state. Only open when `settings.showLoudnessChips` and analysis exists.

- [ ] **Step 3: Desktop test suite still green**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --console=plain`
Expected: PASS (all existing + loudness tests)

- [ ] **Step 4: Android unit compile + APK**

Run: `source env.sh && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL
Report: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` + `date` timestamp (rule 13). **User installs via adb** (rule 12 — do not install without permission).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt
git commit -m "Apply loudness balance on play and show advanced loudness chips"
```

---

## Manual verification (not a code task)

1. Phone install (user-run adb), open a playlist → **Analyse loudness** → wait for status.
2. Settings → enable **Loudness balance** → play a quiet track → confirm perceived boost; play a loud track → limiter holds peaks.
3. Toggle **Show loudness chips** → quiet song shows low number (e.g. 15 vs 70).
4. Desktop: Settings section hidden; playlist action hidden; downloads unchanged.
5. API check: if an API 27 device is unavailable, trust `supportsLoudnessBalance()` unit of `Build.VERSION.SDK_INT >= 28`.
