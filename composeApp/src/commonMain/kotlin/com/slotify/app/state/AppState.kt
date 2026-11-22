package com.slotify.app.state

import com.slotify.app.data.AppRepository
import com.slotify.app.data.AppSettings
import com.slotify.app.data.ManualLinks
import com.slotify.app.library.LibraryScanner
import com.slotify.app.library.createLibraryScanner
import com.slotify.app.match.Matcher
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.platform.pickAudioFile
import com.slotify.app.platform.readBrowserSpDcCookie
import com.slotify.app.playback.AudioPlayer
import com.slotify.app.playback.PlayerEvent
import com.slotify.app.playback.createAudioPlayer
import com.slotify.app.spotify.parseSpotifyPlaylistId
import com.slotify.app.youtube.YoutubeSearchResult
import com.slotify.app.youtube.YoutubeSource
import com.slotify.app.youtube.createYoutubeSource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Tab { PLAYLISTS, LIBRARY, SETTINGS }

data class UiState(
    val tab: Tab = Tab.PLAYLISTS,
    val detailPlaylistId: String? = null,
)

/** What the player bar shows. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val path: String,
)

private data class QueueEntry(
    val path: String,
    val title: String,
    val artist: String,
)

/**
 * Central app controller: owns all state, performs IO/Spotify/matching work
 * on a background scope, and exposes StateFlows for the Compose UI.
 */
