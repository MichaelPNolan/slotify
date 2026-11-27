# Slotify

Playlists in, your own files out. Import Spotify playlist **metadata** (never audio), match tracks to your local library, fetch missing ones from YouTube as quality-preserving opus copies, and play them on desktop (and Android).

**Desktop is the primary development target.** Android shares the same Compose Multiplatform code (thin `:androidApp` APK shell).

Current release line: **`0.1.4`** (see `VERSION`).

---

## Fresh machine setup (from GitHub)

Clone:

```bash
git clone https://github.com/MichaelPNolan/slotify.git
cd slotify
git checkout 0.1.4    # or main / the branch you want
```

**Before you code:** read [`HANDOFF.md`](HANDOFF.md) — hard rules, architecture, Spotify/embed constraints, feature map, open work, and how to rebuild `.mimocode/`.

### 1. Required packages (user-space — no sudo)

| Tool | Why | Install hint |
|------|-----|----------------|
| **JDK 21** | Gradle / Kotlin builds | Temurin or OpenJDK 21 unpacked under `~/tools/` |
| **Android SDK** | APK builds (`assembleDebug`) | cmdline-tools + `platforms;android-37` (PREVIEW) + `build-tools` |
| **yt-dlp** | YouTube search + download (desktop only) | distro package or [yt-dlp releases](https://github.com/yt-dlp/yt-dlp) |
| **ffmpeg** | yt-dlp audio extract / embed metadata | distro package |
| **VLC (libvlc)** | Desktop audio playback (vlcj) | System VLC — desktop player discovers native libs |
| **Python 3 + pip** | Optional: mutagen for YouTube thumbnails | `pip install --user --break-system-packages mutagen` |

Optional but useful on this machine’s layout:

```bash
mkdir -p ~/tools
# Unpack JDK 21 under ~/tools/ (dir name may vary, e.g. jdk-21.0.13+9)
# Place Android SDK at ~/tools/android-sdk (or edit env.sh ANDROID_HOME)
```

`env.sh` (sourced by `./run.sh` and tests) — **edit paths to match your unpack dirs**:

```bash
export JAVA_HOME="$HOME/tools/jdk-21.0.13+9"   # your JDK dir (edit if yours differs)
export ANDROID_HOME="$HOME/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

The Gradle **wrapper** downloads Gradle 9.8.2 itself — you do not need a system Gradle.

### 2. Android SDK packages

With `sdkmanager` on `PATH` (from Android cmdline-tools):

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;35.0.0"
```

**Important:** use the **PREVIEW** package id `platforms;android-37.0` (`sdkmanager --channel=1`). Plain `platforms;android-37` fails with “Failed to find package”. `compileSdk = 37` is required by androidx.compose 1.12.x.

### 3. Rebuild `.mimocode/` (MiMoCode agent tooling)

Not required to *run* Slotify; required to continue development with MiMoCode in this repo.

```bash
# From repo root — package.json + package-lock.json are committed; node_modules is not
mkdir -p .mimocode
cd .mimocode
# If package.json is missing (old clone), recreate from git:
#   git show HEAD:.mimocode/package.json
npm install
cd ..
```

| Path | In git? | Notes |
|------|---------|--------|
| `.mimocode/package.json` | yes | pins `@mimo-ai/plugin` |
| `.mimocode/package-lock.json` | yes | exact transitive versions |
| `.mimocode/node_modules/` | **no** | ~58MB — `npm install` regenerates |
| `.mimocode/.cron-lock` | **no** | local scheduler pid — ignore |

Then start MiMoCode from the project root. Long-lived project knowledge for the agent is **`HANDOFF.md` in this repo** (session memory on the original machine may be newer but does not clone).

### 4. First build & run

```bash
source env.sh

# Desktop app
./run.sh

# Or via Gradle directly
./gradlew :composeApp:run --console=plain

# Tests (desktop; ~2 min first time)
./gradlew :composeApp:desktopTest --console=plain

# Android APK (thin :androidApp shell over the :composeApp KMP library)
./gradlew :androidApp:assembleDebug --console=plain
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Optional: headless import + match a public playlist into app data
./gradlew :composeApp:populatePlaylist \
  --args="https://open.spotify.com/playlist/…" --console=plain
```

Long GUI/build runs: prefer detached shells so an aborted poll does not kill the process.

### 5. Runtime data (not in git)

Created on first launch:

| File | Where | Purpose |
|------|--------|---------|
| `settings.json` | app data | Library roots, Spotify client ID, volume, match threshold |
| `library.json` | app data | Indexed local tracks |
| `playlists.json` | app data | Imported playlists + match state + trash/mark flags |
| `manual_links.json` | app data | User-pinned track → file paths |
| `playback.json` | app data | Resume: active playlist, shuffle, per-playlist index + position |
| `spotify_auth.json` | app data | PKCE / cookie tokens (desktop; if you sign in) |

App data: desktop `~/.local/share/slotify/`; Android app `filesDir` (set from `MainActivity`).

**Never commit these.** They live outside the repo.

Portable mirrors (phone transfer) also use the **Music root** (e.g. `~/Music/` or `/sdcard/Music/`): `playlists.json` + `manual_links.json`. Export from Settings; import on the other device.

Audio **files** Slotify downloads are not stored in app data — they go under `<OS music>/Slotify/` (desktop `~/Music/Slotify/`, Android `/sdcard/Music/Slotify/`). Staging subdirs `downloads/` and `conversions/` are temporary until final names are written at the Slotify root.

### 6. Spotify (optional, metadata only)

Slotify **never downloads Spotify audio**.

**Playlist URL paste (primary):** Playlists tab → paste `https://open.spotify.com/playlist/…`  
Public playlists work with no login (embed page). Private playlists use your Floorp/Firefox `sp_dc` cookie automatically when present.

**Public search:** same tab → “Search public playlists” (artist, genre, …). Slotify queries DuckDuckGo HTML for public Spotify playlist URLs, then imports via the embed path — not the Spotify Web API.

**Manual cookie:** Settings → paste `sp_dc` (DevTools → Application → Cookies → open.spotify.com), or “Check browser session cookie” to probe Floorp/Firefox `cookies.sqlite`.  
Note: Spotify’s `get_access_token` endpoint is often CDN-blocked; URL import uses the embed page instead of that exchange.

**OAuth PKCE (secondary):** Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) with redirect URI exactly:

