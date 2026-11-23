package com.slotify.app.model

import kotlinx.serialization.Serializable

// ── Remote (Spotify) metadata ───────────────────────────────────────

@Serializable
data class RemoteArtist(
    val id: String? = null,
    val name: String,
)

/** A track as listed on Spotify — metadata only, never audio. */
@Serializable
data class RemoteTrack(
    val id: String? = null,
    val uri: String? = null,
    val name: String,
    val artists: List<RemoteArtist> = emptyList(),
    val albumName: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val explicit: Boolean = false,
) {
    /** All artist names joined for display/matching, e.g. "Queen; David Bowie". */
    val artistNames: String get() = artists.joinToString("; ") { it.name }
    val primaryArtist: String get() = artists.firstOrNull()?.name ?: ""
}

@Serializable
data class RemotePlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val ownerName: String? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val snapshotId: String? = null,
    val tracks: List<RemoteTrack> = emptyList(),
)

// ── Local library ───────────────────────────────────────────────────

/** An audio file in the user's local collection. [filePath] is what the player loads. */
@Serializable
data class LocalTrack(
    val filePath: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val isrc: String? = null,
    val fileFormat: String? = null,
    val fileSizeBytes: Long? = null,
    /** Optional display path (Android content URIs keep filePath separate). */
    val displayPath: String? = null,
    /** User mark (skip / delete); survives rescan via AppState re-apply. */
    val mark: RowMark? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: fileNameFromPath(displayPath ?: filePath)

    val displayArtist: String
        get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

    /** Best name for filename-axis matching (content URIs use [displayPath]). */
    val nameForFilenameParse: String get() = displayPath ?: filePath

    /**
     * Path relative to the longest matching [libraryRoots] entry
     * (e.g. `Muse.Collection[…]/Muse - …/01 - Song.mp3` under `~/Music`).
     * Null when the file is outside every root.
     */
    fun relativeToRoots(libraryRoots: List<String>): String? {
        val path = filePath.replace('\\', '/')
        val root = libraryRoots
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotEmpty() && (path == it || path.startsWith("$it/")) }
            .maxByOrNull { it.length }
            ?: return null
        return path.removePrefix(root).trimStart('/')
    }
}

private fun fileNameFromPath(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

// ── Matching ────────────────────────────────────────────────────────

/** A local file matched to a remote track, with confidence 0.0–1.0. */
@Serializable
data class TrackMatch(
    val local: LocalTrack,
    val confidence: Double,
    /** True when the user explicitly pinned this file (survives rematch). */
    val isManual: Boolean = false,
)

/** User mark on a playlist row: skip in playback, or stage for removal. */
@Serializable
enum class RowMark { SKIP, DELETE }

/** Shared row-mark cycle: none → SKIP → DELETE → none (playlist + library rows). */
fun RowMark?.cycleMark(): RowMark? = when (this) {
    null -> RowMark.SKIP
    RowMark.SKIP -> RowMark.DELETE
    RowMark.DELETE -> null
}

/** One playlist row: the Spotify track plus its local match, if any. */
@Serializable
data class ResolvedPlaylistTrack(
    val remote: RemoteTrack,
    val match: TrackMatch? = null,
    /** Null = unmarked. Survives rematch (AppState re-applies by track key). */
    val mark: RowMark? = null,
) {
    val isMatched: Boolean get() = match != null
}

enum class PlaylistSource {
    OAUTH, COOKIE, JSON,
}

/** A Spotify playlist imported into Slotify, with current match state. */
@Serializable
data class AppPlaylist(
    val remote: RemotePlaylist,
    val resolvedTracks: List<ResolvedPlaylistTrack> = emptyList(),
    val importedAtEpochMs: Long = 0L,
    val source: PlaylistSource = PlaylistSource.OAUTH,
    /** Soft-delete flag on the playlists list (not per-track). */
    val markedForDelete: Boolean = false,
    /** In the trash — hidden from the main list until restored or emptied. */
    val trashed: Boolean = false,
) {
    val id: String get() = remote.id
    val name: String get() = remote.name
    val matchedCount: Int get() = resolvedTracks.count { it.isMatched }
    val isFullyMatched: Boolean get() = resolvedTracks.isNotEmpty() && matchedCount == resolvedTracks.size
    /** Rows with no local file yet — surfaced as the “To be sourced” list. */
    val toBeSourced: List<ResolvedPlaylistTrack>
        get() = resolvedTracks.filterNot { it.isMatched }
    val toBeSourcedCount: Int get() = resolvedTracks.size - matchedCount
    val skipCount: Int get() = resolvedTracks.count { it.mark == RowMark.SKIP }
    val deleteMarkedCount: Int get() = resolvedTracks.count { it.mark == RowMark.DELETE }
}
