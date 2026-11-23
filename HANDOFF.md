# Slotify — development handoff

Read this (plus `README.md` setup) before continuing on a new machine.
Source of truth for long-lived notes: project memory `MEMORY.md` under MiMoCode’s data dir; this file is the **in-repo** distilled copy so a fresh clone is enough.

**Branch / release line:** `0.0.3` (`VERSION` file). Remote: `https://github.com/MichaelPNolan/slotify.git` (`origin/0.0.3`). Do not re-init git or re-push unless the remote/branch changes.

---

## Switching stations (start here)

On a **new host**, after `git clone` + `git checkout 0.0.3`:

1. **Read `README.md`** for packages (JDK 21, Android SDK `platforms;android-37.0`, yt-dlp, ffmpeg, VLC) and first build.
2. **Edit `env.sh`** — `JAVA_HOME` / `ANDROID_HOME` are paths from the *previous* machine (last known: `~/tools/jdk-21.0.13+9`, `~/tools/android-sdk`). Your unpack dir will differ.
3. **Rebuild agent tooling:** `cd .mimocode && npm install` (manifests are committed; `node_modules/` is not).
4. **Smoke-check:**
   ```bash
   source env.sh
   ./gradlew :composeApp:desktopTest --console=plain
   ./gradlew :androidApp:assembleDebug --console=plain
   ./run.sh   # optional GUI
   ```
5. **Runtime data does not clone** (by design). Recreate or copy when needed:
   | Data | Where |
   |------|--------|
   | Playlists, library index, pins, playback | Desktop `~/.local/share/slotify/` · Android app `filesDir` |
   | Portable transfer copy | Music root `playlists.json` + `manual_links.json` |
   | Spotify Client ID | Settings field (not in git; `spotify-app.txt` gitignored) |
   | Floorp `sp_dc` | This host’s browser profile only — re-probe via Settings |
   | SAF Music tree grant | Phone only (`SafMusic` prefs + system persisted permissions) |
   | MiMoCode session/project memory | `…/mimocode/memory/` on the **old** machine — not in git; `HANDOFF.md` is the portable catch-up |
6. **Do not** re-init git, force-push, or change the remote/branch unless the user asks.

**Not device-verified on a fresh install until you say so:** Android YouTube fetch, Android Link file (SAF tree grant), phone transfer, batch fetch. Treat “in HANDOFF” as coded, not proven on that handset.

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

Also first-class on 0.0.3: **public playlist search**, **batch YouTube sourcing**, **playlist trash**, **row skip/delete marks**, **playback resume + shuffle**, and **phone transfer** of playlists + pins via portable JSON under the Music root.

User confirmed earlier (2026-10-27): desktop live (scan→match→play), playlist URL import, Link file + YouTube pick, APK builds. Releases 0.0.2 / 0.0.3 (2026-11-22) added the features below; later same day: **Android yt-dlp (Chaquopy)**, **Spotify-based download naming/tags**, **SAF Music-tree Link file**. **Live click-through on device may still be pending** — treat “coded” ≠ “user-verified” unless they say otherwise.

---

## Architecture (why)

