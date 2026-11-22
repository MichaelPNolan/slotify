# Slotify — development handoff

Read this (plus `README.md` setup) before continuing on a new machine.
Source of truth for long-lived notes: project memory `MEMORY.md` under MiMoCode’s data dir; this file is the **in-repo** distilled copy so a fresh clone is enough.

---

## Hard rules (never violate)

1. **No sudo by default** — prefer user-space tooling (`~/tools/`, pip `--user`). **Exception (user 2026-11-22):** the user will install system packages themselves, or grant sudo manually, for anything normal for the system to have (e.g. full `vlc`, build deps). Ask first; never assume root.
2. **Ask permission before any screen capture** (Spectacle, ffmpeg, …).
3. **Never propose Spotify Premium workarounds.** User owns their music; will not subscribe. App = right to store/play their own files.
4. **Spotify = metadata only.** Never fetch Spotify audio.
5. **Audio extension whitelist** (pinned in tests): `aac, mp3, mp4, flac, wav, aif, aiff, opus`. Skip hidden files **and** hidden dirs (AppleDouble `._*`).
6. **YouTube downloads: opus stream copy, never re-encode.**
7. **Volume control always visible** on every tab.
8. Keep instructions copy-pasteable; surface errors where the user sees them (Settings → Last error, terminal stack traces via adapters).

---

## What Slotify is

Compose Multiplatform music player: **desktop-first** (AMD64 Manjaro), Android shares code.

Workflow: Spotify playlist **metadata** → fuzzy-match local library → unmatched tracks: **Link file** or **YouTube download** → play with vlcj (desktop) / MediaPlayer (Android).

User confirmed (2026-10-27): desktop live (scan→match→play), playlist URL import (2 lists), Link file + YouTube pick, APK builds.

---

## Architecture (why)

| Area | Decision |
|------|----------|
| Module | `:composeApp` KMP library (`com.android.kotlin.multiplatform.library` + `jvm("desktop")`) + thin `:androidApp` (`com.android.application`) APK shell. No AGP bypass flags in `gradle.properties`. |
| Toolchain | Gradle **wrapper 9.8.2**, Kotlin **2.4.21**, CMP **1.12.2**, AGP **9.4.1**, **compileSdk 37** (PREVIEW package `platforms;android-37.0`), targetSdk 35, minSdk 26, JDK **21**. |
| Desktop launch | `./run.sh` → `source env.sh` → `./gradlew :composeApp:run`. |
| Desktop window (tiling WM) | Host is **niri**. Full-window Scaffold (`fillMaxSize`); soft open **1100×740**. Known: AWT can miss XWayland resizes (JNA size poll in `Main.kt`). No niri window-rule workarounds. |
| Tags / scan | Desktop: jaudiotagger. Android: MediaMetadataRetriever. |
| Playback | Desktop: vlcj (`compose.desktop.currentOs` — not `.common`). Android: MediaPlayer. |
| Spotify | Embed URL import primary; PKCE secondary; `sp_dc` cookie for private embeds. |
| Matcher | ISRC → 0.99; both-axes containment → ≥0.95; blend title 0.55 / artist 0.30 / duration 0.15. Duration soft (vinyl). Wrong-artist gate caps at 0.70. |
| Manual pins | `manual_links.json`: playlistId → trackKey → path. Rematch applies pins first (`isManual`, conf 1.0). `trackKey` = Spotify id → uri → `name|artistNames`. |
| YouTube | `yt-dlp` desktop only. Flags: `-f "251/140/bestaudio" -x --audio-format best --embed-metadata --embed-thumbnail`. Staging: `Slotify/downloads/` → promoted to `Slotify/` root under final name. |
| Managed music dir | Subdirectory of the OS music folder: desktop `~/Music/Slotify`, Android `/storage/emulated/0/Music/Slotify` (`defaultSlotifyMusicDir()`). **Layout:** final named audio at root; `downloads/` + `conversions/` are staging only (temp until promoted). User may **manually purge** staging to reclaim space. No hash store. Auto-added to `libraryRoots` if not already covered by a root. |
| Persistence | JSON under `~/.local/share/slotify/`. Atomic tmp+rename writes. `updateSettings` transforms **on-disk** state. Audio files live under the managed music dir (not in app data). |

### Spotify import (important)

- **Paste URL** → `parseSpotifyPlaylistId` → `SpotifyEmbedSource` fetches  
  `https://open.spotify.com/embed/playlist/{id}` → parse `__NEXT_DATA__` → `entity.trackList`.  
  Public: no login. Private: attach Floorp `sp_dc` via `readBrowserSpDcCookie()`.
