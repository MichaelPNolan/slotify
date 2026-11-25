# Slotify — development handoff

Read this (plus `README.md` setup) before continuing on a new machine.
Source of truth for long-lived notes: project memory `MEMORY.md` under MiMoCode’s data dir; this file is the **in-repo** distilled copy so a fresh clone is enough.

**Branch / release line:** **`0.1.3`** (checked out here). Remote: `https://github.com/MichaelPNolan/slotify.git` (`origin/0.1.3`). Older lines: `0.1.2`, `0.0.3`, `main`. Do not re-init git or force-push unless the user asks.

**`VERSION` file:** `0.1.3` — keep it in step with the branch when cutting a new line.

---

## Catch-up on this station (branch `0.1.3`)

This host **already has the repo**. No re-clone:

```bash
cd <slotify>
git status -sb          # expect: ## 0.1.3...origin/0.1.3  (after first push)
git pull --ff-only      # only if behind
git log --oneline -8
source env.sh           # JAVA_HOME must match THIS machine
./gradlew :composeApp:desktopTest --console=plain
./gradlew :androidApp:assembleDebug --console=plain
```

### History

- **`0.1.2`** — tip before this branch: artist shuffle, cover art, Bing search, batch score, quarantine, etc. (~48 commits from `5d08e0b`).
- **`0.1.3`** — this line: opened for new work; `VERSION` bumped to `0.1.3`.

### What landed since the old `0.0.3` handoff (`5d08e0b` → `75399a7`, ~48 commits)

| Theme | Branch / area | Summary |
|--------|----------------|---------|
| **Artist shuffle + library session** | `0.1.0` | Four playback modes, played set, marks, add-to-playlist — see `reports/artist-shuffle.md` |
| **Cover-art backdrop** | `0.1.1` | Embedded art behind content; opus parser — see `reports/cover-art-backdrop.md` |
| **Android YT path + write grant** | 0.1.x | Download into Settings music root; `MANAGE_EXTERNAL_STORAGE`; 403 retries |
| **Playback fixes** | 0.1.x | Row tap starts that row; Android `isPlaying` icon bug; corrupt-file quarantine |
| **Spotify public search** | `0.1.2` | DDG → DDG Lite → **Bing** + bare-URL fallback; per-engine status |
| **UI polish** | `0.1.2` | `LinkFileDialog`, `LegalDisclaimer`, PKCE await plumbing **removed** from Settings |
| **Batch YT** | `0.1.2` | Configurable min score; search fallbacks until bar clears; same button stops batch |
| **Rename / heal** | 0.1.x | Editable song-name walk; pins survive; broken rows → To be sourced + held |

Design trail for those features: `specs/`, `plans/`, `reports/` in-repo.

### Architecture rows already current on this branch

- **YouTube Android vs desktop — do not unify** (user): Chaquopy on phone, system `yt-dlp` on Linux. Download dir = Settings `libraryRoots` first (e.g. `/sdcard/Music`), staging `<root>/downloads`, promote to music root (not hardcoded `Slotify/`). Errors go to **`youtubeStatus` only** while Find dialog is open (snackbar hides behind AlertDialog). 403 → `player_client` × format retries.
- **Android write grant:** `READ_MEDIA_AUDIO` is not enough for `/sdcard/Music` writes — `MANAGE_EXTERNAL_STORAGE` + `requestSharedMusicWriteAccess()` (All files access Settings) on EPERM.
- **Android play icon:** optimistic `isPlaying` on load/play (prepareAsync used to leave the button on “Play”).
- **Spotify Settings:** connection / PKCE / browser-cookie **probe section removed**; URL paste + public search remain. Manual `sp_dc`/embed cookie path still used for private playlist **import** via Floorp reader on desktop.

---

## Hard rules (never violate)