class AppState(
    private val repository: AppRepository = AppRepository(),
    private val scanner: LibraryScanner = createLibraryScanner(),
    val player: AudioPlayer = createAudioPlayer(),
    val spotify: SpotifyPort? = null,
    val youtube: YoutubeSource = createYoutubeSource(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    // ── Flows ────────────────────────────────────────────────────────

    /** Convenience views over the (optional) Spotify port — never null for the UI. */
    val spotifyStatus: StateFlow<SpotifyStatus> =
        spotify?.status ?: MutableStateFlow(SpotifyStatus.DISCONNECTED)
    val spotifyDisplayName: StateFlow<String?> =
        spotify?.displayName ?: MutableStateFlow(null)
    /** Last Spotify connection/import failure — shown persistently in Settings. */
    val spotifyError: StateFlow<String?> =
        spotify?.lastError ?: MutableStateFlow(null)

    private val _settings = MutableStateFlow(repository.loadSettings().let {
        if (it.libraryRoots.isEmpty()) it.copy(libraryRoots = emptyList()) else it
    })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _playlists = MutableStateFlow(repository.loadPlaylists())
    val playlists: StateFlow<List<AppPlaylist>> = _playlists.asStateFlow()

    private val _library = MutableStateFlow(repository.loadLibrary())
    val library: StateFlow<List<LocalTrack>> = _library.asStateFlow()

    private val _manualLinks = MutableStateFlow(repository.loadManualLinks())
    /** User-pinned playlist track → file paths (survive rematch). */
    val manualLinks: StateFlow<ManualLinks> = _manualLinks.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _scanStatus = MutableStateFlow<String?>(null)
    val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

    private val _youtubeStatus = MutableStateFlow<String?>(null)
    /** YouTube fetch progress (download %, indexing…) for the Find dialog. */
    val youtubeStatus: StateFlow<String?> = _youtubeStatus.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _volume = MutableStateFlow(_settings.value.volume.coerceIn(0f, 1f))
    /** App output volume 0..1 — exposed for the always-visible volume control. */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ── Playback queue ───────────────────────────────────────────────

    private var queue: List<QueueEntry> = emptyList()
    private var queueIndex: Int = -1

    init {
        // First run: seed suggested library roots (persisted empty → platform defaults).
        if (_settings.value.libraryRoots.isEmpty()) {
            updateSettings { it.copy(libraryRoots = com.slotify.app.data.defaultLibraryRoots()) }
        }
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    PlayerEvent.Completed -> advance(1, auto = true)
                    is PlayerEvent.Failed -> _messages.tryEmit(event.message)
                }
            }
        }
        // Cold start with no index yet (first run or cleared cache): index now,
        // so the library is ready without requiring a manual Scan click.
        if (_library.value.isEmpty() && _settings.value.libraryRoots.isNotEmpty()) {
            scanLibrary()
        }
        // Apply the persisted volume to the fresh player instance.
        player.setVolume(_volume.value)
    }

    // ── Navigation ───────────────────────────────────────────────────

    fun selectTab(tab: Tab) {
        _ui.value = _ui.value.copy(tab = tab, detailPlaylistId = null)
    }

    fun openPlaylist(id: String) {
        _ui.value = _ui.value.copy(tab = Tab.PLAYLISTS, detailPlaylistId = id)
    }

    fun closePlaylistDetail() {
        _ui.value = _ui.value.copy(detailPlaylistId = null)
    }

    // ── Settings ─────────────────────────────────────────────────────

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        // Base the transform on the CURRENT ON-DISK state, not this process's
        // (possibly stale) in-memory snapshot — otherwise an out-of-band file
        // edit (e.g. a client ID provisioned externally) is clobbered by the
        // next unrelated save such as a volume drag.
        val next = transform(repository.loadSettings())
        _settings.value = next
        scope.launch { repository.saveSettings(next) }
    }

    // ── Library ──────────────────────────────────────────────────────

    fun scanLibrary() {
        if (_scanStatus.value?.startsWith("Scanning") == true) return
        scope.launch { runScanAndMatch() }
    }

    /** Scan all configured roots, persist the index, rematch every playlist. */
    private suspend fun runScanAndMatch() {
        val roots = _settings.value.libraryRoots
        if (roots.isEmpty()) {
            _scanStatus.value = "Add a music folder in Settings first"
            return
        }
        _scanStatus.value = "Scanning ${roots.size} folder(s)…"
        val result = runCatching {
            scanner.scan(roots) { line -> _scanStatus.value = line }
        }
        result.onSuccess { tracks ->
            _library.value = tracks
            repository.saveLibrary(tracks)
            _scanStatus.value = "Indexed ${tracks.size} file(s)"
            if (_playlists.value.isNotEmpty()) {
                _scanStatus.value = "Matching ${_playlists.value.size} playlist(s)…"
                rematchAll()
                _scanStatus.value = "Indexed ${tracks.size} file(s) · matches refreshed"
            }
        }.onFailure { e ->
            _scanStatus.value = "Scan failed: ${e.message}"
        }
    }

    // ── YouTube fetch (unmatched tracks → local file) ────────────────

    /** Where quality-preserving YouTube downloads land (inside a scanned root). */
    fun youtubeDownloadDir(): String =
        ((settings.value.libraryRoots.firstOrNull() ?: "Music").trimEnd('/') + "/YouTube Downloads")

    /**
     * Download [result]'s audio (stream copy), re-index the library, and
     * rematch playlists so the new file binds to the requesting track.
     * Progress flows through [youtubeStatus].
     */
    fun fetchYoutube(result: YoutubeSearchResult, playlistId: String?) {
        if (!youtube.isSupported) {
            emit("YouTube fetch is desktop-only for now")
            return
        }
        scope.launch {
            _youtubeStatus.value = "Starting download…"
            val outcome = runCatching {
                youtube.download(result, youtubeDownloadDir()) { line ->
                    _youtubeStatus.value = line
                }
            }
            outcome.onFailure { e ->
                _youtubeStatus.value = null
                emit("Download failed: ${e.message}")
                return@launch
            }
            _youtubeStatus.value = "Indexing…"
            runScanAndMatch()
            _youtubeStatus.value = "Done — matched into library"
            emit("Added “${result.title}” to the library")
            if (playlistId != null) rematchPlaylist(playlistId)
        }
    }

    // ── Spotify ──────────────────────────────────────────────────────

    fun connectOAuth(clientId: String, awaitCode: suspend (String) -> String) {
        val sp = spotify ?: run {
            emit("Spotify is not available in this build")
            return
        }
        if (clientId.isBlank()) {
            emit("Enter your Spotify client ID first (see Settings help)")
            return
        }
        updateSettings { it.copy(spotifyClientId = clientId) }
        scope.launch {
            sp.connectWithOAuth(clientId, awaitCode)
                .onSuccess { emit("Connected to Spotify") }
                .onFailure { emit("Spotify login failed: ${it.message}") }
        }
    }

    fun connectCookie(spDc: String) {
        val sp = spotify ?: run {
            emit("Spotify is not available in this build")
            return
        }
        if (spDc.isBlank()) {
            emit("Paste the sp_dc cookie value first")
            return
        }
        scope.launch {
            sp.connectWithCookie(spDc)
                .onSuccess { emit("Connected to Spotify via session cookie") }
                .onFailure { emit("Cookie login failed: ${it.message}") }
        }
    }

    /**
     * Probe the local Floorp/Firefox `sp_dc` cookie and surface whether it
     * can be used for private-playlist embed imports. Full OAuth-style
     * connect is secondary: Spotify's web token exchange is CDN-blocked from
     * this network, so URL paste does not require a "connected" state.
     */
    fun connectWithBrowserSession() {
        scope.launch {
            val cookie = readBrowserSpDcCookie()
            if (cookie.isNullOrBlank()) {
                emit("No Spotify session cookie found in Floorp/Firefox — public playlist URLs still work")
                return@launch
            }
            // Verify the cookie is still valid for embed (private playlists).
            val probe = runCatching {
                com.slotify.app.spotify.SpotifyEmbedSource().importPlaylist(
                    playlistId = "37i9dQZF1DXcBWIGoYBM5M",
                    spDcCookie = cookie,
                )
            }
            probe.onSuccess { p ->
                emit("Browser session cookie ready (${cookie.take(4)}…${cookie.takeLast(2)}) — ${p.name} embed OK")
            }.onFailure { e ->
                emit("Browser session cookie found but embed probe failed: ${e.message}")
            }
        }
    }

    fun disconnectSpotify() {
        scope.launch {
            spotify?.disconnect()
            emit("Disconnected from Spotify")
        }
    }

    /**
     * Import all Spotify playlists, then match them against the local library.
     * [replaceExisting] = true replaces the whole list (Refresh); false merges
     * imported playlists into any already present (used after a URL import so
     * a full Refresh doesn't wipe pasted playlists).
     */
    fun importFromSpotify(replaceExisting: Boolean = false) {
        val sp = spotify ?: run {
            emit("Spotify is not available in this build")
            return
        }
        scope.launch {
            _importStatus.value = "Starting import…"
            val result = runCatching {
                sp.importAllPlaylists { line -> _importStatus.value = line }
            }
            result.onSuccess { imported ->
                if (imported.isEmpty()) {
                    _importStatus.value = "No playlists found"
                    return@launch
                }
                // Merge by default so playlists pulled in via a pasted URL
                // (which may not appear in /v1/me/playlists) survive a Refresh.
                _playlists.value = if (replaceExisting) {
                    imported
                } else {
                    mergePlaylists(_playlists.value, imported)
                }
                repository.savePlaylists(_playlists.value)
                _importStatus.value = "Imported ${imported.size} playlist(s); matching…"
                rematchAll()
                _importStatus.value = "Imported ${imported.size} playlist(s)"
                emit("Imported ${imported.size} playlist(s)")
            }.onFailure { e ->
                _importStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    /**
     * Import one playlist from a pasted open.spotify.com URL (or
     * `spotify:playlist:…` URI). Metadata only — never audio.
     *
     * Uses the public embed page (works for public playlists with no login).
     * When a Floorp/Firefox `sp_dc` cookie is present it is attached so
     * private playlists resolve too. Does not require OAuth / Web API connect.
     */
    fun importFromSpotifyUrl(url: String) {
        val playlistId = parseSpotifyPlaylistId(url)
        if (playlistId == null) {
            emit("Not a Spotify playlist — paste open.spotify.com/playlist/… or spotify:playlist:…")
            return
        }
        scope.launch {
            _importStatus.value = "Fetching playlist $playlistId…"
            val cookie = readBrowserSpDcCookie()
            val result = runCatching {
                com.slotify.app.spotify.SpotifyEmbedSource().importPlaylist(
                    playlistId = playlistId,
                    spDcCookie = cookie,
                ) { line -> _importStatus.value = line }
            }
            result.onSuccess { playlist ->
                _playlists.value = mergePlaylists(_playlists.value, listOf(playlist))
                repository.savePlaylists(_playlists.value)
                _importStatus.value =
                    "Imported \"${playlist.name}\" (${playlist.remote.tracks.size} tracks); matching…"
                matchPlaylist(playlist.id)
                val after = _playlists.value.firstOrNull { it.id == playlist.id } ?: playlist
                val matched = after.resolvedTracks.count { it.match != null }
                _importStatus.value =
                    "Imported \"${after.name}\" ($matched/${after.resolvedTracks.size} matched)"
                emit("Imported \"${after.name}\"")
                openPlaylist(playlist.id)
            }.onFailure { e ->
                _importStatus.value = "Import failed: ${e.message}"
                emit("Playlist import failed: ${e.message}")
            }
        }
    }

    /** Import a playlist from a JSON file path (loose schema — see Settings). */
    fun importFromJsonFile(path: String) {
        scope.launch {
            val content = com.slotify.app.data.readExternalTextOrNull(path)
            if (content == null) {
                emit("Cannot read file: $path")
                return@launch
            }
            runCatching { repository.parsePlaylistJson(content) }
                .onSuccess { playlist ->
                    _playlists.value = _playlists.value.filterNot { it.id == playlist.id } + playlist
                    repository.savePlaylists(_playlists.value)
                    matchPlaylist(playlist.id)
                    emit("Imported \"${playlist.name}\" (${playlist.remote.tracks.size} tracks)")
                    openPlaylist(playlist.id)
                }
                .onFailure { e ->
                    emit("Not a valid playlist JSON: ${e.message}")
                }
        }
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        repository.savePlaylists(_playlists.value)
        if (_ui.value.detailPlaylistId == id) closePlaylistDetail()
    }

    // ── Matching ─────────────────────────────────────────────────────

    fun rematchPlaylist(id: String) {
        scope.launch { matchPlaylist(id) }
    }

    private fun rematchAll() {
        _playlists.value.forEach { matchPlaylist(it.id) }
    }

    /** Overlay [incoming] onto [existing] by playlist id (incoming wins). */
    private fun mergePlaylists(
        existing: List<AppPlaylist>,
        incoming: List<AppPlaylist>,
    ): List<AppPlaylist> {
        val incomingIds = incoming.map { it.id }.toSet()
        return existing.filterNot { it.id in incomingIds } + incoming
    }

    private fun matchPlaylist(id: String) {
        val playlist = _playlists.value.firstOrNull { it.id == id } ?: return
        val locals = _library.value
        val threshold = _settings.value.matchThreshold
        val links = _manualLinks.value.linksFor(id)
        if (locals.isEmpty() && links.isEmpty()) {
            _playlists.value = _playlists.value.map {
                if (it.id == id) it.copy(
                    resolvedTracks = playlist.remote.tracks.map { t -> ResolvedPlaylistTrack(t, null) },
                ) else it
            }
        } else {
            val resolved = Matcher.matchPlaylist(playlist.remote, locals, threshold, links)
            _playlists.value = _playlists.value.map {
                if (it.id == id) it.copy(resolvedTracks = resolved) else it
            }
        }
        repository.savePlaylists(_playlists.value)
    }

    // ── Manual file links (unmatched rows) ──────────────────────────

    /**
     * Open a native file picker and pin the chosen audio file to
     * [trackKey] in [playlistId]. Indexes the file into the library if it
     * isn't already there, then rematches the playlist.
     *
     * Call from a UI thread is fine — the picker hops to the EDT itself.
     */
    fun linkTrackToFileFromPicker(playlistId: String, trackKey: String) {
        scope.launch {
            val path = pickAudioFile(settings.value.libraryRoots.firstOrNull())
            if (path == null) {
                emit("No file selected")
                return@launch
            }
            linkTrackToFile(playlistId, trackKey, path)
        }
    }

    /** Pin [path] to [trackKey] in [playlistId] (no picker — used by tests / paste). */
    fun linkTrackToFile(playlistId: String, trackKey: String, path: String) {
        scope.launch {
            val local = scanner.readTrack(path)
            if (local == null) {
                emit("Not an audio file: $path")
                return@launch
            }
            if (_library.value.none { it.filePath == local.filePath }) {
                _library.value = _library.value + local
                withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
            }
            _manualLinks.value = _manualLinks.value.put(playlistId, trackKey, local.filePath)
            withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            matchPlaylist(playlistId)
            emit("Linked to ${local.displayTitle}")
        }
    }

    /** Drop a manual pin so the row falls back to fuzzy matching. */
    fun unlinkTrackFile(playlistId: String, trackKey: String) {
        _manualLinks.value = _manualLinks.value.remove(playlistId, trackKey)
        scope.launch {
            withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            matchPlaylist(playlistId)
        }
    }

    // ── Playback ─────────────────────────────────────────────────────

    /** Play a playlist starting at row [fromRowIndex] (unmatched rows are skipped). */
    fun playPlaylist(playlistId: String, fromRowIndex: Int = 0) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val entries = playlist.resolvedTracks.withIndex().mapNotNull { (index, row) ->
            val match = row.match ?: return@mapNotNull null
            index to QueueEntry(
                path = match.local.filePath,
                title = row.remote.name,
                artist = row.remote.artistNames.ifBlank { match.local.displayArtist },
            )
        }
        if (entries.isEmpty()) {
            emit("No local matches in \"${playlist.name}\" yet — scan your library first")
            return
        }
        val startIndex = entries.indexOfFirst { it.first >= fromRowIndex }.let { if (it == -1) 0 else it }
        queue = entries.map { it.second }
        playAt(startIndex)
    }

    /** Play the local library starting at [fromIndex] (search-filtered view passes its own index). */
    fun playLibrary(tracks: List<LocalTrack>, fromIndex: Int = 0) {
        if (tracks.isEmpty()) return
        queue = tracks.map { QueueEntry(it.filePath, it.displayTitle, it.displayArtist) }
        playAt(fromIndex.coerceIn(0, queue.lastIndex))
    }

    fun next() = advance(1, auto = false)

    fun previous() {
        if (player.snapshot.value.positionMs > 3_000) {
            player.seekTo(0)
        } else {
            advance(-1, auto = false)
        }
    }

    fun togglePlay() {
        if (_nowPlaying.value == null) return
        player.toggle()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    private fun advance(delta: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        val target = queueIndex + delta
        if (target !in queue.indices) {
            if (auto) {
                player.stop()
                queueIndex = -1
                _nowPlaying.value = null
            }
            return
        }
        playAt(target)
    }

    private fun playAt(index: Int) {
        val entry = queue.getOrNull(index) ?: return
        queueIndex = index
        _nowPlaying.value = NowPlaying(entry.title, entry.artist, entry.path)
        player.load(entry.path)
    }

    // ── Volume ───────────────────────────────────────────────────────

    /**
     * Update app volume 0..1. Set [persist] only when a gesture finishes
     * (slider drag) so we don't write settings.json on every frame.
     */
    fun setVolume(value: Float, persist: Boolean = true) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        player.setVolume(v)
        if (persist) {
            updateSettings { it.copy(volume = v) }
        }
    }

    // ── Teardown ─────────────────────────────────────────────────────

    fun release() {
        player.release()
        scope.cancel()
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }
}
