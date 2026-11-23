# Cover-art backdrop design

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
- **Desktop impl:** jaudiotagger (already a dependency) —
  `AudioFileIO.read(file).tag?.createFirstArtwork()?.binaryData`.
  *Risk:* verify early that this returns art for opus; if null, add a small
  fallback parser for the base64 `METADATA_BLOCK_PICTURE` block in the Vorbis
  comment (pure, unit-testable).
- **Android impl:** jaudiotagger first (parity with scanner), fallback to
  `MediaMetadataRetriever.getEmbeddedPicture`. `content://` link paths skip
  straight to the retriever (jaudiotagger cannot open URIs).
- Decoding: `expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?` —
  Android `BitmapFactory.decodeByteArray().asImageBitmap()`, desktop Skia
  `Image.makeFromEncoded(bytes).asComposeImageBitmap()` (skiko ships with
  Compose Desktop). Everything `runCatching → null`.

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
- **desktopTest:** committed fixture audio file with embedded art (opus
  preferred, mp3 fallback if the local toolchain cannot write opus art);
  asserts bytes read + decode succeed; artless fixture asserts null. Skip
  gracefully when fixtures cannot be generated, like `VlcAudioPlayerTest`.
- **Manual:** desktop `run.sh` + fresh APK — sharp cover behind rows, survives
  pause, clears when nothing loaded, plain background for artless files,
  no stale art on fast skip, no tab-switch flicker.

## [S9] Out of scope

- Scan-time art extraction or sidecar cache files.
- Spotify playlist-cover network fallback, placeholder artwork.
- Blur treatment, art inside the player bar, art in library/playlist rows.
- Player-API art sources (vlcj / MediaPlayer).