1. **No sudo by default** — prefer user-space tooling (`~/tools/`, pip `--user`). **Exception (user 2026-11-22):** the user will install system packages themselves, or grant sudo manually, for anything normal for the system to have (e.g. full `vlc`, build deps). Ask first; never assume root.
2. **Ask permission before any screen capture** (Spectacle, ffmpeg, …).
3. **Never propose Spotify Premium workarounds.** User owns their music; will not subscribe. App = right to store/play their own files.
4. **Spotify = metadata only.** Never fetch Spotify audio.
5. **Audio extension whitelist** (pinned in tests): `aac, mp3, mp4, flac, wav, aif, aiff, opus`. Skip hidden files **and** hidden dirs (AppleDouble `._*`). Android scan currently passes `skipHidden = false` for FS walk but still filters non-whitelist extensions — keep whitelist and hidden-dir policy in sync when touching scanners.
6. **YouTube downloads: opus stream copy, never re-encode.**
7. **Volume control always visible** on every tab.
8. Keep instructions copy-pasteable; surface errors where the user sees them (Settings → Last error, terminal stack traces via adapters).

---

## What Slotify is

Compose Multiplatform music player: **desktop-first** (AMD64; authoring host Manjaro, current host CachyOS), Android shares code.

Workflow: Spotify playlist **metadata** → fuzzy-match local library → unmatched tracks: **Link file** or **YouTube download** → play with vlcj (desktop) / MediaPlayer (Android).

Also first-class: **public playlist search**, **batch YouTube**, **playlist trash**, **row marks**, **playback resume**, **phone transfer**, then on **0.1.x**: **four playback modes + artist blocks**, **library session UI**, **cover-art backdrop**, **corrupt-file quarantine**, **editable rename / heal**, **Bing public search**.

User confirmed earlier (2026-10-27): desktop live. 0.0.2–0.0.3 (2026-11-22): Android yt-dlp, Spotify download naming, SAF Link file. 0.1.0–0.1.2 (2026-11-23): artist shuffle, cover art, Android write grant, playback/search polish. Treat “coded” ≠ “user-verified” unless they say otherwise.

---

## Architecture (why)

