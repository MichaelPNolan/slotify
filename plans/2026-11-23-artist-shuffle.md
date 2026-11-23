# Artist Shuffle + Library Session UI Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/artist-shuffle.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four cycled playback modes (Linear / Shuffle / Artist (2) / Artist (all)) with a played-set + confirm-reset popup, plus library row marks, Find current, highlight/grey-out, per-row `+` add-to-playlist, narrow m/s/d mark chips, and tappable % badge.

**Architecture:** Hybrid playback — LINEAR/SHUFFLE keep the precomputed queue order (now also applied to the library); ARTIST_2/ARTIST_ALL pick each next song through a pure `ArtistSession` that appends to a history-only queue. One in-memory `playedKeys` set (library file paths) drives grey-out and the no-repeat guarantee; UI screens read new/updated StateFlows from `AppState`.

**Tech Stack:** Kotlin / Compose Multiplatform (commonMain), kotlinx-coroutines StateFlow, kotlinx-serialization persistence, `kotlin.test` in `commonTest`, Gradle (`./gradlew`).

## Global Constraints

- Spec: `specs/2026-11-23-artist-shuffle-design.md` (sections `[S1]`–`[S13]`). Task `Covers:` lines trace to it.
- NEVER `sudo` (unless the user explicitly grants it for system packages).
- Spotify = metadata only, never audio. Audio whitelist: `aac,mp3,mp4,flac,wav,aif,aiff,opus`. YouTube stream copy must never re-encode.
- Keep unused functions (do not delete helpers that lose their only caller; superseded private composables may be replaced by a shared equivalent).
- Do NOT silence Gradle/Kotlin warnings — replace deprecated APIs instead.
- Do NOT commit: `.mimocode/`, `spotify-app.txt`, `~/.local/share/slotify/*`, cookies/Client IDs. Stage only the specific files each task names (the worktree has unrelated dirty files).
- Build/test commands require `source env.sh` first (JDK 21 + Android SDK). Tests: `./gradlew :composeApp:desktopTest`. Full verification: `./gradlew :composeApp:desktopTest :androidApp:assembleDebug`.
- Play modes per spec: `LINEAR → SHUFFLE → ARTIST_2 → ARTIST_ALL → LINEAR`.
- `playedKeys` is in-memory only; artist session state is in-memory only; mode + library marks persist.
- Response style: concise; commit messages short and imperative.

---

### Task 1: PlaybackMode enum, cycle, storage mapping

**Covers:** S3 (cycle order), S11 (mode persistence)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/playback/PlaybackMode.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/data/AppRepository.kt:33-38` (`PlaybackStore` gains `mode`)
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/playback/PlaybackModeTest.kt`

**Interfaces:**
- Consumes: `com.slotify.app.data.PlaybackStore`
- Produces: `enum class PlaybackMode { LINEAR, SHUFFLE, ARTIST_2, ARTIST_ALL }`, `fun PlaybackMode.cycle(): PlaybackMode`, `fun PlaybackMode.isArtistMode(): Boolean`, `fun PlaybackStore.initialMode(): PlaybackMode`, `PlaybackStore(mode: String = "")`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.slotify.app.playback

