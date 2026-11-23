# Cover-art backdrop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the playing track's embedded cover image sharp behind the content screens (with a dark scrim) whenever a track is loaded, on desktop and Android.

**Architecture:** On-demand extraction — a `CoverArtReader` (expect/actual: desktop = opus parser + jaudiotagger, Android = opus parser/jaudiotagger/MediaMetadataRetriever chain) reads embedded picture bytes when `nowPlaying` changes; AppState decodes via CMP's common `decodeToImageBitmap()`, caches per path in a 6-entry LRU, and exposes `coverArt: StateFlow<ImageBitmap?>`; a new `CoverBackdrop` composable draws it as the first child of the content `Box` in `App.kt`.

**Tech Stack:** Compose Multiplatform 1.12.2 (common `decodeToImageBitmap`), jaudiotagger 3.0.1 (mp3/flac/m4a only — **it has no opus reader, verified**), hand-rolled Ogg/OpusTags parser for opus art, Android `MediaMetadataRetriever`, Kotlin coroutines StateFlow.

## Global Constraints

- **A file with no embedded image yields the plain background. Never generate, fetch, or acquire substitute artwork** (user, 2026-11-23: "if there is no image … don't try to make or acquire one").
- No new dependencies (jaudiotagger + CMP already in the build).
- Spotify = metadata only; audio whitelist `aac,mp3,mp4,flac,wav,aif,aiff,opus` untouched; YouTube stream copy untouched.
- Keep unused functions; **never silence Gradle/Kotlin warnings** — replace deprecated APIs instead.
- Never commit: `.mimocode/*`, `spotify-app.txt`, `~/.local/share/slotify/*`, cookies/Client IDs. The working tree also holds unrelated uncommitted changes (SpotifyPublicSearch, LinkFileDialog, LegalDisclaimer, Main.kt, MainActivity, BrowserSession.android.kt) owned by another session — **leave them unstaged**.
- Branch: `0.1.1`. Every task stages only its own files.
- **No adb access without asking the user first** (the earlier one-time permission was for the search-crash fix only). Deliver the APK path + timestamp; the user installs.
- Toolchain: `source env.sh` first (JDK 21 + Android SDK). Tests: `./gradlew :composeApp:desktopTest` (filter: `--tests "pkg.Class"`). APK: `./gradlew :androidApp:assembleDebug` → `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- Spec: `specs/2026-11-23-cover-art-backdrop-design.md` (`[S1]`…`[S9]`).

---

### Task 1: CoverArtCache (bounded path→bitmap LRU)

**Covers:** [S5], [S7]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtCache.kt`
- Test: `composeApp/src/commonTest/kotlin/com/slotify/app/library/CoverArtCacheTest.kt`

