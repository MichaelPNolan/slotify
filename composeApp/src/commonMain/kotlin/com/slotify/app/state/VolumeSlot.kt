package com.slotify.app.state

/**
 * True when the volume row’s right-hand slot should be the
 * “Analyse loudness” button instead of Neutral/Boost/Limiting text.
 * Button only when nothing is playing and the active playlist still has
 * unanalysed tracks (Android API 28+ with balance support).
 */
fun showLoudnessAnalyseButton(
    isPlaying: Boolean,
    hasActivePlaylist: Boolean,
    playlistIncomplete: Boolean,
    loudnessSupported: Boolean,
): Boolean =
    loudnessSupported && hasActivePlaylist && playlistIncomplete && !isPlaying
