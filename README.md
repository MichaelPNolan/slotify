# Slotify

Playlists in, your own files out. Import Spotify playlist **metadata** (never audio), match tracks to your local library, fetch missing ones from YouTube as quality-preserving opus copies, and play them on desktop (and Android).

**Desktop is the primary development target.** Android shares the same Compose Multiplatform code.

---

## Fresh machine setup (from GitHub)

Clone:

```bash
git clone https://github.com/MichaelPNolan/slotify.git
cd slotify
```

### 1. Required packages (user-space — no sudo)

| Tool | Why | Install hint |
|------|-----|----------------|
| **JDK 21** | Gradle / Kotlin builds | Temurin or OpenJDK 21 unpacked under `~/tools/` |
| **Android SDK** | APK builds (`assembleDebug`) | cmdline-tools + `platforms;android-37` (PREVIEW) + `build-tools` |
| **yt-dlp** | YouTube search + download | distro package or [yt-dlp releases](https://github.com/yt-dlp/yt-dlp) |
| **ffmpeg** | yt-dlp audio extract / embed metadata | distro package |
| **VLC (libvlc)** | Desktop audio playback (vlcj) | System VLC — desktop player discovers native libs |
| **Python 3 + pip** | Optional: mutagen for YouTube thumbnails | `pip install --user --break-system-packages mutagen` |

Optional but useful on this machine’s layout:

```bash
mkdir -p ~/tools
# Place JDK at ~/tools/jdk-21.0.13+9  (or edit env.sh JAVA_HOME)
# Place Android SDK at ~/tools/android-sdk (or edit env.sh ANDROID_HOME)
```

`env.sh` (sourced by `./run.sh` and tests):

```bash
export JAVA_HOME="$HOME/tools/jdk-21.0.13+9"
export ANDROID_HOME="$HOME/tools/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
```

Adjust paths if your JDK/SDK live elsewhere. The Gradle **wrapper** downloads Gradle 9.8.2 itself — you do not need a system Gradle.

### 2. Android SDK packages

With `sdkmanager` on `PATH` (from Android cmdline-tools):

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;35.0.0"
```

**Important:** use the **PREVIEW** package id `platforms;android-37.0` (`sdkmanager --channel=1`). Plain `platforms;android-37` fails with “Failed to find package”. `compileSdk = 37` is required by androidx.compose 1.12.x.

### 3. First build & run

```bash
source env.sh

# Desktop app
./run.sh

# Or via Gradle directly
./gradlew :composeApp:run --console=plain

# Tests (desktop; ~2 min first time)
./gradlew :composeApp:desktopTest --console=plain

# Android APK
./gradlew :composeApp:assembleDebug --console=plain
# → composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Long GUI/build runs: prefer detached shells so an aborted poll does not kill the process.

### 4. Runtime data (not in git)

Created on first launch under `~/.local/share/slotify/`:

| File | Purpose |
|------|---------|
| `settings.json` | Library roots, Spotify client ID, volume, match threshold |
| `library.json` | Indexed local tracks |
| `playlists.json` | Imported playlists + match state |
| `manual_links.json` | User-pinned track → file paths |
| `spotify_auth.json` | PKCE / cookie tokens (if you sign in) |

**Never commit these.** They are outside the repo.

### 5. Spotify (optional, metadata only)

Slotify **never downloads Spotify audio**.

**Playlist URL paste (primary):** Playlists tab → paste `https://open.spotify.com/playlist/…`  
Public playlists work with no login (embed page). Private playlists use your Floorp/Firefox `sp_dc` cookie automatically when present.

**Manual cookie:** Settings → paste `sp_dc` (DevTools → Application → Cookies → open.spotify.com).  
Note: Spotify’s `get_access_token` endpoint is often CDN-blocked; URL import uses the embed page instead of that exchange.

**OAuth PKCE (secondary):** Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) with redirect URI exactly:

```text
http://127.0.0.1:43821/callback
```

Paste the Client ID into Settings. Free-account + Development-mode apps are limited by Spotify’s Web API premium policy — prefer URL paste / cookie.

Do **not** put Client IDs or cookies in git. `spotify-app.txt` is gitignored.

### 6. YouTube fetch (desktop)

- Search is keyless (`ytsearchN:` via yt-dlp).
- Downloads are **opus stream copy** (never re-encode).
- Files land in `<firstLibraryRoot>/YouTube Downloads/` (must be a scanned root).
- Android YouTube fetch is not supported yet (no yt-dlp runtime).

### 7. Demo fixtures (optional)

```bash
scripts/make-demo-fixtures.sh   # → ~/slotify-demo-music/
SLOTIFY_DEMO=1 ./run.sh
```

---

## Project layout

```text
composeApp/
  src/commonMain/   # shared UI, state, matcher, Spotify, YouTube
  src/commonTest/   # unit tests
  src/desktopMain/  # vlcj playback, yt-dlp, file picker, Floorp cookie reader
  src/androidMain/  # MediaPlayer playback, MediaMetadataRetriever scanner
gradle/libs.versions.toml
env.sh              # local toolchain (edit per machine)
run.sh              # desktop launcher
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

AGP 9 + KMP in one module needs these `gradle.properties` flags (do not remove until the project is split):

```properties
android.builtInKotlin=false
android.newDsl=false
```

---

## Attribution

Fuzzy matching and Spotify client pieces are ported from [Parachord](https://github.com/Parachord/parachord-mobile) (MIT), © Jason Herskowitz — see source file headers.

---

## License / data policy

- Spotify: **metadata only** — never fetch Spotify audio streams.
- Your library stays on your machine.
- Audio extension whitelist: `aac, mp3, mp4, flac, wav, aif, aiff, opus` (hidden files skipped).