import com.slotify.app.data.PlaybackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackModeTest {
    @Test
    fun cycleWrapsThroughAllFourModesInOrder() {
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.LINEAR.cycle())
        assertEquals(PlaybackMode.ARTIST_2, PlaybackMode.SHUFFLE.cycle())
        assertEquals(PlaybackMode.ARTIST_ALL, PlaybackMode.ARTIST_2.cycle())
        assertEquals(PlaybackMode.LINEAR, PlaybackMode.ARTIST_ALL.cycle())
    }

    @Test
    fun artistModesFlag() {
        assertTrue(PlaybackMode.ARTIST_2.isArtistMode())
        assertTrue(PlaybackMode.ARTIST_ALL.isArtistMode())
        assertFalse(PlaybackMode.SHUFFLE.isArtistMode())
        assertFalse(PlaybackMode.LINEAR.isArtistMode())
    }

    @Test
    fun storedModeWinsOverLegacyShuffleFlag() {
        assertEquals(
            PlaybackMode.ARTIST_ALL,
            PlaybackStore(shuffle = false, mode = "ARTIST_ALL").initialMode(),
        )
        assertEquals(PlaybackMode.SHUFFLE, PlaybackStore(shuffle = true, mode = "").initialMode())
        assertEquals(PlaybackMode.LINEAR, PlaybackStore(shuffle = false, mode = "").initialMode())
        // Unreadable names fall back to the legacy flag.
        assertEquals(PlaybackMode.SHUFFLE, PlaybackStore(shuffle = true, mode = "GARBAGE").initialMode())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.playback.*"`
Expected: FAIL — compile error `unresolved reference: PlaybackMode` (and `mode` unknown on `PlaybackStore`).

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/playback/PlaybackMode.kt`:

```kotlin
package com.slotify.app.playback

import com.slotify.app.data.PlaybackStore

/** Player-bar cycle: Linear → Shuffle → Artist (2) → Artist (all). */
enum class PlaybackMode { LINEAR, SHUFFLE, ARTIST_2, ARTIST_ALL }

fun PlaybackMode.cycle(): PlaybackMode = entries[(ordinal + 1) % entries.size]

fun PlaybackMode.isArtistMode(): Boolean =
    this == PlaybackMode.ARTIST_2 || this == PlaybackMode.ARTIST_ALL

/** Restore mode from the playback store; legacy files only carry `shuffle`. */
fun PlaybackStore.initialMode(): PlaybackMode =
    mode.takeIf { it.isNotBlank() }
        ?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
        ?: if (shuffle) PlaybackMode.SHUFFLE else PlaybackMode.LINEAR
```

Modify `PlaybackStore` in `AppRepository.kt`:

```kotlin
/** Global playback session state persisted to [AppRepository.PLAYBACK_FILE]. */
@Serializable
data class PlaybackStore(
    val activePlaylistId: String? = null,
    val shuffle: Boolean = false,
    /** [com.slotify.app.playback.PlaybackMode].name — "" on legacy files. */
    val mode: String = "",
    val byPlaylist: Map<String, PlaylistProgress> = emptyMap(),
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.playback.*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/playback/PlaybackMode.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/data/AppRepository.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/playback/PlaybackModeTest.kt
git commit -m "Add PlaybackMode cycle + store mapping"
```

---

### Task 2: ArtistSession (pure pick logic)

**Covers:** S5 (artist passes, no repeats, pass completion)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/playback/ArtistSession.kt`
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/playback/ArtistSessionTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + `kotlin.random.Random`)
- Produces: `data class ArtistSourceItem(val key: String, val artist: String)`, `class ArtistSession(items: List<ArtistSourceItem>, songsPerArtist: Int, random: Random = Random.Default)` with `fun next(played: Set<String>): String?`, `fun reshuffle()`, `fun restartAround(artist: String)`, `val isPassComplete: Boolean`, `fun artistOf(key: String): String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.slotify.app.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSessionTest {

    private fun items() = listOf(
        ArtistSourceItem("a1", "Artist A"), ArtistSourceItem("a2", "Artist A"), ArtistSourceItem("a3", "Artist A"),
        ArtistSourceItem("b1", "Artist B"), ArtistSourceItem("b2", "Artist B"), ArtistSourceItem("b3", "Artist B"),
        ArtistSourceItem("c1", "Artist C"), ArtistSourceItem("c2", "Artist C"), ArtistSourceItem("c3", "Artist C"),
    )

    /** Drain one pass: every pick is fed back into [played] like the app does. */
    private fun drainPass(session: ArtistSession, played: MutableSet<String>): List<String> {
        val picks = mutableListOf<String>()
        while (true) {
            val key = session.next(played) ?: break
            assertTrue(key !in played, "repeated $key within a pass")
            picks += key
            played += key
        }
        return picks
    }

    @Test
    fun artistTwoPlaysTwoPerArtistThenMovesOn() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(1))
        val played = mutableSetOf<String>()
        val picks = drainPass(session, played)
        assertEquals(6, picks.size, "3 artists × 2 songs")
        // Contiguous runs of ≤2 per artist, artists distinct per run.
        val runs = picks.chunked(2)
        assertEquals(3, runs.size)
        assertEquals(3, runs.map { session.artistOf(it.first()) }.distinct().size, "each run a different artist")
        runs.forEach { run -> run.forEach { assertEquals(session.artistOf(run.first()), session.artistOf(it)) } }
        assertTrue(session.isPassComplete)
        assertNull(session.next(played), "pass exhausted")
    }

    @Test
    fun artistAllPlaysWholeBlockBeforeNextArtist() {
        val session = ArtistSession(items(), songsPerArtist = Int.MAX_VALUE, random = Random(2))
        val played = mutableSetOf<String>()
        val picks = drainPass(session, played)
        assertEquals(9, picks.size, "all songs in one pass")
        // Blocks of 3, one artist each.
        picks.chunked(3).forEach { block ->
            block.forEach { assertEquals(session.artistOf(block.first()), session.artistOf(it)) }
        }
    }

    @Test
    fun reshuffleAfterPassAllowsFullSecondPass() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(3))
        val played = mutableSetOf<String>()
        drainPass(session, played)
        assertTrue(session.isPassComplete)
        played.clear() // app clears playedKeys on pass completion (S5)
        session.reshuffle()
        assertFalse(session.isPassComplete)
        val second = drainPass(session, played)
        assertEquals(6, second.size)
    }

    @Test
    fun restartAroundPutsThatArtistFirstAndCountsCurrentTowardQuota() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(4))
        // User manually picked b1 (already played); block restarts around B.
        val played = mutableSetOf("b1")
        session.restartAround("Artist B")
        // playedThisArtist = 1 (b1) → exactly one more B pick before moving on.
        val first = assertNotNull(session.next(played))
        assertEquals("Artist B", session.artistOf(first), "B block comes first")
        played += first
        val second = assertNotNull(session.next(played))
        assertTrue(session.artistOf(second) != "Artist B", "quota 2 = b1 + one more, block moves on")
    }

    @Test
    fun restartAroundUnknownArtistFallsBackToFullReshuffle() {
        val session = ArtistSession(items(), songsPerArtist = 2, random = Random(5))
        session.restartAround("Nobody")
        assertNotNull(session.next(emptySet()))
    }
}
```

Note: `assertFalse` is imported in the test header above.

- [ ] **Step 2: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.playback.ArtistSessionTest"`
Expected: FAIL — compile error `unresolved reference: ArtistSession`.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/playback/ArtistSession.kt`:

```kotlin
package com.slotify.app.playback

import kotlin.random.Random

/** One playable track reduced to what artist ordering needs. */
data class ArtistSourceItem(val key: String, val artist: String)

/**
 * Random artist-block ordering for ARTIST_2 / ARTIST_ALL: shuffle artists,
 * hand out each artist's unplayed songs (up to [songsPerArtist] per block),
 * and report pass exhaustion so the caller can reshuffle + clear played keys.
 */
class ArtistSession(
    items: List<ArtistSourceItem>,
    private val songsPerArtist: Int,
    private val random: Random = Random.Default,
) {
    private val byArtist: Map<String, List<String>> = items.groupBy({ it.artist }, { it.key })
    private var artistOrder: List<String> = emptyList()
    private var artistIdx = 0
    private var playedThisArtist = 0

    var isPassComplete = false
        private set

    init {
        reshuffle()
    }

    /** Fresh random pass over every artist (init and pass completion). */
    fun reshuffle() {
        artistOrder = byArtist.keys.shuffled(random)
        artistIdx = 0
        playedThisArtist = 0
        isPassComplete = false
    }

    /**
     * Next key to play given everything already [played], or null when this
     * pass has no block left — caller then [reshuffle]s and clears played keys.
     */
    fun next(played: Set<String>): String? {
        if (isPassComplete) return null
        while (artistIdx < artistOrder.size) {
            val artist = artistOrder[artistIdx]
            val candidates = byArtist[artist].orEmpty().filter { it !in played }
            if (candidates.isNotEmpty() && playedThisArtist < songsPerArtist) {
                playedThisArtist++
                return candidates.random(random)
            }
            artistIdx++
            playedThisArtist = 0
        }
        isPassComplete = true
        return null
    }

    /**
     * Rebuild the pass around [artist] after a manual pick: that artist is the
     * first block (the picked song already counts as one) and everyone else
     * follows in random order.
     */
    fun restartAround(artist: String) {
        if (artist !in byArtist) {
            reshuffle()
            return
        }
        artistOrder = listOf(artist) + (byArtist.keys - artist).shuffled(random)
        artistIdx = 0
        playedThisArtist = 1
        isPassComplete = false
    }

    /** Which artist owns [key] (tests + restartAround callers). */
    fun artistOf(key: String): String? =
        byArtist.entries.firstOrNull { key in it.value }?.key
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.playback.ArtistSessionTest"`
Expected: PASS (5 tests). Any failure means the quota math in `ArtistSession.next`/`restartAround` disagrees with the test comments — re-derive from S5 rather than weakening assertions.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/playback/ArtistSession.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/playback/ArtistSessionTest.kt
git commit -m "Add ArtistSession pass/pick logic"
```

---

### Task 3: Library marks (LocalTrack.mark, scan survival, cycle fn)

**Covers:** S7 (marks persist + cycle), S4 (marked rows excluded — exclusion lands in Task 4's queue build)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/model/Models.kt:48-60` (`LocalTrack` gains `mark`), and after `RowMark` (line ~103) add `cycleMark()`
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt:274-296` (`runScanAndMatch` re-applies marks), and near `cyclePlaylistRowMark` (~1222) add `cycleLibraryRowMark`
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/model/RowMarkTest.kt`

**Interfaces:**
- Consumes: `RowMark { SKIP, DELETE }` (Models.kt:103)
- Produces: `LocalTrack(mark: RowMark? = null)`, `fun RowMark?.cycleMark(): RowMark?`, `fun AppState.cycleLibraryRowMark(filePath: String)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.slotify.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowMarkTest {
    @Test
    fun cycleMarkWalksNoneSkipDeleteNone() {
        val none: RowMark? = null
        assertEquals(RowMark.SKIP, none.cycleMark())
        assertEquals(RowMark.DELETE, RowMark.SKIP.cycleMark())
        assertNull(RowMark.DELETE.cycleMark())
    }

    @Test
    fun localTrackSerializesMarkWithDefaultNull() {
        val track = LocalTrack(filePath = "/m/a.mp3", title = "A")
        assertNull(track.mark)
        assertEquals(RowMark.SKIP, track.copy(mark = RowMark.SKIP).mark)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.model.RowMarkTest"`
Expected: FAIL — compile error `unresolved reference: cycleMark`.

- [ ] **Step 3: Write minimal implementation**

In `Models.kt`, add to `LocalTrack` (after `displayPath`):

```kotlin
    /** Optional display path (Android content URIs keep filePath separate). */
    val displayPath: String? = null,
    /** User mark (skip / delete); survives rescan via AppState re-apply. */
    val mark: RowMark? = null,
) {
```

After the `RowMark` enum (Models.kt ~103):

```kotlin
/** Shared row-mark cycle: none → SKIP → DELETE → none (playlist + library rows). */
fun RowMark?.cycleMark(): RowMark? = when (this) {
    null -> RowMark.SKIP
    RowMark.SKIP -> RowMark.DELETE
    RowMark.DELETE -> null
}
```

In `AppState.kt` `runScanAndMatch` (replace the `result.onSuccess` body assignments):

```kotlin
        result.onSuccess { tracks ->
            val priorMarks = _library.value.associate { it.filePath to it.mark }
            val marked = tracks.map { t -> priorMarks[t.filePath]?.let { m -> t.copy(mark = m) } ?: t }
            _library.value = marked
            repository.saveLibrary(marked)
            _scanStatus.value = "Indexed ${marked.size} file(s)"
            if (_playlists.value.isNotEmpty()) {
                _scanStatus.value = "Matching ${_playlists.value.size} playlist(s)…"
                rematchAll()
                _scanStatus.value = "Indexed ${marked.size} file(s) · matches refreshed"
            }
        }.onFailure { e ->
            _scanStatus.value = "Scan failed: ${e.message}"
        }
```

In `AppState.kt`, simplify `cyclePlaylistRowMark` to use the shared cycle and add the library sibling right after `setPlaylistRowMark`:

```kotlin
    /** Cycle a row’s mark: none → SKIP → DELETE → none. */
    fun cyclePlaylistRowMark(playlistId: String, trackKey: String) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val current = playlist.resolvedTracks
            .find { Matcher.trackKey(it.remote) == trackKey }
            ?.mark
        setPlaylistRowMark(playlistId, trackKey, current.cycleMark())
    }

    /** Cycle a library row's mark by file path; persists the index. */
    fun cycleLibraryRowMark(filePath: String) {
        _library.value = _library.value.map { t ->
            if (t.filePath == filePath) t.copy(mark = t.mark.cycleMark()) else t
        }
        scope.launch {
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
        }
    }
```

(If `withContext`/`Dispatchers` imports are already present in AppState — they are, from saveLibrary callers — no import change.)

- [ ] **Step 4: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS — full suite green (legacy `LocalTrack` JSON files load: missing `mark` decodes to null).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/model/Models.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/model/RowMarkTest.kt
git commit -m "Library row marks: LocalTrack.mark + shared cycle + rescan survival"
```

---

### Task 4: AppState playback engine (mode plumbing, played set, artist advance)

**Covers:** S4, S5, S6 (marking), S3 (cycle entry point), S11 (mode persisted)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt` (imports; fields ~199; init ~210; `startPlaylist` ~1671-1705; `setShuffle` ~1708; `persistPlaybackProgress` ~1756; `playLibrary` ~1765; `advance` ~1805; `playAt` ~1832)

**Interfaces:**
- Consumes: `PlaybackMode`/`cycle`/`isArtistMode`/`initialMode` (Task 1), `ArtistSession`/`ArtistSourceItem` (Task 2), `LocalTrack.mark` (Task 3)
- Produces (used by Tasks 5–8): `val playbackMode: StateFlow<PlaybackMode>`, `val playedKeys: StateFlow<Set<String>>`, `fun cyclePlaybackMode()`, `fun setShuffle(enabled: Boolean)` (now delegates)

- [ ] **Step 1: Add imports**

At the top of `AppState.kt` with the other `com.slotify.app.*` imports:

```kotlin
import com.slotify.app.playback.ArtistSession
import com.slotify.app.playback.ArtistSourceItem
import com.slotify.app.playback.PlaybackMode
import com.slotify.app.playback.cycle
import com.slotify.app.playback.initialMode
import com.slotify.app.playback.isArtistMode
```

- [ ] **Step 2: Add mode/played fields**

Replace the `_shuffleEnabled` field block (AppState.kt ~199-201) with:

```kotlin
    private val _shuffleEnabled = MutableStateFlow(false)
    /** Kept in sync with [playbackMode] (true when mode != LINEAR) for existing UI. */
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.LINEAR)
    /** Linear / Shuffle / Artist (2) / Artist (all) — drives queue order (S3). */
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    /** Library paths played since launch / last confirmed reset — grey-out (S6). */
    private val _playedKeys = MutableStateFlow<Set<String>>(emptySet())
    val playedKeys: StateFlow<Set<String>> = _playedKeys.asStateFlow()

    /** Artist-mode state; (re)built lazily, dropped on source/mode changes. */
    private var artistSession: ArtistSession? = null
    /** Library snapshot used for artist items + LINEAR/SHUFFLE reorders. */
    private var librarySource: List<LocalTrack> = emptyList()
```

In the second `init` block, replace `_shuffleEnabled.value = playbackStore.shuffle` with:

```kotlin
        _playbackMode.value = playbackStore.initialMode()
        _shuffleEnabled.value = _playbackMode.value != PlaybackMode.LINEAR
```

- [ ] **Step 3: startPlaylist — mode-aware order + artist history queue**

In `startPlaylist`, replace from `val orderedKeys: List<String> = when {` through the end of the function (`persistPlaybackProgress()` before the closing brace) with:

```kotlin
        val mode = _playbackMode.value
        val orderedKeys: List<String> = when {
            savedProgress != null -> {
                val existing = savedProgress.orderKeys.filter { it in keysToEntries }
                val missing = keysToEntries.keys.filterNot { it in existing.toSet() }
                if (mode == PlaybackMode.SHUFFLE && missing.isNotEmpty()) {
                    (existing + missing.shuffled())
                } else {
                    existing + missing
                }
            }
            else -> {
                val all = playable.map { (_, row, _) -> Matcher.trackKey(row.remote) }
                if (mode == PlaybackMode.SHUFFLE) all.shuffled() else all
            }
        }
        if (orderedKeys.isEmpty()) {
            emit("No playable tracks in \"${playlist.name}\"")
            return
        }

        val fullQueue = orderedKeys.mapNotNull { keysToEntries[it] }
        val resolvedIndex = if (savedProgress != null) {
            val key = savedProgress.orderKeys[savedProgress.orderIndex]
            fullQueue.indexOfFirst { it.trackKey == key }.takeIf { it >= 0 } ?: 0
        } else {
            val orig = playable.indexOfFirst { it.first >= fromRowIndex }
            val origKey = playable.getOrNull(orig)?.let { Matcher.trackKey(it.second.remote) }
            (origKey?.let { k -> fullQueue.indexOfFirst { it.trackKey == k } } ?: 0)
                .takeIf { it >= 0 } ?: 0
        }
        artistSession = null
        // ARTIST_* fresh start: queue is history only; picks append the future.
        queue = if (mode.isArtistMode() && savedProgress == null) {
            listOf(fullQueue[resolvedIndex.coerceIn(0, fullQueue.lastIndex)])
        } else {
            fullQueue
        }
        val startPos = savedProgress?.positionMs?.coerceAtLeast(0L) ?: 0L
        _activePlaylistId.value = playlistId
        queueIndex = if (queue.isEmpty()) 0 else {
            if (mode.isArtistMode() && savedProgress == null) 0
            else resolvedIndex.coerceIn(0, queue.lastIndex)
        }
        playAt(queueIndex, seekToMs = startPos)
        persistPlaybackProgress()
    }
```

- [ ] **Step 4: Replace setShuffle with applyMode core**

Replace the entire body of `fun setShuffle(enabled: Boolean) { … }` (AppState.kt ~1708-1743) with:

```kotlin
    fun setShuffle(enabled: Boolean) =
        applyMode(if (enabled) PlaybackMode.SHUFFLE else PlaybackMode.LINEAR)

    /** Cycle Linear → Shuffle → Artist (2) → Artist (all); keeps the current song (S3). */
    fun cyclePlaybackMode() = applyMode(_playbackMode.value.cycle())

    private fun applyMode(next: PlaybackMode) {
        _playbackMode.value = next
        _shuffleEnabled.value = next != PlaybackMode.LINEAR
        playbackStore = playbackStore.copy(shuffle = next != PlaybackMode.LINEAR, mode = next.name)
        scope.launch { repository.savePlayback(playbackStore) }
        if (queue.isEmpty()) return
        if (next.isArtistMode()) {
            artistSession = null
            restartArtistSessionAroundCurrent()
            return
        }
        // LINEAR / SHUFFLE: rebuild the order around the current song.
        val currentKey = queue.getOrNull(queueIndex)?.trackKey
        val sourceKeys = currentSourceKeys()
        if (sourceKeys.isEmpty()) return
        val reordered = if (next == PlaybackMode.SHUFFLE) {
            val rest = sourceKeys.filter { it != currentKey }.shuffled()
            if (currentKey != null) listOf(currentKey) + rest else rest
        } else {
            sourceKeys
        }
        val newQueue = reordered.mapNotNull { k -> queue.find { it.trackKey == k } ?: queueEntryForKey(k) }
        if (newQueue.isEmpty()) return
        queue = newQueue
        queueIndex = currentKey?.let { k ->
            newQueue.indexOfFirst { it.trackKey == k }.takeIf { i -> i >= 0 }
        } ?: 0
        persistPlaybackProgress()
    }

    /** Full playable source keys for the active playlist, or the library snapshot. */
    private fun currentSourceKeys(): List<String> = _activePlaylistId.value
        ?.let { id -> _playlists.value.find { it.id == id } }
        ?.resolvedTracks
        ?.filter { it.mark != RowMark.SKIP && it.mark != RowMark.DELETE && it.match != null }
        ?.map { Matcher.trackKey(it.remote) }
        ?: librarySource.map { "lib:${it.filePath}" }

    /** Build a queue entry for a source key that is not in the current queue. */
    private fun queueEntryForKey(key: String): QueueEntry? {
        val plId = _activePlaylistId.value
        if (plId != null) {
            val row = _playlists.value.find { it.id == plId }
                ?.resolvedTracks?.find { Matcher.trackKey(it.remote) == key } ?: return null
            val match = row.match ?: return null
            return QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(match.local.filePath),
                title = row.remote.name,
                artist = row.remote.artistNames.ifBlank { match.local.displayArtist },
                trackKey = key,
            )
        }
        val local = librarySource.find { "lib:${it.filePath}" == key } ?: return null
        return QueueEntry(
            path = com.slotify.app.data.resolveSymlinkTarget(local.filePath),
            title = local.displayTitle,
            artist = local.displayArtist,
            trackKey = key,
        )
    }

    private fun artistItems(): List<ArtistSourceItem> {
        val plId = _activePlaylistId.value
        if (plId != null) {
            val rows = _playlists.value.find { it.id == plId }?.resolvedTracks ?: return emptyList()
            return rows.mapNotNull { row ->
                if (row.mark == RowMark.SKIP || row.mark == RowMark.DELETE || row.match == null) {
                    return@mapNotNull null
                }
                val artist = row.remote.primaryArtist.ifBlank { row.match.local.displayArtist }
                ArtistSourceItem(Matcher.trackKey(row.remote), artist)
            }
        }
        return librarySource.map {
            ArtistSourceItem("lib:${it.filePath}", it.displayArtist)
        }
    }

    private fun ensureArtistSession(): ArtistSession? {
        artistSession?.let { return it }
        val items = artistItems().takeIf { it.isNotEmpty() } ?: return null
        val per = if (_playbackMode.value == PlaybackMode.ARTIST_2) 2 else Int.MAX_VALUE
        return ArtistSession(items, per).also { artistSession = it }
    }

    private fun restartArtistSessionAroundCurrent() {
        val current = queue.getOrNull(queueIndex) ?: return
        val session = ensureArtistSession() ?: return
        val artist = artistItems().find { it.key == current.trackKey }?.artist ?: return
        session.restartAround(artist)
    }
```

- [ ] **Step 5: playLibrary — mode order + marks + history queue**

Replace the whole body of `fun playLibrary(tracks: List<LocalTrack>, fromIndex: Int = 0)` (AppState.kt ~1765-1778) with:

```kotlin
    fun playLibrary(tracks: List<LocalTrack>, fromIndex: Int = 0) {
        if (tracks.isEmpty()) return
        persistPlaybackProgress()
        _activePlaylistId.value = null
        val mode = _playbackMode.value
        val startKey = tracks.getOrNull(fromIndex)?.let { "lib:${it.filePath}" }
        val playable = tracks.filter { it.mark == null }
        if (playable.isEmpty()) {
            emit("Every row here is marked skip or delete")
            return
        }
        librarySource = playable
        artistSession = null
        val entries = playable.map {
            QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(it.filePath),
                title = it.displayTitle,
                artist = it.displayArtist,
                trackKey = "lib:${it.filePath}",
            )
        }
        val startEntry = entries.find { it.trackKey == startKey } ?: entries.first()
        queue = when {
            mode.isArtistMode() -> listOf(startEntry)
            mode == PlaybackMode.SHUFFLE ->
                listOf(startEntry) + entries.filter { it.trackKey != startEntry.trackKey }.shuffled()
            else -> entries
        }
        queueIndex = queue.indexOfFirst { it.trackKey == startEntry.trackKey }.coerceAtLeast(0)
        playAt(queueIndex)
    }
```

- [ ] **Step 6: persist mode in progress snapshots**

In `persistPlaybackProgress`, replace:

```kotlin
        playbackStore = playbackStore.copy(
            activePlaylistId = plId,
            shuffle = _shuffleEnabled.value,
            byPlaylist = playbackStore.byPlaylist + (plId to progress),
        )
```

with:

```kotlin
        playbackStore = playbackStore.copy(
            activePlaylistId = plId,
            shuffle = _playbackMode.value != PlaybackMode.LINEAR,
            mode = _playbackMode.value.name,
            byPlaylist = playbackStore.byPlaylist + (plId to progress),
        )
```

- [ ] **Step 7: advance — artist picks, pass completion, mode-gated reshuffle**

Replace the whole `private fun advance(delta: Int, auto: Boolean)` body (AppState.kt ~1805-1830) with:

```kotlin
    private fun advance(delta: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        val mode = _playbackMode.value
        if (mode.isArtistMode() && delta > 0) {
            if (queueIndex < queue.lastIndex) {
                playAt(queueIndex + 1)
                persistPlaybackProgress()
                return
            }
            // End of history — ask the artist session for the next pick (S5).
            val session = ensureArtistSession()
            var key = session?.next(_playedKeys.value)
            if (key == null && session != null) {
                // Pass complete: new pass + clear played, then retry once.
                session.reshuffle()
                _playedKeys.value = emptySet()
                emit("Artist pass complete — starting a new pass")
                key = session.next(_playedKeys.value)
            }
            if (key == null) {
                persistPlaybackProgress()
                player.pause()
                return
            }
            val entry = queue.find { it.trackKey == key } ?: queueEntryForKey(key)
            if (entry == null) {
                persistPlaybackProgress()
                player.pause()
                return
            }
            queue = queue + entry
            playAt(queue.lastIndex)
            persistPlaybackProgress()
            return
        }
        val target = queueIndex + delta
        if (auto && target > queue.lastIndex && mode == PlaybackMode.SHUFFLE) {
            // Exhausted this shuffle cycle — reshuffle all tracks for a new pass.
            val byKey = queue.associateBy { it.trackKey }
            val newQueue = queue.map { it.trackKey }.shuffled().mapNotNull { byKey[it] }
            if (newQueue.isNotEmpty()) {
                queue = newQueue
                persistPlaybackProgress()
                playAt(0)
                emit("Shuffle cycle complete — starting a new pass")
                return
            }
        }
        if (target !in queue.indices) {
            if (auto) {
                persistPlaybackProgress()
                player.pause()
            }
            return
        }
        playAt(target)
        persistPlaybackProgress()
    }
```

- [ ] **Step 8: mark played on every play**

In `playAt`, after `queueIndex = index` add:

```kotlin
        markPlayed(entry)
```

And add the helper next to `advance`:

```kotlin
    /** Record the played library path (playlist rows map via their match) — S6. */
    private fun markPlayed(entry: QueueEntry) {
        val path = when {
            entry.trackKey.startsWith("lib:") -> entry.trackKey.removePrefix("lib:")
            else -> _playlists.value.find { it.id == _activePlaylistId.value }
                ?.resolvedTracks
                ?.find { Matcher.trackKey(it.remote) == entry.trackKey }
                ?.match?.local?.filePath
        }
        if (path != null) _playedKeys.value = _playedKeys.value + path
    }
```

- [ ] **Step 9: Run full test suite**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS. Any compile error here is almost always a missing import (`cycle`, `initialMode`, `isArtistMode`) or a leftover reference to `_shuffleEnabled.value` in code not updated — fix by referencing `_playbackMode.value` with the right predicate.

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt
git commit -m "Playback engine: four modes, played set, artist advance"
```

---

### Task 5: PlayerBar mode badge

**Covers:** S3 (icon feedback only)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/PlayerBar.kt:41,88-98`

**Interfaces:**
- Consumes: `AppState.playbackMode`, `AppState.cyclePlaybackMode()` (Task 4), `PlaybackMode.isArtistMode()` (Task 1)
- Produces: UI only

- [ ] **Step 1: Swap the shuffle collect + button**

Replace `val shuffle by state.shuffleEnabled.collectAsState()` (line 41) with:

```kotlin
    val playbackMode by state.playbackMode.collectAsState()
```

Replace the shuffle `IconButton` block (lines 88-98) with:

```kotlin
                IconButton(onClick = { state.cyclePlaybackMode() }) {
                    Box {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = when (playbackMode) {
                                PlaybackMode.LINEAR -> "Mode: linear"
                                PlaybackMode.SHUFFLE -> "Mode: shuffle"
                                PlaybackMode.ARTIST_2 -> "Mode: artist shuffle, 2 per artist"
                                PlaybackMode.ARTIST_ALL -> "Mode: artist shuffle, all per artist"
                            },
                            tint = if (playbackMode == PlaybackMode.LINEAR) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (playbackMode.isArtistMode()) {
                            Text(
                                text = if (playbackMode == PlaybackMode.ARTIST_2) "2" else "★",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 6.dp, y = 5.dp),
                            )
                        }
                    }
                }
```

New imports for `PlayerBar.kt`:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.offset
import androidx.compose.ui.unit.sp
import com.slotify.app.playback.PlaybackMode
import com.slotify.app.playback.isArtistMode
```

(`Alignment`, `FontWeight`, `MaterialTheme`, `Text`, `Icon`, `IconButton` already imported.)

- [ ] **Step 2: Compile check**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (no unused-import warnings introduced — `shuffleEnabled` collect removed).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/PlayerBar.kt
git commit -m "Player bar: mode cycle button with 2/★ artist badge"
```

---

### Task 6: Pick flow + "Reset all played already?" dialog

**Covers:** S6 (popup rules)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt` (after `QueueEntry` ~71 add `PickAction`; near playback section add request/resolve fns)
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/ResetPlayedDialog.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/App.kt` (dialog host inside the content `Box`)
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt:683` (row play → `requestPlaylistPick`)

**Interfaces:**
- Consumes: `playedKeys` (Task 4), `playPlaylist`/`playLibrary` (existing)
- Produces: `sealed interface PickAction { data class Playlist(playlistId: String, fromRowIndex: Int); data class Library(tracks: List<LocalTrack>, index: Int) }`, `val pendingResetPick: StateFlow<PickAction?>`, `fun requestPlaylistPick(playlistId: String, fromRowIndex: Int)`, `fun requestLibraryPick(tracks: List<LocalTrack>, index: Int)`, `fun resolvePendingResetPick(reset: Boolean)`, `fun cancelPendingResetPick()`, `@Composable fun ResetPlayedDialog(onReset, onJustPlay, onDismiss)`

- [ ] **Step 1: PickAction + request/resolve in AppState**

After the private `QueueEntry` data class (AppState.kt ~71):

```kotlin
/** A row pick paused for the "Reset all played already?" answer (S6). */
sealed interface PickAction {
    data class Playlist(val playlistId: String, val fromRowIndex: Int) : PickAction
    data class Library(val tracks: List<LocalTrack>, val index: Int) : PickAction
}
```

In the playback section (next to `cyclePlaybackMode` from Task 4):

```kotlin
    private val _pendingResetPick = MutableStateFlow<PickAction?>(null)
    /** Non-null → show the reset-played dialog before starting this pick. */
    val pendingResetPick: StateFlow<PickAction?> = _pendingResetPick.asStateFlow()

    /** Playlist row pick — asks whenever a played set exists (S6). */
    fun requestPlaylistPick(playlistId: String, fromRowIndex: Int) {
        val sameRow = _nowPlaying.value != null &&
            _activePlaylistId.value == playlistId &&
            currentOriginalRowIndex(playlistId) == fromRowIndex
        if (_playedKeys.value.isEmpty() || sameRow) {
            playPlaylist(playlistId, fromRowIndex)
            return
        }
        _pendingResetPick.value = PickAction.Playlist(playlistId, fromRowIndex)
    }

    /** Library row pick — asks only when this row is already greyed (S6). */
    fun requestLibraryPick(tracks: List<LocalTrack>, index: Int) {
        val track = tracks.getOrNull(index) ?: return
        val key = "lib:${track.filePath}"
        val sameRow = _activePlaylistId.value == null &&
            _nowPlaying.value != null &&
            queue.getOrNull(queueIndex)?.trackKey == key
        if (key !in _playedKeys.value || sameRow) {
            playLibrary(tracks, index)
            return
        }
        _pendingResetPick.value = PickAction.Library(tracks, index)
    }

    /** Dialog answer: [reset] clears played keys, then the pick plays. */
    fun resolvePendingResetPick(reset: Boolean) {
        val action = _pendingResetPick.value ?: return
        _pendingResetPick.value = null
        if (reset) _playedKeys.value = emptySet()
        when (action) {
            is PickAction.Playlist -> playPlaylist(action.playlistId, action.fromRowIndex)
            is PickAction.Library -> playLibrary(action.tracks, action.index)
        }
    }

    fun cancelPendingResetPick() {
        _pendingResetPick.value = null
    }
```

- [ ] **Step 2: Dialog composable**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/ui/ResetPlayedDialog.kt`:

```kotlin
package com.slotify.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ResetPlayedDialog(
    onReset: () -> Unit,
    onJustPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset all played already?") },
        text = {
            Text(
                text = "Reset clears the played marks so any song can repeat. " +
                    "“Just play this” plays the picked song and keeps everything marked.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text("Reset all played") }
        },
        dismissButton = {
            TextButton(onClick = onJustPlay) { Text("Just play this") }
        },
    )
}
```

- [ ] **Step 3: Host the dialog in App.kt**

Inside the content `Box` in `App.kt`, right after the `LegalDisclaimerDialog` block:

```kotlin
                    val pendingPick by state.pendingResetPick.collectAsState()
                    pendingPick?.let {
                        ResetPlayedDialog(
                            onReset = { state.resolvePendingResetPick(reset = true) },
                            onJustPlay = { state.resolvePendingResetPick(reset = false) },
                            onDismiss = { state.cancelPendingResetPick() },
                        )
                    }
```

(`ResetPlayedDialog` is same-package — no import. `collectAsState` already imported.)

- [ ] **Step 4: Route playlist row picks through the request**

In `PlaylistDetailScreen.kt`, find row-level pick calls:

Run: `grep -n "playPlaylist(" composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt`
Expected: the row play `IconButton` at ~line 683 (`state.playPlaylist(playlistId, originalIndex)`).

Replace that call with `state.requestPlaylistPick(playlistId, originalIndex)`. Any *header* play/stop control in the same file that toggles the whole playlist (`togglePlaylistPlay`) stays unchanged.

- [ ] **Step 5: Compile + test**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/ResetPlayedDialog.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/App.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt
git commit -m "Reset-played confirmation before picks that would clear the set"
```

---

### Task 7: Shared narrow m/s/d MarkChip

**Covers:** S7 (narrow single-letter chip shared by playlist + library rows)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/MarkChip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt:442-460` (delete private `MarkChip`, call site at 393/585 picks up the shared one)

**Interfaces:**
- Consumes: `RowMark`, `cycleMark()` (Task 3); call sites already pass `onClick = { state.cyclePlaylistRowMark(...) }`
- Produces: `@Composable fun MarkChip(mark: RowMark?, onClick: () -> Unit)` — used by Tasks 7 (playlist) and 8 (library)

- [ ] **Step 1: Create the shared chip**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/ui/MarkChip.kt`:

```kotlin
package com.slotify.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.slotify.app.model.RowMark

/** Narrow single-letter row mark: m (none) → s → d (red) → m. Shared by
 *  playlist detail rows and library rows (S7). */
@Composable
fun MarkChip(mark: RowMark?, onClick: () -> Unit) {
    val (label, color) = when (mark) {
        null -> "m" to MaterialTheme.colorScheme.outline
        RowMark.SKIP -> "s" to MaterialTheme.colorScheme.secondary
        RowMark.DELETE -> "d" to MaterialTheme.colorScheme.error
    }
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = color,
        ),
    )
}
```

- [ ] **Step 2: Delete the private copy in PlaylistDetailScreen**

Remove the private `MarkChip` composable (PlaylistDetailScreen.kt ~442-460, the one with `null -> "Mark"`, `SKIP -> "Skip"`, `DELETE -> "Del"` labels) and its doc comment ("Cycles mark: none → Skip → Delete → none."). The two call sites (`MarkChip(mark = row.mark, onClick = { state.cyclePlaylistRowMark(...) })`) now resolve to the shared import:

```kotlin
import com.slotify.app.ui.MarkChip
```

Keep the `SuggestionChip`/`SuggestionChipDefaults` imports in this file — Task 10 removes the Linked chip later; if they become unused at that point, drop them there (no unused-import warnings).

- [ ] **Step 3: Compile + test**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/MarkChip.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt
git commit -m "Shared narrow m/s/d mark chip"
```

