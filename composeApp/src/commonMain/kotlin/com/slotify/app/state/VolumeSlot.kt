package com.slotify.app.state

/**
 * True when the volume row’s right-hand slot should be the
 * “Analyse loudness” button instead of Neutral/Boost/Limiting text.
 *
 * Playing → remedy text; paused / not playing → the button (when balance is
 * supported and there is an active playlist to analyse).
 */
fun showLoudnessAnalyseButton(
    isPlaying: Boolean,
    hasActivePlaylist: Boolean,
    loudnessSupported: Boolean,
): Boolean =
    loudnessSupported && hasActivePlaylist && !isPlaying
