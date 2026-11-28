package com.slotify.app.playback

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Immutable player state for the UI. */
data class PlayerSnapshot(
    val currentPath: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

sealed interface PlayerEvent {
    /** Current file finished playing naturally. */
    data object Completed : PlayerEvent

    /** Playback failed (bad file, missing codec, audio device…). */
    data class Failed(val message: String) : PlayerEvent
}

/**
 * Minimal audio player over a single file at a time.
 * Desktop actual: vlcj (system VLC). Android actual: android.media.MediaPlayer.
 * Queue/advance logic lives in the UI controller, not here.
 */
interface AudioPlayer {
    val snapshot: StateFlow<PlayerSnapshot>
    val events: SharedFlow<PlayerEvent>

    /** Start (or switch to) [path]. Autoplays unless [startPaused]. */
    fun load(path: String, startPaused: Boolean = false)

    fun play()
    fun pause()

    fun toggle() {
        if (snapshot.value.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long)

    /** Output volume, 0f (muted) .. 1f (full). Independent of system volume. */
    fun setVolume(volume: Float)

    /**
     * Per-track loudness correction (Android DynamicsProcessing: makeup boost +
     * limiter). Desktop default: no-op. Applied on prepare when possible.
     * [boostDb] ≥ 0 for Recommended; may be negative only for Custom mode.
     * [enableLimiter] true when balance is on (control loud tracks).
     * [limiterThresholdDb] — dBFS where the limiter starts cutting (0 = full scale;
     * negative = earlier). Custom mode sets this below max volume.
     */
    fun setLoudnessCorrection(
        boostDb: Float,
        enableLimiter: Boolean,
        limiterThresholdDb: Float = 0f,
    ) {}

    /**
     * Last dynamics attach/update line for Settings (non-silent diagnostics).
     * Never contains file paths or secrets.
     */
    val dynamicsStatus: StateFlow<String>

    /**
     * Live gain reduction 0f..1f for the volume-row bar — how far the monitored
     * peak sits past the limiter threshold, converted to attenuation at the
     * limiter ratio (REDUCTION_METER_FULL_DB = full bar). Desktop stays 0f.
     */
    val reductionMeter: StateFlow<Float>

    fun stop()

    /** Free native resources. The player is unusable afterwards. */
    fun release()
}

/** Platform player factory. */
expect fun createAudioPlayer(): AudioPlayer