| Area | Decision |
|------|----------|
| Module | `:composeApp` KMP library (`com.android.kotlin.multiplatform.library` + `jvm("desktop")`) + thin `:androidApp` (`com.android.application`) APK shell + launcher icon. No AGP bypass flags in `gradle.properties`. |
| Toolchain | Gradle **wrapper 9.8.2**, Kotlin **2.4.21**, CMP **1.12.2**, AGP **9.4.1**, **compileSdk 37** (PREVIEW package `platforms;android-37.0`), targetSdk 35, minSdk 26, JDK **21**. Desktop runtime artifact: `desktop-jvm-linux-x64` (not `compose.desktop.currentOs`). |
| Desktop launch | `./run.sh` → `source env.sh` → `./gradlew :composeApp:run`. |
| Desktop window (tiling WM) | Host is **niri**. Full-window Scaffold (`fillMaxSize`); soft open **1100×740**. Known: AWT can miss XWayland resizes (JNA size poll in `Main.kt`). No niri window-rule workarounds. |
| Tags / scan | Shared walk: `distinctLibraryRoots` + `collectAudioFiles` (canonical dedupe, no double-count of nested roots). Desktop: jaudiotagger. **Android: jaudiotagger first** (parity with desktop titles/artists/ISRC), MediaMetadataRetriever fallback only. Single-file `readTrack` for Link/YouTube index. |
| Playback | Desktop: vlcj. Android: MediaPlayer. **Four modes** (`PlaybackMode`): Linear → Shuffle → Artist(2) → Artist(★) via player-bar cycle + badge. Artist modes use `ArtistSession` (random artist blocks; **played set is in-memory** — app restart clears it; confirm dialog before wipe). **Android `isPlaying`:** optimistic on `load`/`play` so the button doesn’t stay “Play” while audio runs. **Playback failure → Quarantine:** corrupt file leaves the index; playlist rows → To be sourced + `heldTrackKeys` (stays unsourced until user pins/downloads). |
| Persistence | JSON under app data (`~/.local/share/slotify/` desktop; `filesDir` Android). Atomic tmp+rename. **`playback.json`** = mode + resume (index/position). **`quarantine.json`** = corrupt paths. `LocalTrack.mark` re-applied by path after rescan. |
| Managed music dir | Desktop `~/Music/Slotify`. **Android YT writes Settings music root** (first `libraryRoots`, e.g. `/sdcard/Music`), staging `<root>/downloads`, promote to root — see YouTube row. |
| Spotify | Embed URL import primary; **public search DDG HTML → DDG Lite → Bing** (+ bare-URL scan). Settings **no longer has** PKCE/browser-session connect UI (removed 0.1.x); private embeds still use Floorp `sp_dc` on desktop. |
| Matcher | ISRC → 0.99; both-axes containment → ≥0.95; blend title 0.55 / artist 0.30 / duration 0.15. Duration soft (vinyl); >120s gap caps 0.80. Wrong-artist gate caps at 0.70. **Also scores filename-parsed title/artist and takes max(tag, filename)** so Android/desktop tag disagreement does not flip matches. |
| Manual pins | `manual_links.json`: playlistId → trackKey → path. Rematch applies pins first (`isManual`, conf 1.0). `trackKey` = Spotify id → uri → `name\|artistNames`. Portable copy under Music root; paths **remapped** on load (`ManualLinks.remapMusicPaths`). |
| YouTube | **Desktop:** system `yt-dlp` + ffmpeg (`YoutubeDlpSource`) — flags `-f "251/140/bestaudio" -x --audio-format best --embed-metadata --embed-thumbnail` (unchanged). **Android:** bundled yt-dlp via Chaquopy (`dev.ffmpegkit-maintained:yt-dlp-android:2.0.2`) + `ffmpeg-kit-audio` remux (`YoutubeSource.android.kt`). **Do not unify** — Chaquopy on phone, system binary on Linux (user, 2026-11-23). Search/download use a helper Python module (`slotify_ytdlp.py` in app filesDir) because the stock `ytdlp_runner` omits `--dump-json`/`--flat-playlist`/`full format control`. **Chaquopy edges:** never `PyObject.toJava(List)` on `sys.path`/`sys.modules`; Python `search()` returns a JSON **string** only. **Download dir = Settings music root** (`libraryRoots` first, e.g. `/sdcard/Music`), staging `<root>/downloads` (fallback `<root>` if subdir not writable); **promote completed file to the music root** — not a hardcoded `Slotify/` path (user, 2026-11-23). Write-probe before fetch. **HTTP 403:** helper retries `player_client` sets × formats; mobile UA + referer; stream copy only. **Error UI:** download failures write **only** `youtubeStatus` (red inside Find dialog) — **never** `emit()`/snackbar while that dialog is open (snackbar sits behind AlertDialog). Results re-enable after failure (busy = in-flight status only). ABIs: arm64-v8a + x86_64. Fallback search phrasings. Index **only the new file**. Auto-pin download to requesting row. |
| Batch YouTube | Unmatched rows; threshold = **`settings.youtubeBatchMinScore`** (default **0.85**, slider in Settings + detail). Search **fallbacks keep trying until best ≥ minScore**. Same button **stops** an in-flight batch. Status on `youtubeStatus` / `youtubeBatchRunning`. |
| Portable transfer | Export/import `playlists.json` + `manual_links.json` to/from **Music library roots** (not `Slotify/`). Cold start: app-private playlists win; else Music-root portable file is loaded once and copied into app data. Pins merged portable → app. |
| Android pickers | **SAF-first (2026-11-22):** Link file / JSON open with `EXTRA_INITIAL_URI` inside a **persisted Music tree** (`ACTION_OPEN_DOCUMENT_TREE` once → `takePersistableUriPermission` → `SafMusic` prefs). Document resolution: real path if `primary:`/MediaStore yields one, else **keep `content://`** (no forced copy into Slotify). `AndroidAudioPlayer` + `AndroidLibraryScanner.readTrack` accept content URIs; matcher uses `LocalTrack.nameForFilenameParse` (`displayPath` for SAF). Match still pins by `filePath` (path or content URI). **Write access (user EPERM 2026-11-23):** `READ_MEDIA_AUDIO` is not enough for `File` writes under `/sdcard/Music` (yt-dlp / promote). Manifest declares `MANAGE_EXTERNAL_STORAGE` (+ `WRITE_EXTERNAL_STORAGE` maxSdk 28). On missing grant, app opens **All files access** Settings (`requestSharedMusicWriteAccess`); download error tells the user to enable it and retry. |
| Symlink staging | **Desktop only** (`supportsSymlinkStaging()`). Stage matched playlist files as symlinks into Slotify for phone copy (`cp -L` / rsync `-L`). UI hidden on Android. |
| Playlist trash | Soft-delete: mark → trash → restore or empty. Empty-trash can delete audio **only** if no remaining playlist references the path (`trashOnlyAudioPaths`). |
| Row marks | Playlist + library: shared `cycleMark()` none → SKIP → DELETE → none. Playlist: survive rematch; SKIP out of queue; DELETE removed on “delete marked”. Library: `LocalTrack.mark`, re-applied by path after rescan; marked rows dimmed / excluded from queue. |

