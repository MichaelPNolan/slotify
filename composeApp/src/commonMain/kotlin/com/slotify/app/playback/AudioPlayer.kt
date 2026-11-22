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

    fun stop()

    /** Free native resources. The player is unusable afterwards. */
    fun release()
}

/** Platform player factory. */
expect fun createAudioPlayer(): AudioPlayer
