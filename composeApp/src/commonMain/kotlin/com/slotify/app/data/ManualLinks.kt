// Written fresh for Slotify — user-pinned playlist track → file links.

package com.slotify.app.data

import kotlinx.serialization.Serializable

/**
 * Persistent map of playlistId → trackKey → absolute audio file path.
 * Survives rematch and playlist re-import so explicit user choices win over
 * fuzzy matching.
 */
@Serializable
data class ManualLinks(
    /** playlistId → (trackKey → filePath). See [com.slotify.app.match.Matcher.trackKey]. */
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

    /** Overlay [other] onto this; entries in [other] win on key conflicts. */
    fun mergedWith(other: ManualLinks): ManualLinks {
        if (other.byPlaylist.isEmpty()) return this
        var next = this
        for ((plId, pins) in other.byPlaylist) {
            for ((key, path) in pins) {
                next = next.put(plId, key, path)
            }
        }
        return next
    }

    /**
     * Rewrite every pin path from a source machine’s `…/Music/…` layout onto
     * this device’s music root (parent of [defaultSlotifyMusicDir]).
     * Desktop `/home/owner/Music/Slotify/x.opus` → phone
     * `/storage/emulated/0/Music/Slotify/x.opus`.
     */
    fun remapMusicPaths(): ManualLinks {
        fun remap(path: String): String {
            val norm = path.replace('\\', '/')
            val marker = "/Music/"
            val idx = norm.indexOf(marker)
            if (idx < 0) return path
            val rel = norm.substring(idx + marker.length)
            if (rel.isEmpty()) return path
            val slotify = defaultSlotifyMusicDir().replace('\\', '/').trimEnd('/')
            val musicRoot = slotify.substringBeforeLast('/', "")
            return "$musicRoot/$rel"
        }
        return copy(
            byPlaylist = byPlaylist.mapValues { (_, pins) ->
                pins.mapValues { (_, p) -> remap(p) }
            },
        )
    }
}