### Spotify import (important)

- **Paste URL** → `parseSpotifyPlaylistId` → `SpotifyEmbedSource` fetches  
  `https://open.spotify.com/embed/playlist/{id}` → parse `__NEXT_DATA__` → `entity.trackList`.  
  Public: no login. Private: attach Floorp `sp_dc` via `readBrowserSpDcCookie()`.
- **Public search:** Playlists tab free-text → `SpotifyPublicSearch` engines in order: **DuckDuckGo HTML → DDG Lite → Bing** (`site:open.spotify.com/playlist …`), plus bare-URL fallback; per-engine status (blocked/HTTP/empty), limit ~20, scrollable results. Import still uses the embed path. Not the Web API (free/dev 403/429). **Settings connect/PKCE UI removed** — URL paste + search only for discovery.
- **`get_access_token` is CDN-blocked** (Fastly “403 URL Blocked”). Embed `accessToken` is **not** accepted by `api.spotify.com` for free accounts (“user is not registered for this application”).
- OAuth Web API + free account + Development-mode app → **403**  
  `"Active premium subscription required for the owner of the app…"` — do not retry as if it were a token bug. (PKCE path largely unplugged from UI as of 0.1.x; leftover code/tests may remain.)
- Embed list caps ~**100** tracks; `?offset=` does not paginate.
- Refresh **merges** playlists (`replaceExisting=false`) so URL-imported lists survive. `mergePlaylists` = incoming wins by id, keeps non-overlapping existing.
- Redirect URI (exact): `http://127.0.0.1:43821/callback`  
  Scopes: `playlist-read-private playlist-read-collaborative`
- Floorp opens `Desktop.browse()` as an **unfocused background tab** — always print authorize URL to the terminal.

- **Naming convention (agreed direction, not fully built):** on-disk names `Artist - Title [- Version].<ext>` (sanitize path-illegal chars). Multi-format OK; index one logical track, many file candidates. Prefer flac → opus → mp3 → aac at play/match time when several exist. No re-encode by default.
- **Staging vs final (user, 2026-11-22):** under `Slotify/`, `downloads/` and `conversions/` hold temp/in-progress audio only. Finished working files at Slotify root. Purge staging is **manual**.
- **Multi-root libraries (user, 2026-11-22):** roots can be mixed-artist collections. Matcher scores across **all** `libraryRoots`. Overlapping roots must go through `distinctLibraryRoots` (see 0.0.3). Open work: **listen-to-confirm** UX (confidence chip alone is not enough). Keep unused functions until replacements land.

### Floorp `sp_dc` (desktop)