---

### Task 8: Add-to-playlist state + dialog

**Covers:** S8 (create new / add to existing as accepted match), S9 (message helper for Find current)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt` (library/playback section)
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/AddToPlaylistDialog.kt`

**Interfaces:**
- Consumes: `LocalTrack` (Task 3 added `mark`), `AppPlaylist`, `RemoteTrack`, `TrackMatch(isManual = true)`, `repository.savePlaylists`
- Produces: `fun showMessage(text: String)`, `fun createPlaylistWithTrack(name: String, track: LocalTrack)`, `fun addToPlaylist(playlistId: String, track: LocalTrack)`, `@Composable fun AddToPlaylistDialog(track, playlists, onCreate: (String) -> Unit, onAdd: (String) -> Unit, onDismiss)`

- [ ] **Step 1: AppState functions**

Add to `AppState.kt` (library section, after `cycleLibraryRowMark`):

```kotlin
    /** Short UI message for controls without a status line (S9 Find current). */
    fun showMessage(text: String) = emit(text)

    /** Create a new playlist containing [track] as an accepted match (S8). */
    fun createPlaylistWithTrack(name: String, track: LocalTrack) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val remoteTrack = localToRemote(track)
        val playlist = AppPlaylist(
            remote = RemotePlaylist(
                id = "local-${nowEpochMs()}",
                name = trimmed,
                trackCount = 1,
                tracks = listOf(remoteTrack),
            ),
            resolvedTracks = listOf(
                ResolvedPlaylistTrack(
                    remoteTrack,
                    TrackMatch(track, confidence = 1.0, isManual = true),
                ),
            ),
            importedAtEpochMs = nowEpochMs(),
            source = PlaylistSource.JSON,
        )
        _playlists.value = _playlists.value + playlist
        scope.launch {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("Created \"$trimmed\"")
        }
    }

    /** Append [track] to an existing playlist as an accepted match (S8). */
    fun addToPlaylist(playlistId: String, track: LocalTrack) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        if (playlist.resolvedTracks.any { it.match?.local?.filePath == track.filePath }) {
            emit("\"${playlist.name}\" already has this song")
            return
        }
        val remoteTrack = localToRemote(track)
        val newTracks = playlist.remote.tracks + remoteTrack
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(
                remote = pl.remote.copy(tracks = newTracks, trackCount = newTracks.size),
                resolvedTracks = pl.resolvedTracks + ResolvedPlaylistTrack(
                    remoteTrack,
                    TrackMatch(track, confidence = 1.0, isManual = true),
                ),
            )
        }
        scope.launch {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("Added to \"${playlist.name}\"")
        }
    }

    private fun localToRemote(track: LocalTrack) = RemoteTrack(
        name = track.displayTitle,
        artists = listOf(RemoteArtist(name = track.displayArtist)),
        albumName = track.album,
        durationMs = track.durationMs,
    )
```

