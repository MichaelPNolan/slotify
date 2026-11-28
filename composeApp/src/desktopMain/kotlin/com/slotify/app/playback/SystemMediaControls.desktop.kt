package com.slotify.app.playback

/** Desktop: no OS media surface in use (VLC owns playback). */
actual fun createSystemMediaControls(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
): SystemMediaControls = object : SystemMediaControls {
    override fun update(state: SystemMediaState) {}
    override fun release() {}
}
