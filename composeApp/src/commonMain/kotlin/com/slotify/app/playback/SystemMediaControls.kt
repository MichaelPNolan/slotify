package com.slotify.app.playback

/** Snapshot pushed to OS media surfaces (lock screen, media notification). */
data class SystemMediaState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * OS-side media controls around the existing player — the audio engine is not
 * touched. Commands arrive via the callbacks and are routed through the app's
 * normal play/pause/advance/seek paths.
 */
interface SystemMediaControls {
    fun update(state: SystemMediaState)
    fun release()
}

expect fun createSystemMediaControls(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
): SystemMediaControls