Imports: add `import com.slotify.app.platform.nowEpochMs` if not already present. `RemotePlaylist`/`RemoteArtist`/`TrackMatch`/`ResolvedPlaylistTrack`/`AppPlaylist` — add any the compiler reports missing (most already import for playlist work).

- [ ] **Step 2: Dialog composable**

Create `composeApp/src/commonMain/kotlin/com/slotify/app/ui/AddToPlaylistDialog.kt`:

```kotlin
package com.slotify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack

/** Create-new / add-to-existing confirmation for one library song (S8). */
@Composable
fun AddToPlaylistDialog(
    track: LocalTrack,
    playlists: List<AppPlaylist>,
    onCreate: (name: String) -> Unit,
    onAdd: (playlistId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "“${track.displayTitle}” — create a new playlist or pick an existing one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New playlist name") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { onCreate(newName.trim()) },
                    enabled = newName.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create new playlist") }
                HorizontalDivider()
                if (playlists.isEmpty()) {
                    Text(
                        text = "No existing playlists yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                playlists.forEach { pl ->
                    TextButton(
                        onClick = { onAdd(pl.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "${pl.name} (${pl.resolvedTracks.size})", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
```

- [ ] **Step 3: Compile + test**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/AddToPlaylistDialog.kt
git commit -m "Add-to-playlist create/append + confirmation dialog"
```

---

### Task 9: Library screen (Find current, highlight, grey, chips, + flow)

**Covers:** S9 (Find current, highlight, Stop, grey), S7 (library row layout + marks UI), S8 (per-row `+` wiring), S6 (row picks route through `requestLibraryPick`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/LibraryScreen.kt` (full rewrite below)

