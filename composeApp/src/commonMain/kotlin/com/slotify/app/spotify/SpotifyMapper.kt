// Written fresh for Slotify (Parachord maps playlists inside Android-side
// sync code, not shared — no direct port). Consumes the ported Sp* DTOs from
// SpotifyClient.kt and the pre-existing com.slotify.app.match.validateIsrc.

package com.slotify.app.spotify

import com.slotify.app.match.validateIsrc
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.PlaylistSource
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack

/**
 * Map one playlist-track item to a [RemoteTrack], skipping the two
 * un-importable shapes Spotify returns:
 *  - `item.track == null` (removed/unavailable tracks stay as placeholders),
 *  - `item.is_local` (files on the user's device — Slotify matches those
 *    against its own local library instead).
 */
fun SpPlaylistTrackItem.toRemoteTrackOrNull(): RemoteTrack? {
    val track = track ?: return null
    if (isLocal) return null
    return RemoteTrack(
        id = track.id,
        uri = track.uri ?: track.id?.let { "spotify:track:$it" },
        name = track.name ?: "",
        artists = track.artists.mapNotNull { artist ->
            artist.name?.let { RemoteArtist(id = artist.id, name = it) }
        },
        albumName = track.album?.name,
        durationMs = track.durationMs,
        isrc = validateIsrc(track.externalIds?.isrc),
        trackNumber = track.trackNumber,
        discNumber = track.discNumber,
        explicit = track.explicit,
    )
}

/** Map a full page of playlist items to importable [RemoteTrack]s (null/local dropped). */
fun List<SpPlaylistTrackItem>.toRemoteTracks(): List<RemoteTrack> = mapNotNull { it.toRemoteTrackOrNull() }

/**
 * Assemble the Slotify domain model for one imported playlist.
 *
 * @param items every fetched [SpPlaylistTrackItem] across all track pages.
 * @param trackCount is the count of *importable* tracks ([RemotePlaylist.tracks].size),
 *   not Spotify's raw total — placeholders and local files are not rows.
 */
fun mapAppPlaylist(
    playlist: SpPlaylistSimple,
    items: List<SpPlaylistTrackItem>,
    importedAtEpochMs: Long,
    source: PlaylistSource,
): AppPlaylist {
    val id = requireNotNull(playlist.id) { "playlist id must be present before mapping" }
    val tracks = items.toRemoteTracks()
    return AppPlaylist(
        remote = RemotePlaylist(
            id = id,
            name = playlist.name ?: "",
            description = playlist.description,
            ownerName = playlist.owner?.displayName,
            coverUrl = playlist.images.bestImageUrl(),
            trackCount = tracks.size,
            snapshotId = playlist.snapshotId,
            tracks = tracks,
        ),
        resolvedTracks = tracks.map { ResolvedPlaylistTrack(remote = it) },
        importedAtEpochMs = importedAtEpochMs,
        source = source,
    )
}
