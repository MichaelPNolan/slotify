package com.slotify.app.state

/**
 * Playlist the volume-row Analyse action should target: the **open detail**
 * first (what the user is looking at), else the last **played** playlist.
 * Never analyse playback-active while another playlist is open on screen.
 */
fun loudnessTargetPlaylistId(
    openDetailPlaylistId: String?,
    activePlaybackPlaylistId: String?,
): String? = openDetailPlaylistId ?: activePlaybackPlaylistId