**Interfaces:**
- Consumes: `playedKeys`, `nowPlaying`, `requestLibraryPick`, `cycleLibraryRowMark`, `showMessage`, `togglePlay` (existing), `MarkChip` (Task 7), `AddToPlaylistDialog` (Task 8)
- Produces: UI only

- [ ] **Step 1: Replace LibraryScreen.kt entirely**

```kotlin
package com.slotify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.ui.AddToPlaylistDialog
import com.slotify.app.ui.MarkChip
import com.slotify.app.ui.formatMs
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(state: AppState) {
    val library by state.library.collectAsState()
    val scanStatus by state.scanStatus.collectAsState()
    val nowPlaying by state.nowPlaying.collectAsState()
    val playedKeys by state.playedKeys.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val playlists by state.playlists.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var addTarget by remember { mutableStateOf<LocalTrack?>(null) }

    val filtered = remember(library, query) {
        if (query.isBlank()) library
        else {
            val q = query.trim().lowercase()
            library.filter {
                it.displayTitle.lowercase().contains(q) || it.displayArtist.lowercase().contains(q)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${library.size} file(s)" +
                        if (query.isNotBlank()) " · ${filtered.size} match(es)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = {
                    val path = nowPlaying?.path
                    if (path == null) {
                        state.showMessage("Nothing playing")
                        return@FilledTonalButton
                    }
                    val idx = filtered.indexOfFirst { it.filePath == path }
                    if (idx < 0) {
                        state.showMessage("Playing track is hidden by search")
                    } else {
                        scope.launch { listState.animateScrollToItem(idx) }
                    }
                },
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find current")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { state.scanLibrary() }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan")
            }
        }

        scanStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (status.startsWith("Scanning") || status.startsWith("Reading")) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search title or artist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        if (filtered.isEmpty()) {
            Text(
                text = if (library.isEmpty()) {
                    "Add folders in Settings, then press Scan to index your music."
                } else {
                    "Nothing matches \"$query\""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                itemsIndexed(filtered, key = { _, it -> it.filePath }) { index, track ->
                    val isCurrent = nowPlaying?.path == track.filePath
                    val isPlayed = track.filePath in playedKeys
                    val markDimmed = track.mark != null
                    val titleColor = when {
                        isCurrent -> MaterialTheme.colorScheme.surface
                        track.mark == RowMark.DELETE -> MaterialTheme.colorScheme.error
                        isPlayed || markDimmed ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val subColor = when {
                        isCurrent -> MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        track.mark == RowMark.DELETE ->
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        isPlayed || markDimmed ->
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.onSurface
                                else Color.Transparent,
                            )
                            .clickable { state.requestLibraryPick(filtered, index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(track.displayArtist)
                                    track.album?.let { append(" · ").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = subColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = track.durationMs?.let { formatMs(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = subColor,
                        )
                        Spacer(Modifier.width(8.dp))
                        MarkChip(
                            mark = track.mark,
                            onClick = { state.cycleLibraryRowMark(track.filePath) },
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { addTarget = track }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add to playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        val showStop = isCurrent && playerSnap.isPlaying
                        IconButton(
                            onClick = {
                                if (isCurrent) state.togglePlay()
                                else state.requestLibraryPick(filtered, index)
                            },
                        ) {
                            Icon(
                                imageVector = if (showStop) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (showStop) "Stop" else "Play",
                                tint = if (showStop) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    addTarget?.let { track ->
        AddToPlaylistDialog(
            track = track,
            playlists = playlists,
            onCreate = { name ->
                state.createPlaylistWithTrack(name, track)
                addTarget = null
            },
            onAdd = { playlistId ->
                state.addToPlaylist(playlistId, track)
                addTarget = null
            },
            onDismiss = { addTarget = null },
        )
    }
}
```

