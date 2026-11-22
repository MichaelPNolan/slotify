package com.slotify.app.state

import com.slotify.app.data.AppRepository
import com.slotify.app.data.AppSettings
import com.slotify.app.data.ManualLinks
import com.slotify.app.data.PlaybackStore
import com.slotify.app.data.PlaylistProgress
import com.slotify.app.library.LibraryScanner
import com.slotify.app.library.createLibraryScanner
import com.slotify.app.match.Matcher
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.RowMark
import com.slotify.app.platform.pickAudioFile
import com.slotify.app.platform.readBrowserSpDcCookie
import com.slotify.app.playback.AudioPlayer
import com.slotify.app.playback.PlayerEvent
import com.slotify.app.playback.createAudioPlayer
import com.slotify.app.spotify.parseSpotifyPlaylistId
import com.slotify.app.spotify.PublicPlaylistHit
import com.slotify.app.spotify.SpotifyPublicSearch
import com.slotify.app.youtube.YoutubeQuery
import com.slotify.app.youtube.YoutubeRanking
import com.slotify.app.youtube.YoutubeSearchResult
import com.slotify.app.youtube.YoutubeSource
import com.slotify.app.youtube.createYoutubeSource
import com.slotify.app.youtube.searchWithFallbacks
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    val trackKey: String,
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

    private val _youtubeBatchRunning = MutableStateFlow(false)
    /** True while [batchFetchYoutube] is walking a playlist. */
    val youtubeBatchRunning: StateFlow<Boolean> = _youtubeBatchRunning.asStateFlow()

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _playlistSearchResults = MutableStateFlow<List<PublicPlaylistHit>>(emptyList())
    /** Public Spotify playlist hits for the current search query. */
    val playlistSearchResults: StateFlow<List<PublicPlaylistHit>> =
        _playlistSearchResults.asStateFlow()

    private val _playlistSearchStatus = MutableStateFlow<String?>(null)
    val playlistSearchStatus: StateFlow<String?> = _playlistSearchStatus.asStateFlow()

    private val _playlistSearchRunning = MutableStateFlow(false)
    val playlistSearchRunning: StateFlow<Boolean> = _playlistSearchRunning.asStateFlow()

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

    private val _activePlaylistId = MutableStateFlow<String?>(null)
    /** Playlist currently owning the queue (for play/stop toggle + resume). */
    val activePlaylistId: StateFlow<String?> = _activePlaylistId.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    /** Shuffle without repeats until the playlist is exhausted (then reshuffle). */
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private var playbackStore: PlaybackStore = repository.loadPlayback()

    init {
        // First run: seed suggested library roots (persisted empty → platform defaults).
        if (_settings.value.libraryRoots.isEmpty()) {
            updateSettings { it.copy(libraryRoots = com.slotify.app.data.defaultLibraryRoots()) }
        }
        _shuffleEnabled.value = playbackStore.shuffle
        _activePlaylistId.value = playbackStore.activePlaylistId
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    PlayerEvent.Completed -> advance(1, auto = true)
                    is PlayerEvent.Failed -> _messages.tryEmit(event.message)
                }
            }
        }
        // Periodic resume snapshot while playing (position + index).
        scope.launch {
            while (true) {
                delay(4_000)
                if (player.snapshot.value.isPlaying) persistPlaybackProgress()
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

    /**
     * Staging dir for raw YouTube downloads: `<OS music>/Slotify/downloads`.
     * Ensures the managed root (and staging subdirs) exist and that the root
     * is covered by a scanned library root. Final named files are promoted
     * to the Slotify root; `downloads/` + `conversions/` are manually purgeable.
     */
    fun youtubeDownloadDir(): String {
        val managed = com.slotify.app.data.defaultSlotifyMusicDir()
        val roots = _settings.value.libraryRoots
        val covered = roots.any { root ->
            val r = root.trimEnd('/')
            managed == r || managed.startsWith("$r/")
        }
        if (!covered) {
            updateSettings { it.copy(libraryRoots = it.libraryRoots + managed) }
        }
        // Create managed root + staging layout (idempotent).
        runCatching {
            val root = java.io.File(managed)
            root.mkdirs()
            com.slotify.app.data.SLOTIFY_STAGING_DIRS.forEach { name ->
                java.io.File(root, name).mkdirs()
            }
        }
        return com.slotify.app.data.slotifyDownloadsDir(managed)
    }

    /**
     * Move a finished download out of staging into the Slotify root under its
     * final `Artist - Title.ext` name. Staging stays until the user purges it.
     */
    private fun promoteFromStaging(stagedPath: String): String {
        val managed = com.slotify.app.data.defaultSlotifyMusicDir()
        val staged = java.io.File(stagedPath)
        if (!staged.isFile) return stagedPath
        val downloads = java.io.File(com.slotify.app.data.slotifyDownloadsDir(managed))
        val conversions = java.io.File(com.slotify.app.data.slotifyConversionsDir(managed))
        val inStaging = staged.parentFile?.let { p ->
            p.absolutePath == downloads.absolutePath || p.absolutePath == conversions.absolutePath
        } == true
        if (!inStaging) return staged.absolutePath

        val dest = java.io.File(managed, staged.name)
        if (dest.absolutePath == staged.absolutePath) return staged.absolutePath
        if (dest.exists()) {
            // Same final name already present — keep staged copy as alternate take.
            return staged.absolutePath
        }
        return runCatching {
            if (staged.renameTo(dest)) {
                dest.absolutePath
            } else {
                staged.copyTo(dest, overwrite = false).absolutePath.also { staged.delete() }
            }
        }.getOrDefault(staged.absolutePath)
    }

    /**
     * Download [result]'s audio (stream copy), index **only that file**,
     * pin it to the requesting playlist row when known, and rematch that
     * playlist so the UI flips the row to matched immediately.
     *
     * Never runs a full library rescan — one new file must not re-read
     * every root (backup dirs included). Progress: [youtubeStatus].
     */
    fun fetchYoutube(
        result: YoutubeSearchResult,
        playlistId: String?,
        remoteTrack: RemoteTrack? = null,
    ) {
        if (!youtube.isSupported) {
            emit("YouTube fetch is desktop-only for now")
            return
        }
        scope.launch {
            _youtubeStatus.value = "Starting download…"
            val ok = downloadIndexAndPin(result, playlistId, remoteTrack)
            if (!ok) {
                _youtubeStatus.value = null
                return@launch
            }
            val rowMatched = playlistId?.let { id ->
                _playlists.value.firstOrNull { it.id == id }?.resolvedTracks?.any { row ->
                    (remoteTrack == null || Matcher.trackKey(row.remote) == Matcher.trackKey(remoteTrack)) &&
                        row.isMatched
                } == true
            } == true
            _youtubeStatus.value = when {
                rowMatched -> "Done — added to playlist"
                playlistId != null -> "Saved to library — playlist row still unmatched"
                else -> "Done — added to library"
            }
            emit(
                when {
                    rowMatched -> "Added “${result.title}” to the playlist"
                    else -> "Added “${result.title}” to the library"
                },
            )
            // Brief hold so the dialog can show Done, then re-enable picking.
            kotlinx.coroutines.delay(1_500)
            if (_youtubeStatus.value?.startsWith("Done") == true ||
                _youtubeStatus.value?.startsWith("Saved") == true
            ) {
                _youtubeStatus.value = null
            }
        }
    }

    /**
     * Walk every unmatched row of [playlistId]: search YouTube, and when the
     * best ranked result scores **≥ [minScore]** (default 0.85), download +
     * pin it. Rows below the threshold stay in “To be sourced”.
     */
    fun batchFetchYoutube(playlistId: String, minScore: Double = 0.85) {
        if (!youtube.isSupported) {
            emit("YouTube fetch is desktop-only for now")
            return
        }
        if (_youtubeBatchRunning.value) {
            emit("A YouTube batch is already running")
            return
        }
        scope.launch {
            _youtubeBatchRunning.value = true
            try {
                val playlist = _playlists.value.firstOrNull { it.id == playlistId }
                if (playlist == null) {
                    _youtubeStatus.value = null
                    emit("Playlist not found")
                    return@launch
                }
                // Snapshot unmatched rows — rematch after each fetch mutates resolvedTracks.
                val targets = playlist.resolvedTracks
                    .filter { !it.isMatched }
                    .map { it.remote }
                if (targets.isEmpty()) {
                    _youtubeStatus.value = null
                    emit("Nothing left to source in “${playlist.name}”")
                    return@launch
                }
                val pct = (minScore * 100).toInt()
                var fetched = 0
                var skipped = 0
                var failed = 0
                targets.forEachIndexed { i, remote ->
                    val n = i + 1
                    _youtubeStatus.value = "Batch $n/${targets.size}: searching “${remote.name}”…"
                    val ranked = runCatching {
                        // Fallback phrasings — primary query can return 0 from the API
                        // even when youtube.com finds plenty (e.g. “Hoochie Coochie Man”).
                        val attempt = searchWithFallbacks(
                            youtube = youtube,
                            baseQuery = YoutubeQuery.forTrack(remote),
                            track = remote,
                        )
                        if (attempt.error != null && attempt.results.isEmpty()) throw attempt.error
                        YoutubeRanking.rank(attempt.results, remote)
                    }.getOrElse { e ->
                        failed++
                        emit("Search failed for “${remote.name}”: ${e.message}")
                        return@forEachIndexed
                    }
                    val best = ranked.firstOrNull()
                    if (best == null || best.score < minScore) {
                        skipped++
                        val shown = best?.let { "${(it.score * 100).toInt()}%" } ?: "no result"
                        _youtubeStatus.value =
                            "Batch $n/${targets.size}: skip “${remote.name}” ($shown < $pct%)"
                        return@forEachIndexed
                    }
                    _youtubeStatus.value =
                        "Batch $n/${targets.size}: download “${remote.name}” (${(best.score * 100).toInt()}%)…"
                    val ok = downloadIndexAndPin(best.result, playlistId, remote)
                    if (ok) {
                        fetched++
                    } else {
                        failed++
                    }
                }
                _youtubeStatus.value =
                    "Batch done — $fetched fetched · $skipped under $pct% · $failed failed"
                emit(
                    "YouTube batch: $fetched added, $skipped left to source (under $pct%), $failed failed",
                )
                kotlinx.coroutines.delay(2_500)
                if (_youtubeStatus.value?.startsWith("Batch") == true) {
                    _youtubeStatus.value = null
                }
            } finally {
                _youtubeBatchRunning.value = false
            }
        }
    }

    /**
     * Stream-copy download → promote out of staging → index **one** file →
     * optional manual pin → rematch that playlist only.
     * Returns false on failure (status/snackbar already updated).
     */
    private suspend fun downloadIndexAndPin(
        result: YoutubeSearchResult,
        playlistId: String?,
        remoteTrack: RemoteTrack?,
    ): Boolean {
        val path = runCatching {
            youtube.download(result, youtubeDownloadDir()) { line ->
                _youtubeStatus.value = line
            }.let { promoteFromStaging(it) }
        }.getOrElse { e ->
            emit("Download failed for “${result.title}”: ${e.message}")
            return false
        }

        _youtubeStatus.value = "Indexing new file…"
        val local = withContext(Dispatchers.IO) { scanner.readTrack(path) }
        if (local != null && _library.value.none { it.filePath == local.filePath }) {
            _library.value = _library.value + local
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
        }

        if (playlistId != null) {
            _youtubeStatus.value = "Updating playlist…"
            if (remoteTrack != null && local != null) {
                // Pin this download to the requesting row — fuzzy match often
                // fails on YouTube titles/artists.
                _manualLinks.value = _manualLinks.value.put(
                    playlistId,
                    Matcher.trackKey(remoteTrack),
                    local.filePath,
                )
                withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            }
            matchPlaylist(playlistId)
        }
        return true
    }

    /** Clear a finished YouTube status so the Find dialog can start another fetch. */
    fun clearYoutubeStatusIfIdle() {
        val s = _youtubeStatus.value ?: return
        val busy = s.contains("Download") || s.contains("Index") ||
            s.contains("Starting") || s.contains("Updating") ||
            s.contains("Searching") || s.contains("Batch") ||
            s.contains("download") || s.contains("searching")
        if (!busy) _youtubeStatus.value = null
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
     * Search public Spotify playlists by free-text (artist, genre, …).
     * Results are metadata only; pick one to import via the embed path.
     */
    fun searchPublicPlaylists(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _playlistSearchResults.value = emptyList()
            _playlistSearchStatus.value = null
            return
        }
        if (_playlistSearchRunning.value) return
        scope.launch {
            _playlistSearchRunning.value = true
            _playlistSearchStatus.value = "Searching public playlists for “$q”…"
            val outcome = runCatching {
                SpotifyPublicSearch().searchPlaylists(q, limit = 12)
            }
            _playlistSearchRunning.value = false
            outcome.onSuccess { hits ->
                _playlistSearchResults.value = hits
                _playlistSearchStatus.value = if (hits.isEmpty()) {
                    "No public playlists found for “$q”"
                } else {
                    "${hits.size} public playlist(s) — tap Import"
                }
            }.onFailure { e ->
                _playlistSearchResults.value = emptyList()
                _playlistSearchStatus.value = "Search failed: ${e.message}"
            }
        }
    }

    fun clearPlaylistSearch() {
        _playlistSearchResults.value = emptyList()
        _playlistSearchStatus.value = null
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
                // Drop search hits so the list doesn’t stay stale after import.
                clearPlaylistSearch()
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
        // Hard remove (used by empty-trash internals). List UI uses mark → trash.
        _playlists.value = _playlists.value.filterNot { it.id == id }
        repository.savePlaylists(_playlists.value)
        if (_ui.value.detailPlaylistId == id) closePlaylistDetail()
        if (_activePlaylistId.value == id) {
            persistPlaybackProgress()
            player.pause()
            _activePlaylistId.value = null
        }
    }

    /** Toggle soft-delete flag on a playlist row in the list view. */
    fun togglePlaylistMarkForDelete(id: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == id) pl.copy(markedForDelete = !pl.markedForDelete) else pl
        }
        repository.savePlaylists(_playlists.value)
    }

    /**
     * Move every playlist flagged [AppPlaylist.markedForDelete] into the trash
     * (soft delete — restore from Trash until emptied).
     */
    fun deleteMarkedPlaylists() {
        scope.launch {
            val marked = _playlists.value.filter { it.markedForDelete && !it.trashed }
            if (marked.isEmpty()) {
                emit("No playlists marked for delete")
                return@launch
            }
            val ids = marked.map { it.id }.toSet()
            _playlists.value = _playlists.value.map { pl ->
                if (pl.id in ids) pl.copy(trashed = true, markedForDelete = false) else pl
            }
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            if (_ui.value.detailPlaylistId in ids) closePlaylistDetail()
            emit("Moved ${marked.size} playlist(s) to trash")
        }
    }

    fun restorePlaylistFromTrash(id: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == id) pl.copy(trashed = false, markedForDelete = false) else pl
        }
        repository.savePlaylists(_playlists.value)
        emit("Restored playlist from trash")
    }

    /**
     * File paths matched only by trashed playlists (still referenced by no
     * active playlist). Safe set to offer for deletion when emptying trash.
     */
    fun trashOnlyAudioPaths(): Set<String> {
        val activePaths = _playlists.value
            .filterNot { it.trashed }
            .flatMap { pl -> pl.resolvedTracks.mapNotNull { it.match?.local?.filePath } }
            .toSet()
        val trashedPaths = _playlists.value
            .filter { it.trashed }
            .flatMap { pl -> pl.resolvedTracks.mapNotNull { it.match?.local?.filePath } }
            .toSet()
        return trashedPaths - activePaths
    }

    /**
     * Permanently remove trashed playlists. When [deleteUniqueAudio] is true,
     * also delete audio files that are **not** referenced by any remaining
     * playlist (and drop them from the library index + manual links).
     */
    fun emptyTrash(deleteUniqueAudio: Boolean = false) {
        scope.launch {
            val trashed = _playlists.value.filter { it.trashed }
            if (trashed.isEmpty()) {
                emit("Trash is empty")
                return@launch
            }
            val orphanPaths = if (deleteUniqueAudio) trashOnlyAudioPaths() else emptySet()
            val trashedIds = trashed.map { it.id }.toSet()
            _playlists.value = _playlists.value.filterNot { it.id in trashedIds }
            // Drop playback progress for removed playlists.
            playbackStore = playbackStore.copy(
                byPlaylist = playbackStore.byPlaylist - trashedIds,
                activePlaylistId = playbackStore.activePlaylistId
                    ?.takeIf { it !in trashedIds },
            )
            withContext(Dispatchers.IO) {
                repository.savePlaylists(_playlists.value)
                repository.savePlayback(playbackStore)
            }
            if (_ui.value.detailPlaylistId in trashedIds) closePlaylistDetail()

            var deletedFiles = 0
            if (deleteUniqueAudio && orphanPaths.isNotEmpty()) {
                // Re-check: path must still be absent from every remaining playlist.
                val stillActive = _playlists.value
                    .flatMap { pl -> pl.resolvedTracks.mapNotNull { it.match?.local?.filePath } }
                    .toSet()
                for (path in orphanPaths) {
                    if (path in stillActive) continue
                    val removedFile = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = java.io.File(path)
                            if (f.isFile) f.delete() else false
                        }.getOrDefault(false)
                    }
                    if (removedFile) deletedFiles++
                    _library.value = _library.value.filterNot { it.filePath == path }
                    _manualLinks.value = _manualLinks.value.let { links ->
                        var next = links
                        for ((plId, pins) in links.byPlaylist) {
                            for ((key, p) in pins) {
                                if (p == path) next = next.remove(plId, key)
                            }
                        }
                        next
                    }
                }
                withContext(Dispatchers.IO) {
                    repository.saveLibrary(_library.value)
                    repository.saveManualLinks(_manualLinks.value)
                }
            }
            emit(
                buildString {
                    append("Emptied trash (${trashed.size} playlist(s))")
                    if (deleteUniqueAudio) append(" · deleted $deletedFiles unique file(s)")
                },
            )
        }
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
        // Marks (skip / delete) are user intent — keep them across rematch.
        val priorMarks = playlist.resolvedTracks
            .mapNotNull { row -> row.mark?.let { Matcher.trackKey(row.remote) to it } }
            .toMap()
        fun applyMarks(rows: List<ResolvedPlaylistTrack>): List<ResolvedPlaylistTrack> =
            rows.map { row ->
                val key = Matcher.trackKey(row.remote)
                val m = priorMarks[key] ?: row.mark
                if (m == row.mark) row else row.copy(mark = m)
            }

        if (locals.isEmpty() && links.isEmpty()) {
            _playlists.value = _playlists.value.map {
                if (it.id == id) it.copy(
                    resolvedTracks = applyMarks(
                        playlist.remote.tracks.map { t -> ResolvedPlaylistTrack(t, null) },
                    ),
                ) else it
            }
        } else {
            val resolved = applyMarks(Matcher.matchPlaylist(playlist.remote, locals, threshold, links))
            _playlists.value = _playlists.value.map {
                if (it.id == id) it.copy(resolvedTracks = resolved) else it
            }
        }
        repository.savePlaylists(_playlists.value)
    }

    /** Cycle a row’s mark: none → SKIP → DELETE → none. */
    fun cyclePlaylistRowMark(playlistId: String, trackKey: String) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val current = playlist.resolvedTracks
            .find { Matcher.trackKey(it.remote) == trackKey }
            ?.mark
        val next = when (current) {
            null -> RowMark.SKIP
            RowMark.SKIP -> RowMark.DELETE
            RowMark.DELETE -> null
        }
        setPlaylistRowMark(playlistId, trackKey, next)
    }

    fun setPlaylistRowMark(playlistId: String, trackKey: String, mark: RowMark?) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(
                resolvedTracks = pl.resolvedTracks.map { row ->
                    if (Matcher.trackKey(row.remote) == trackKey) row.copy(mark = mark) else row
                },
            )
        }
        repository.savePlaylists(_playlists.value)
    }

    /**
     * Remove every row marked [RowMark.DELETE] from this playlist
     * (remote list + resolved rows so a later rematch won’t resurrect them).
     * Audio files are never deleted here — only playlist membership.
     */
    fun deleteMarkedTracks(playlistId: String) {
        scope.launch {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@launch
            val marked = playlist.resolvedTracks.filter { it.mark == RowMark.DELETE }
            if (marked.isEmpty()) {
                emit("No tracks marked for delete")
                return@launch
            }
            val keys = marked.map { Matcher.trackKey(it.remote) }.toSet()
            val newRemote = playlist.remote.copy(
                tracks = playlist.remote.tracks.filterNot { Matcher.trackKey(it) in keys },
            )
            val newResolved = playlist.resolvedTracks.filterNot { Matcher.trackKey(it.remote) in keys }
            _playlists.value = _playlists.value.map {
                if (it.id == playlistId) {
                    it.copy(remote = newRemote, resolvedTracks = newResolved)
                } else {
                    it
                }
            }
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("Removed ${marked.size} marked track(s) from “${playlist.name}”")
        }
    }

    // ── Manual file links (unmatched rows) ──────────────────────────

    /**
     * For every matched playlist row (optionally one [playlistId]), ensure a
     * **symlink** exists under the managed Slotify root pointing at the source
     * file in `~/Music/…` (or elsewhere). Copying Slotify then carries only
     * playlist tracks — use a copy mode that follows links (`cp -L`, rsync `-L`,
     * or “follow symbolic links”) so the phone gets real files.
     *
     * Already-real files in Slotify and existing correct links are left alone.
     * Rows marked [RowMark.DELETE] are skipped. Progress → [scanStatus].
     */
    fun stagePlaylistFilesAsSymlinks(playlistId: String? = null) {
        if (_scanStatus.value?.startsWith("Staging") == true) return
        scope.launch {
            val managed = com.slotify.app.data.defaultSlotifyMusicDir()
            val root = java.io.File(managed)
            withContext(Dispatchers.IO) {
                root.mkdirs()
                com.slotify.app.data.SLOTIFY_STAGING_DIRS.forEach { name ->
                    java.io.File(root, name).mkdirs()
                }
            }

            val playlists = if (playlistId != null) {
                _playlists.value.filter { it.id == playlistId }
            } else {
                _playlists.value
            }
            // Unique source paths → preferred basename (first wins).
            val wanted = LinkedHashMap<String, String>()
            for (pl in playlists) {
                for (row in pl.resolvedTracks) {
                    if (row.mark == RowMark.DELETE) continue
                    val path = row.match?.local?.filePath ?: continue
                    if (path in wanted) continue
                    val base = com.slotify.app.data.sanitizeSlotifyBaseName(
                        listOf(
                            row.remote.artistNames.ifBlank { null },
                            row.remote.name,
                        ).filterNotNull().joinToString(" - "),
                    )
                    wanted[path] = base
                }
            }
            if (wanted.isEmpty()) {
                _scanStatus.value = "No matched playlist files to stage"
                return@launch
            }

            var created = 0
            var already = 0
            var failed = 0
            var skippedSlotify = 0
            var n = 0
            withContext(Dispatchers.IO) {
                for ((srcPath, base) in wanted) {
                    n++
                    if (n % 10 == 0 || n == wanted.size) {
                        _scanStatus.value = "Staging links $n/${wanted.size}…"
                    }
                    val src = java.io.File(srcPath)
                    if (!src.isFile) {
                        failed++
                        continue
                    }
                    if (com.slotify.app.data.isUnderSlotifyRoot(srcPath, managed)) {
                        // Already a real (or linked) file in Slotify.
                        skippedSlotify++
                        already++
                        continue
                    }
                    val ext = src.extension.ifBlank { "bin" }
                    val dest = uniqueLinkDestination(root, base, ext, src)
                    when (linkStatus(dest, src)) {
                        LinkStatus.SAME -> {
                            already++
                        }
                        LinkStatus.BUSY -> {
                            // Real file occupies the name — leave it; try numbered name.
                            val alt = uniqueLinkDestination(root, base, ext, src, forceNumberFrom = 2)
                            if (linkStatus(alt, src) == LinkStatus.SAME) {
                                already++
                            } else if (createLink(alt, src)) {
                                created++
                            } else {
                                failed++
                            }
                        }
                        LinkStatus.OTHER -> {
                            // Different target already linked under this name — number it.
                            val alt = numberedDest(root, base, ext, src)
                            if (createLink(alt, src)) created++ else failed++
                        }
                        LinkStatus.MISSING -> {
                            if (createLink(dest, src)) created++ else failed++
                        }
                    }
                }
            }
            _scanStatus.value =
                "Staged $created symlink(s) into Slotify · $already already there · $failed failed"
            emit(
                "Slotify: +$created links · $already already present" +
                    if (failed > 0) " · $failed failed" else "",
            )
        }
    }

    private enum class LinkStatus { SAME, OTHER, BUSY, MISSING }

    private fun linkStatus(dest: java.io.File, src: java.io.File): LinkStatus {
        if (!dest.exists() && !java.nio.file.Files.isSymbolicLink(dest.toPath())) {
            return LinkStatus.MISSING
        }
        return try {
            if (java.nio.file.Files.isSymbolicLink(dest.toPath())) {
                val target = java.nio.file.Files.readSymbolicLink(dest.toPath()).toFile()
                if (target.canonicalFile == src.canonicalFile) LinkStatus.SAME else LinkStatus.OTHER
            } else if (dest.isFile) {
                if (dest.canonicalFile == src.canonicalFile) LinkStatus.SAME else LinkStatus.BUSY
            } else {
                LinkStatus.BUSY
            }
        } catch (_: Exception) {
            LinkStatus.BUSY
        }
    }

    private fun uniqueLinkDestination(
        root: java.io.File,
        base: String,
        ext: String,
        src: java.io.File,
        forceNumberFrom: Int? = null,
    ): java.io.File {
        if (forceNumberFrom == null) {
            val primary = java.io.File(root, "$base.$ext")
            if (!primary.exists() && !java.nio.file.Files.isSymbolicLink(primary.toPath())) {
                return primary
            }
        }
        return numberedDest(root, base, ext, src, start = forceNumberFrom ?: 2)
    }

    private fun numberedDest(
        root: java.io.File,
        base: String,
        ext: String,
        src: java.io.File,
        start: Int = 2,
    ): java.io.File {
        var i = start
        while (i < 1000) {
            val cand = java.io.File(root, "$base ($i).$ext")
            val st = linkStatus(cand, src)
            if (st == LinkStatus.SAME || st == LinkStatus.MISSING) return cand
            i++
        }
        return java.io.File(root, "$base (${src.nameWithoutExtension.takeLast(6)}).$ext")
    }

    private fun createLink(dest: java.io.File, src: java.io.File): Boolean = runCatching {
        if (dest.exists()) dest.delete()
        val link = dest.toPath()
        val target = src.toPath().toAbsolutePath().normalize()
        java.nio.file.Files.createSymbolicLink(link, target)
        true
    }.getOrDefault(false)

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

    /**
     * Prep a single matched row for a YouTube re-fetch:
     * 1. Unlink the pin for this row only.
     * 2. Drop the current file from the index when no other row/pin needs it.
     * 3. Delete the file from disk **only** when it lives under the managed
     *    Slotify directory and nothing else references it (never touch shared
     *    or external library files).
     * 4. Rematch this playlist so the row lands back in “To be sourced”.
     *
     * Call from a coroutine; opens nothing — UI then shows the search dialog.
     */
    suspend fun prepareRedoYoutubeMatch(playlistId: String, trackKey: String) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val row = playlist.resolvedTracks.find { Matcher.trackKey(it.remote) == trackKey }
        val path = row?.match?.local?.filePath

        _manualLinks.value = _manualLinks.value.remove(playlistId, trackKey)
        withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }

        if (path.isNullOrBlank()) {
            matchPlaylist(playlistId)
            return
        }

        val stillNeeded = pathReferencedElsewhere(
            path = path,
            exceptPlaylistId = playlistId,
            exceptTrackKey = trackKey,
        )
        if (!stillNeeded) {
            _library.value = _library.value.filterNot { it.filePath == path }
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }

            val managed = com.slotify.app.data.defaultSlotifyMusicDir().trimEnd('/')
            val underManaged = path == managed || path.startsWith("$managed/")
            if (underManaged) {
                val deleted = withContext(Dispatchers.IO) {
                    runCatching { java.io.File(path).delete() }.getOrDefault(false)
                }
                if (deleted) {
                    emit("Removed wrong file and re-opened for re-fetch")
                } else {
                    emit("Unlinked — file still on disk (could not delete)")
                }
            } else {
                emit("Unlinked external file (left on disk)")
            }
        } else {
            emit("Unlinked — file is still used by another entry")
        }
        matchPlaylist(playlistId)
    }

    /** True when [path] is still required by another pin or another playlist row. */
    private fun pathReferencedElsewhere(
        path: String,
        exceptPlaylistId: String,
        exceptTrackKey: String,
    ): Boolean {
        for ((plId, pins) in _manualLinks.value.byPlaylist) {
            for ((key, pinnedPath) in pins) {
                if (pinnedPath == path && !(plId == exceptPlaylistId && key == exceptTrackKey)) {
                    return true
                }
            }
        }
        for (pl in _playlists.value) {
            for (row in pl.resolvedTracks) {
                val rowPath = row.match?.local?.filePath ?: continue
                if (rowPath != path) continue
                val sameRow = pl.id == exceptPlaylistId &&
                    Matcher.trackKey(row.remote) == exceptTrackKey
                if (!sameRow) return true
            }
        }
        return false
    }

    // ── Playback ─────────────────────────────────────────────────────

    /**
     * Play a specific playlist row ([fromRowIndex] = original playlist index).
     * - Same row already loaded → toggle pause/resume.
     * - Any other row (including while another track is playing/paused) →
     *   start **that** row, never blindly resume a different track.
     */
    fun playPlaylist(playlistId: String, fromRowIndex: Int = 0) {
        val sameRowLoaded = _activePlaylistId.value == playlistId &&
            _nowPlaying.value != null &&
            queue.isNotEmpty() &&
            currentOriginalRowIndex(playlistId) == fromRowIndex
        if (sameRowLoaded) {
            if (player.snapshot.value.isPlaying) {
                persistPlaybackProgress()
                player.pause()
                emit("Paused — press play to resume")
            } else {
                player.play()
            }
            return
        }
        startPlaylist(playlistId, fromRowIndex)
    }

    /**
     * Play / stop toggle for a playlist card or detail header button.
     * - Playing this playlist → pause (persist for resume).
     * - Paused on this playlist → resume the **current** track.
     * - Otherwise start (or resume from persisted track index + position).
     */
    fun togglePlaylistPlay(playlistId: String, fromRowIndex: Int = 0) {
        val playingThis = _activePlaylistId.value == playlistId &&
            _nowPlaying.value != null &&
            player.snapshot.value.isPlaying
        if (playingThis) {
            persistPlaybackProgress()
            player.pause()
            emit("Paused — press play to resume")
            return
        }
        // Same playlist, paused with a queue → resume in place (playlist button).
        if (fromRowIndex == 0 &&
            _activePlaylistId.value == playlistId &&
            _nowPlaying.value != null &&
            queue.isNotEmpty() &&
            queueIndex in queue.indices
        ) {
            player.play()
            return
        }
        startPlaylist(playlistId, fromRowIndex)
    }

    /** Original playlist row index of the track currently in the queue, or null. */
    private fun currentOriginalRowIndex(playlistId: String): Int? {
        val key = queue.getOrNull(queueIndex)?.trackKey ?: return null
        val pl = _playlists.value.find { it.id == playlistId } ?: return null
        return pl.resolvedTracks
            .indexOfFirst { Matcher.trackKey(it.remote) == key }
            .takeIf { it >= 0 }
    }

    private fun startPlaylist(playlistId: String, fromRowIndex: Int = 0) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (playlist.trashed) {
            emit("Playlist is in the trash — restore it first")
            return
        }
        // Snapshot whatever was playing before we rebuild the queue.
        if (queue.isNotEmpty() && queueIndex in queue.indices && _activePlaylistId.value != null) {
            persistPlaybackProgress()
        }

        val playable = playlist.resolvedTracks.withIndex().mapNotNull { (index, row) ->
            if (row.mark == RowMark.SKIP || row.mark == RowMark.DELETE) return@mapNotNull null
            val match = row.match ?: return@mapNotNull null
            Triple(index, row, match)
        }
        if (playable.isEmpty()) {
            emit("No local matches in \"${playlist.name}\" yet — scan your library first")
            return
        }

        val saved = playbackStore.byPlaylist[playlistId]
        val useSavedOrder = fromRowIndex == 0 &&
            saved != null &&
            saved.orderKeys.isNotEmpty() &&
            saved.orderIndex in saved.orderKeys.indices
        val savedProgress = if (useSavedOrder) saved else null

        val keysToEntries = playable.associate { (_, row, match) ->
            Matcher.trackKey(row.remote) to QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(match.local.filePath),
                title = row.remote.name,
                artist = row.remote.artistNames.ifBlank { match.local.displayArtist },
                trackKey = Matcher.trackKey(row.remote),
            )
        }

        val orderedKeys: List<String> = when {
            savedProgress != null -> {
                val existing = savedProgress.orderKeys.filter { it in keysToEntries }
                val missing = keysToEntries.keys.filterNot { it in existing.toSet() }
                if (_shuffleEnabled.value && missing.isNotEmpty()) {
                    (existing + missing.shuffled())
                } else {
                    existing + missing
                }
            }
            else -> {
                val all = playable.map { (_, row, _) -> Matcher.trackKey(row.remote) }
                if (_shuffleEnabled.value) all.shuffled() else all
            }
        }
        if (orderedKeys.isEmpty()) {
            emit("No playable tracks in \"${playlist.name}\"")
            return
        }

        queue = orderedKeys.mapNotNull { keysToEntries[it] }
        val startIndex = if (savedProgress != null) {
            val key = savedProgress.orderKeys[savedProgress.orderIndex]
            queue.indexOfFirst { it.trackKey == key }.takeIf { it >= 0 } ?: 0
        } else {
            val orig = playable.indexOfFirst { it.first >= fromRowIndex }
            val origKey = playable.getOrNull(orig)?.let { Matcher.trackKey(it.second.remote) }
            origKey?.let { k -> queue.indexOfFirst { it.trackKey == k } }
                ?.takeIf { it >= 0 } ?: 0
        }
        val startPos = savedProgress?.positionMs?.coerceAtLeast(0L) ?: 0L
        _activePlaylistId.value = playlistId
        queueIndex = startIndex.coerceIn(0, queue.lastIndex)
        playAt(queueIndex, seekToMs = startPos)
        persistPlaybackProgress()
    }

    fun setShuffle(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        playbackStore = playbackStore.copy(shuffle = enabled)
        scope.launch { repository.savePlayback(playbackStore) }
        val plId = _activePlaylistId.value ?: return
        val currentKey = queue.getOrNull(queueIndex)?.trackKey
        val playlist = _playlists.value.find { it.id == plId } ?: return
        val keys = playlist.resolvedTracks
            .filter {
                it.mark != RowMark.SKIP && it.mark != RowMark.DELETE && it.match != null
            }
            .map { Matcher.trackKey(it.remote) }
        if (keys.isEmpty()) return
        val reordered = if (enabled) {
            val rest = keys.filter { it != currentKey }.shuffled()
            if (currentKey != null) listOf(currentKey) + rest else rest
        } else {
            val seq = keys
            if (currentKey != null && currentKey in seq) {
                val i = seq.indexOf(currentKey)
                seq.drop(i) + seq.take(i)
            } else {
                seq
            }
        }
        val byKey = queue.associateBy { it.trackKey }
        val newQueue = reordered.mapNotNull { byKey[it] }
        if (newQueue.isEmpty()) return
        queue = newQueue
        queueIndex = if (currentKey != null) {
            newQueue.indexOfFirst { it.trackKey == currentKey }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        persistPlaybackProgress()
    }

    /** Snapshot index + position into [playbackStore] and write playback.json. */
    private fun persistPlaybackProgress() {
        val plId = _activePlaylistId.value ?: return
        val idx = queueIndex
        if (idx !in queue.indices) return
        val position = player.snapshot.value.positionMs.coerceAtLeast(0L)
        val progress = PlaylistProgress(
            orderKeys = queue.map { it.trackKey },
            orderIndex = idx,
            positionMs = position,
        )
        playbackStore = playbackStore.copy(
            activePlaylistId = plId,
            shuffle = _shuffleEnabled.value,
            byPlaylist = playbackStore.byPlaylist + (plId to progress),
        )
        scope.launch { repository.savePlayback(playbackStore) }
    }

    /** Play the local library starting at [fromIndex] (search-filtered view passes its own index). */
    fun playLibrary(tracks: List<LocalTrack>, fromIndex: Int = 0) {
        if (tracks.isEmpty()) return
        persistPlaybackProgress()
        _activePlaylistId.value = null
        queue = tracks.map {
            QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(it.filePath),
                title = it.displayTitle,
                artist = it.displayArtist,
                trackKey = "lib:${it.filePath}",
            )
        }
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
        if (player.snapshot.value.isPlaying) {
            persistPlaybackProgress()
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        persistPlaybackProgress()
    }

    private fun advance(delta: Int, auto: Boolean) {
        if (queue.isEmpty()) return
        val target = queueIndex + delta
        if (auto && target > queue.lastIndex && _shuffleEnabled.value) {
            // Exhausted this shuffle cycle — reshuffle all tracks for a new pass.
            val byKey = queue.associateBy { it.trackKey }
            val newQueue = queue.map { it.trackKey }.shuffled().mapNotNull { byKey[it] }
            if (newQueue.isNotEmpty()) {
                queue = newQueue
                persistPlaybackProgress()
                playAt(0)
                emit("Shuffle cycle complete — starting a new pass")
                return
            }
        }
        if (target !in queue.indices) {
            if (auto) {
                persistPlaybackProgress()
                player.pause()
                // Keep queue + index so the user can resume from here.
            }
            return
        }
        playAt(target)
        persistPlaybackProgress()
    }

    private fun playAt(index: Int, seekToMs: Long = 0L) {
        val entry = queue.getOrNull(index) ?: return
        queueIndex = index
        _nowPlaying.value = NowPlaying(entry.title, entry.artist, entry.path)
        player.load(entry.path, startPaused = seekToMs > 0)
        if (seekToMs > 0) {
            scope.launch {
                repeat(40) {
                    delay(100)
                    val snap = player.snapshot.value
                    if (snap.durationMs > 0) {
                        player.seekTo(seekToMs.coerceAtMost(snap.durationMs))
                        if (seekToMs > 0 && !snap.isPlaying) {
                            // Loaded paused for resume — stay paused until Play.
                        }
                        return@launch
                    }
                }
                player.seekTo(seekToMs)
            }
        }
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
        persistPlaybackProgress()
        runCatching { repository.savePlayback(playbackStore) }
        player.release()
        scope.cancel()
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }
}
