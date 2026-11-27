---
feature: loudness-balance
status: delivered
specs:
  - specs/2026-11-27-loudness-balance-design.md
plans:
  - plans/2026-11-27-loudness-balance.md
branch: 0.1.5
commits: 8b77afa..e033ba7
---

# Loudness Balance (EBU R128 + DynamicsProcessing) — Final Report

## What Was Built

Android-only loudness analysis and playback balance. New downloads are measured with a **pure-Kotlin EBU R128** analyser (integrated LUFS, loudness range, true peak) after ffmpeg-kit decodes the file to PCM. A playlist **Analyse loudness** action backfills missing tracks and stores a **playlist mean LUFS** profile in app-private `loudness.json`.

When **Loudness balance** is on (Settings, API 28+ only), each play applies **boost-only** corrective gain toward that playlist’s mean (capped by Max boost, default +12 dB) plus a limiter for loud tracks — through Android `DynamicsProcessing` on the MediaPlayer session, never a negative volume cut. Advanced mode can show a **0–99 loudness chip** per row (0 = silence, 99 = very loud) and a dialog to inspect LUFS/LRA/true-peak and set **Recommended | Bypass | Custom** correction.

## Architecture

- **`composeApp/.../loudness/EbuR128.kt`** — pure Kotlin BS.1770/EBU R128: K-weight biquads on input, 400 ms blocks / 100 ms hop, absolute −70 LUFS + relative −10 LU gating, LRA, raw peak as dBTP stand-in (sample peak). Helpers `loudnessChip()`, `recommendedBoostDb()`.
- **`LoudnessStore.kt`** — app-private `loudness.json` via `AppFiles` (atomic write): per-path `TrackLoudness` (metrics + chip + correction mode) and `PlaylistLoudnessProfile` (mean/count). Never mirrored to portable music-dir JSON.
- **`LoudnessAnalyser.kt`** — `expect fun analyseFileLoudness(path)` / `supportsLoudnessBalance()`. Android: ffmpeg-kit → temp `f32le` stereo → `EbuR128`. Desktop: `null` / `false`.
- **`AppState`** — `analyseLoudnessAsync` after download index; `startLoudnessBackfill` / `cancelLoudnessBackfill` with `loudnessStatus` StateFlow; `playlistMeanLufs`; `applyLoudnessToPlayer` from `playAt`.
- **`AndroidAudioPlayer.setLoudnessCorrection(boostDb, enableLimiter)`** — API 28+: `DynamicsProcessing` input gain = boost, optional limiter (threshold −1.5 dB) on the session after prepare; cleared on load/stop/release. Interface default no-op (desktop).
- **UI** — Settings section (hidden unless `supportsLoudnessBalance()`); playlist **Analyse loudness** button + status/progress; advanced chips + `LoudnessDetailDialog`.

### Design Decisions

- **Pure Kotlin R128 over libebur128 JNI** — no cinterop/`.so`; decode still uses bundled ffmpeg-kit (already a dependency).
- **Android-only end to end** — desktop analysers are no-ops so the feature does not half-ship on vlcj.
- **Boost-only Recommended path** — quiet tracks get makeup toward the mean; loudness above the mean is handled by the limiter, not a volume cut.
- **API floor 28** — `DynamicsProcessing` does not exist below that; the toggle and playlist action are hidden there rather than emulated.

## Usage

1. Settings → **Loudness balance** (Android API 28+): Enable balance, Max boost (0–24 dB), Show loudness chips.
2. Playlist detail → **Analyse loudness** (Stop analysis cancels). Status shows n/N and failure count; mean profile updates when the pass ends.
3. Play a track — Recommended applies `clamp(mean − trackLufs, 0, maxBoost)` via DynamicsProcessing + limiter.
4. With chips on, tap a chip → full metrics + Recommended/Bypass/Custom (custom −12…+24 dB).

## Verification

- `./gradlew :composeApp:desktopTest` — **BUILD SUCCESSFUL** (includes `EbuR128Test`, `LoudnessStoreTest` + full suite).
- `./gradlew :composeApp:compileAndroidMain` / `:composeApp:compileKotlinDesktop` — clean (only pre-existing expect/actual Beta warnings).
- Debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` — **2026-11-27 20:56:01 +0700**, 69610477 bytes. **User installs** (rule 12); DynamicsProcessing listen-check is manual on device.

## Journey Log

- [pivot] User first sketched libebur128 JNI, then chose pure Kotlin EBU R128 after decode/build trade-offs.
- [lesson] `DynamicsProcessing.Config.Builder` on API 37 is 9-arg `(variant, ch, preEqEn, preEqN, mbcEn, mbcN, postEqEn, postEqN, limiterEn)` — verify with `javap` on `android.jar`, not memory.
- [lesson] Plan sine fixture range −25…−10 LUFS was wrong for stereo peak-0.5 (~−7 LUFS); corrected to −15…0 before implementation.

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `specs/2026-11-27-loudness-balance-design.md` | Design | Marked superseded by this report |
| `plans/2026-11-27-loudness-balance.md` | Implementation plan | 8 tasks, all executed |
