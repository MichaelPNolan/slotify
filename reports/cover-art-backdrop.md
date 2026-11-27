---
feature: cover-art-backdrop
status: delivered
specs:
  - specs/2026-11-23-cover-art-backdrop-design.md
branch: 0.1.1
commits: b22daed..1c508d9
---

# Cover-art backdrop — Final Report

## What Was Built

While a track is loaded, Slotify now shows that song's embedded cover image
as a full backdrop behind the content screens — sharp, cropped to fill, with
a dark vertical gradient scrim so playlist/library rows stay readable. The
backdrop occupies exactly the region above the bottom bar (player + volume +
nav), appears on every tab (Playlists, playlist detail, Library, Settings),
stays while paused, crossfades (~250 ms) when the song changes, and clears
when nothing is loaded.

Extraction is on demand: when `nowPlaying` changes, the embedded picture is
read on an IO thread, decoded to an `ImageBitmap`, and cached per path
(6-entry LRU). A file with **no** embedded image yields the plain background
— the app never generates, fetches, or acquires a substitute (explicit user
rule). Works on desktop and Android, including `content://` link files on
Android.

## Architecture

Data flow: `nowPlaying` path change → `AppState` watcher → `CoverArtReader`
(bytes) → `decodeToImageBitmap()` → `CoverArtCache` → `coverArt: StateFlow`
→ `CoverBackdrop` in `App.kt`.

| Component | File | Role |
|-----------|------|------|
| Reader contract | `commonMain/library/CoverArtReader.kt` | `interface CoverArtReader { suspend fun readArt(path): ByteArray? }` + `expect fun createCoverArtReader()` |
| Desktop actual | `desktopMain/library/CoverArtReader.desktop.kt` | `.opus` → parser; else jaudiotagger (mp3/flac/m4a/aiff); `content://` → null |
| Android actual | `androidMain/library/CoverArtReader.android.kt` | `content://` → `MediaMetadataRetriever`; `.opus` → parser then retriever; else jaudiotagger then retriever |
| Opus parser | `commonMain/library/OpusArtwork.kt` | `readOpusEmbeddedPicture(path)`: Ogg page walk → `OpusTags` packet → `METADATA_BLOCK_PICTURE` → base64 → FLAC-Picture header skip → raw image bytes |
| Cache | `commonMain/library/CoverArtCache.kt` | Access-order LRU, cap 6, keyed by file path |
| State | `state/AppState.kt` | `coverArt: StateFlow<ImageBitmap?>`; watcher = `map{path}.distinctUntilChanged()` → IO read → decode → cache → publish (stale-path guard) |
| UI | `ui/CoverBackdrop.kt` + `ui/App.kt` | `Crossfade(250 ms)`: `Image(Crop)` + gradient scrim 45 %→70 % black; first child of the content `Box` |

Every extraction/decode step is `runCatching → null`; failures degrade to the
plain background and can never affect playback.

### Design Decisions

- **On-demand extraction instead of scan-time cache** — keeps `library.json`
  and scan cost untouched; a 6-entry LRU makes back/forward instant.
- **Dedicated opus parser** — the project's jaudiotagger 3.0.1 build has *no
  opus reader at all* (verified: `No Reader associated with this
  extension:opus`), so opus art is parsed directly from the `OpusTags`
  comment header (RFC 7845: magic + vendor string + comment count + comments).
- **jaudiotagger helper lives per-platform, not in commonMain** — the
  dependency is declared only in the desktop/android target blocks, so
  commonMain cannot import it; each actual file carries a private copy.
- **Decode via CMP's common `ByteArray.decodeToImageBitmap()`** — exists on
  both targets, so no platform decode code and no extra dependencies.
- **Backdrop renders nothing when art is null** — per the user's rule, a
  missing picture is never substituted with generated or fetched artwork.

## Usage

No configuration — the feature is automatic. Play any track whose file has
an embedded cover (opus `METADATA_BLOCK_PICTURE`, mp3 APIC, etc.) and the
backdrop appears behind the content area; pause keeps it; clearing playback
removes it. Files without art simply keep the previous look.

Verification checklist (manual, spec `[S8]`):

1. Track with art → sharp cover behind rows, darkest at bottom, text readable.
2. Pause → art stays; stop/clear → plain background returns.
3. Track without art → plain background; nothing generated or fetched.
4. Fast skips → no stale cover (only current song's art).
5. Tab switches mid-song → no blink.
6. Android: same, plus `content://` linked file if present.

## Verification

- **12 new tests, all green** in `:composeApp:desktopTest`:
  - `CoverArtCacheTest` (4) — get/put, unknown path, LRU eviction, recency refresh.
  - `OpusArtworkTest` (4) — artful opus → PNG bytes, artless opus → null, non-opus → null, missing file → null.
  - `CoverArtReaderDesktopTest` (4) — opus + mp3 with art → bytes, artless files → null, missing path → null.
- Full suite `:composeApp:desktopTest` + `:androidApp:assembleDebug` — BUILD
  SUCCESSFUL; warning audit shows only the pre-existing expect/actual Beta
  warnings (nothing suppressed).
- APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` —
  **2026-11-23 19:35**, 69.1 MB (awaiting user install/manual pass; no adb
  without asking).
- Screen-background audit: no screen root paints an opaque background (only
  LibraryScreen's row highlight uses `background`), so the backdrop shows
  through everywhere.

## Journey Log

- [lesson] Verify library claims empirically: jaudiotagger 3.0.1 ships no opus classes at all, and its artwork API is `getFirstArtwork()` (not `createFirstArtwork()`).
- [pivot] The fixture test caught the parser reading comment-count before the vendor string — RFC 7845 puts vendor first; fixed in parser and plan.
- [lesson] ffmpeg refuses `attached_pic` on ogg/opus ("Invalid argument"); mutagen writes `METADATA_BLOCK_PICTURE` correctly for test fixtures.
- [lesson] Target-scoped Gradle deps are invisible to commonMain even when both targets declare them — shared helpers must live per-platform (or in `commonMain` only if the dep is in the common block).
- [lesson] `tween` is `androidx.compose.animation.core.tween`; the `androidx.compose.animation` package holds `Crossfade` only.

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `specs/2026-11-23-cover-art-backdrop-design.md` | Design (amended once with verified extraction facts) | See §S4 for the opus/jaudiotagger constraint |