```text
http://127.0.0.1:43821/callback
```

Paste the Client ID into Settings. Free-account + Development-mode apps are limited by Spotify’s Web API premium policy — prefer URL paste / cookie / public search.

Do **not** put Client IDs or cookies in git. `spotify-app.txt` is gitignored.

### 7. Unmatched rows → local files

On a playlist detail screen (“To be sourced”):

- **Link file** — pick an existing audio file (desktop file dialog; Android system audio picker → real path or copy into Slotify). Pins survive rematch.
- **Find on YouTube** — keyless search (`ytsearchN:` via yt-dlp), ranked pick; download is **opus stream copy** (never re-encode), promoted to `<music>/Slotify/`, indexed, and pinned to the row.
- **Batch fetch** — walk unmatched rows; auto-download when the best ranked result scores **≥ 85%**.
- **Hold 5s or swipe a row left** — re-fetch: unlink (and delete the managed wrong file if nothing else needs it), then open the YouTube dialog again.

Files land under the managed dir:

- `downloads/` and `conversions/` — **staging only** (purge yourself when you want space back)
- final named audio (`Artist - Title.ext`) at the Slotify root
- managed root is ensured to be inside a scanned library root
- **Android** uses bundled yt-dlp (`yt-dlp-android` / Chaquopy) + ffmpeg-kit stream-copy remux — same `251/140/bestaudio` policy as desktop (never re-encode). Needs **arm64-v8a or x86_64**. Debug APK ~66MB (Python + ffmpeg in package).

### 8. Phone transfer (desktop → Android)

1. Desktop Settings → **Export playlists** (writes `playlists.json` + `manual_links.json` to the Music root).
2. Optional: **Stage playlist files as links into Slotify** (desktop only), then copy with **follow symlinks** (`cp -L`, `rsync -L`, or “follow symbolic links”) so the phone gets real files.
3. Put the JSON files in the phone’s Music root (`/sdcard/Music/`), not under `Slotify/`.
4. On Android: Settings → **Import playlists**, or **Pick file…** if scoped storage blocks a raw path. Pins are remapped to this device’s Music paths, then playlists rematch against the local scan.

### 9. Demo fixtures (optional)

```bash
scripts/make-demo-fixtures.sh   # → ~/slotify-demo-music/
SLOTIFY_DEMO=1 ./run.sh
```

---

## What you get in the UI

| Tab | Highlights |
|-----|------------|
| **Playlists** | URL paste + clipboard, public search, Refresh (merge), play/stop cards, shuffle, mark → trash, trash restore / empty (optional delete of audio used only by trashed playlists) |
| **Playlist detail** | Filter; single scroll list (To be sourced + Matched); row Skip/Del marks; Link file; YouTube find / batch; hold/swipe re-fetch |
| **Library** | Search + play local files |
| **Settings** | Spotify connect paths, URL import, library roots + scan, export/import portable JSON, desktop symlink stage, JSON pick/path import, persistent Last error |

Volume control stays visible on every tab. Playback resumes from `playback.json` (position + track + shuffle).

---

## Project layout

```text
composeApp/
  src/commonMain/   # shared UI, state, matcher, Spotify, YouTube, portable JSON
  src/commonTest/   # unit tests
  src/desktopMain/  # vlcj, system yt-dlp, file picker, Floorp cookie, PopulateCli
  src/androidMain/  # MediaPlayer, scanner, SAF pickers, Chaquopy yt-dlp, MainActivity
androidApp/         # thin com.android.application APK shell + launcher icon
.mimocode/          # agent tooling manifests (npm install → node_modules)
gradle/libs.versions.toml
env.sh              # local toolchain (edit per machine)
run.sh              # desktop launcher
HANDOFF.md          # development handoff / rules / open work
README.md           # fresh-machine setup
VERSION             # 0.0.3
```

### Key versions

| Component | Version |
|-----------|---------|
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Gradle wrapper | 9.8.2 |
| Android Gradle Plugin | 9.4.1 |
| compileSdk | 37 (preview package) |
| JDK | 21 |

Modules: **`:composeApp`** is the KMP library (`com.android.kotlin.multiplatform.library` + `jvm("desktop")`). **`:androidApp`** is a thin `com.android.application` shell that produces the APK. No AGP bypass flags in `gradle.properties`.

---

## Attribution

Fuzzy matching and Spotify client pieces are ported from [Parachord](https://github.com/Parachord/parachord-mobile) (MIT), © Jason Herskowitz — see source file headers.

---

## License / data policy

- Spotify: **metadata only** — never fetch Spotify audio streams.
- Your library stays on your machine (and any phone you copy it to).
- Audio extension whitelist: `aac, mp3, mp4, flac, wav, aif, aiff, opus` (hidden files skipped on desktop scan).
