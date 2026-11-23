# Artist Shuffle, Library Session UI, and Row-Mark Rework — Design

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/artist-shuffle.md)

Date: 2026-11-23 · Branch: `0.1.0` · Status: approved

## [S1] Problem

Slotify has a single boolean shuffle: playlists reorder on toggle and reshuffle at queue end, but the library ignores shuffle entirely, there is no artist-aware ordering, and nothing tracks what has already played. Playlist rows show a verbose Mark/Skip/Del cycle chip and a percentage-confidence chip that cannot be acted on. The library has no way to find the playing song, mark rows, or build playlists from what is on the device.

## [S2] Solution overview

Four playback modes cycled from the player-bar shuffle icon (Linear, Shuffle, Artist (2), Artist (all)), implemented as a hybrid: linear/shuffle keep the precomputed queue order, artist modes pick the next song dynamically through an artist session. One global in-memory "played" set drives library grey-out and no-repeat rules, cleared only by a confirmed reset popup or a new artist pass. The library screen gains letter mark chips, a per-row `+` add-to-playlist flow, a Find current button, current-song highlight with Stop icon, and greyed played rows. Playlist rows lose the "Linked" chip and gain a tappable percentage badge that confirms the match.

## [S3] Mode cycle and icon badge

- Tapping the shuffle icon in PlayerBar cycles: `LINEAR → SHUFFLE → ARTIST_2 → ARTIST_ALL → LINEAR`.
- Icon feedback only (no snackbar on change):
  - **LINEAR** — plain grey (onSurfaceVariant) shuffle icon, no badge.
  - **SHUFFLE** — primary-tinted icon, no badge (today's "on" look).
  - **ARTIST_2** — icon with a small **2** badge overlay.
  - **ARTIST_ALL** — icon with a small **★** badge overlay.
- Mode is persisted in the playback store. Legacy stored `shuffle: true/false` maps to SHUFFLE / LINEAR on load.

## [S4] Precomputed modes (LINEAR, SHUFFLE)

- Order generated up front exactly as today's shuffle does for playlists (saved orderKeys merge, shuffle the missing, keep current song on mode switch).
- **New:** `playLibrary` applies the mode too — SHUFFLE builds a shuffled order (starting at the tapped row's position); LINEAR stays source order. The library previously ignored shuffle.
- End of queue: SHUFFLE reshuffles for a new pass (existing behavior, keeps its completion message). LINEAR pauses at end (existing behavior).

## [S5] Dynamic artist modes (ARTIST_2, ARTIST_ALL)

- Source = whatever is playing: the library list passed to `playLibrary` (including a search-filtered view) or the playable rows of a playlist. Excluded always: rows marked SKIP or DELETE, unmatched playlist rows.
- Group by display artist (library: `displayArtist`; playlist: first entry of `remote.artistNames`, falling back to the local match artist / "Unknown artist").
- A pass: shuffle the artist order, then for each artist pick its songs at random — exactly **2** unplayed songs (ARTIST_2) or **all** of them (ARTIST_ALL) — then move to the next artist. No song repeats within a pass.
- Pass complete (every artist done): start a **new pass** (reshuffle artist order) **and clear the played set**, with a short status message analogous to today's "Shuffle cycle complete".
- Mechanics: the queue keeps the played history; when auto/manual next is at the end, the artist session appends the next pick and advances. `Previous` walks back through history without un-playing marks.
- Switching mode mid-song keeps the current song; the artist session is (re)built around it (current artist's block excludes the current song).

## [S6] Played set and reset popup

- One global **in-memory** set of library file paths (`playedKeys`), keyed like library queue keys (`lib:<filePath>`). Empty at every app launch.
- Playing a playlist row also marks its matched local file's path.
- While the set is **non-empty**:
  - Tapping a **greyed** library row, or tapping **any playlist row**, opens the dialog **"Reset all played already?"** with:
    - **[Reset all played]** — clear the set, then play the picked song.
    - **[Just play this]** — play the picked song, set unchanged (it may already be grey).
  - Tapping a **non-greyed** library row just plays; set unchanged.
- While the set is empty: no popup anywhere; picks behave normally.
- The currently playing row's highlight always wins over grey.

## [S7] Library row layout and marks

- Row (left → right): title/artist/duration block · **m/s/d chip** · **+ icon** · play/stop icon.
- Chip = same cycle as playlist rows: none → SKIP → DELETE-mark → none; label is a single narrow letter: **m** (none), **s** (skip), **d** (red, delete-mark). Identical component is used on playlist detail rows (which lose their wide "Mark/Skip/Del" text chip).
- Styling parity with playlist rows: SKIP/DELETE-marked library rows are dimmed; DELETE-mark text tinted red.
- Marks persist with the library index (a nullable mark on `LocalTrack`, re-applied by file path after every rescan) and exclude the row from library playback queues.
- No bulk "delete marked" action in the library yet — requested later only if testing shows the need.

## [S8] Add to playlist (per-row `+`)

- Every library row has a `+` icon → opens the **"Add to playlist"** confirmation page (scrollable dialog):
  - **Create new playlist** — name field + Create button.
  - **Existing playlists** — scrollable list; tapping one confirms immediately.
- The picked song is appended as a fully accepted track: `RemoteTrack` built from the `LocalTrack` (title, artist, album, duration), match with `confidence = 1.0` and `isManual = true` (so it survives rematch and shows no % badge), playlist `source = JSON`.
- After add: playlist is saved through the existing repository path and a confirmation snackbar shows which playlist received the song. Repeat per row to build a playlist.

## [S9] Find current, highlight, grey-out

- **Find current** button in the Library header. Resolves the playing song by file path (`nowPlaying.path`), so it also works when the song came from a playlist.
  - Found in the visible (filtered) list → smooth-scroll to it.
  - Hidden by the active search filter → snackbar "Playing track is hidden by search".
  - Nothing playing → snackbar "Nothing playing".
- **Current song row**: inverted colors (content-colored background, surface-colored text) regardless of pause state; its play icon becomes **Stop** (tap pauses/stops, matching playlist-row behavior). Highlight overrides grey.
- **Played songs**: rows whose key is in `playedKeys` render greyed (dimmed text) per [S6].

## [S10] Playlist row badge changes

- **"Linked" chip removed** entirely from playlist detail rows (`unlinkTrackFile` stays in AppState, unused from UI).
- **Percentage chip becomes tappable**: tap = confirm this match is acceptable → pin it by re-using `linkTrackToFile` with the row's existing match path (sets `isManual = true`) → the badge disappears from that row (manual rows show no chip).
- Re-picking a different file stays on the existing search/re-match button — no new control.

## [S11] Persistence and restart

- Playback store: `shuffle: Boolean` replaced by a mode field (legacy bool still read for old files).
- Artist session state and `playedKeys` are in-memory only. Restart mid-artist-mode resumes the current track at its saved index/position; the next advance builds a fresh artist pass (played set empty, so repeats are possible — accepted).
- Library marks live in the library index JSON and survive rescans (re-applied by file path, mirroring how playlist marks survive rematch).
- `PlaybackStore`, `PlaylistProgress`, and per-playlist resume keep working for LINEAR/SHUFFLE exactly as today.

## [S12] Non-goals

- Bulk delete of library files; multi-select add-to-playlist; persisted played sets; snackbar on mode change; playlist-card (whole-playlist) Mark chip changes; Android-specific behavior differences (all of this is commonMain).

## [S13] Acceptance criteria

1. Shuffle icon cycles the four modes; badge states match [S3]; mode survives restart.
2. Pure-function tests for the artist session: 2 songs per artist (ARTIST_2), full blocks (ARTIST_ALL), random artist order, no repeats within a pass, pass completion clears played + reshuffles.
3. Library SHUFFLE actually shuffles (regression guard) and skips SKIP/DELETE-marked rows.
4. Popup rules per [S6] (unit-test session transitions; manual check of dialog copy/buttons).
5. Find current scrolls to the playing row; filtered-out case shows the snackbar.
6. m/s/d chip: identical narrow single-letter cycle in playlist detail and library rows.
7. % tap pins the match, badge disappears, Linked chip gone; added-via-`+` tracks show no badge.
8. `+` flow creates a new playlist and adds to an existing one; both persist across restart.
9. `./gradlew :composeApp:desktopTest` green; no warning suppression; desktop + Android debug APK both build.