**Interfaces:**
- Consumes: `androidx.compose.ui.graphics.ImageBitmap` (Compose UI, already on the classpath).
- Produces: `class CoverArtCache(maxEntries: Int = 6)` with `operator fun get(path: String): ImageBitmap?`, `fun put(path: String, art: ImageBitmap)`, `val size: Int`. Used by AppState in Task 5.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.slotify.app.library

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoverArtCacheTest {

    private fun bmp() = ImageBitmap(width = 1, height = 1)

    @Test
    fun putThenGetReturnsArt() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        assertNotNull(cache["/a.mp3"])
        assertEquals(1, cache.size)
    }

    @Test
    fun unknownPathIsNull() {
        assertNull(CoverArtCache()["/nope.mp3"])
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCap() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        cache.put("/b.mp3", bmp())
        cache.put("/c.mp3", bmp())
        assertNull(cache["/a.mp3"], "oldest entry must be evicted")
        assertNotNull(cache["/b.mp3"])
        assertNotNull(cache["/c.mp3"])
        assertEquals(2, cache.size)
    }

    @Test
    fun readingAnEntryRefreshesItsRecency() {
        val cache = CoverArtCache(maxEntries = 2)
        cache.put("/a.mp3", bmp())
        cache.put("/b.mp3", bmp())
        assertNotNull(cache["/a.mp3"])   // refresh /a
        cache.put("/c.mp3", bmp())       // evicts /b, not /a
        assertNotNull(cache["/a.mp3"])
        assertNull(cache["/b.mp3"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.CoverArtCacheTest"`
Expected: compilation FAIL — `unresolved reference: CoverArtCache`.

- [ ] **Step 3: Write the implementation**

`composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtCache.kt`:

```kotlin
package com.slotify.app.library

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Bounded path → decoded cover cache for the backdrop: the current song plus
 * the most recently used, so back/forward between tracks never re-reads a
 * file. Access-order LinkedHashMap gives LRU eviction at [maxEntries].
 */
class CoverArtCache(private val maxEntries: Int = 6) {

    private val entries = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > maxEntries
    }

    operator fun get(path: String): ImageBitmap? = entries[path]

    fun put(path: String, art: ImageBitmap) {
        entries[path] = art
    }

    val size: Int get() = entries.size
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.CoverArtCacheTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtCache.kt \
        composeApp/src/commonTest/kotlin/com/slotify/app/library/CoverArtCacheTest.kt
git commit -m "CoverArtCache: bounded LRU for backdrop art"
```

---

### Task 2: Ogg Opus picture parser + fixture tests

**Covers:** [S4], [S8]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/library/OpusArtwork.kt`
- Test: `composeApp/src/desktopTest/kotlin/com/slotify/app/library/OpusArtworkTest.kt`
- Stage (already generated, currently untracked): `composeApp/src/desktopTest/resources/art/cover-art.opus`, `cover-art.mp3`, `no-art.opus`, `no-art.mp3`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `fun readOpusEmbeddedPicture(path: String): ByteArray?` (public, commonMain) — called by the desktop reader (Task 3) and the Android reader (Task 4). Fixtures served on the test classpath under `/art/<name>`.

Fixture provenance (recorded, files are committed once):
- `cover-art.opus` — 1 s sine (ffmpeg libopus) + 64×64 red PNG written into `METADATA_BLOCK_PICTURE` via mutagen.
- `cover-art.mp3` — 1 s sine (ffmpeg libmp3lame) + attached red PNG (`APIC`).
- `no-art.opus` / `no-art.mp3` — same sines with no picture tags.

- [ ] **Step 1: Stage the fixtures**

```bash
git add composeApp/src/desktopTest/resources/art/
```

- [ ] **Step 2: Write the failing test**

`composeApp/src/desktopTest/kotlin/com/slotify/app/library/OpusArtworkTest.kt`:

```kotlin
package com.slotify.app.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpusArtworkTest {

    private fun fixture(name: String): String =
        File(checkNotNull(javaClass.getResource("/art/$name")) { "fixture $name missing" }.toURI()).path

    @Test
    fun opusWithEmbeddedArtReturnsPngBytes() {
        val bytes = readOpusEmbeddedPicture(fixture("cover-art.opus"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8, "expected a real picture block")
        // PNG magic: 89 50 4E 47
        assertTrue(
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte(),
            "expected PNG bytes, got ${bytes.take(4).toList()}",
        )
    }

    @Test
    fun artlessOpusReturnsNull() {
        assertNull(readOpusEmbeddedPicture(fixture("no-art.opus")))
    }

    @Test
    fun nonOpusFileReturnsNull() {
        // Parser must bail on non-Ogg bytes, never throw.
        assertNull(readOpusEmbeddedPicture(fixture("cover-art.mp3")))
    }

    @Test
    fun missingFileReturnsNull() {
        assertNull(readOpusEmbeddedPicture("/definitely/missing/file.opus"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.OpusArtworkTest"`
Expected: compilation FAIL — `unresolved reference: readOpusEmbeddedPicture`.

- [ ] **Step 4: Write the parser**

`composeApp/src/commonMain/kotlin/com/slotify/app/library/OpusArtwork.kt`:

```kotlin
package com.slotify.app.library

import java.io.File
import java.util.Base64

/**
 * Raw embedded picture (e.g. PNG/JPEG bytes) from an Ogg Opus file's
 * METADATA_BLOCK_PICTURE comment. Needed because the project's jaudiotagger
 * build has no opus reader at all (spec S4). Returns null for artless,
 * unreadable, or foreign files — callers must never substitute a generated
 * image.
 */
fun readOpusEmbeddedPicture(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    // OpusTags lives in the first Ogg pages (RFC 7845) — 512 KiB is ample.
    val head = file.inputStream().use { input ->
        val buf = ByteArray(512 * 1024)
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) break
            offset += n
        }
        buf.copyOf(offset)
    }
    parseOpusPicture(head)
}.getOrNull()

/** Ogg page walk over [data]; collects the OpusTags packet and decodes its picture. */
internal fun parseOpusPicture(data: ByteArray): ByteArray? {
    var offset = 0
    var pageIndex = 0
    val packet = ArrayList<Byte>(8192)
    var collecting = false
    while (offset + 27 <= data.size && pageIndex <= 64) {
        if (!data.regionMatchesAscii(offset, "OggS")) return null
        val segCount = data[offset + 26].toInt() and 0xFF
        val table = offset + 27
        if (table + segCount > data.size) return null
        var payloadLen = 0
        for (i in 0 until segCount) payloadLen += data[table + i].toInt() and 0xFF
        val payload = table + segCount
        if (payload + payloadLen > data.size) return null

        when (pageIndex) {
            0 -> if (!data.regionMatchesAscii(payload, "OpusHead")) return null
            1 -> {
                if (!data.regionMatchesAscii(payload, "OpusTags")) return null
                collecting = true
                if (appendLeadingPacket(data, table, segCount, payload, packet)) {
                    return pictureFromCommentPacket(packet)
                }
            }
            else -> if (collecting && appendLeadingPacket(data, table, segCount, payload, packet)) {
                return pictureFromCommentPacket(packet)
            }
        }
        offset = payload + payloadLen
        pageIndex++
    }
    return null
}

/**
 * Appends the page's leading packet bytes to [packet] (lacing runs of 255
 * continue a packet onto the next page). True once a segment < 255 ends it.
 */
private fun appendLeadingPacket(
    data: ByteArray,
    table: Int,
    segCount: Int,
    payload: Int,
    packet: ArrayList<Byte>,
): Boolean {
    var pos = payload
    for (i in 0 until segCount) {
        val len = data[table + i].toInt() and 0xFF
        for (j in 0 until len) packet.add(data[pos + j])
        pos += len
        if (len < 255) return true
    }
    return false
}

/** OpusTags packet → picture bytes from the first METADATA_BLOCK_PICTURE comment. */
private fun pictureFromCommentPacket(packet: List<Byte>): ByteArray? {
    if (packet.size < 16) return null
    if (!regionMatchesAscii(packet, 0, "OpusTags")) return null
    var i = 8
    fun u32le(): Int? {
        if (i + 4 > packet.size) return null
        var v = 0
        repeat(4) { v = v or ((packet[i + it].toInt() and 0xFF) shl (8 * it)) }
        i += 4
        return v
    }
    val count = u32le() ?: return null
    repeat(count) {
        val len = u32le() ?: return null
        if (len < 0 || i + len > packet.size) return null
        val entry = String(packet.subList(i, i + len).toByteArray(), Charsets.UTF_8)
        i += len
        if (entry.startsWith("METADATA_BLOCK_PICTURE=", ignoreCase = true)) {
            val base64 = entry.substringAfter('=')
            val block = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: return null
            return pictureDataFromFlacBlock(block)
        }
    }
    return null
}

/** FLAC METADATA_BLOCK_PICTURE payload: skip the 32-byte-ish header fields, keep image data. */
private fun pictureDataFromFlacBlock(b: ByteArray): ByteArray? {
    var i = 0
    fun u32be(): Long? {
        if (i + 4 > b.size) return null
        var v = 0L
        repeat(4) { v = (v shl 8) or (b[i + it].toLong() and 0xFF) }
        i += 4
        return v
    }
    u32be() ?: return null                       // picture type
    val mimeLen = u32be() ?: return null         // MIME type length
    i += mimeLen.toInt()
    if (i > b.size) return null
    val descLen = u32be() ?: return null         // description length
    i += descLen.toInt()
    if (i > b.size) return null
    repeat(4) { u32be() ?: return null }         // width, height, depth, colors
    val dataLen = u32be() ?: return null         // image data length
    if (dataLen <= 0 || i + dataLen > b.size) return null
    return b.copyOfRange(i, i + dataLen.toInt())
}

private fun ByteArray.regionMatchesAscii(offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > size) return false
    for (j in ascii.indices) if (this[offset + j] != ascii[j].code.toByte()) return false
    return true
}

private fun regionMatchesAscii(bytes: List<Byte>, offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > bytes.size) return false
    for (j in ascii.indices) if (bytes[offset + j] != ascii[j].code.toByte()) return false
    return true
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.OpusArtworkTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/library/OpusArtwork.kt \
        composeApp/src/desktopTest/kotlin/com/slotify/app/library/OpusArtworkTest.kt \
        composeApp/src/desktopTest/resources/art/
git commit -m "Read embedded art from Ogg Opus METADATA_BLOCK_PICTURE"
```

---

### Task 3: CoverArtReader interface + desktop reader

**Covers:** [S3], [S4], [S7]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtReader.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/slotify/app/library/CoverArtReader.desktop.kt`
- Test: `composeApp/src/desktopTest/kotlin/com/slotify/app/library/CoverArtReaderDesktopTest.kt`

**Interfaces:**
- Consumes: `readOpusEmbeddedPicture(path): ByteArray?` (Task 2); fixtures at `/art/*` (Task 2).
- Produces:
  - `interface CoverArtReader { suspend fun readArt(path: String): ByteArray? }`
  - `expect fun createCoverArtReader(): CoverArtReader` (Android actual lands in Task 4)
  - a **private** `readTagArtwork(path)` helper in each platform reader file — jaudiotagger is a target-scoped dependency (desktop + androidMain blocks only) and must NOT be imported from commonMain.

- [ ] **Step 1: Write the failing test**

`composeApp/src/desktopTest/kotlin/com/slotify/app/library/CoverArtReaderDesktopTest.kt`:

```kotlin
package com.slotify.app.library

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverArtReaderDesktopTest {

    private val reader = createCoverArtReader()

    private fun fixture(name: String): String =
        File(checkNotNull(javaClass.getResource("/art/$name")) { "fixture $name missing" }.toURI()).path

    @Test
    fun opusWithArtReturnsPictureBytes() = runBlocking {
        val bytes = reader.readArt(fixture("cover-art.opus"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8)
    }

    @Test
    fun mp3WithArtReturnsPictureBytes() = runBlocking {
        val bytes = reader.readArt(fixture("cover-art.mp3"))
        assertNotNull(bytes)
        assertTrue(bytes.size > 8)
    }

    @Test
    fun artlessFilesReturnNull() = runBlocking {
        assertNull(reader.readArt(fixture("no-art.opus")))
        assertNull(reader.readArt(fixture("no-art.mp3")))
    }

    @Test
    fun missingPathReturnsNull() = runBlocking {
        assertNull(reader.readArt("/definitely/missing/file.mp3"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.CoverArtReaderDesktopTest"`
Expected: compilation FAIL — `unresolved reference: createCoverArtReader`.

- [ ] **Step 3: Write the common declarations**

`composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtReader.kt` — no imports, no jaudiotagger (target-scoped dependency):

```kotlin
package com.slotify.app.library

/** Embedded cover-picture bytes for a local audio file. Null = no art / unreadable. */
interface CoverArtReader {
    suspend fun readArt(path: String): ByteArray?
}

/** Platform reader factory: desktop actual in this task, Android actual in Task 4. */
expect fun createCoverArtReader(): CoverArtReader
```

- [ ] **Step 4: Write the desktop actual**

`composeApp/src/desktopMain/kotlin/com/slotify/app/library/CoverArtReader.desktop.kt`:

```kotlin
package com.slotify.app.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO

actual fun createCoverArtReader(): CoverArtReader = DesktopCoverArtReader()

/**
 * jaudiotagger: mp3/flac/m4a/aiff — NOT opus (this build has no opus reader;
 * .opus is routed to [readOpusEmbeddedPicture] first). Null on any failure.
 */
private fun readTagArtwork(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    AudioFileIO.read(file).tag?.firstArtwork?.binaryData
}.getOrNull()

class DesktopCoverArtReader : CoverArtReader {
    override suspend fun readArt(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                path.startsWith("content://") -> null // SAF links are Android-side only
                path.endsWith(".opus", ignoreCase = true) -> readOpusEmbeddedPicture(path)
                else -> readTagArtwork(path)
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `source env.sh && ./gradlew :composeApp:desktopTest --tests "com.slotify.app.library.CoverArtReaderDesktopTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/library/CoverArtReader.kt \
        composeApp/src/desktopMain/kotlin/com/slotify/app/library/CoverArtReader.desktop.kt \
        composeApp/src/desktopTest/kotlin/com/slotify/app/library/CoverArtReaderDesktopTest.kt
git commit -m "CoverArtReader: desktop embedded-art reader (opus parser + jaudiotagger)"
```

---

### Task 4: Android reader actual

**Covers:** [S4]

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/slotify/app/library/CoverArtReader.android.kt`

**Interfaces:**
- Consumes: `CoverArtReader` + `createCoverArtReader()` (Task 3), `readOpusEmbeddedPicture` (Task 2), `com.slotify.app.data.androidAppContext` (existing, `internal var androidAppContext: Context?`, same pattern as `AndroidLibraryScanner.kt`). Android carries its own private `readTagArtwork` (jaudiotagger is a target dep here too).
- Produces: the Android `actual fun createCoverArtReader()` — after this task `createCoverArtReader()` resolves on both targets; AppState (Task 5) compiles for Android too.

No Android unit-test source set is configured — this task's gate is compilation (`assembleDebug`) plus the later manual pass (Task 7).

- [ ] **Step 1: Write the Android actual**

`composeApp/src/androidMain/kotlin/com/slotify/app/library/CoverArtReader.android.kt`:

```kotlin
package com.slotify.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.slotify.app.data.androidAppContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO

actual fun createCoverArtReader(): CoverArtReader = AndroidCoverArtReader()

/** jaudiotagger first for parity with the scanner — mp3/flac/m4a/aiff, never opus. */
private fun readTagArtwork(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    AudioFileIO.read(file).tag?.firstArtwork?.binaryData
}.getOrNull()

/**
 * Chain per spec S4: content:// → retriever only; .opus → common parser then
 * retriever; everything else → jaudiotagger then retriever. Any failure or
 * missing picture yields null — never a substitute image.
 */
class AndroidCoverArtReader : CoverArtReader {
    override suspend fun readArt(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                path.startsWith("content://") -> retrieverPicture(path)
                path.endsWith(".opus", ignoreCase = true) ->
                    readOpusEmbeddedPicture(path) ?: retrieverPicture(path)
                else -> readTagArtwork(path) ?: retrieverPicture(path)
            }
        }.getOrNull()
    }

    private fun retrieverPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) {
                val ctx: Context = androidAppContext ?: return null
                retriever.setDataSource(ctx, Uri.parse(path))
            } else {
                retriever.setDataSource(path)
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
```

- [ ] **Step 2: Compile both targets**

Run: `source env.sh && ./gradlew :composeApp:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL — `createCoverArtReader()` resolves for Android (actual exists) and desktop; no new compiler warnings.

- [ ] **Step 3: Warning audit**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop :androidApp:assembleDebug --rerun-tasks 2>&1 | grep -E "^[^ ].*w:" | head -20`
Expected: only pre-existing expect/actual Beta warnings (or empty) — **never silence a warning; fix it instead.**

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/slotify/app/library/CoverArtReader.android.kt
git commit -m "CoverArtReader: Android chain (opus parser / jaudiotagger / MediaMetadataRetriever)"
```

---

### Task 5: AppState wiring — coverArt flow, watcher, cache

**Covers:** [S2], [S5], [S7]

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt`
  - constructor (class header, line ~120): add the reader parameter
  - fields next to `_nowPlaying` (line ~237): `_coverArt`, `coverArt`, `coverArtCache`
  - the big `init` block (line ~286, where the periodic persistence `scope.launch` lives): add the watcher

**Interfaces:**
- Consumes: `CoverArtReader`/`createCoverArtReader()` (Tasks 3–4), `CoverArtCache` (Task 1), `androidx.compose.ui.graphics.decodeToImageBitmap` (CMP common), `nowPlaying: StateFlow<NowPlaying?>` (existing, `AppState.kt:237-238`).
- Produces: `val coverArt: StateFlow<ImageBitmap?>` on AppState — consumed by `CoverBackdrop` (Task 6).

- [ ] **Step 1: Add imports**

Add to AppState.kt imports:

```kotlin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.slotify.app.library.CoverArtCache
import com.slotify.app.library.CoverArtReader
import com.slotify.app.library.createCoverArtReader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

(`withContext`, `Dispatchers`, `launch`, `MutableStateFlow`, `asStateFlow` are already imported.)

- [ ] **Step 2: Add the constructor parameter**

In the class header, after the existing `scanner` parameter:

```kotlin
    private val coverArtReader: CoverArtReader = createCoverArtReader(),
```

So the header reads:

```kotlin
class AppState(
    private val repository: AppRepository = AppRepository(),
    private val scanner: LibraryScanner = createLibraryScanner(),
    private val coverArtReader: CoverArtReader = createCoverArtReader(),
    val player: AudioPlayer = createAudioPlayer(),
    ...
```

- [ ] **Step 3: Add the state fields**

Directly under the existing lines

```kotlin
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
```

add:

```kotlin
    /** Embedded cover of the loaded track for the content backdrop; null = plain background. */
    private val _coverArt = MutableStateFlow<ImageBitmap?>(null)
    val coverArt: StateFlow<ImageBitmap?> = _coverArt.asStateFlow()

    /** Bounded path → decoded art so back/forward never re-reads a file. */
    private val coverArtCache = CoverArtCache()
```

- [ ] **Step 4: Add the watcher in `init`**

Inside the existing `init` block that starts the periodic persistence loop (the `scope.launch { while (true) { delay(4_000) … } }` around line 292), add **after** that loop's closing brace, still in `init`:

```kotlin
        // Cover backdrop: follow the loaded track. Never throws into playback;
        // a missing/corrupt picture is null → plain background (spec S4/S5).
        scope.launch {
            nowPlaying
                .map { it?.path }
                .distinctUntilChanged()
                .collect { path ->
                    if (path == null) {
                        _coverArt.value = null
                        return@collect
                    }
                    coverArtCache[path]?.let { cached ->
                        _coverArt.value = cached
                        return@collect
                    }
                    val art = withContext(Dispatchers.IO) {
                        coverArtReader.readArt(path)?.let { bytes ->
                            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
                        }
                    }
                    if (art != null) coverArtCache.put(path, art)
                    // Stale-result guard: only publish if this is still the current track.
                    if (nowPlaying.value?.path == path) _coverArt.value = art
                }
        }
```

Semantics note for reviewers: the collector is sequential — one read in flight at a time; a fast skip finishes the stale read, the guard drops it, then the latest path is processed. `distinctUntilChanged()` means artless tracks are read once per path change, not on every emission.

- [ ] **Step 5: Compile and run the full suite**

Run: `source env.sh && ./gradlew :composeApp:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests (Tasks 1–3 + pre-existing) pass.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/state/AppState.kt
git commit -m "AppState: expose coverArt by following nowPlaying with per-path cache"
```

---

### Task 6: CoverBackdrop composable + App.kt placement

**Covers:** [S2], [S6], [S9]

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/CoverBackdrop.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/slotify/app/ui/App.kt` (content `Box`, line ~93)

**Interfaces:**
- Consumes: `state.coverArt: StateFlow<ImageBitmap?>` (Task 5).
- Produces: `@Composable fun CoverBackdrop(state: AppState)` rendered as the first child of App.kt's content `Box` (behind every screen). No other UI API changes.

- [ ] **Step 1: Write the composable**

`composeApp/src/commonMain/kotlin/com/slotify/app/ui/CoverBackdrop.kt`:

```kotlin
package com.slotify.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.slotify.app.state.AppState

/**
 * The current song's embedded cover behind the content screens: sharp image,
 * dark gradient scrim for readability (spec S6). Renders nothing when art is
 * null — never generates or fetches a substitute (spec S2/S9).
 */
@Composable
fun CoverBackdrop(state: AppState) {
    val art by state.coverArt.collectAsState()
    // Stays mounted so Crossfade can also fade OUT when art clears.
    Crossfade(targetState = art, animationSpec = tween(250), label = "coverArt") { bitmap ->
        if (bitmap != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.70f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Place it in App.kt**

In `App.kt`, change the content Box from

```kotlin
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when {
```

to

```kotlin
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    CoverBackdrop(state)
                    when {
```

(`CoverBackdrop` is in the same `com.slotify.app.ui` package — no import needed.)

- [ ] **Step 3: Screen-background audit**

Run: `grep -n "^\s*\(Column\|Box\|Surface\|LazyColumn\)(" composeApp/src/commonMain/kotlin/com/slotify/app/ui/screens/*.kt | head -30` and then check each screen's **root** modifier for `.background(` or an opaque `Surface`.

Expected: roots are transparent today (e.g. LibraryScreen's root is a bare `Column(Modifier.fillMaxSize())`). If any screen root paints an opaque background, remove that one `background(...)` modifier so the art shows through, and include the file in the commit. Note findings in the task outcome either way.

- [ ] **Step 4: Compile + suite**

Run: `source env.sh && ./gradlew :composeApp:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/slotify/app/ui/CoverBackdrop.kt \
        composeApp/src/commonMain/kotlin/com/slotify/app/ui/App.kt
# + any screen root background removed in Step 3
git commit -m "CoverBackdrop: sharp cover + scrim behind content, above the player bar"
```

---

### Task 7: Full verification + handoff

**Covers:** [S1], [S8]

**Files:** none (evidence only — any fix-ups discovered here get their own commit with their own message).

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: verified APK + test/warning evidence for the session report.

- [ ] **Step 1: Full test suite + APK**

Run: `source env.sh && ./gradlew :composeApp:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL (includes all new tests from Tasks 1–3 plus the existing suite).

- [ ] **Step 2: Warning audit**

Run: `source env.sh && ./gradlew :composeApp:compileKotlinDesktop :composeApp:compileTestKotlinDesktop :androidApp:assembleDebug --rerun-tasks 2>&1 | grep -E "^[^ ].*w:" | head -30`
Expected: only the pre-existing expect/actual Beta warnings (or empty output). **If new warnings appear, fix the code — never suppress.**

- [ ] **Step 3: Record APK timestamp**

Run: `ls -l --time-style=+%Y-%m-%d\ %H:%M androidApp/build/outputs/apk/debug/androidApp-debug.apk`
Report this exact timestamp with the handoff.

- [ ] **Step 4: Desktop manual smoke (user or agent at the keyboard)**

Launch `./run.sh` (or `source env.sh && ./gradlew :composeApp:run`) and walk the spec `[S8]` checklist:
- Play a track **with** embedded art → sharp cover fills the content area, darkest at the bottom, rows readable.
- Pause → art stays. Stop/clear → plain background returns.
- Play a track **without** art → plain background; nothing is generated or fetched.
- Skip quickly between tracks → no stale cover (only the current song's art shows).
- Switch tabs (Playlists → Library → Settings) mid-song → no blink.
- Playlist detail also shows the backdrop.

- [ ] **Step 5: Android handoff (user installs — no adb without asking)**

Report: APK path + timestamp from Step 3, plus the same `[S8]` checklist for the phone (opus files with/without art, `content://` linked file if present, pause/stop behavior).

- [ ] **Step 6: Mark outcome**

If every step above passes, the feature is done — record results for the session report (no commit needed if no files changed). If a step fails, fix in the smallest place, re-run that step, and commit the fix separately.


