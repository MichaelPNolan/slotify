package com.slotify.app.state

/**
 * Player-bar title tap: highlight the song in the **active playlist** when it
 * belongs there; only fall back to Library when there is no active playlist
 * (or the song is not in it).
 */
fun preferPlaylistFollow(
    hasNowPlaying: Boolean,
    activePlaylistId: String?,
    nowPlayingInActivePlaylist: Boolean,
): Boolean =
    hasNowPlaying && activePlaylistId != null && nowPlayingInActivePlaylist
