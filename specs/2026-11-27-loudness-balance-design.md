# Loudness Balance (EBU R128 + DynamicsProcessing) — Design

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/loudness-balance.md)

Date: 2026-11-27 · Branch: `0.1.5` · Status: approved

## [S1] Problem

Downloaded tracks vary in perceived loudness. Within one playlist, quiet songs sit far below the loud ones, so playback needs constant volume fiddling. Users want an average loudness profile for a playlist so quiet tracks can be boosted (and loud ones controlled) automatically. Advanced users want to inspect the numbers, tweak or bypass the correction, and spot very quiet files that might deserve a better re-download.

## [S2] Solution overview

Android-only end-to-end. Measure each new download (and on-demand playlist backfill) with a **pure-Kotlin EBU R128** analyser fed by ffmpeg-kit decode. Persist full results in an **app-private** store. Balance toward the **playlist mean LUFS**: **boost-only** corrective gain for quiet tracks, **compression/limiting** (no negative cut) for loud tracks, applied through **Android `DynamicsProcessing`** on API 28+. Global Settings toggle + playlist “Analyse loudness” action. Per-track correction mode **Recommended | Bypass | Custom**. Advanced UI exposes a **0–99 loudness chip** (0 = silence, 99 = very loud) plus full LUFS / LRA / true-peak for review.

## [S3] Measurement pipeline

- `expect object LoudnessAnalyser` / `expect fun analyseFile(path: String): LoudnessResult?` in `commonMain` (alongside other library/platform seams).
- **Android actual:** decode file → float PCM via bundled **ffmpeg-kit-audio** (native sample rate, stereo or mono as present; downmix only if >2 channels) → pure-Kotlin EBU R128:
  - K-weighting (high-shelf + high-pass), 400 ms blocks, 75% overlap, absolute gate −70 LUFS, relative gate −10 LU relative to gated mean → **integrated LUFS**.
  - **Loudness range** from short-term (3 s) moments with the standard LRA gates.
  - **True peak** via 4× polyphase-style oversample of sample peaks → **dBTP**.
- Decode or analysis failure → `null` (skip; counted in UI). Never throw into play/download paths.
- Runs on `Dispatchers.Default`, one file at a time (sequential queue shared by download hook and backfill).
- **Desktop actual:** no-op (`null`). No desktop analysis in this pass.
- `data class LoudnessResult(integratedLufs, loudnessRange, truePeakDbtp)` as sketched; chip derived, not measured separately.

## [S4] Persistence

- App-private JSON only (Android app files dir; **not** mirrored to the portable music-dir copies of `playlists.json` / `manual_links.json`).
- Layout: `loudness.json`
  - `tracks`: map key (same trackKey/path key as library) → `{ integratedLufs, loudnessRange, truePeakDbtp, chip0_99, correction: "recommended" | "bypass" | "custom", customGainDb?, analysedAt }`.
  - `playlists`: playlist id → `{ meanLufs, analysedCount, totalMembers, updatedAt }`.
- Atomic write (tmp + rename), same pattern as existing stores.
- Corrupt file → treat as empty and rewrite on next save.
- Chip (absolute map — same track always same number): `chip0_99 = clamp(round((integratedLufs + 70.0) / 70.0 * 99.0), 0, 99)` so −70 LUFS → 0, 0 LUFS → 99.

## [S5] Playlist backfill

- Playlist detail action: **Analyse loudness** (visible on API 28+ when balance feature is available).
- Sequential pass over playable rows **missing** analysis only; skip missing/unplayable paths.
- Status `StateFlow` under the batch bar: `n/N`, current title, failure count, cancel via `ensureActive()` — same pattern as batch YouTube.
- After pass (or cancel): recompute playlist mean over all analysed members; persist profile.
- No full-library walk; no tagless library writes; no automatic rematch.

## [S6] Apply path (play)

- API **28+ only**. If global balance **on** and track has analysis and current playlist has a mean profile:
  - **Recommended:** `boostDb = clamp(meanLufs − trackLufs, 0, maxBoostDb)` — **boost only**, never cut below 0 dB from this path.
  - **Bypass:** no correction.
  - **Custom:** user `gainDb` (may be negative; still subject to limiter).
  - Loud tracks are controlled by DynamicsProcessing **compressor + limiter** (threshold near target/ceiling), not by reducing a volume multiplier.
- Wire per-track into `AndroidAudioPlayer`’s MediaPlayer **audio session** via `DynamicsProcessing` (channel config: pre-gain/makeup for boost, compressor, limiter). Detach/replace when track changes.
- Existing global volume slider continues to multiply on top (rule 7: volume always visible).
- API &lt;28: feature UI hidden; no DynamicsProcessing attach.
- No profile / no analysis → skip balance silently (no error spam).

## [S7] UI

- **Settings:** “Loudness balance” switch (API 28+ only; hidden otherwise). “Max boost” slider (cap, default **+12 dB**, range 0–24).
- **Playlist detail:** “Analyse loudness” action + live status (S5).
- **Advanced mode:** when the user has advanced UI enabled (existing pattern or overflow):
  - Analysed rows show the **0–99 chip** (quiet = low).
  - Track detail / long-press sheet: integrated LUFS, LRA, true peak, recommended boost preview (`mean − lufs`, shown even when 0), correction mode radio **Recommended | Bypass | Custom** + custom dB slider (−12…+24).
- Non-advanced browsing: no chips, no extra chrome.

## [S8] Error handling

- Analyse failure → `null` → skip track; backfill status shows `k failed`.
- DynamicsProcessing create/config fail → fall back to **volume-only boost** (setVolume scale) or no-op; only surface via Settings → Last error if user-visible.
- Persist failure → keep previous file (atomic rename); never crash.
- Play path never throws on missing/corrupt loudness entries.

## [S9] Testing

- Pure-Kotlin unit tests (`commonTest` / `androidUnitTest` as fits):
  - R128 fixtures: known sine / pink-noise PCM → integrated LUFS within tolerance; gate behaviour (silence ignored).
  - Chip mapping clamps 0…99.
  - Playlist mean + `analysedCount`.
  - Boost clamp: quiet → positive boost; loud → 0 dB corrective gain (boost-only, no volume cut).
  - Correction mode round-trip in `loudness.json`.
  - Backfill skip-missing + failure count logic with fake analyser.
- DynamicsProcessing attach is **not** unit-tested on JVM; verified manually on device.
- Desktop `LoudnessAnalyser` actual returns null and is a no-op.

## [S10] Out of scope

- Desktop apply / VLC dynamics.
- Re-encoding or baking gain into audio files (stream-copy rule stays).
- Full-library background scan.
- libebur128 JNI / cinterop (rejected in favour of pure Kotlin).
- Portable export of loudness data to phone via music-dir JSON.