- Profiles: `~/.config/floorp/` (active Path in `profiles.ini`, e.g. `zqjk64do.default-default`).
- `cookies.sqlite` **locked while browser runs** — copy `+ -wal`/`-shm` to temp first.
- Plaintext `moz_cookies.value`; `sp_dc` host `.spotify.com` (~210 chars). Skip NSS `v10`/`v11`/`v20`. Never log full cookie.
- Reader: `readBrowserSpDcCookie()` + `org.xerial:sqlite-jdbc` (desktopMain).

### Phone transfer (0.0.3)

1. Desktop: Settings → **Export playlists** → writes `playlists.json` + `manual_links.json` to Music root (e.g. `~/Music/`). Saving playlists also best-effort mirrors to Music root.
2. Copy audio (optionally after **Stage playlist files as links** with `cp -L` / rsync `-L`) + those JSON files onto the phone under `/sdcard/Music/`.
3. Android: Settings → **Import playlists** (or cold start with empty app data). Portable pins remapped `…/Music/…` → `/sdcard/Music/…`, then rematch.
4. Raw `/sdcard/…/*.json` often **blocked** by scoped storage → use **Pick file…** (SAF). Filename field also accepts `content://` and resolves `/sdcard` ↔ `/storage/emulated/0` aliases when readable.

---

## Feature map (0.0.x → 0.1.2)

| Area | Behavior |
|------|----------|
| Playlists tab | URL paste + clipboard; public search (DDG→Bing); Refresh (OAuth merge); mark → trash; trash always visible + empty; play/stop cards. |
| Playlist detail | Single LazyColumn (To be sourced + Matched). Filter. Row marks. **`LinkFileDialog`** (search/re-match path). Find on YouTube / batch (tunable min score, stop button). **Hold 5s / swipe** → redo. Swipe **unsourced** row left → confirm → remove from playlist. **% badge tap = pin** (Linked chip gone). Confidence chip not a one-click play. |
| Library tab | Search; **Find current** (player-bar title tap jumps here + scrolls to playing row); highlight/grey played; **m/s/d** mark chips; **+** add-to-playlist (create/append, pinned conf 1.0). Auto-follow: tap playing title locks, drag releases. |
| Settings | URL import; roots + scan; **batch min-score slider**; Music export/import + desktop symlink stage; JSON pick/path; **Legal disclaimer**; About. **No** Spotify connection / browser-session probe / PKCE await. Persistent **Last error**. |
| Playback | Four modes + played set (in-memory) + artist blocks; resume from `playback.json`; row tap starts **that** row (saved resume is header-only). Marks skip/delete out of queue. Corrupt file → quarantine + To be sourced + hold. |
| Cover art | `CoverBackdrop` behind list regions only (headers plain). Embedded art only (opus parser / jaudiotagger / MediaMetadataRetriever); **never** fetch/generate a substitute. 6-entry LRU. Android `content://` supported. |
| Rename / heal | One-at-a-time editable rename walk (primary artist conservative); live pins kept; healed rows rematch immediately; broken paths → unsourced + held. |
| Headless CLI | `./gradlew :composeApp:populatePlaylist --args="<spotify-url>"` → embed import + scan + match. |

Design docs: `specs/2026-11-23-*.md`, `plans/…`, `reports/artist-shuffle.md`, `reports/cover-art-backdrop.md`.

---

## Open work

- **T2 content-fingerprint reconnect**: size + head/tail hash per match; scan re-binds by fingerprint first; play-failure self-heal beyond quarantine. (Designed, not built.)
- Android: **token storage still in-memory** if any PKCE path is revived; launcher icon from `graphics/slotify.png`.
- Android YouTube / SAF Link / phone transfer / batch — **device-proven only if the user says so**; still treat as smoke-test territory. Debug APK ~**66MB**; release → ABI splits/AAB. Free `yt-dlp-android` has no curl-cffi.
- **Already-downloaded** YouTube files (pre-Spotify-naming) may still have wrong titles/tags — re-fetch or re-link.
- Played set is **in-memory only** (spec: restart clears) — if user wants persistence, that’s a product change.
- Listen-to-confirm UX for fuzzy matches; multi-format play preference (flac→opus→…).
- `VERSION` still `0.0.3` while branch is `0.1.2` — bump only when cutting a release label.