- [ ] **Step 2: Compile + test**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS. If `Icons.Default.MyLocation` is unresolved, the icons-extended artifact is missing for that symbol — pick `Icons.Default.Search` fallback ONLY after confirming the artifact list; the project already uses extended icons (`CloudDownload`, `PlaylistPlay`), so this should compile.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/LibraryScreen.kt
git commit -m "Library: find current, highlight/grey, m/s/d chips, + add-to-playlist"
```

---

### Task 10: Playlist row badges — drop Linked, tappable %

**Covers:** S10

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt:656-671` (chip block), `:697-716` (`ConfidenceChip`)

**Interfaces:**
- Consumes: `state.linkTrackToFile(playlistId, trackKey, path)` (existing AppState:1465)
- Produces: `ConfidenceChip(confidence, libraryMatch, onAccept)` (private, same file)

- [ ] **Step 1: Replace the Linked / chip block**

Find (PlaylistDetailScreen.kt ~656-671):

```kotlin
        if (match.isManual) {
            SuggestionChip(
                onClick = {
                    state.unlinkTrackFile(playlistId, Matcher.trackKey(row.remote))
                },
                label = { Text("Linked") },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    labelColor = MaterialTheme.colorScheme.tertiary,
                ),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            ConfidenceChip(confidence = match.confidence, libraryMatch = isExistingLibrary)
            Spacer(Modifier.width(4.dp))
        }
```

