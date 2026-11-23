# Cover-art backdrop design

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/cover-art-backdrop.md)

Date: 2026-11-23 · Branch: 0.1.1 · Status: approved in brainstorm

## [S1] Problem

Audio files (notably the user's opus collection) carry embedded cover images
(`METADATA_BLOCK_PICTURE` / ID3 APIC), but Slotify never reads or shows them.
The area behind the playlists/library is dead space while a song is loaded.

## [S2] Requirements (user-confirmed)

- Cover appears as a **backdrop behind the content area** — the region above
  the player bar (player + volume + nav), while a track is loaded.
- Treatment: **sharp image, scaled to fill, with a dark scrim** (no blur) so
  rows stay readable.
- Files **without** embedded art keep today's plain background — no
  placeholder, no network fetch (no Spotify cover fallback).
- Visible **whenever a track is loaded** (playing or paused); clears when
  nothing is loaded.
- Covers Playlists, playlist detail, Library, **and Settings** (no blink when
  switching tabs mid-song).
- Desktop + Android both.

## [S3] Solution overview

Approach A (chosen over scan-time cache / player-provided art): **read the
embedded picture on demand when the track changes**, decode to `ImageBitmap`,
cache per path, render in a new backdrop composable behind the content.
No new dependencies, no `library.json` growth, no scan cost.

## [S4] Extraction layer

Mirror the existing `LibraryScanner` pattern:

- `commonMain`: `interface CoverArtReader { suspend fun readArt(path: String): ByteArray? }`
  plus `expect fun createCoverArtReader(): CoverArtReader`.
- **Verified facts (2026-11-23):** the project's jaudiotagger 3.0.1 build has
  **no opus reader at all** (`No Reader associated with this extension:opus`),
  while `getFirstArtwork()` on mp3 works (returns APIC bytes). Decoding is a
  common CMP API: `ByteArray.decodeToImageBitmap()` — no expect/actual needed
  for decode.
- **Desktop impl:** `.opus` → dedicated `readOpusEmbeddedPicture(path)` parser
  in commonMain (walk Ogg pages → `OpusTags` packet → find
  `METADATA_BLOCK_PICTURE` → base64-decode → skip FLAC-Picture header → raw
  image bytes); other formats → jaudiotagger `tag?.firstArtwork?.binaryData`.
  Everything `runCatching → null`.
- **Android impl:** `content://` → `MediaMetadataRetriever` only (jaudiotagger
  cannot open URIs); `.opus` files → the common parser, retriever fallback;
  other formats → jaudiotagger first (parity with the scanner), retriever
  fallback.
- A file with **no** embedded picture returns `null` at every layer — the app
  then keeps the plain background and must never generate, fetch, or acquire
  a substitute image.

## [S5] State & caching (AppState)

- `val coverArt: StateFlow<ImageBitmap?>` exposed from `AppState`.
- Internal watcher in `init`: collect `nowPlaying`, distinct by `path`;
  `null` → clear flow; path change → cache hit ? publish : read+decode on IO.
- **Stale-result guard:** after the suspend read, publish only if the path is
  still the current `nowPlaying.path` (fast skips must not show old art).
- `CoverArtCache`: small pure LRU keyed by path, cap **6** entries (bounded
  ~a few MB; back/forward between recent songs is instant).
- `nowPlaying == null` → flow cleared → plain background.

## [S6] Backdrop rendering (UI)

- New `CoverBackdrop(state)` composable; first child of the content `Box` in
  `ui/App.kt` so it fills exactly the area above the bottom bar. It renders
  **nothing** when `coverArt` is null.
- Layers: `Image(bitmap, ContentScale.Crop)` → dark vertical gradient scrim
  (~45 % → 70 % black, darkest at the bottom) → screens draw above (their
  top-level containers must stay transparent; audit removes any opaque
  top-level screen background found).
- ~250 ms crossfade between covers; short fade-out when art clears.
- Dialogs/snackbars keep composing above the backdrop; `PlayerBar` untouched.

## [S7] Error handling & performance

- Every extraction/decode step `runCatching → null`: a corrupt picture
  degrades to the plain background and can never affect playback.
- All IO on a background dispatcher; the UI only observes flows.
- Cache bounds memory; no re-reads for recently played paths.

## [S8] Testing & verification

- **commonTest:** `CoverArtCache` get/put/evict/size tests with tiny in-memory
  `ImageBitmap`s.
- **desktopTest:** four committed fixtures under
  `desktopTest/resources/art/` (`cover-art.opus`, `cover-art.mp3`,
  `no-art.opus`, `no-art.mp3` — generated once via mutagen/ffmpeg): with-art
  fixtures assert bytes read (and PNG magic), artless fixtures assert null.
  Missing-path input asserts null. No runtime fixture generation in tests.
- **Manual:** desktop `run.sh` + fresh APK — sharp cover behind rows, survives
  pause, clears when nothing loaded, plain background for artless files,
  no stale art on fast skip, no tab-switch flicker.

## [S9] Out of scope

- Scan-time art extraction or sidecar cache files.
- Spotify playlist-cover network fallback, placeholder artwork.
- Blur treatment, art inside the player bar, art in library/playlist rows.
- Player-API art sources (vlcj / MediaPlayer).
