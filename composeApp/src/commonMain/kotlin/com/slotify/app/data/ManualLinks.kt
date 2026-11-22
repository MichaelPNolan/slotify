// Written fresh for Slotify — user-pinned playlist track → local file links.

package com.slotify.app.data

import kotlinx.serialization.Serializable

/**
 * Persistent map of playlistId → trackKey → absolute audio file path.
 * Survives rematch and playlist re-import so explicit user choices win over
 * fuzzy matching.
 */
@Serializable
data class ManualLinks(
    /** playlistId → (trackKey → filePath). See Matcher.trackKey. */
    val byPlaylist: Map<String, Map<String, String>> = emptyMap(),
) {
    fun linksFor(playlistId: String): Map<String, String> = byPlaylist[playlistId].orEmpty()

    fun put(playlistId: String, trackKey: String, filePath: String): ManualLinks {
        val inner = byPlaylist[playlistId].orEmpty() + (trackKey to filePath)
        return copy(byPlaylist = byPlaylist + (playlistId to inner))
    }

    fun remove(playlistId: String, trackKey: String): ManualLinks {
        val inner = (byPlaylist[playlistId].orEmpty()) - trackKey
        return if (inner.isEmpty()) {
            copy(byPlaylist = byPlaylist - playlistId)
        } else {
            copy(byPlaylist = byPlaylist + (playlistId to inner))
        }
    }
}