Replace with:

```kotlin
        if (!match.isManual) {
            ConfidenceChip(
                confidence = match.confidence,
                libraryMatch = isExistingLibrary,
                onAccept = {
                    // Tap % = accept this match: pin the current file (S10).
                    state.linkTrackToFile(
                        playlistId,
                        Matcher.trackKey(row.remote),
                        match.local.filePath,
                    )
                },
            )
            Spacer(Modifier.width(4.dp))
        }
```

(`unlinkTrackFile` loses its only UI caller — keep the function per the keep-unused-functions rule.)

- [ ] **Step 2: Make ConfidenceChip tappable**

Replace the private `ConfidenceChip` (~697-716) with:

```kotlin
@Composable
private fun ConfidenceChip(
    confidence: Double,
    libraryMatch: Boolean = false,
    onAccept: () -> Unit = {},
) {
    val percent = (confidence * 100).toInt()
    // Library matches use the amber secondary so they read differently from
    // confidence-only green/red chips on Slotify-sourced rows.
    val color = when {
        libraryMatch -> Color(0xFFFFB74D)
        confidence >= 0.90 -> Color(0xFF66BB6A)
        confidence >= 0.80 -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    SuggestionChip(
        onClick = onAccept,
        label = { Text("$percent%") },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = color,
        ),
    )
}
```