| Area | Decision |
|------|----------|
| Module | `:composeApp` KMP library (`com.android.kotlin.multiplatform.library` + `jvm("desktop")`) + thin `:androidApp` (`com.android.application`) APK shell + launcher icon. No AGP bypass flags in `gradle.properties`. |
| Toolchain | Gradle **wrapper 9.8.2**, Kotlin **2.4.21**, CMP **1.12.2**, AGP **9.4.1**, **compileSdk 37** (PREVIEW package `platforms;android-37.0`), targetSdk 35, minSdk 26, JDK **21**. Desktop runtime artifact: `desktop-jvm-linux-x64` (not `compose.desktop.currentOs`). |
| Desktop launch | `./run.sh` → `source env.sh` → `./gradlew :composeApp:run`. |
| Desktop window (tiling WM) | Host is **niri**. Full-window Scaffold (`fillMaxSize`); soft open **1100×740**. Known: AWT can miss XWayland resizes (JNA size poll in `Main.kt`). No niri window-rule workarounds. |
| Tags / scan | Shared walk: `distinctLibraryRoots` + `collectAudioFiles` (canonical dedupe, no double-count of nested roots). Desktop: jaudiotagger. **Android: jaudiotagger first** (parity with desktop titles/artists/ISRC), MediaMetadataRetriever fallback only. Single-file `readTrack` for Link/YouTube index. |
| Playback | Desktop: vlcj. Android: MediaPlayer. Per-row play/stop toggle; same-row = pause/resume. |
| Persistence | JSON under app data (`~/.local/share/slotify/` desktop; `filesDir` Android). Atomic tmp+rename. `updateSettings` transforms **on-disk** state. Audio lives under managed music dir, not app data. New: **`playback.json`** (resume index + position + shuffle + active playlist). |
| Managed music dir | Desktop `~/Music/Slotify`, Android `/sdcard/Music/Slotify` (`defaultSlotifyMusicDir()`). Final named audio at root; `downloads/` + `conversions/` staging only (manual purge). No hash store. Auto-added to `libraryRoots` if uncovered. |
| Spotify | Embed URL import primary; public search via DuckDuckGo HTML; PKCE secondary; `sp_dc` cookie for private embeds. |
| Matcher | ISRC → 0.99; both-axes containment → ≥0.95; blend title 0.55 / artist 0.30 / duration 0.15. Duration soft (vinyl); >120s gap caps 0.80. Wrong-artist gate caps at 0.70. **Also scores filename-parsed title/artist and takes max(tag, filename)** so Android/desktop tag disagreement does not flip matches. |
| Manual pins | `manual_links.json`: playlistId → trackKey → path. Rematch applies pins first (`isManual`, conf 1.0). `trackKey` = Spotify id → uri → `name\|artistNames`. Portable copy under Music root; paths **remapped** on load (`ManualLinks.remapMusicPaths`). |
| YouTube | **Desktop:** system `yt-dlp` + ffmpeg (`YoutubeDlpSource`) — flags `-f "251/140/bestaudio" -x --audio-format best --embed-metadata --embed-thumbnail` (unchanged). **Android:** bundled yt-dlp via Chaquopy (`dev.ffmpegkit-maintained:yt-dlp-android:2.0.2`) + `ffmpeg-kit-audio` remux (`YoutubeSource.android.kt`). Search/download use a helper Python module (`slotify_ytdlp.py` in app filesDir) because the stock `ytdlp_runner` omits `--dump-json`/`--flat-playlist`/full format control. Download format `251/140/bestaudio` (stream), then **ffmpeg `-c:a copy`** into whitelist ext (webm→.opus, m4a→.mp4) — never re-encode. **Naming/tags:** when the Spotify row is known, file is named `Artist - Title` from **Spotify** metadata (`preferredDownloadBaseName`) and tags are overwritten via `writeAudioMetadata` (yt-dlp/YouTube embed uses YouTube title/channel — that breaks fuzzy match). ABIs: arm64-v8a + x86_64 only. Fallback search phrasings when primary returns 0. Staging → promote to Slotify root. Index **only the new file** (never full rescan). Auto-pin download to requesting row. |
| Batch YouTube | Unmatched rows, best ranked score **≥ 0.85** auto-fetch; below threshold stay “To be sourced”. Status on `youtubeStatus` / `youtubeBatchRunning`. |
| Portable transfer | Export/import `playlists.json` + `manual_links.json` to/from **Music library roots** (not `Slotify/`). Cold start: app-private playlists win; else Music-root portable file is loaded once and copied into app data. Pins merged portable → app. |
| Android pickers | **SAF-first (2026-11-22):** Link file / JSON open with `EXTRA_INITIAL_URI` inside a **persisted Music tree** (`ACTION_OPEN_DOCUMENT_TREE` once → `takePersistableUriPermission` → `SafMusic` prefs). Document resolution: real path if `primary:`/MediaStore yields one, else **keep `content://`** (no forced copy into Slotify). `AndroidAudioPlayer` + `AndroidLibraryScanner.readTrack` accept content URIs; matcher uses `LocalTrack.nameForFilenameParse` (`displayPath` for SAF). Match still pins by `filePath` (path or content URI). |
| Symlink staging | **Desktop only** (`supportsSymlinkStaging()`). Stage matched playlist files as symlinks into Slotify for phone copy (`cp -L` / rsync `-L`). UI hidden on Android. |
| Playlist trash | Soft-delete: mark → trash → restore or empty. Empty-trash can delete audio **only** if no remaining playlist references the path (`trashOnlyAudioPaths`). |
| Row marks | `RowMark.SKIP` / `DELETE` cycle on detail rows; survive rematch; SKIP excluded from queue; DELETE removed from playlist membership on “delete marked” (files never auto-deleted there). |

