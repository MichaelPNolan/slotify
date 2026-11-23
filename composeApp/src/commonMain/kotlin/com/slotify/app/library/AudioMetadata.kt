package com.slotify.app.library

/**
 * Write canonical metadata onto a local audio file after download.
 * Used so YouTube-sourced files carry **Spotify** title/artist (what the
 * matcher scores), not the YouTube title/channel from yt-dlp embed.
 *
 * Best-effort: returns false when the container/tagger cannot accept writes
 * (caller should still pin the path for playlist matching).
 */
expect fun writeAudioMetadata(
    path: String,
    title: String?,
    artist: String?,
    album: String?,
    isrc: String?,
): Boolean