---

## Commands (project root)

```bash
source env.sh   # FIRST — fix JAVA_HOME if this host’s JDK dir differs
./run.sh                          # desktop
SLOTIFY_DEMO=1 ./run.sh           # demo fixtures
./gradlew :composeApp:compileKotlinDesktop --console=plain
./gradlew :composeApp:desktopTest --console=plain
./gradlew :composeApp:compileAndroidMain --console=plain
./gradlew :androidApp:assembleDebug --console=plain
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :composeApp:populatePlaylist --args="https://open.spotify.com/playlist/…" --console=plain
```

- Long GUI/build runs: **detach** (`setsid nohup … > /tmp/log 2>&1 < /dev/null &`). Aborted blocking polls kill the run.
- Audio tests: `SLOTIFY_AUDIO_TESTS=1` (silent by default).
- Regenerate demo: `scripts/make-demo-fixtures.sh` → `~/slotify-demo-music/`.
- Parachord reference (MIT):  
  `git clone --depth 1 https://github.com/Parachord/parachord-mobile /tmp/parachord-mobile`  
  (`/tmp` wipes between sessions.)

---

## Machine-specific (may differ per host)

- Original authoring host: Manjaro x86_64, KDE Wayland; Floorp; ffmpeg n9.0.1; yt-dlp 2026.08.19; VLC 3.0.23.
- Previous host (CachyOS): yt-dlp 2026.08.19, ffmpeg n9.0.2, libvlc 3.0.23 + `vlc-plugins-base`. JDK 21 at `~/tools/jdk-21.0.13+9` (see `env.sh`); Android SDK at `~/tools/android-sdk` with `platforms;android-37.0` + `build-tools;35.0.0`.
- This host (last re-measure in-tree): JDK path in `env.sh` (`JAVA_HOME` — confirm it exists here); Android SDK `~/tools/android-sdk` with `platforms;android-37.0`. Branch **`0.1.2`** after `git pull`. APK ~66MB (yt-dlp/ffmpeg natives).
- Library: `/home/owner/Music` (~98 files post-whitelist on original host; 40 `.m4a` excluded by design). Android default root `/sdcard/Music` (single path — do not also add `/storage/emulated/0/Music` or files double-count before canonicalization).

Do **not** commit: `spotify-app.txt`, `~/.local/share/slotify/*` / Android `filesDir` JSON, cookies, Client IDs in notes if avoidable (Client ID in Settings is public-ish for PKCE but still machine state).

---

## Attribution

Matcher + Spotify client pieces ported from [Parachord](https://github.com/Parachord/parachord-mobile) (MIT), © Jason Herskowitz — see file headers.

---

## MiMoCode / `.mimocode` on a new machine

`.mimocode/` is **local agent tooling**, not app source. `node_modules/` is gitignored (~58MB). To rebuild:

```bash
cd slotify/.mimocode   # or mkdir -p .mimocode && cd .mimocode
npm install            # restores @mimo-ai/plugin from package.json / package-lock.json
```

- Declared dep: **`@mimo-ai/plugin`** (see `.mimocode/package.json`).
- `.cron-lock` and `node_modules/` are machine-local — ignore or delete; do not commit.
- After install, run MiMoCode from the project root as usual. Session/project memory lives under the MiMoCode data directory (`…/mimocode/memory/`), **not** in this repo — this file is the portable catch-up; live memory may be newer on the original machine.
- **Station switch:** project memory does **not** follow the repo. New session should start from `HANDOFF.md` (+ `README.md` for setup). Optionally copy `MEMORY.md` by hand from the other host if you want history.

If `npm install` fails offline: use the lockfile’s resolved tarball URL or reinstall the plugin package version above only.