- **`get_access_token` is CDN-blocked** (Fastly “403 URL Blocked”). Embed `accessToken` is **not** accepted by `api.spotify.com` for free accounts (“user is not registered for this application”).
- OAuth Web API + free account + Development-mode app → **403**  
  `"Active premium subscription required for the owner of the app…"` — do not retry as if it were a token bug.
- Embed list caps ~**100** tracks; `?offset=` does not paginate.
- Refresh **merges** playlists (`replaceExisting=false`) so URL-imported lists survive.
- Redirect URI (exact): `http://127.0.0.1:43821/callback`  
  Scopes: `playlist-read-private playlist-read-collaborative`
- Floorp opens `Desktop.browse()` as an **unfocused background tab** — always print authorize URL to the terminal.

- **Naming convention (agreed direction, not fully built):** on-disk names `Artist - Title [- Version].<ext>` (sanitize path-illegal chars). Multi-format OK (`flac` master + `mp3`/`aac`/`opus` variants); index one logical track, many file candidates. No re-encode by default — YouTube stays opus stream copy; CD rips stay flac. Prefer flac → opus → mp3 → aac at play/match time when several exist.
- **Staging vs final (user, 2026-11-22):** under `Slotify/`, `downloads/` and `conversions/` hold temp/in-progress audio only. Finished working files are named and stored at the Slotify root. Purge of staging is **manual** when the user wants space back — no auto-delete.
- **Multi-root libraries (user, 2026-11-22):** Muse.Collection is only one source dir — roots can be mixed-artist collections. Matcher already scores across **all** `libraryRoots`. Open work: **listen-to-confirm** UX so the user plays a candidate and accepts/rejects fuzzy matches (confidence chip is not enough alone). Keep unused functions until replacements land.

### Floorp `sp_dc` (desktop)

- Profiles: `~/.config/floorp/` (active Path in `profiles.ini`, e.g. `zqjk64do.default-default`).
- `cookies.sqlite` **locked while browser runs** — copy `+ -wal`/`-shm` to temp first.
- Plaintext `moz_cookies.value`; `sp_dc` host `.spotify.com` (~210 chars). Skip NSS `v10`/`v11`/`v20`. Never log full cookie.
- Reader: `readBrowserSpDcCookie()` + `org.xerial:sqlite-jdbc` (desktopMain).

---

## Open work

- **T2 content-fingerprint reconnect**: size + head/tail hash per match; scan re-binds by fingerprint first; play-failure self-heal. (Designed, not built.)
- Android: token storage is in-memory; PKCE loopback not ported (cookie is the Android story); YouTube fetch desktop-only; launcher icon placeholder.
- PKCE refresh path untested live (`spotify_auth.json` lacks `clientType`).

---

## Commands (project root)

```bash
source env.sh
./run.sh                          # desktop
SLOTIFY_DEMO=1 ./run.sh           # demo fixtures
./gradlew :composeApp:compileKotlinDesktop --console=plain
./gradlew :composeApp:desktopTest --console=plain
./gradlew :androidApp:assembleDebug --console=plain
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
- Current host (CachyOS): yt-dlp 2026.08.19, ffmpeg n9.0.2, libvlc 3.0.23 + `vlc-plugins-base` (no `vlc` CLI — fine for vlcj). JDK 21 Temurin at `~/tools/jdk-21.0.13.1+2`; Android SDK at `~/tools/android-sdk` with `platforms;android-37.0` + `build-tools;35.0.0`. Paths live in `env.sh`.
- Library: `/home/owner/Music` (~98 files post-whitelist on original host; 40 `.m4a` excluded by design).

Do **not** commit: `spotify-app.txt`, `~/.local/share/slotify/*`, cookies, Client IDs in notes if avoidable (Client ID in Settings is public-ish for PKCE but still machine state).

---

## Attribution

Matcher + Spotify client pieces ported from [Parachord](https://github.com/Parachord/parachord-mobile) (MIT), © Jason Herskowitz — see file headers.

---

## MiMoCode / `.mimocode` on a new machine

`.mimocode/` is **local agent tooling**, not app source. `node_modules/` is gitignored (58MB). To rebuild:

```bash
cd slotify/.mimocode   # or mkdir -p .mimocode && cd .mimocode
npm install            # restores @mimo-ai/plugin@0.1.15 from package.json / package-lock.json
```

- Declared dep: **`@mimo-ai/plugin` `0.1.15`** (see `.mimocode/package.json`).
- `.cron-lock` and `node_modules/` are machine-local — ignore or delete; do not commit.
- After install, run MiMoCode from the project root as usual. Session/project memory lives under the MiMoCode data directory (`…/mimocode/memory/`), **not** in this repo — copy `HANDOFF.md` (this file) is the portable catch-up; live memory may be newer on the original machine.

If `npm install` fails offline: use the lockfile’s resolved tarball URL or reinstall the plugin package version above only.
