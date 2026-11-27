---
feature: artist-shuffle
status: delivered
specs:
  - specs/2026-11-23-artist-shuffle-design.md
branch: 0.1.0
commits: aac1754..5724108
---

# Artist Shuffle + Library Session UI — Final Report

## What Was Built

The player-bar shuffle button now cycles four playback modes — **Linear → Shuffle → Artist (2) → Artist (all)** — with a small `2` / `★` badge overlaid on the icon for the artist modes. Artist modes order playback as random artist blocks: pick a random artist, play two random unplayed songs by them (or all of them), then move on. No song repeats inside a pass; when every artist is done the app starts a new pass and clears the played marks with a status message. Linear and Shuffle keep the precomputed queue order they always had, and Shuffle now works from the Library too (it previously only reordered playlists).

A single in-memory **played set** (library file paths, keyed `lib:<path>`) drives a new Library session UI: played rows are greyed, the currently playing row is highlighted with inverted colors and a Stop icon, and a **Find current** header button smooth-scrolls to whatever song is playing (snackbar if a search filter hides it). Picking a row at the wrong moment asks first: a greyed library row or any playlist row while the set is non-empty opens **"Reset all played already?"** with `Reset all played` (clears, then plays) and `Just play this` (plays, set intact).

Library rows gained the same narrow cycling mark chip as playlist rows — **m** (none) → **s** (skip) → **d** (red, delete-mark) — persisted on `LocalTrack.mark` and re-applied by file path after every rescan; marked rows are dimmed and excluded from playback. Each library row also has a **+** that opens an "Add to playlist" confirmation dialog: type a name to create a new playlist, or tap an existing one — the song is appended as a fully pinned match (no % badge). On playlist rows the "Linked" chip is gone; tapping the **% badge** now pins that match as accepted and makes the badge disappear (re-picking a file stays on the existing search/re-match button).

## Architecture

- `composeApp/src/commonMain/kotlin/com/slotify/app/playback/PlaybackMode.kt` — `enum PlaybackMode { LINEAR, SHUFFLE, ARTIST_2, ARTIST_ALL }`, `cycle()`, `isArtistMode()`, and `PlaybackStore.initialMode()` (reads new `mode: String` field, falls back to legacy `shuffle: Boolean`).
- `composeApp/src/commonMain/kotlin/com/slotify/app/playback/ArtistSession.kt` — pure state machine: groups `ArtistSourceItem(key, artist)` by artist, shuffles artist order per pass, `next(played)` returns the next unplayed key within quota (2 or unbounded) or null at pass end; `restartAround(artist)` rebuilds a pass around a manual pick (current song counts toward quota).
- `AppState.kt` — mode plumbing: `_playbackMode` + synced legacy `_shuffleEnabled`, `cyclePlaybackMode()` → `applyMode()` (persists mode, reorders around current song or rebuilds the artist session), `playedKeys` flow, `markPlayed()` on every `playAt()`. In artist modes the queue holds **history only**; `advance()` appends the session's next pick (with one reshuffle+clear retry at pass end). `PickAction` + `requestPlaylistPick`/`requestLibraryPick` gate row picks behind the confirm dialog. Library: `cycleLibraryRowMark()`, `createPlaylistWithTrack()`/`addToPlaylist()` (pinned `TrackMatch(confidence = 1.0, isManual = true)`), `showMessage()`.
- UI: `PlayerBar.kt` (cycle button + badge), `ResetPlayedDialog.kt` and `AddToPlaylistDialog.kt` (hosted from `App.kt` and `LibraryScreen.kt`), shared `MarkChip.kt`, rewritten `LibraryScreen.kt`, `PlaylistDetailScreen.kt` (tappable ConfidenceChip, Linked chip removed).
- Persistence: `PlaybackStore.mode`, `LocalTrack.mark` (both backward-compatible defaults).

### Design Decisions

- **Hybrid ordering** — Linear/Shuffle precompute the queue as before while artist modes pick dynamically, because persistence (resume-after-restart), previous, and end-of-pass reshuffle already worked on a concrete order; the artist state machine only had to answer "what's next at the end of history".
- **History-only queue in artist modes** — a full scaffold queue would let auto-advance drift through source order instead of asking the session; keeping only played history makes append the single path forward and makes Previous walk real history.
- **Played set is in-memory with a confirm popup** — closing the app starts fresh (per requirements), and clearing a visible grey-out needed an explicit `Reset all played already?` step so a stray tap can't wipe it.
- **Pin-on-accept for %** — reuses `linkTrackToFile` with the row's existing path, so an accepted match survives rematch with zero new persistence machinery.

## Usage

- Player bar: tap the shuffle icon to cycle modes; badge shows `2` (two songs per artist) or `★` (all songs per artist). Mode survives restart.
- Library header: **Find current** scrolls to the playing song; **Scan** unchanged.
- Row taps: non-greyed library row plays immediately; greyed library row or any playlist row asks the reset question first.
- Marks: tap the chip to cycle `m` → `s` → `d` → `m`. Skip/delete-marked rows never enter a queue.
- Library row **+**: create a named playlist or tap an existing one to add that song (duplicates are refused with a message).
- Playlist row **%**: tap to accept/pin the match; badge disappears.

## Verification

- `./gradlew :composeApp:desktopTest` green — new suites: `PlaybackModeTest` (3), `ArtistSessionTest` (5: quota, contiguity, no repeats, reshuffle, restartAround), `RowMarkTest` (2), plus the full pre-existing suite.
- `./gradlew :androidApp:assembleDebug` green — APK `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (Nov 23 13:38).
- Warning audit: only the pre-existing `expect/actual` Beta notices; no new warnings.
- Manual smoke checklist (10 items: mode cycling + restart, artist blocks, library shuffle, grey-out + popup both paths, Find current, highlight/Stop, chips, % pin + rescan, + create/add persistence) delivered for on-device/desktop testing.

## Journey Log

- [lesson] This Kotlin toolchain rejects enum `.entries` and unqualified `values()` inside extensions — use `PlaybackMode.values()`.
- [lesson] `Modifier.offset(x, y)` imports from `androidx.compose.foundation.layout`, not `androidx.compose.ui.draw`.
- [pivot] The artist-quota test originally assumed the manually picked song was not counted; `restartAround` sets the block counter to 1, so the test was corrected to match the spec's "block excludes/counts the current song".
- [dead end] A precomputed full-queue for artist modes would let `advance()` play scaffold rows the session never chose — resolved by making artist queues history-only with appended picks.

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `specs/2026-11-23-artist-shuffle-design.md` | Design spec | 13 sections, user-approved |