### Spotify import (important)

- **Paste URL** → `parseSpotifyPlaylistId` → `SpotifyEmbedSource` fetches  
  `https://open.spotify.com/embed/playlist/{id}` → parse `__NEXT_DATA__` → `entity.trackList`.  
  Public: no login. Private: attach Floorp `sp_dc` via `readBrowserSpDcCookie()`.
- **Public search:** Playlists tab free-text → `SpotifyPublicSearch` hits DuckDuckGo HTML (`site:open.spotify.com/playlist …`) → Import uses the same embed path. Not the Web API (free/dev tokens 403/429; embed tokens burn quota).
- **`get_access_token` is CDN-blocked** (Fastly “403 URL Blocked”). Embed `accessToken` is **not** accepted by `api.spotify.com` for free accounts (“user is not registered for this application”).
- OAuth Web API + free account + Development-mode app → **403**  
  `"Active premium subscription required for the owner of the app…"` — do not retry as if it were a token bug.
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

## Feature map (0.0.2 + 0.0.3 + post-0.0.3 fix)

| Area | Behavior |
|------|----------|
| Playlists tab | URL paste + clipboard button; public search; Refresh (OAuth merge); mark → trash; trash view + empty (optional unique-audio delete); play/stop cards; search filter on detail. |
| Playlist detail | Single LazyColumn: To be sourced + Matched (full phone scroll). Filter field. Row marks (Skip/Del). Link file (Android: grant Music tree once, then picker opens **in** Music) / Find on YouTube / Unlink. **Hold 5s or swipe left** → prepare redo (unlink + delete managed wrong file if unreferenced) → re-fetch dialog. Batch YouTube button (≥85%). |
| Library tab | Search title/artist; play from filtered index. |
| Settings | Spotify (PKCE / browser cookie probe / manual sp_dc), URL import, roots + scan, Music folder export/import + desktop symlink stage, JSON pick/path import, About. Persistent **Last error**. |
| Playback | Resume from `playback.json` (paused at saved position). Shuffle without repeats until exhausted, then reshuffle cycle. Marks SKIP/DELETE excluded from queue. Symlink targets resolved before play. |
| Headless CLI | `./gradlew :composeApp:populatePlaylist --args="<spotify-url>"` → embed import + scan + match into app data (`PopulateCli`). |

---

## Open work

- **T2 content-fingerprint reconnect**: size + head/tail hash per match; scan re-binds by fingerprint first; play-failure self-heal. (Designed, not built.)
- Android: **token storage still in-memory** (`JsonFileTokenStorage.android` → `InMemoryTokenStorage`, TODO T1.6 SharedPreferences); PKCE loopback **not** ported (`pkceAwait` throws — cookie / URL paste only); launcher icon now real (from `graphics/slotify.png`).
- Android YouTube **coded, not device-tested** here — smoke-test search + one opus download into `/sdcard/Music/Slotify/downloads/`. Debug APK ~**66MB** (Python + ffmpeg); release should use ABI splits/AAB. Free `yt-dlp-android` has no curl-cffi (YouTube usually OK).
- Android **SAF Link file** (Music tree grant + `content://` playback) **coded, not device-tested** — confirm picker shows Music and a linked track plays after app restart (persisted URI permission).
- **Already-downloaded** YouTube files (pre-Spotify-naming fix) may still have YouTube titles/tags — re-fetch or re-link those rows to retag.
- PKCE refresh path untested live (`spotify_auth.json` lacks `clientType`).
- Listen-to-confirm UX for fuzzy matches; multi-format play preference (flac→opus→…).
- User live-testing of Link file, YouTube multi-match, batch fetch, phone transfer — confirm before marking done.

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
- **Next host:** re-measure tool versions; update this section and `env.sh` after first successful build.
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
- **Station switch:** project memory will **not** follow you. After clone, tell the new session to read `HANDOFF.md` + `README.md` first. Optionally copy `MEMORY.md` by hand from the old host if you want history.

If `npm install` fails offline: use the lockfile’s resolved tarball URL or reinstall the plugin package version above only.
