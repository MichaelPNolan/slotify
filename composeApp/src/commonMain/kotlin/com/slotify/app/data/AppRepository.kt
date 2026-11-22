package com.slotify.app.data

import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.PlaylistSource
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.platform.nowEpochMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val libraryRoots: List<String> = emptyList(),
    val spotifyClientId: String = "",
    val matchThreshold: Double = 0.75,
    /** App output volume, 0.0–1.0. Persists across launches. */
    val volume: Float = 1.0f,
)

/** Resume point for one playlist (survives app restart). */
@Serializable
data class PlaylistProgress(
    /** Play order as track keys (Matcher.trackKey) — shuffle-safe. */
    val orderKeys: List<String> = emptyList(),
    val orderIndex: Int = -1,
    val positionMs: Long = 0L,
)

/** Global playback session state persisted to [AppRepository.PLAYBACK_FILE]. */
@Serializable
data class PlaybackStore(
    val activePlaylistId: String? = null,
    val shuffle: Boolean = false,
    val byPlaylist: Map<String, PlaylistProgress> = emptyMap(),
)

/** JSON file accepted by "import from file": loose schema, common in exports. */
@Serializable
data class PlaylistJsonFile(
    val name: String = "Imported playlist",
    val description: String? = null,
    val tracks: List<TrackJson> = emptyList(),
)

@Serializable
data class TrackJson(
    val name: String = "",
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)

/**
 * JSON persistence over [AppFiles]: settings, imported playlists, library index.
 * All methods are synchronous — call them off the main thread.
 */
class AppRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    // ── Settings ─────────────────────────────────────────────────────

    fun loadSettings(): AppSettings = runCatching {
        AppFiles.readTextOrNull(SETTINGS_FILE)?.let { json.decodeFromString<AppSettings>(it) }
    }.getOrNull() ?: AppSettings()

    fun saveSettings(settings: AppSettings) {
        AppFiles.writeText(SETTINGS_FILE, json.encodeToString(settings))
    }

    // ── Playlists ────────────────────────────────────────────────────

    fun loadPlaylists(): List<AppPlaylist> = runCatching {
        AppFiles.readTextOrNull(PLAYLISTS_FILE)?.let {
            json.decodeFromString<List<AppPlaylist>>(it)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun savePlaylists(playlists: List<AppPlaylist>) {
        AppFiles.writeText(PLAYLISTS_FILE, json.encodeToString(playlists))
    }

    // ── Library index cache ──────────────────────────────────────────

    fun loadLibrary(): List<LocalTrack> = runCatching {
        AppFiles.readTextOrNull(LIBRARY_FILE)?.let {
            json.decodeFromString<List<LocalTrack>>(it)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun saveLibrary(library: List<LocalTrack>) {
        AppFiles.writeText(LIBRARY_FILE, json.encodeToString(library))
    }

    // ── Manual track → file links ───────────────────────────────────

    fun loadManualLinks(): ManualLinks = runCatching {
        AppFiles.readTextOrNull(MANUAL_LINKS_FILE)?.let { json.decodeFromString<ManualLinks>(it) }
    }.getOrNull() ?: ManualLinks()

    fun saveManualLinks(links: ManualLinks) {
        AppFiles.writeText(MANUAL_LINKS_FILE, json.encodeToString(links))
    }

    // ── Playback resume / shuffle ────────────────────────────────────

    fun loadPlayback(): PlaybackStore = runCatching {
        AppFiles.readTextOrNull(PLAYBACK_FILE)?.let { json.decodeFromString<PlaybackStore>(it) }
    }.getOrNull() ?: PlaybackStore()

    fun savePlayback(store: PlaybackStore) {
        AppFiles.writeText(PLAYBACK_FILE, json.encodeToString(store))
    }

    // ── Playlist JSON import ─────────────────────────────────────────

    /** Parse a pasted-file playlist JSON into an [AppPlaylist] (unmatched). */
    fun parsePlaylistJson(content: String, source: PlaylistSource = PlaylistSource.JSON): AppPlaylist {
        val parsed = json.decodeFromString<PlaylistJsonFile>(content)
        val tracks = parsed.tracks.mapNotNull { t ->
            val name = t.name.ifBlank { t.title } ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            val artists = when {
                t.artists.isNotEmpty() -> t.artists.map { RemoteArtist(name = it) }
                !t.artist.isNullOrBlank() -> listOf(RemoteArtist(name = t.artist))
                else -> emptyList()
            }
            RemoteTrack(
                name = name,
                artists = artists,
                albumName = t.album,
                durationMs = t.durationMs,
                isrc = t.isrc,
            )
        }
        val remote = RemotePlaylist(
            id = "json-${slug(parsed.name)}-${tracks.size}",
            name = parsed.name,
            description = parsed.description,
            trackCount = tracks.size,
            tracks = tracks,
        )
        return AppPlaylist(
            remote = remote,
            resolvedTracks = tracks.map { ResolvedPlaylistTrack(it, null) },
            importedAtEpochMs = nowEpochMs(),
            source = source,
        )
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "playlist" }

    companion object {
        const val SETTINGS_FILE = "settings.json"
        const val PLAYLISTS_FILE = "playlists.json"
        const val LIBRARY_FILE = "library.json"
        const val MANUAL_LINKS_FILE = "manual_links.json"
        const val PLAYBACK_FILE = "playback.json"
    }
}