- [ ] **Step 3: Compile + test**

Run: `source env.sh && ./gradlew :composeApp:desktopTest`
Expected: PASS, no unused-import warnings from this file.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/PlaylistDetailScreen.kt
git commit -m "Drop Linked chip; % badge tap pins the match"
```

---

### Task 11: Full verification

**Covers:** S13 (acceptance), S12 (non-goals held: no bulk library delete, no multi-select)

**Files:**
- None expected (fix-ups only if verification finds regressions)

- [ ] **Step 1: Full test + Android build**

Run: `source env.sh && ./gradlew :composeApp:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL; desktopTest green; APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

- [ ] **Step 2: Warning audit**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop 2>&1 | grep -E "^w:"`
Expected: only the pre-existing `expect/actual classes` Beta warning from `JsonFileTokenStorage.desktop.kt`. No new warnings (unused imports/variables) — fix any by removing/using the symbol, never by suppression flags.

- [ ] **Step 3: Manual smoke checklist (desktop run)**

Run: `source env.sh && ./gradlew :composeApp:run` and verify:
1. Shuffle icon cycles Linear → Shuffle → Artist (2) [badge 2] → Artist (all) [badge ★] → Linear; mode survives restart.
2. In Artist (2): plays 2 songs per artist in blocks, no repeats within a pass, pass completion message + played set cleared.
3. Library shuffle actually shuffles from a tapped row; skip-marked rows never play.
4. Grey-out: play a few library rows; they grey; tapping a greyed row shows "Reset all played already?" with both buttons behaving per S6.
5. Picking any playlist row with a non-empty played set shows the same dialog.
6. Find current scrolls to the playing song (try with an active search that hides it → snackbar).
7. Current row: inverted colors + Stop icon; tap stops/resumes.
8. m/s/d chips cycle in playlist detail and library; `d` is red.
9. `%` tap → badge disappears (match pinned, survives rescan); Linked chip gone.
10. Library `+` → create new playlist and add to existing; both persist after restart.

- [ ] **Step 4: Commit any fix-ups**

```bash
git add -p   # only files actually changed by fixes
git commit -m "Fix verification findings for artist shuffle"
```

(If nothing failed, no commit — verification-only task.)

---
