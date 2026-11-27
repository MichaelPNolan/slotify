package com.slotify.app.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.slotify.app.data.AppFiles
import com.slotify.app.data.AppRepository
import com.slotify.app.data.AppSettings
import com.slotify.app.data.ManualLinks
import com.slotify.app.data.PlaybackStore
import com.slotify.app.data.PlaylistProgress
import com.slotify.app.data.Quarantine
import com.slotify.app.library.AUDIO_EXTENSIONS
import com.slotify.app.library.CoverArtCache
import com.slotify.app.library.CoverArtReader
import com.slotify.app.library.createCoverArtReader
import com.slotify.app.library.isSongFileNameMisnamed
import com.slotify.app.library.proposeSongFileName
import com.slotify.app.library.LibraryScanner
import com.slotify.app.library.createLibraryScanner
import com.slotify.app.library.writeAudioMetadata
import com.slotify.app.loudness.CorrectionMode
import com.slotify.app.loudness.analyseFileLoudness
import com.slotify.app.loudness.appLoudnessStore
import com.slotify.app.loudness.recommendedBoostDb
import com.slotify.app.loudness.supportsLoudnessBalance
import com.slotify.app.match.Matcher
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.PlaylistSource
import com.slotify.app.model.RemoteArtist
import com.slotify.app.model.RemotePlaylist
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.RowMark
import com.slotify.app.model.TrackMatch
import com.slotify.app.model.cycleMark
import com.slotify.app.platform.nowEpochMs
import com.slotify.app.platform.pickAudioFile
import com.slotify.app.platform.readBrowserSpDcCookie
import com.slotify.app.platform.hasSharedMusicWriteAccess
import com.slotify.app.platform.requestSharedMusicWriteAccess
import com.slotify.app.platform.clearYoutubeCookies
import com.slotify.app.platform.importYoutubeCookies
import com.slotify.app.platform.isYoutubeAuthFailure
import com.slotify.app.platform.pickYoutubeCookiesFile
import com.slotify.app.platform.youtubeAuthFailureMessage
import com.slotify.app.platform.youtubeCookiesConfigured
import com.slotify.app.platform.youtubeCookiesPathOrNull
import com.slotify.app.platform.youtubeCookiesStatusLabel
import com.slotify.app.platform.YoutubeCookiesImport
import com.slotify.app.playback.ArtistSession
import com.slotify.app.playback.ArtistSourceItem
import com.slotify.app.playback.AudioPlayer
import com.slotify.app.playback.PlaybackMode
import com.slotify.app.playback.PlayerEvent
import com.slotify.app.playback.createAudioPlayer
import com.slotify.app.playback.cycle
import com.slotify.app.playback.initialMode
import com.slotify.app.playback.isArtistMode
import com.slotify.app.playback.primaryArtistName
import com.slotify.app.playback.splitArtistList
import com.slotify.app.spotify.parseSpotifyPlaylistId
import com.slotify.app.spotify.PublicPlaylistHit
import com.slotify.app.spotify.SpotifyPublicSearch
import com.slotify.app.youtube.YoutubeQuery
import com.slotify.app.youtube.YoutubeRanking
import com.slotify.app.youtube.YoutubeSearchResult
import com.slotify.app.youtube.YoutubeSource
import com.slotify.app.youtube.createYoutubeSource
import com.slotify.app.youtube.preferredDownloadBaseName
import com.slotify.app.youtube.searchUntilScore
import com.slotify.app.youtube.youtubeStatusClearableWhenIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** A row pick paused for the "Reset all played already?" answer (S6). */
sealed interface PickAction {
    data class Playlist(val playlistId: String, val fromRowIndex: Int) : PickAction
    data class Library(val tracks: List<LocalTrack>, val index: Int) : PickAction
}

/** One fix-song-names case: original file + editable proposed name. */
data class FixNamesCase(
    val oldPath: String,
    val newPath: String,
    val currentName: String,
    val proposedName: String,
    /** Primary (first) tag artist — drives re-offer warnings for edits. */
    val primaryArtist: String = "",
)

/** Walk state for the fix-song-names mode (one case at a time). */
data class FixNamesSession(
    val cases: List<FixNamesCase>,
    val index: Int = 0,
    val renamedCount: Int = 0,
    val error: String? = null,
) {
    val current: FixNamesCase? get() = cases.getOrNull(index)
    val done: Boolean get() = index >= cases.size
}

/**
 * True when [pinPath] is not indexed yet under [have] (the index's file paths).
 * Pins are often stored in a different spelling than the index — e.g.
 * `/sdcard/Music/x` while the index holds the canonical
 * `/storage/emulated/0/Music/x` — so a raw-string check alone re-appended the
 * pin on every rematch and duplicated the library row (which crashed the
 * Library list's key uniqueness check).
 */
internal fun pinNeedsIndex(pinPath: String, have: Set<String>): Boolean {
    if (pinPath in have) return false
    val canonical = runCatching { java.io.File(pinPath).canonicalPath }.getOrDefault(pinPath)
    return canonical !in have
}

/**
 * True when [startPlaylist] should resume persisted progress instead of
 * starting the requested row. Only the playlist header's start passes
 * [fromRowTap] = false; an explicit row tap (even row 0) must always start
 * that exact row at position 0 — resuming saved progress silently loaded a
 * different track, paused, so tapping the first row looked like a dead tap
 * and playback-failure detection never fired.
 */
internal fun useSavedProgress(fromRowTap: Boolean, fromRowIndex: Int, hasSaved: Boolean): Boolean =
    !fromRowTap && fromRowIndex == 0 && hasSaved

/**
 * Central app controller: owns all state, performs IO/Spotify/matching work
 * on a background scope, and exposes StateFlows for the Compose UI.
 */
class AppState(
    private val repository: AppRepository = AppRepository(),
    private val scanner: LibraryScanner = createLibraryScanner(),
    private val coverArtReader: CoverArtReader = createCoverArtReader(),
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

    /**
     * App-private playlists, with fallback to `Music/Slotify/playlists.json`
     * (the portable copy users move to a phone). When music-dir import is
     * used, we persist into app storage and rematch once the library is ready.
     */
    private val playlistsFromMusicDir: Boolean
    private val _playlists: MutableStateFlow<List<AppPlaylist>>
    val playlists: StateFlow<List<AppPlaylist>>

    init {
        val fromApp = repository.loadPlaylists()
        if (fromApp.isNotEmpty()) {
            _playlists = MutableStateFlow(fromApp)
            playlistsFromMusicDir = false
        } else {
            val fromMusic = repository.loadPlaylistsFromMusicDir()
            _playlists = MutableStateFlow(fromMusic ?: emptyList())
            playlistsFromMusicDir = fromMusic != null && fromMusic.isNotEmpty()
            if (playlistsFromMusicDir) {
                // Persist into app data so the next cold start does not depend on Music.
                runCatching { repository.savePlaylists(_playlists.value) }
            }
        }
        playlists = _playlists.asStateFlow()
    }

    /** Playback-failed files held out of the index — see [quarantineFailedPlayback]. */
    private val quarantine = Quarantine(repository.loadQuarantine())

    private val _library = MutableStateFlow(
        repository.loadLibrary().filterNot { quarantine.contains(it.filePath) },
    )
    val library: StateFlow<List<LocalTrack>> = _library.asStateFlow()

    /**
     * App pins merged with portable pins from `Music/Slotify/manual_links.json`
     * (paths remapped for this device). Without these, rematch after a phone
     * import is fuzzy-only and drops high-confidence desktop pins.
     */
    private val _manualLinks = MutableStateFlow(
        repository.loadManualLinks().let { appPins ->
            val portable = runCatching { repository.loadManualLinksFromMusicDir() }.getOrNull()
            if (portable == null || portable.byPlaylist.isEmpty()) {
                appPins
            } else {
                appPins.mergedWith(portable)
            }
        },
    )
    /** User-pinned playlist track → file paths (survive rematch). */
    val manualLinks: StateFlow<ManualLinks> = _manualLinks.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _scanStatus = MutableStateFlow<String?>(null)
    val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

    private val _youtubeStatus = MutableStateFlow<String?>(null)
    /** YouTube fetch progress (download %, indexing…) for the Find dialog. */
    val youtubeStatus: StateFlow<String?> = _youtubeStatus.asStateFlow()

    private val _linkFillStatus = MutableStateFlow<String?>(null)
    /**
     * Progress for Link-file cascade (same playlist only): blue line under the
     * batch bar on Playlist detail — same style as [youtubeStatus].
     * Prefix `Link fill failed` → error color.
     */
    val linkFillStatus: StateFlow<String?> = _linkFillStatus.asStateFlow()

    private val _linkFillRunning = MutableStateFlow(false)
    val linkFillRunning: StateFlow<Boolean> = _linkFillRunning.asStateFlow()

    /** App-private loudness analysis + playlist mean profiles. */
    val loudnessStore = appLoudnessStore()

    private val _loudnessStatus = MutableStateFlow<String?>(null)
    /** Backfill progress (Playlist detail line). Terminal statuses start with “Loudness”. */
    val loudnessStatus: StateFlow<String?> = _loudnessStatus.asStateFlow()

    private var loudnessJob: Job? = null

    /** Fire-and-forget analysis for one new download (no status UI). */
    fun analyseLoudnessAsync(path: String) {
        if (!supportsLoudnessBalance()) return
        scope.launch(Dispatchers.Default) {
            val r = analyseFileLoudness(path) ?: return@launch
            withContext(Dispatchers.IO) { loudnessStore.putTrack(path, r) }
        }
    }

    private val _youtubeBatchRunning = MutableStateFlow(false)
    /** True while [batchFetchYoutube] is walking a playlist. */
    val youtubeBatchRunning: StateFlow<Boolean> = _youtubeBatchRunning.asStateFlow()

    /** In-flight batch fetch — cancelled by [stopBatchYoutube]. */
    private var youtubeBatchJob: Job? = null

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

    /** In-flight public search — cancelled when the user presses Search again. */
    private var playlistSearchJob: Job? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Embedded cover of the loaded track for the content backdrop; null = plain background. */
    private val _coverArt = MutableStateFlow<ImageBitmap?>(null)
    val coverArt: StateFlow<ImageBitmap?> = _coverArt.asStateFlow()

    /** Bounded path → decoded art so back/forward never re-reads a file. */
    private val coverArtCache = CoverArtCache()

    private val _volume = MutableStateFlow(_settings.value.volume.coerceIn(0f, 1f))
    /** App output volume 0..1 — exposed for the always-visible volume control. */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _loudnessBar = MutableStateFlow(LoudnessBar())
    /** Remedy applied to the current track — Neutral / Boost / Limiting (volume bar). */
    val loudnessBar: StateFlow<LoudnessBar> = _loudnessBar.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ── Playback queue ───────────────────────────────────────────────

    private var queue: List<QueueEntry> = emptyList()
    private var queueIndex: Int = -1

    private val _activePlaylistId = MutableStateFlow<String?>(null)
    /** Playlist currently owning the queue (for play/stop toggle + resume). */
    val activePlaylistId: StateFlow<String?> = _activePlaylistId.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    /** Kept in sync with [playbackMode] (true when mode != LINEAR) for existing UI. */
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.LINEAR)
    /** Linear / Shuffle / Artist (2) / Artist (all) — drives queue order (S3). */
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    /** Library paths played since launch / last confirmed reset — grey-out (S6). */
    private val _playedKeys = MutableStateFlow<Set<String>>(emptySet())
    val playedKeys: StateFlow<Set<String>> = _playedKeys.asStateFlow()

    /** Auto-scroll the Library to the playing song; a user drag releases it. */
    private val _libraryFollow = MutableStateFlow(false)
    val libraryFollow: StateFlow<Boolean> = _libraryFollow.asStateFlow()

    /**
     * Bumped on every title-tap so a re-tap while already following still
     * re-runs the Library scroll (StateFlow true→true would not emit).
     */
    private val _libraryFollowTick = MutableStateFlow(0)
    val libraryFollowTick: StateFlow<Int> = _libraryFollowTick.asStateFlow()

    /** Bumped when title-tap should re-center the **playlist** detail list. */
    private val _playlistFollowTick = MutableStateFlow(0)
    val playlistFollowTick: StateFlow<Int> = _playlistFollowTick.asStateFlow()

    /** True when the now-playing song is a matched row of [playlistId]. */
    fun nowPlayingIsInPlaylist(playlistId: String): Boolean {
        val np = _nowPlaying.value ?: return false
        val playlist = _playlists.value.find { it.id == playlistId } ?: return false
        return playlist.resolvedTracks.any { row ->
            val local = row.match?.local?.filePath
            val byPath = local != null && (
                local == np.path ||
                    com.slotify.app.data.resolveSymlinkTarget(local) == np.path
                )
            val byTitle = row.remote.name == np.title &&
                (np.artist.isBlank() || np.artist == row.remote.artistNames ||
                    row.remote.artistNames.contains(np.artist))
            byPath || byTitle
        }
    }

    /**
     * Player-bar title tap: highlight the song in the current playlist when
     * it belongs there; otherwise fall back to Library follow.
     */
    fun engageCurrentFollow() {
        val plId = _activePlaylistId.value
        if (preferPlaylistFollow(
                hasNowPlaying = _nowPlaying.value != null,
                activePlaylistId = plId,
                nowPlayingInActivePlaylist = plId != null && nowPlayingIsInPlaylist(plId),
            )
        ) {
            _playlistFollowTick.value++
            if (_ui.value.tab != Tab.PLAYLISTS || _ui.value.detailPlaylistId != plId) {
                openPlaylist(plId!!)
            }
            return
        }
        engageLibraryFollow()
    }

    /** Player-bar title tap (library-only path): lock follow, jump to Library. */
    fun engageLibraryFollow() {
        _libraryFollow.value = true
        _libraryFollowTick.value++
        // Show the list so the scroll is visible (no-op if already on Library).
        if (_ui.value.tab != Tab.LIBRARY || _ui.value.detailPlaylistId != null) {
            selectTab(Tab.LIBRARY)
        }
    }

    /** User dragged the library list — stop auto-following until re-engaged. */
    fun releaseLibraryFollow() {
        _libraryFollow.value = false
    }

    /** Artist-mode state; (re)built lazily, dropped on source/mode changes. */
    private var artistSession: ArtistSession? = null
    /** Library snapshot used for artist items + LINEAR/SHUFFLE reorders. */
    private var librarySource: List<LocalTrack> = emptyList()

    private var playbackStore: PlaybackStore = repository.loadPlayback()

    init {
        // First run: seed suggested library roots (persisted empty → platform defaults).
        if (_settings.value.libraryRoots.isEmpty()) {
            updateSettings { it.copy(libraryRoots = com.slotify.app.data.defaultLibraryRoots()) }
        }
        _playbackMode.value = playbackStore.initialMode()
        _shuffleEnabled.value = _playbackMode.value != PlaybackMode.LINEAR
        _activePlaylistId.value = playbackStore.activePlaylistId
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    PlayerEvent.Completed -> advance(1, auto = true)
                    is PlayerEvent.Failed -> {
                        _messages.tryEmit(event.message)
                        quarantineFailedPlayback()
                    }
                }
            }
        }
        // Custom threshold / correction changes must hit the *current* track
        // without requiring a restart — playAt alone would leave a stale limiter.
        scope.launch {
            loudnessStore.revision.collect {
                val path = _nowPlaying.value?.path ?: return@collect
                applyLoudnessToPlayer(path)
            }
        }
        // Periodic resume snapshot while playing (position + index).
        scope.launch {
            while (true) {
                delay(4_000)
                if (player.snapshot.value.isPlaying) persistPlaybackProgress()
            }
        }
        // Cover backdrop: follow the loaded track. Never throws into playback;
        // a missing/corrupt picture is null → plain background (spec S4/S5).
        scope.launch {
            nowPlaying
                .map { it?.path }
                .distinctUntilChanged()
                .collect { path ->
                    if (path == null) {
                        _coverArt.value = null
                        return@collect
                    }
                    coverArtCache[path]?.let { cached ->
                        _coverArt.value = cached
                        return@collect
                    }
                    val art = withContext(Dispatchers.IO) {
                        coverArtReader.readArt(path)?.let { bytes ->
                            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
                        }
                    }
                    if (art != null) coverArtCache.put(path, art)
                    // Stale-result guard: only publish if this is still the current track.
                    if (nowPlaying.value?.path == path) _coverArt.value = art
                }
        }
        // Cold start with no index yet (first run or cleared cache): index now,
        // so the library is ready without requiring a manual Scan click.
        if (_library.value.isEmpty() && _settings.value.libraryRoots.isNotEmpty()) {
            scanLibrary()
        } else if (playlistsFromMusicDir && _library.value.isNotEmpty() && _playlists.value.isNotEmpty()) {
            // Playlists arrived from Music/Slotify — rematch against local files
            // (desktop paths in the JSON won’t resolve on the phone).
            scope.launch { rematchAll() }
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

    // ── YouTube cookies.txt (NOT Spotify browser sp_dc) ──────────────

    private val _youtubeCookiesStatus = MutableStateFlow(youtubeCookiesStatusLabel())
    /** Settings status line — never cookie contents. */
    val youtubeCookiesStatus: StateFlow<String> = _youtubeCookiesStatus.asStateFlow()

    private fun refreshYoutubeCookiesStatus() {
        _youtubeCookiesStatus.value = youtubeCookiesStatusLabel()
    }

    /** SAF/desktop pick → private copy. Replaces any previous cookies file. */
    fun importYoutubeCookiesFromPicker() {
        scope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching { pickYoutubeCookiesFile() }.getOrNull()
            }
            if (source.isNullOrBlank()) {
                // User cancelled — keep existing status.
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { importYoutubeCookies(source) }
                    .getOrElse { e -> YoutubeCookiesImport.Failed(e.message ?: "import failed") }
            }
            when (result) {
                is YoutubeCookiesImport.Ok -> {
                    refreshYoutubeCookiesStatus()
                    emit("YouTube cookies imported (${result.byteCount} bytes) — contents hidden")
                }
                is YoutubeCookiesImport.Failed -> {
                    // Do not echo file paths that might include private storage layout beyond reason.
                    emit("YouTube cookies import failed: ${result.reason}")
                }
                YoutubeCookiesImport.Cancelled -> Unit
            }
        }
    }

    /** Delete the private cookies copy (yt-dlp then runs unauthenticated). */
    fun clearYoutubeCookiesFromSettings() {
        scope.launch {
            withContext(Dispatchers.IO) { clearYoutubeCookies() }
            refreshYoutubeCookiesStatus()
            emit("YouTube cookies cleared — downloads are unauthenticated again")
        }
    }

    /** Absolute private path for yt-dlp `cookiefile` (null = unauthenticated). */
    fun youtubeCookiesPathForYtDlp(): String? = youtubeCookiesPathOrNull()

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
            // Corrupt (quarantined) files stay out of the index; a replaced or
            // fixed copy (size/mtime changed) rehabilitates and re-enters.
            val quarantineSizeBefore = quarantine.all.size
            val scannable = tracks.filterNot { quarantine.shouldSkip(it.filePath) }
            if (quarantine.all.size != quarantineSizeBefore) {
                scope.launch { withContext(Dispatchers.IO) { repository.saveQuarantine(quarantine.all) } }
            }
            val priorMarks = _library.value.associate { it.filePath to it.mark }
            val marked = scannable.map { t -> priorMarks[t.filePath]?.let { m -> t.copy(mark = m) } ?: t }
            _library.value = marked
            repository.saveLibrary(marked)
            _scanStatus.value = "Indexed ${marked.size} file(s)"
            if (_playlists.value.isNotEmpty()) {
                _scanStatus.value = "Matching ${_playlists.value.size} playlist(s)…"
                rematchAll()
                _scanStatus.value = "Indexed ${marked.size} file(s) · matches refreshed"
            }
        }.onFailure { e ->
            _scanStatus.value = "Scan failed: ${e.message}"
        }
    }

    // ── YouTube fetch (unmatched tracks → local file) ────────────────

    /**
     * Music folder from Settings (`libraryRoots` first entry), e.g. `/sdcard/Music`
     * on the phone. Falls back to the platform default root.
     */
    fun musicRootFromSettings(): String {
        val fromSettings = _settings.value.libraryRoots
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .firstOrNull()
        if (fromSettings != null) return fromSettings
        return com.slotify.app.data.defaultLibraryRoots()
            .firstOrNull()?.trim()?.trimEnd('/')
            ?: com.slotify.app.data.defaultSlotifyMusicDir().substringBeforeLast('/')
    }

    /**
     * Staging dir for raw YouTube downloads: `<settings music root>/downloads`
     * (e.g. `/sdcard/Music/downloads` on the phone — **not** a hardcoded
     * Slotify subfolder). Creates `downloads` if missing. Final files are
     * promoted to the music root itself so the scanner finds them under the
     * configured library root.
     */
    fun youtubeDownloadDir(): String {
        val musicRoot = musicRootFromSettings()
        val roots = _settings.value.libraryRoots
        val covered = roots.any { root ->
            val r = root.trimEnd('/')
            musicRoot == r || musicRoot.startsWith("$r/")
        }
        if (!covered) {
            updateSettings { it.copy(libraryRoots = it.libraryRoots + musicRoot) }
        }

        fun probe(dir: java.io.File): String? = runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            if (!dir.isDirectory) return null
            val f = java.io.File(dir, ".slotify-write-test")
            f.writeText("ok")
            f.delete()
            dir.absolutePath
        }.getOrNull()

        val downloads = java.io.File(musicRoot, "downloads")
        val candidates = listOf(
            downloads,
            // Scoped storage may block subdirs — fall back to the music root itself.
            java.io.File(musicRoot),
        )
        candidates.firstNotNullOfOrNull { probe(it) }?.let { return it }

        // EPERM / scoped storage: READ_MEDIA_AUDIO is not a write grant.
        // Launch the system UI (All files access / WRITE_EXTERNAL_STORAGE).
        if (!hasSharedMusicWriteAccess()) {
            requestSharedMusicWriteAccess()
            error(
                "Storage write access needed for $musicRoot — enable “All files access” " +
                    "for Slotify in the Settings page that just opened, then retry the download.",
            )
        }
        error(
            "Music folder not writable: $musicRoot. " +
                "Allow All files access for Slotify (Settings → Apps → Slotify → Permissions), then retry.",
        )
    }

    /**
     * Move a finished download out of `<music>/downloads` into the music root
     * under its final `Artist - Title.ext` name. Already at the root → no-op.
     */
    private fun promoteFromStaging(stagedPath: String): String {
        val musicRoot = musicRootFromSettings()
        val staged = java.io.File(stagedPath)
        if (!staged.isFile) return stagedPath
        val downloads = java.io.File(musicRoot, "downloads")
        val conversions = java.io.File(musicRoot, "conversions")
        val inStaging = staged.parentFile?.let { p ->
            p.absolutePath == downloads.absolutePath || p.absolutePath == conversions.absolutePath
        } == true
        if (!inStaging) return staged.absolutePath

        val dest = java.io.File(musicRoot, staged.name)
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
            emit("YouTube fetch unavailable — yt-dlp runtime failed to start")
            return
        }
        scope.launch {
            _youtubeStatus.value = "Starting download…"
            val ok = downloadIndexAndPin(result, playlistId, remoteTrack)
            if (!ok) {
                // Keep the failure on youtubeStatus so the Find dialog shows it
                // in-place — a snackbar alone renders behind the AlertDialog.
                if (_youtubeStatus.value.isNullOrBlank() ||
                    _youtubeStatus.value?.startsWith("Starting download") == true
                ) {
                    _youtubeStatus.value = "Download failed — see message"
                }
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
            // Brief hold so the dialog can show Done, then re-enable picking.
            // No snackbar here either — it would sit under the Find dialog.
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
     * best ranked result scores **≥ [minScore]** (defaults to
     * [AppSettings.youtubeBatchMinScore], 0.85), download + pin it. Rows below
     * the threshold stay in “To be sourced”.
     *
     * While running, [stopBatchYoutube] cancels the job (same button toggles).
     */
    fun batchFetchYoutube(
        playlistId: String,
        minScore: Double = _settings.value.youtubeBatchMinScore,
    ) {
        if (!youtube.isSupported) {
            emit("YouTube fetch unavailable — yt-dlp runtime failed to start")
            return
        }
        if (_youtubeBatchRunning.value) {
            emit("A YouTube batch is already running — press Stop")
            return
        }
        val job = scope.launch {
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
                    // Cooperative stop between rows (suspend points also cancel).
                    ensureActive()
                    val n = i + 1
                    _youtubeStatus.value = "Batch $n/${targets.size}: searching “${remote.name}”…"
                    val ranked = runCatching {
                        // Keep trying fallback phrasings until best score ≥ minScore
                        // (or every variant is exhausted) — first non-empty query
                        // alone often ranks below the bar.
                        val attempt = searchUntilScore(
                            youtube = youtube,
                            baseQuery = YoutubeQuery.forTrack(remote),
                            track = remote,
                            minScore = minScore,
                        )
                        if (attempt.error != null && attempt.results.isEmpty()) throw attempt.error
                        YoutubeRanking.rank(attempt.results, remote)
                    }.getOrElse { e ->
                        ensureActive()
                        failed++
                        emit("Search failed for “${remote.name}”: ${e.message}")
                        return@forEachIndexed
                    }
                    ensureActive()
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
            } catch (e: CancellationException) {
                _youtubeStatus.value = "Batch stopped"
                // Don't leave “Batch stuck” forever — Find must accept manual downloads.
                kotlinx.coroutines.delay(4_000)
                if (_youtubeStatus.value == "Batch stopped") {
                    _youtubeStatus.value = null
                }
                throw e
            } finally {
                _youtubeBatchRunning.value = false
                youtubeBatchJob = null
            }
        }
        youtubeBatchJob = job
    }

    /** Cancel an in-flight [batchFetchYoutube] (same button as start). */
    fun stopBatchYoutube() {
        val job = youtubeBatchJob ?: return
        if (!job.isActive) return
        _youtubeStatus.value = "Stopping batch…"
        job.cancel()
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
        // Prefer Spotify artist/title for the on-disk name when the row is known —
        // YouTube title/channel often fails fuzzy match against the playlist.
        val preferredBase = remoteTrack?.let { preferredDownloadBaseName(it) }
        // First ~20 chars of the *source* media title on Downloading lines (T94).
        val sourceLabel = result.title.trim().take(20).let { t ->
            if (result.title.trim().length > 20) "$t…" else t
        }
        val path = runCatching {
            youtube.download(
                result = result,
                targetDir = youtubeDownloadDir(),
                onProgress = { line ->
                    // “Starting download of <file>…” comes from the platform
                    // with the exact basename it passes to yt-dlp — pass through.
                    _youtubeStatus.value = when {
                        line.startsWith("Starting download") -> line
                        line.startsWith("Downloading") ->
                            "Downloading “$sourceLabel”${line.removePrefix("Downloading")}"
                        else -> line
                    }
                },
                baseName = preferredBase,
            ).let { promoteFromStaging(it) }
        }.getOrElse { e ->
            // Status only — a snackbar would render *behind* Find-on-YouTube.
            // Auth failures get dedicated copy; never log/include cookie path.
            val chain = generateSequence(e) { it.cause }
                .map { it.message ?: it::class.java.simpleName }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
            _youtubeStatus.value = when {
                isYoutubeAuthFailure(chain) ->
                    youtubeAuthFailureMessage(youtubeCookiesConfigured())
                else -> "Download failed: " + (chain.take(400).ifBlank {
                    e.message ?: e::class.java.simpleName
                })
            }
            return false
        }

        _youtubeStatus.value = "Indexing new file…"
        val finalPath = withContext(Dispatchers.IO) {
            retitleDownloadedFile(path, remoteTrack, preferredBase)
        }
        val local = withContext(Dispatchers.IO) { scanner.readTrack(finalPath) }
        if (local != null && _library.value.none { it.filePath == local.filePath }) {
            _library.value = _library.value + local
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
            if (supportsLoudnessBalance()) analyseLoudnessAsync(finalPath)
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
                // A fresh download is the manual replacement — release the
                // corrupt-reset hold and rehabilitate a quarantined path.
                clearManualHold(playlistId, Matcher.trackKey(remoteTrack))
                if (quarantine.contains(local.filePath)) {
                    quarantine.remove(local.filePath)
                    withContext(Dispatchers.IO) { repository.saveQuarantine(quarantine.all) }
                }
            }
            matchPlaylist(playlistId)
        }
        return true
    }

    /**
     * After download: overwrite tags with Spotify metadata (yt-dlp embed uses
     * YouTube title/channel) and rename to `Artist - Title` when we know the row.
     * Returns the path to index (may differ after rename).
     */
    private suspend fun retitleDownloadedFile(
        path: String,
        remoteTrack: RemoteTrack?,
        preferredBase: String?,
    ): String {
        if (remoteTrack == null) return path
        val src = java.io.File(path)
        if (!src.isFile) return path

        writeAudioMetadata(
            path = src.absolutePath,
            title = remoteTrack.name,
            artist = remoteTrack.artistNames.ifBlank { null },
            album = remoteTrack.albumName,
            isrc = remoteTrack.isrc,
        )

        val base = preferredBase ?: return src.absolutePath
        if (src.nameWithoutExtension == base) return src.absolutePath
        val dest = java.io.File(src.parentFile, "$base.${src.extension}")
        if (dest.absolutePath == src.absolutePath) return src.absolutePath
        if (dest.exists()) {
            // Same final name already present — keep this file as a numbered take.
            val alt = java.io.File(src.parentFile, "$base (2).${src.extension}")
            if (!alt.exists() && src.renameTo(alt)) return alt.absolutePath
            return src.absolutePath
        }
        return if (src.renameTo(dest)) dest.absolutePath else src.absolutePath
    }

    /** Clear a finished YouTube status so the Find dialog can start another fetch. */
    fun clearYoutubeStatusIfIdle() {
        val s = _youtubeStatus.value ?: return
        if (youtubeStatusClearableWhenIdle(s)) {
            _youtubeStatus.value = null
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
     * Search public Spotify playlists by free-text (artist, genre, …).
     * Results are metadata only; pick one to import via the embed path.
     *
     * Pressing Search while a run is in progress **cancels** the old job and
     * starts over with the current field text (button never greys out).
     */
    fun searchPublicPlaylists(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            playlistSearchJob?.cancel()
            playlistSearchJob = null
            _playlistSearchResults.value = emptyList()
            _playlistSearchStatus.value = null
            _playlistSearchRunning.value = false
            return
        }
        // Supersede any in-flight search with the new query.
        playlistSearchJob?.cancel()
        val job = scope.launch {
            _playlistSearchRunning.value = true
            _playlistSearchStatus.value = "Searching public playlists for “$q”…"
            try {
                val outcome = SpotifyPublicSearch().searchPlaylists(q, limit = 20)
                if (!isActive) return@launch
                val notes = outcome.engineNotes.joinToString(" · ")
                _playlistSearchResults.value = outcome.hits
                _playlistSearchStatus.value = when {
                    outcome.hits.isNotEmpty() ->
                        "${outcome.hits.size} public playlist(s) — tap Import"
                    outcome.allFailed ->
                        "Search failed — $notes"
                    outcome.enginesFailed > 0 && outcome.hits.isEmpty() ->
                        "No results — $notes"
                    else ->
                        "No public playlists for “$q” — $notes"
                }
            } catch (e: CancellationException) {
                // Replaced by a newer Search press — leave UI to the new job.
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch
                val msg = e.message ?: e::class.simpleName ?: "unknown error"
                _playlistSearchResults.value = emptyList()
                _playlistSearchStatus.value = when {
                    msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("timed out", ignoreCase = true) ->
                        "Search timed out — press Search to retry “$q”"
                    else -> "Search failed: $msg"
                }
            } finally {
                if (isActive) {
                    _playlistSearchRunning.value = false
                }
            }
        }
        playlistSearchJob = job
    }

    fun clearPlaylistSearch() {
        playlistSearchJob?.cancel()
        playlistSearchJob = null
        _playlistSearchResults.value = emptyList()
        _playlistSearchStatus.value = null
        _playlistSearchRunning.value = false
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
                // Pull portable pins for this id (if exported to Music/Slotify)
                // before rematch — search import alone is fuzzy-only.
                mergePortablePinsForPlaylist(playlist.id)
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

    /**
     * Merge pins for [playlistId] from `Music/Slotify/manual_links.json`
     * (paths remapped for this device) into app pin storage.
     */
    private suspend fun mergePortablePinsForPlaylist(playlistId: String) {
        val portable = withContext(Dispatchers.IO) {
            runCatching { repository.loadManualLinksFromMusicDir() }.getOrNull()
        } ?: return
        val pins = portable.linksFor(playlistId)
        if (pins.isEmpty()) return
        var next = _manualLinks.value
        for ((key, path) in pins) {
            next = next.put(playlistId, key, path)
        }
        _manualLinks.value = next
        withContext(Dispatchers.IO) { repository.saveManualLinks(next) }
    }

    /**
     * Import playlist JSON from a file path or SAF `content://` URI.
     * Accepts full `List<AppPlaylist>` export and loose single-playlist JSON.
     */
    fun importFromJsonFile(path: String) {
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                com.slotify.app.data.readExternalTextOrNull(path)
            }
            if (content == null) {
                emit(
                    "Cannot read: $path — on Android, non-media files under " +
                        "/sdcard need the Pick file button (system picker), not a raw path.",
                )
                return@launch
            }
            runCatching { repository.parsePlaylistJsonFile(content) }
                .onSuccess { incoming ->
                    if (incoming.isEmpty()) {
                        emit("No playlists found in file")
                        return@onSuccess
                    }
                    _playlists.value = mergePlaylists(_playlists.value, incoming)
                    withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
                    incoming.forEach { matchPlaylist(it.id) }
                    emit(
                        if (incoming.size == 1) {
                            "Imported \"${incoming[0].name}\" " +
                                "(${incoming[0].remote.tracks.size} tracks)"
                        } else {
                            "Imported ${incoming.size} playlists from file"
                        },
                    )
                    if (incoming.size == 1) openPlaylist(incoming[0].id)
                }
                .onFailure { e ->
                    emit("Not a valid playlist JSON: ${e.message}")
                }
        }
    }

    /**
     * System file picker → import. Works on Android (SAF content URI) and
     * desktop (absolute path). Preferred over typing a /sdcard path.
     */
    fun pickAndImportPlaylistJson() {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching { com.slotify.app.platform.pickPlaylistJsonFile() }.getOrNull()
            }
            if (picked.isNullOrBlank()) {
                emit("No file selected")
                return@launch
            }
            importFromJsonFile(picked)
        }
    }

    /**
     * Import a playlist JSON by filename (or absolute path / content URI).
     * Relative names resolve under the Music root first (e.g. `/sdcard/Music`).
     */
    fun importPlaylistJsonByName(fileName: String) {
        val name = fileName.trim()
        if (name.isEmpty()) {
            emit("Enter a JSON filename, full path, or use Pick file")
            return
        }
        if (name.startsWith("content://")) {
            importFromJsonFile(name)
            return
        }
        val candidates = buildList {
            if (name.startsWith("/") || name.contains(":\\")) {
                add(name)
                // Common Android alias if user typed /storage/emulated/0/…
                if (name.startsWith("/storage/emulated/0/")) {
                    add("/sdcard/" + name.removePrefix("/storage/emulated/0/"))
                }
                if (name.startsWith("/sdcard/")) {
                    add("/storage/emulated/0/" + name.removePrefix("/sdcard/"))
                }
            } else {
                com.slotify.app.data.portableJsonSearchDirs().forEach { dir ->
                    add(dir.trimEnd('/') + "/" + name)
                }
                val home = System.getProperty("user.home") ?: ""
                if (home.isNotBlank()) add(home.trimEnd('/') + "/" + name)
            }
        }
        val diagnostics = candidates.joinToString("\n") { p ->
            val f = java.io.File(p)
            val exists = runCatching { f.exists() }.getOrDefault(false)
            val isFile = runCatching { f.isFile }.getOrDefault(false)
            val canRead = runCatching { f.canRead() }.getOrDefault(false)
            "$p (exists=$exists file=$isFile read=$canRead)"
        }
        val resolved = candidates.firstOrNull { p ->
            runCatching { java.io.File(p).isFile && java.io.File(p).canRead() }.getOrDefault(false)
        }
        if (resolved == null) {
            emit(
                "Cannot open file. Android scoped storage often blocks raw /sdcard paths " +
                    "for .json — use Pick file instead.\n$diagnostics",
            )
            return
        }
        importFromJsonFile(resolved)
    }

    /**
     * Re-read portable `playlists.json` (Music root first, then Slotify) and
     * merge into the app — used after copying files to a phone.
     */
    fun importPlaylistsFromMusicDir() {
        scope.launch {
            val incoming = withContext(Dispatchers.IO) {
                repository.loadPlaylistsFromMusicDir()
            }
            if (incoming.isNullOrEmpty()) {
                emit(
                    "No playlists.json in " +
                        com.slotify.app.data.portableJsonSearchDirs().joinToString(),
                )
                return@launch
            }
            // Refresh all portable pins (remapped) before rematch.
            val portablePins = withContext(Dispatchers.IO) {
                runCatching { repository.loadManualLinksFromMusicDir() }.getOrNull()
            }
            if (portablePins != null && portablePins.byPlaylist.isNotEmpty()) {
                _manualLinks.value = _manualLinks.value.mergedWith(portablePins)
                withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            }
            _playlists.value = mergePlaylists(_playlists.value, incoming)
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            _scanStatus.value = "Imported ${incoming.size} playlist(s); matching…"
            rematchAll()
            _scanStatus.value = "Imported ${incoming.size} playlist(s)"
            emit("Imported ${incoming.size} playlist(s)")
        }
    }

    /** Copy current playlists + pins to the Music root for phone transfer. */
    fun exportPlaylistsToMusicDir() {
        scope.launch {
            val count = _playlists.value.size
            if (count == 0) {
                emit("No playlists to export")
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.savePlaylistsToMusicDir(_playlists.value)
                    repository.saveManualLinksToMusicDir(_manualLinks.value)
                }
            }.onSuccess {
                val dir = com.slotify.app.data.portableJsonExportDir()
                emit("Exported $count playlist(s) + match pins to $dir")
                _importStatus.value = "Exported $count playlist(s) + manual_links.json → $dir"
            }.onFailure { e ->
                emit("Export failed: ${e.message}")
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

    private suspend fun rematchAll() {
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

    private suspend fun matchPlaylist(id: String) {
        val playlist = _playlists.value.firstOrNull { it.id == id } ?: return
        var locals = _library.value
        val threshold = _settings.value.matchThreshold
        val links = _manualLinks.value.linksFor(id)

        // Pinned files can fall out of the index (scan outside roots, file
        // moved). Re-read existing ones so pins never silently degrade;
        // drop pins whose file is gone so the row can re-match honestly.
        if (links.isNotEmpty()) {
            val have = locals.mapTo(HashSet()) { it.filePath }
            var added = false
            var dropped = false
            for (path in links.values.distinct()) {
                if (!pinNeedsIndex(path, have)) continue
                if (!java.io.File(path).isFile) {
                    _manualLinks.value = _manualLinks.value.let { l ->
                        var next = l
                        for ((plId, pins) in l.byPlaylist) {
                            for ((key, p) in pins) {
                                if (p == path) next = next.remove(plId, key)
                            }
                        }
                        next
                    }
                    dropped = true
                    continue
                }
                val t = runCatching { scanner.readTrack(path) }.getOrNull() ?: continue
                have.add(t.filePath)
                locals = locals + t
                added = true
            }
            if (added) {
                _library.value = locals
                withContext(Dispatchers.IO) { repository.saveLibrary(locals) }
            }
            if (dropped) {
                withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            }
        }

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
            val resolved = Matcher.applyHolds(
                applyMarks(Matcher.matchPlaylist(playlist.remote, locals, threshold, links)),
                playlist.heldTrackKeys,
            )
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
        setPlaylistRowMark(playlistId, trackKey, current.cycleMark())
    }

    /** Cycle a library row's mark by file path; persists the index. */
    fun cycleLibraryRowMark(filePath: String) {
        _library.value = _library.value.map { t ->
            if (t.filePath == filePath) t.copy(mark = t.mark.cycleMark()) else t
        }
        scope.launch {
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
        }
    }

    /** Short UI message for controls without a status line (S9 Find current). */
    fun showMessage(text: String) = emit(text)

    /** Delete every library row marked DELETE: files off disk, rows out of
     *  the index, then rematch playlists against what remains. */
    fun deleteMarkedLibraryFiles() {
        val marked = _library.value.filter { it.mark == RowMark.DELETE }
        if (marked.isEmpty()) {
            showMessage("No library rows marked for deletion")
            return
        }
        scope.launch {
            _scanStatus.value = "Deleting ${marked.size} marked file(s)…"
            var deleted = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (t in marked) {
                    if (t.filePath.startsWith("content://")) {
                        failed++ // SAF document URIs need a DocumentsContract delete.
                        continue
                    }
                    val ok = runCatching { java.io.File(t.filePath).delete() }.getOrDefault(false)
                    if (ok) deleted++ else failed++
                }
            }
            val gone = marked.map { it.filePath }.toSet()
            _library.value = _library.value.filterNot { it.filePath in gone }
            withContext(Dispatchers.IO) { repository.saveLibrary(_library.value) }
            if (_playlists.value.isNotEmpty()) {
                rematchAll()
            }
            _scanStatus.value = when {
                failed == 0 -> "Deleted $deleted marked file(s)"
                deleted == 0 -> "Delete failed for $failed file(s) — check storage access"
                else -> "Deleted $deleted file(s); $failed failed — check storage access"
            }
        }
    }

    // ── Fix song names (one case at a time) ─────────────────────────

    private val _fixNames = MutableStateFlow<FixNamesSession?>(null)
    /** Non-null → the fix-song-names dialog is open with a live walk. */
    val fixNames: StateFlow<FixNamesSession?> = _fixNames.asStateFlow()

    /** Snapshot every misnamed library file (artist known, prefix missing). */
    fun beginFixSongNames() {
        // Clear broken links first so they're never proposed as renames.
        healMissingMatches()
        val cases = _library.value.mapNotNull { t ->
            val artist = t.artist?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = t.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (t.filePath.startsWith("content://")) return@mapNotNull null
            val file = java.io.File(t.filePath)
            if (!file.isFile) return@mapNotNull null
            if (!isSongFileNameMisnamed(file.nameWithoutExtension, artist)) return@mapNotNull null
            val proposed = proposeSongFileName(artist, title, file.extension)
            if (proposed == file.name) return@mapNotNull null
            val parent = file.parentFile ?: return@mapNotNull null
            FixNamesCase(
                oldPath = t.filePath,
                newPath = java.io.File(parent, proposed).path,
                currentName = file.name,
                proposedName = proposed,
                primaryArtist = primaryArtistName(artist),
            )
        }
        if (cases.isEmpty()) {
            showMessage("No misnamed song files found")
            return
        }
        _fixNames.value = FixNamesSession(cases)
    }

    /** Same file despite symlink aliases or /sdcard vs /storage spellings. */
    private fun pathsReferToSameFile(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return a == b
        if (a == b) return true
        val ca = runCatching { java.io.File(a).canonicalPath }.getOrNull() ?: return false
        val cb = runCatching { java.io.File(b).canonicalPath }.getOrNull() ?: return false
        return ca == cb
    }

    /**
     * Revert playlist rows whose audio file no longer exists to unsourced
     * (match = null) and drop their pins — broken links never linger.
     * [playlistId] limits the walk (play-time check); null = every playlist.
     */
    fun healMissingMatches(playlistId: String? = null) {
        val targets = _playlists.value
            .filter { playlistId == null || it.id == playlistId }
            .flatMap { pl ->
                pl.resolvedTracks.mapNotNull { row ->
                    val p = row.match?.local?.filePath ?: return@mapNotNull null
                    if (p.startsWith("content://")) return@mapNotNull null
                    if (java.io.File(p).isFile) return@mapNotNull null
                    pl.id to Matcher.trackKey(row.remote)
                }
            }
        if (targets.isEmpty()) return
        val keysByPlaylist = targets.groupBy({ it.first }, { it.second })
        _playlists.value = _playlists.value.map { pl ->
            val keys = keysByPlaylist[pl.id] ?: return@map pl
            pl.copy(
                resolvedTracks = pl.resolvedTracks.map { row ->
                    if (Matcher.trackKey(row.remote) in keys) row.copy(match = null) else row
                },
            )
        }
        var linksChanged = false
        var links = _manualLinks.value
        for ((plId, keys) in keysByPlaylist) {
            for (key in keys) {
                // Only drop the pin if the PINNED path is dead too — a pin that
                // already points at a live file (e.g. updated by a rename)
                // should re-source the row on the rematch below.
                val pinPath = links.byPlaylist[plId]?.get(key) ?: continue
                if (pinPath.startsWith("content://")) continue
                if (!java.io.File(pinPath).isFile) {
                    links = links.remove(plId, key)
                    linksChanged = true
                }
            }
        }
        if (linksChanged) {
            _manualLinks.value = links
            scope.launch { withContext(Dispatchers.IO) { repository.saveManualLinks(links) } }
        }
        scope.launch { withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) } }
        // Re-match affected playlists right away: rows whose pin already points
        // at a live file re-source; rows with nothing left become unsourced.
        scope.launch {
            keysByPlaylist.keys.forEach { id -> matchPlaylist(id) }
        }
        emit("Broken file link cleared — re-matching affected playlists")
    }

    /**
     * Playback failed (corrupt/unreadable file): quarantine the file, drop it
     * from the library index, clear every playlist row matched to it, and HOLD
     * those rows for manual sourcing — **no fuzzy rematch** until the user
     * links or downloads a replacement (spec: reset to *To be sourced* so a
     * different or better copy can be sourced). The audio file is never
     * deleted.
     */
    private suspend fun quarantineFailedPlayback() {
        val path = _nowPlaying.value?.path ?: queue.getOrNull(queueIndex)?.path ?: return
        val name = _nowPlaying.value?.title ?: path.substringAfterLast('/')
        val isContentUri = path.startsWith("content://")

        var quarantineChanged = false
        if (!isContentUri && !quarantine.contains(path)) {
            quarantine.add(path)
            quarantineChanged = true
        }
        withContext(Dispatchers.IO) {
            if (quarantineChanged) repository.saveQuarantine(quarantine.all)
            if (!isContentUri) {
                _library.value = _library.value.filterNot { pathsReferToSameFile(it.filePath, path) }
                repository.saveLibrary(_library.value)
            }
        }

        // Drop every pin that points at the failed file.
        val originalLinks = _manualLinks.value
        var links = originalLinks
        for ((plId, pins) in originalLinks.byPlaylist) {
            for ((key, p) in pins) {
                if (pathsReferToSameFile(p, path)) {
                    links = links.remove(plId, key)
                }
            }
        }

        // Rows matched to the file → unsourced + held for manual sourcing.
        var rowsChanged = false
        _playlists.value = _playlists.value.map { pl ->
            val affected = pl.resolvedTracks.mapNotNull { row ->
                val matched = row.match?.local?.filePath ?: return@mapNotNull null
                if (!pathsReferToSameFile(matched, path)) return@mapNotNull null
                Matcher.trackKey(row.remote)
            }
            if (affected.isEmpty()) pl else {
                rowsChanged = true
                pl.copy(
                    resolvedTracks = pl.resolvedTracks.map { row ->
                        if (Matcher.trackKey(row.remote) in affected) row.copy(match = null) else row
                    },
                    heldTrackKeys = pl.heldTrackKeys + affected,
                )
            }
        }

        if (links != originalLinks) {
            _manualLinks.value = links
            withContext(Dispatchers.IO) { repository.saveManualLinks(links) }
        }
        if (rowsChanged) {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("“$name” couldn't play — reset to To be sourced")
        } else if (!isContentUri) {
            emit("“$name” couldn't play — removed from the library index")
        }
    }

    /** A pin is manual sourcing — release the corrupt-reset hold for this row. */
    private suspend fun clearManualHold(playlistId: String, trackKey: String) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (trackKey !in playlist.heldTrackKeys) return
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(heldTrackKeys = it.heldTrackKeys - trackKey) else it
        }
        withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
    }

    /**
     * Rename the current case to [editedName] (user may tweak the proposal).
     * Playlists/pins/library are scanned BEFORE the rename; matches are
     * compared by canonical path (symlink/alias-safe); saves complete BEFORE
     * the walk advances; a final heal reverts anything still broken to
     * unsourced.
     */
    fun fixNamesRename(editedName: String) {
        val session = _fixNames.value ?: return
        val case = session.current ?: return
        fun fail(msg: String) {
            val cur = _fixNames.value ?: return
            if (cur.current != case) return
            _fixNames.value = cur.copy(error = msg)
        }
        val name = editedName.trim()
        if (name.isEmpty() || name == "." || name == "..") return fail("Enter a file name")
        if (name.contains('/') || name.contains('\\')) {
            return fail("File name cannot contain path separators")
        }
        val oldFile = java.io.File(case.oldPath)
        if (!oldFile.exists()) return fail("Original file no longer exists")
        val parent = oldFile.parentFile ?: return fail("Cannot resolve this file's folder")
        val target = java.io.File(parent, name)
        if (pathsReferToSameFile(target.path, case.oldPath)) return fail("Name unchanged — edit it first")
        if (target.exists()) return fail("“$name” already exists in this folder")

        // 1. Find every playlist row linked to this file BEFORE renaming.
        val linkedRows: Map<String, Set<Int>> = _playlists.value.associate { pl ->
            pl.id to pl.resolvedTracks
                .mapIndexedNotNull { i, row ->
                    val p = row.match?.local?.filePath
                    if (p != null && pathsReferToSameFile(p, case.oldPath)) i else null
                }
                .toSet()
        }

        scope.launch {
            if (_fixNames.value?.current != case) return@launch
            // 2. Rename on disk (IO).
            val renamed = withContext(Dispatchers.IO) {
                runCatching { oldFile.renameTo(target) }.getOrDefault(false)
            }
            if (!renamed) {
                fail("Rename failed — check storage access")
                return@launch
            }
            val newPath = target.path

            // 3a. Library index row (alias-safe).
            _library.value = _library.value.map { t ->
                if (!pathsReferToSameFile(t.filePath, case.oldPath)) t else t.copy(
                    filePath = newPath,
                    displayPath = if (t.displayPath != null &&
                        pathsReferToSameFile(t.displayPath, case.oldPath)
                    ) {
                        newPath
                    } else {
                        t.displayPath
                    },
                )
            }

            // 3b. Playlist matches found in step 1.
            _playlists.value = _playlists.value.map { pl ->
                val hit = linkedRows[pl.id].orEmpty()
                if (hit.isEmpty()) return@map pl
                pl.copy(
                    resolvedTracks = pl.resolvedTracks.mapIndexed { i, row ->
                        val m = if (i in hit) row.match else null
                        if (m == null) row else row.copy(
                            match = m.copy(
                                local = m.local.copy(
                                    filePath = newPath,
                                    displayPath = if (m.local.displayPath != null &&
                                        pathsReferToSameFile(m.local.displayPath, case.oldPath)
                                    ) {
                                        newPath
                                    } else {
                                        m.local.displayPath
                                    },
                                ),
                            ),
                        )
                    },
                )
            }

            // 3c. Manual pins (alias-safe).
            val links = _manualLinks.value
            val updatedLinks = links.copy(
                byPlaylist = links.byPlaylist.mapValues { (_, tracks) ->
                    tracks.mapValues { (_, p) ->
                        if (pathsReferToSameFile(p, case.oldPath)) newPath else p
                    }
                },
            )
            if (updatedLinks != links) _manualLinks.value = updatedLinks

            // 3d. Live queue + now-playing.
            val newResolved = com.slotify.app.data.resolveSymlinkTarget(newPath)
            queue = queue.map { e ->
                if (pathsReferToSameFile(e.path, case.oldPath)) e.copy(path = newResolved) else e
            }
            _nowPlaying.value = _nowPlaying.value?.let { np ->
                if (pathsReferToSameFile(np.path, case.oldPath)) np.copy(path = newResolved) else np
            }

            // Persist BEFORE advancing — a lost save must never strand links.
            withContext(Dispatchers.IO) {
                repository.saveLibrary(_library.value)
                repository.savePlaylists(_playlists.value)
                repository.saveManualLinks(_manualLinks.value)
            }

            // Safety net: anything still pointing at a missing file → unsourced.
            healMissingMatches()

            val cur = _fixNames.value ?: return@launch
            if (cur.current != case) return@launch
            _fixNames.value = cur.copy(
                index = cur.index + 1,
                renamedCount = cur.renamedCount + 1,
                error = null,
            )
        }
    }

    /** Advance without changing this file (session-only skip). */
    fun fixNamesSkip() {
        val session = _fixNames.value ?: return
        _fixNames.value = session.copy(index = session.index + 1, error = null)
    }

    fun closeFixSongNames() {
        _fixNames.value = null
        // Flush any pending state — a lost save must never strand links.
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.saveLibrary(_library.value) }
                runCatching { repository.savePlaylists(_playlists.value) }
                runCatching { repository.saveManualLinks(_manualLinks.value) }
            }
        }
    }

    /** Create a new playlist containing [track] as an accepted match (S8). */
    fun createPlaylistWithTrack(name: String, track: LocalTrack) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val remoteTrack = localToRemote(track)
        val playlist = AppPlaylist(
            remote = RemotePlaylist(
                id = "local-${nowEpochMs()}",
                name = trimmed,
                trackCount = 1,
                tracks = listOf(remoteTrack),
            ),
            resolvedTracks = listOf(
                ResolvedPlaylistTrack(
                    remoteTrack,
                    TrackMatch(track, confidence = 1.0, isManual = true),
                ),
            ),
            importedAtEpochMs = nowEpochMs(),
            source = PlaylistSource.JSON,
        )
        _playlists.value = _playlists.value + playlist
        updateSettings { it.copy(lastAddedPlaylistId = playlist.id) }
        scope.launch {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("Created \"$trimmed\"")
        }
    }

    /** Append [track] to an existing playlist as an accepted match (S8). */
    fun addToPlaylist(playlistId: String, track: LocalTrack) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        if (playlist.resolvedTracks.any { it.match?.local?.filePath == track.filePath }) {
            emit("\"${playlist.name}\" already has this song")
            return
        }
        val remoteTrack = localToRemote(track)
        val newTracks = playlist.remote.tracks + remoteTrack
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(
                remote = pl.remote.copy(tracks = newTracks, trackCount = newTracks.size),
                resolvedTracks = pl.resolvedTracks + ResolvedPlaylistTrack(
                    remoteTrack,
                    TrackMatch(track, confidence = 1.0, isManual = true),
                ),
            )
        }
        updateSettings { it.copy(lastAddedPlaylistId = playlistId) }
        scope.launch {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
            emit("Added to \"${playlist.name}\"")
        }
    }

    private fun localToRemote(track: LocalTrack) = RemoteTrack(
        name = track.displayTitle,
        artists = listOf(RemoteArtist(name = track.displayArtist)),
        albumName = track.album,
        durationMs = track.durationMs,
    )

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

    /** Remove one track from a playlist (metadata only — never deletes audio). */
    fun deletePlaylistRow(playlistId: String, trackKey: String) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val removed = playlist.remote.tracks.find { Matcher.trackKey(it) == trackKey }
            ?: return
        val newTracks = playlist.remote.tracks.filterNot { Matcher.trackKey(it) == trackKey }
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id != playlistId) pl else pl.copy(
                remote = pl.remote.copy(tracks = newTracks, trackCount = newTracks.size),
                resolvedTracks = pl.resolvedTracks.filterNot { Matcher.trackKey(it.remote) == trackKey },
            )
        }
        // Drop any pin for the removed row so it can't dangle.
        if (_manualLinks.value.byPlaylist[playlistId]?.containsKey(trackKey) == true) {
            _manualLinks.value = _manualLinks.value.remove(playlistId, trackKey)
            scope.launch {
                withContext(Dispatchers.IO) { repository.saveManualLinks(_manualLinks.value) }
            }
        }
        scope.launch {
            withContext(Dispatchers.IO) { repository.savePlaylists(_playlists.value) }
        }
        emit("Removed \"${removed.name}\" from \"${playlist.name}\"")
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
            // Explicit user pick = manual sourcing — release the hold and
            // rehabilitate a quarantined path (the user chose it deliberately).
            clearManualHold(playlistId, trackKey)
            if (quarantine.contains(local.filePath)) {
                quarantine.remove(local.filePath)
                withContext(Dispatchers.IO) { repository.saveQuarantine(quarantine.all) }
            }
            // Immediate feedback — do not wait on folder tag reads / rematch.
            emit("Linked to ${local.displayTitle}")
            _linkFillRunning.value = true
            try {
                _linkFillStatus.value = "Matching other rows in this playlist…"
                val siblingPins = autoLinkSiblingsFromDirectory(playlistId, local.filePath)
                _linkFillStatus.value = "Refreshing matches…"
                matchPlaylist(playlistId)
                _linkFillStatus.value = when {
                    siblingPins > 0 ->
                        "Linked · +$siblingPins nearby track(s) in this playlist"
                    else -> "Linked · no extra matches in that folder"
                }
            } catch (e: Throwable) {
                _linkFillStatus.value = "Link fill failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _linkFillRunning.value = false
            }
        }
    }

    /**
     * After a successful Link file, pin sibling audio files in the **same
     * directory** to still-unmatched rows of **this playlist only**.
     *
     * Safety rails (user: only unmatched in same playlist — be careful):
     * - never touches a row that already has a match
     * - never touches held rows (corrupt-file quarantine holds)
     * - never writes another playlist's `manual_links`
     * - exclusive: one file → one track, one track → one file
     * - dual-axis gate in [Matcher.siblingMatchConfidence] (title words + artist)
     *
     * Progress → [linkFillStatus] (playlist detail, blue/error line).
     * Returns how many extra pins were added (0 when the path has no usable
     * directory, e.g. Android `content://` URIs).
     */
    private suspend fun autoLinkSiblingsFromDirectory(playlistId: String, linkedPath: String): Int {
        if (linkedPath.startsWith("content://")) {
            _linkFillStatus.value = "Linked · SAF path has no sibling folder"
            return 0
        }
        val dir = java.io.File(linkedPath).parentFile ?: return 0
        if (!dir.isDirectory) return 0

        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return 0
        val held = playlist.heldTrackKeys
        val existingPins = _manualLinks.value.linksFor(playlistId)

        // Unmatched + not held + not already pinned in THIS playlist.
        val openRemotes = playlist.resolvedTracks
            .filter { row ->
                val key = Matcher.trackKey(row.remote)
                row.match == null && key !in held && key !in existingPins
            }
            .map { it.remote }
        if (openRemotes.isEmpty()) {
            _linkFillStatus.value = "Linked · no open rows left in this playlist"
            return 0
        }

        val alreadyUsedPaths = buildSet {
            add(linkedPath)
            addAll(existingPins.values)
            playlist.resolvedTracks.forEach { row -> row.match?.local?.filePath?.let { add(it) } }
        }

        // Path → track for O(1) membership. Do **not** call canonicalPath while
        // walking the directory: on Android FUSE each canonicalize is a syscall
        // and /sdcard/Music has hundreds of entries (that was the “Listing…” stall).
        val libraryByPath = HashMap<String, LocalTrack>(_library.value.size)
        for (t in _library.value) {
            libraryByPath[t.filePath] = t
        }
        runCatching { java.io.File(linkedPath).canonicalPath }.getOrNull()?.let { canon ->
            libraryByPath[canon]?.let { libraryByPath[linkedPath] = it }
        }

        _linkFillStatus.value = "Listing files next to the link…"
        val siblingFiles = withContext(Dispatchers.IO) {
            val out = ArrayList<java.io.File>()
            val entries = dir.listFiles() ?: return@withContext emptyList()
            var n = 0
            for (f in entries) {
                n++
                if (n % 100 == 0) {
                    _linkFillStatus.value = "Listing files… $n (${f.name.take(30)})"
                }
                if (!f.isFile) continue
                val name = f.name
                if (name.startsWith(".")) continue
                if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue
                if (f.absolutePath in alreadyUsedPaths) continue
                if (quarantine.contains(f.absolutePath)) continue
                out.add(f)
            }
            out
        }
        if (siblingFiles.isEmpty()) {
            _linkFillStatus.value = "Linked · no other audio files in that folder"
            return 0
        }

        // Filename-only match: use the indexed row when we have it, otherwise a
        // path-only LocalTrack (basename axes). No tag reads / no library write —
        // simple name matching does not need to demux the file.
        val siblingLocals = ArrayList<LocalTrack>(siblingFiles.size)
        for (f in siblingFiles) {
            val path = f.absolutePath
            siblingLocals.add(libraryByPath[path] ?: LocalTrack(filePath = path))
        }

        _linkFillStatus.value =
            "Matching ${openRemotes.size} open row(s) against ${siblingLocals.size} file(s)…"
        val pins = Matcher.assignSiblingLinks(openRemotes, siblingLocals)
        if (pins.isEmpty()) {
            _linkFillStatus.value = "Linked · no confident sibling matches"
            return 0
        }

        var links = _manualLinks.value
        var added = 0
        for ((key, path) in pins) {
            // Re-check: only this playlist, only still-open rows.
            val row = playlist.resolvedTracks.find { Matcher.trackKey(it.remote) == key }
            if (row == null || row.match != null || key in held) continue
            if (links.linksFor(playlistId).containsKey(key)) continue
            links = links.put(playlistId, key, path)
            added++
        }
        if (added == 0) return 0
        _manualLinks.value = links
        withContext(Dispatchers.IO) { repository.saveManualLinks(links) }
        return added
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
        startPlaylist(playlistId, fromRowIndex, fromRowTap = true)
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

    private fun startPlaylist(playlistId: String, fromRowIndex: Int = 0, fromRowTap: Boolean = false) {
        val initial = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (initial.trashed) {
            emit("Playlist is in the trash — restore it first")
            return
        }
        // Revert rows whose file vanished so a tap never lands on a dead link.
        healMissingMatches(playlistId)
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
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
        val hasSavedProgress = saved != null &&
            saved.orderKeys.isNotEmpty() &&
            saved.orderIndex in saved.orderKeys.indices
        val useSavedOrder = useSavedProgress(fromRowTap, fromRowIndex, hasSavedProgress)
        val savedProgress = if (useSavedOrder) saved else null

        val keysToEntries = playable.associate { (_, row, match) ->
            Matcher.trackKey(row.remote) to QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(match.local.filePath),
                title = row.remote.name,
                artist = row.remote.artistNames.ifBlank { match.local.displayArtist },
                trackKey = Matcher.trackKey(row.remote),
            )
        }

        val mode = _playbackMode.value
        val orderedKeys: List<String> = when {
            savedProgress != null -> {
                val existing = savedProgress.orderKeys.filter { it in keysToEntries }
                val missing = keysToEntries.keys.filterNot { it in existing.toSet() }
                if (mode == PlaybackMode.SHUFFLE && missing.isNotEmpty()) {
                    (existing + missing.shuffled())
                } else {
                    existing + missing
                }
            }
            else -> {
                val all = playable.map { (_, row, _) -> Matcher.trackKey(row.remote) }
                if (mode == PlaybackMode.SHUFFLE) all.shuffled() else all
            }
        }
        if (orderedKeys.isEmpty()) {
            emit("No playable tracks in \"${playlist.name}\"")
            return
        }

        val fullQueue = orderedKeys.mapNotNull { keysToEntries[it] }
        val resolvedIndex = if (savedProgress != null) {
            val key = savedProgress.orderKeys[savedProgress.orderIndex]
            fullQueue.indexOfFirst { it.trackKey == key }.takeIf { it >= 0 } ?: 0
        } else {
            val orig = playable.indexOfFirst { it.first >= fromRowIndex }
            val origKey = playable.getOrNull(orig)?.let { Matcher.trackKey(it.second.remote) }
            (origKey?.let { k -> fullQueue.indexOfFirst { it.trackKey == k } } ?: 0)
                .takeIf { it >= 0 } ?: 0
        }
        artistSession = null
        // ARTIST_* fresh start: queue is history only; picks append the future.
        queue = if (mode.isArtistMode() && savedProgress == null) {
            listOf(fullQueue[resolvedIndex.coerceIn(0, fullQueue.lastIndex)])
        } else {
            fullQueue
        }
        val startPos = savedProgress?.positionMs?.coerceAtLeast(0L) ?: 0L
        _activePlaylistId.value = playlistId
        queueIndex = if (mode.isArtistMode() && savedProgress == null) {
            0
        } else {
            resolvedIndex.coerceIn(0, queue.lastIndex)
        }
        if (mode.isArtistMode() && savedProgress == null) {
            anchorArtistSessionToCurrent()
        }
        playAt(queueIndex, seekToMs = startPos)
        persistPlaybackProgress()
    }

    fun setShuffle(enabled: Boolean) =
        applyMode(if (enabled) PlaybackMode.SHUFFLE else PlaybackMode.LINEAR)

    /** Cycle Linear → Shuffle → Artist (2) → Artist (all); keeps the current song (S3). */
    fun cyclePlaybackMode() = applyMode(_playbackMode.value.cycle())

    private val _pendingResetPick = MutableStateFlow<PickAction?>(null)
    /** Non-null → show the reset-played dialog before starting this pick. */
    val pendingResetPick: StateFlow<PickAction?> = _pendingResetPick.asStateFlow()

    /**
     * Set by “Just play this” — stop asking for the rest of the session.
     * Cleared only by an explicit “Reset all played” (or a pass that clears the set).
     */
    private var resetAskSuppressed = false

    /** Playlist row pick — asks only when this row's file already played (S6). */
    fun requestPlaylistPick(playlistId: String, fromRowIndex: Int) {
        val sameRow = _nowPlaying.value != null &&
            _activePlaylistId.value == playlistId &&
            currentOriginalRowIndex(playlistId) == fromRowIndex
        if (sameRow) {
            playPlaylist(playlistId, fromRowIndex)
            return
        }
        val file = _playlists.value.find { it.id == playlistId }
            ?.resolvedTracks?.getOrNull(fromRowIndex)?.match?.local?.filePath
        if (!shouldAskResetPlayed(file, _playedKeys.value, resetAskSuppressed)) {
            playPlaylist(playlistId, fromRowIndex)
            return
        }
        _pendingResetPick.value = PickAction.Playlist(playlistId, fromRowIndex)
    }

    /** Library row pick — asks only when this row is already greyed (S6). */
    fun requestLibraryPick(tracks: List<LocalTrack>, index: Int) {
        val track = tracks.getOrNull(index) ?: return
        val queueKey = "lib:${track.filePath}"
        val sameRow = _activePlaylistId.value == null &&
            _nowPlaying.value != null &&
            queue.getOrNull(queueIndex)?.trackKey == queueKey
        // playedKeys holds plain file paths — compare the path, not the queue key.
        if (sameRow || !shouldAskResetPlayed(track.filePath, _playedKeys.value, resetAskSuppressed)) {
            playLibrary(tracks, index)
            return
        }
        _pendingResetPick.value = PickAction.Library(tracks, index)
    }

    /** Dialog answer: [reset] clears played keys, then the pick plays. */
    fun resolvePendingResetPick(reset: Boolean) {
        val action = _pendingResetPick.value ?: return
        _pendingResetPick.value = null
        if (reset) {
            _playedKeys.value = emptySet()
            resetAskSuppressed = false
        } else {
            // “Just play this” — do not nag again this session.
            resetAskSuppressed = true
        }
        when (action) {
            is PickAction.Playlist -> playPlaylist(action.playlistId, action.fromRowIndex)
            is PickAction.Library -> playLibrary(action.tracks, action.index)
        }
    }

    fun cancelPendingResetPick() {
        _pendingResetPick.value = null
    }

    private fun applyMode(next: PlaybackMode) {
        _playbackMode.value = next
        _shuffleEnabled.value = next != PlaybackMode.LINEAR
        playbackStore = playbackStore.copy(shuffle = next != PlaybackMode.LINEAR, mode = next.name)
        scope.launch { repository.savePlayback(playbackStore) }
        if (queue.isEmpty()) return
        if (next.isArtistMode()) {
            artistSession = null
            restartArtistSessionAroundCurrent()
            return
        }
        // LINEAR / SHUFFLE: rebuild the order around the current song.
        val currentKey = queue.getOrNull(queueIndex)?.trackKey
        val sourceKeys = currentSourceKeys()
        if (sourceKeys.isEmpty()) return
        val reordered = if (next == PlaybackMode.SHUFFLE) {
            val rest = sourceKeys.filter { it != currentKey }.shuffled()
            if (currentKey != null) listOf(currentKey) + rest else rest
        } else {
            sourceKeys
        }
        val newQueue = reordered.mapNotNull { k -> queue.find { it.trackKey == k } ?: queueEntryForKey(k) }
        if (newQueue.isEmpty()) return
        queue = newQueue
        queueIndex = currentKey?.let { k ->
            newQueue.indexOfFirst { it.trackKey == k }.takeIf { i -> i >= 0 }
        } ?: 0
        persistPlaybackProgress()
    }

    /** Full playable source keys for the active playlist, or the library snapshot. */
    private fun currentSourceKeys(): List<String> = _activePlaylistId.value
        ?.let { id -> _playlists.value.find { it.id == id } }
        ?.resolvedTracks
        ?.filter { it.mark != RowMark.SKIP && it.mark != RowMark.DELETE && it.match != null }
        ?.map { Matcher.trackKey(it.remote) }
        ?: librarySource.map { "lib:${it.filePath}" }

    /** Build a queue entry for a source key that is not in the current queue. */
    private fun queueEntryForKey(key: String): QueueEntry? {
        val plId = _activePlaylistId.value
        if (plId != null) {
            val row = _playlists.value.find { it.id == plId }
                ?.resolvedTracks?.find { Matcher.trackKey(it.remote) == key } ?: return null
            val match = row.match ?: return null
            return QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(match.local.filePath),
                title = row.remote.name,
                artist = row.remote.artistNames.ifBlank { match.local.displayArtist },
                trackKey = key,
            )
        }
        val local = librarySource.find { "lib:${it.filePath}" == key } ?: return null
        return QueueEntry(
            path = com.slotify.app.data.resolveSymlinkTarget(local.filePath),
            title = local.displayTitle,
            artist = local.displayArtist,
            trackKey = key,
        )
    }

    private fun artistItems(): List<ArtistSourceItem> {
        val plId = _activePlaylistId.value
        if (plId != null) {
            val rows = _playlists.value.find { it.id == plId }?.resolvedTracks ?: return emptyList()
            return rows.mapNotNull { row ->
                if (row.mark == RowMark.SKIP || row.mark == RowMark.DELETE || row.match == null) {
                    return@mapNotNull null
                }
                val raw = row.remote.artistNames.ifBlank { row.match.local.displayArtist }
                val primary = row.remote.primaryArtist.ifBlank { primaryArtistName(raw) }
                ArtistSourceItem(
                    key = Matcher.trackKey(row.remote),
                    artist = primary.ifBlank { "Unknown artist" },
                    allArtists = row.remote.artists.map { it.name }
                        .ifEmpty { splitArtistList(raw) }
                        .ifEmpty { listOf(primary) },
                )
            }
        }
        return librarySource.map { t ->
            val raw = t.displayArtist
            ArtistSourceItem(
                key = "lib:${t.filePath}",
                artist = primaryArtistName(raw),
                allArtists = splitArtistList(raw),
            )
        }
    }

    private fun ensureArtistSession(): ArtistSession? {
        artistSession?.let { return it }
        val items = artistItems().takeIf { it.isNotEmpty() } ?: return null
        val per = if (_playbackMode.value == PlaybackMode.ARTIST_2) 2 else Int.MAX_VALUE
        return ArtistSession(items, per).also { artistSession = it }
    }

    private fun restartArtistSessionAroundCurrent() {
        anchorArtistSessionToCurrent()
    }

    /**
     * Build the artist session around the song being started: the current
     * artist's block plays first (current song counts toward the quota), with
     * collab partners as fallback when the primary block runs dry (S5).
     */
    private fun anchorArtistSessionToCurrent() {
        val session = ensureArtistSession() ?: return
        val currentKey = queue.getOrNull(queueIndex)?.trackKey ?: return
        val item = artistItems().find { it.key == currentKey } ?: return
        session.restartAround(item.artist, item.allArtists)
    }

    /**
     * playedKeys holds file paths (grey-out space); ArtistSession keys are
     * track keys — map the active source's played rows into key space so the
     * session never repeats a song.
     */
    private fun playedSessionKeys(): Set<String> {
        val paths = _playedKeys.value
        if (paths.isEmpty()) return emptySet()
        val plId = _activePlaylistId.value
        if (plId != null) {
            val rows = _playlists.value.find { it.id == plId }?.resolvedTracks ?: return emptySet()
            return rows.filter { it.match?.local?.filePath in paths }
                .map { Matcher.trackKey(it.remote) }
                .toSet()
        }
        return paths.map { "lib:$it" }.toSet()
    }

    /** Keys marked skip/delete in the active source right now (advance-time guard). */
    private fun markedSkipKeys(): Set<String> {
        val plId = _activePlaylistId.value
        if (plId != null) {
            return _playlists.value.find { it.id == plId }
                ?.resolvedTracks
                ?.filter { it.mark == RowMark.SKIP || it.mark == RowMark.DELETE }
                ?.map { Matcher.trackKey(it.remote) }
                ?.toSet()
                ?: emptySet()
        }
        return _library.value
            .filter { it.mark == RowMark.SKIP || it.mark == RowMark.DELETE }
            .map { "lib:${it.filePath}" }
            .toSet()
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
            shuffle = _playbackMode.value != PlaybackMode.LINEAR,
            mode = _playbackMode.value.name,
            byPlaylist = playbackStore.byPlaylist + (plId to progress),
        )
        scope.launch { repository.savePlayback(playbackStore) }
    }

    /** Play the local library starting at [fromIndex] (search-filtered view passes its own index). */
    fun playLibrary(tracks: List<LocalTrack>, fromIndex: Int = 0) {
        if (tracks.isEmpty()) return
        persistPlaybackProgress()
        _activePlaylistId.value = null
        val mode = _playbackMode.value
        val startKey = tracks.getOrNull(fromIndex)?.let { "lib:${it.filePath}" }
        val playable = tracks.filter { it.mark == null }
        if (playable.isEmpty()) {
            emit("Every row here is marked skip or delete")
            return
        }
        librarySource = playable
        artistSession = null
        val entries = playable.map {
            QueueEntry(
                path = com.slotify.app.data.resolveSymlinkTarget(it.filePath),
                title = it.displayTitle,
                artist = it.displayArtist,
                trackKey = "lib:${it.filePath}",
            )
        }
        val startEntry = entries.find { it.trackKey == startKey } ?: entries.first()
        queue = when {
            mode.isArtistMode() -> listOf(startEntry)
            mode == PlaybackMode.SHUFFLE ->
                listOf(startEntry) + entries.filter { it.trackKey != startEntry.trackKey }.shuffled()
            else -> entries
        }
        queueIndex = queue.indexOfFirst { it.trackKey == startEntry.trackKey }.coerceAtLeast(0)
        if (mode.isArtistMode()) {
            anchorArtistSessionToCurrent()
        }
        playAt(queueIndex)
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
        val mode = _playbackMode.value
        val skipped = if (delta > 0) markedSkipKeys() else emptySet()
        if (mode.isArtistMode() && delta > 0) {
            // Walk history past rows marked skip/delete (possibly marked mid-session).
            var target = queueIndex + 1
            while (target in queue.indices && queue[target].trackKey in skipped) target++
            if (target in queue.indices) {
                playAt(target)
                persistPlaybackProgress()
                return
            }
            // End of history — ask the artist session for the next pick (S5).
            val session = ensureArtistSession()
            var key: String? = null
            if (session != null) {
                var excluded = playedSessionKeys()
                while (true) {
                    val k = session.next(excluded) ?: break
                    if (k !in skipped) {
                        key = k
                        break
                    }
                    excluded = excluded + k
                }
                if (key == null) {
                    // Pass complete → new pass + clear played, then retry once.
                    session.reshuffle()
                    _playedKeys.value = emptySet()
                    emit("Artist pass complete — starting a new pass")
                    excluded = setOfNotNull(queue.getOrNull(queueIndex)?.trackKey)
                    while (true) {
                        val k = session.next(excluded) ?: break
                        if (k !in skipped) {
                            key = k
                            break
                        }
                        excluded = excluded + k
                    }
                }
            }
            if (key == null) {
                persistPlaybackProgress()
                player.pause()
                if (!auto) emit("No more songs to play")
                return
            }
            val entry = queue.find { it.trackKey == key } ?: queueEntryForKey(key)
            if (entry == null) {
                persistPlaybackProgress()
                player.pause()
                if (!auto) emit("Cannot load the next song")
                return
            }
            queue = queue + entry
            playAt(queue.lastIndex)
            persistPlaybackProgress()
            return
        }
        var target = queueIndex + delta
        if (delta > 0) {
            // Skip rows the user marked s/d — next must jump over them (S7).
            while (target in queue.indices && queue[target].trackKey in skipped) target++
        }
        if (auto && target > queue.lastIndex && mode == PlaybackMode.SHUFFLE) {
            // Exhausted this shuffle cycle — reshuffle all tracks for a new pass.
            val byKey = queue.associateBy { it.trackKey }
            val newQueue = queue.map { it.trackKey }.shuffled().mapNotNull { byKey[it] }
            if (newQueue.isNotEmpty()) {
                queue = newQueue
                persistPlaybackProgress()
                var start = 0
                while (start in queue.indices && queue[start].trackKey in skipped) start++
                if (start in queue.indices) {
                    playAt(start)
                    emit("Shuffle cycle complete — starting a new pass")
                    return
                }
            }
        }
        if (target !in queue.indices) {
            if (auto) {
                persistPlaybackProgress()
                player.pause()
                // Keep queue + index so the user can resume from here.
            } else {
                emit("End of queue")
            }
            return
        }
        playAt(target)
        persistPlaybackProgress()
    }

    /** Record the played library path (playlist rows map via their match) — S6. */
    private fun markPlayed(entry: QueueEntry) {
        val path = when {
            entry.trackKey.startsWith("lib:") -> entry.trackKey.removePrefix("lib:")
            else -> _playlists.value.find { it.id == _activePlaylistId.value }
                ?.resolvedTracks
                ?.find { Matcher.trackKey(it.remote) == entry.trackKey }
                ?.match?.local?.filePath
        }
        if (path != null) _playedKeys.value = _playedKeys.value + path
    }

    private fun playAt(index: Int, seekToMs: Long = 0L) {
        val entry = queue.getOrNull(index) ?: return
        queueIndex = index
        markPlayed(entry)
        _nowPlaying.value = NowPlaying(entry.title, entry.artist, entry.path)
        player.load(entry.path, startPaused = seekToMs > 0)
        applyLoudnessToPlayer(entry.path)
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
     * S6: balance toward playlist mean when enabled.
     * Recommended → boost-only clamp; Bypass → no correction; Custom → user dB.
     * Limiter only when the track sits above the playlist mean (loud side).
     * Updates [loudnessBar] for the volume-row status.
     */
    private fun applyLoudnessToPlayer(path: String) {
        fun neutral(balanceOff: Boolean = false) {
            player.setLoudnessCorrection(0f, enableLimiter = false)
            _loudnessBar.value = LoudnessBar(balanceOff = balanceOff)
        }
        if (!supportsLoudnessBalance()) {
            neutral()
            return
        }
        val s = _settings.value
        if (!s.loudnessBalance) {
            // Still playing a song — make it obvious why Custom did nothing.
            neutral(balanceOff = true)
            return
        }
        val track = loudnessStore.track(path)
        if (track == null) {
            neutral()
            return
        }
        if (track.correction == CorrectionMode.BYPASS) {
            neutral()
            return
        }
        val mean = _activePlaylistId.value?.let { playlistMeanLufs(it) }
        val boost = when (track.correction) {
            CorrectionMode.RECOMMENDED ->
                recommendedBoostDb(
                    track.integratedLufs,
                    mean,
                    s.loudnessMaxBoostDb.toDouble(),
                ).toFloat()
            CorrectionMode.BYPASS -> 0f
            CorrectionMode.CUSTOM -> track.customGainDb.toFloat()
        }
        // Loud side: integrated above the playlist mean → arm the limiter (no cut).
        // Custom always arms it so the user’s threshold (below full scale) applies.
        val loud = mean != null && track.integratedLufs > mean
        val limiting = when (track.correction) {
            CorrectionMode.CUSTOM -> true
            CorrectionMode.BYPASS -> false
            CorrectionMode.RECOMMENDED -> loud
        }
        val threshold = when (track.correction) {
            CorrectionMode.CUSTOM -> track.customLimitThresholdDb.toFloat()
            else -> 0f
        }
        player.setLoudnessCorrection(
            boost,
            enableLimiter = limiting,
            limiterThresholdDb = threshold,
        )
        _loudnessBar.value = LoudnessBar(boostDb = boost, limiting = limiting)
    }

    /** Re-read store + push correction to the player for the current path (live sliders). */
    fun refreshLoudnessForCurrentTrack() {
        val path = _nowPlaying.value?.path ?: return
        applyLoudnessToPlayer(path)
    }

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

    // ── Loudness backfill ────────────────────────────────────────────

    fun startLoudnessBackfill(playlistId: String) {
        if (!supportsLoudnessBalance()) return
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        if (loudnessJob?.isActive == true) return
        val plName = playlist.name.take(24)
        _loudnessStatus.value = "Preparing loudness analysis · $plName…"
        loudnessJob = scope.launch(Dispatchers.Default) {
            var failed = 0
            var done = 0
            try {
                val paths = playlist.resolvedTracks
                    .mapNotNull { it.match?.local?.filePath }
                    .distinct()
                val todo = paths.filter { loudnessStore.track(it) == null }
                val total = todo.size
                if (total == 0) {
                    _loudnessStatus.value = "All tracks already analysed · $plName"
                    recomputePlaylistProfile(playlistId)
                    return@launch
                }
                for (p in todo) {
                    ensureActive()
                    val name = p.substringAfterLast('/')
                    _loudnessStatus.value =
                        "Analysing $plName · ${done + 1}/$total (${name.take(32)})"
                    val r = withContext(Dispatchers.IO) { analyseFileLoudness(p) }
                    if (r != null) {
                        withContext(Dispatchers.IO) { loudnessStore.putTrack(p, r) }
                    } else {
                        failed++
                    }
                    done++
                }
                recomputePlaylistProfile(playlistId)
                _loudnessStatus.value = if (failed > 0) {
                    "Loudness done · $plName · $done analysed · $failed failed"
                } else {
                    "Loudness done · $plName · $done analysed"
                }
            } catch (e: CancellationException) {
                recomputePlaylistProfile(playlistId)
                _loudnessStatus.value = "Loudness cancelled · $plName"
                throw e
            } catch (e: Exception) {
                _loudnessStatus.value =
                    "Loudness failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun cancelLoudnessBackfill() {
        val job = loudnessJob ?: return
        if (!job.isActive) return
        // Current file may still be decoding — surface that we’re winding down
        // instead of leaving “Analysing…” until the next ensureActive().
        _loudnessStatus.value = "Stopping analysis…"
        job.cancel()
    }

    fun playlistMeanLufs(playlistId: String): Double? =
        loudnessStore.playlist(playlistId)?.meanLufs

    /** True when [playlistId] still has playable rows without a stored analysis. */
    fun playlistNeedsLoudnessAnalysis(playlistId: String?): Boolean {
        if (playlistId == null || !supportsLoudnessBalance()) return false
        val playlist = _playlists.value.find { it.id == playlistId } ?: return false
        val paths = playlist.resolvedTracks
            .mapNotNull { it.match?.local?.filePath }
            .distinct()
        if (paths.isEmpty()) return false
        return paths.any { loudnessStore.track(it) == null }
    }

    private fun recomputePlaylistProfile(playlistId: String) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        val paths = playlist.resolvedTracks
            .mapNotNull { it.match?.local?.filePath }
            .distinct()
        val lufs = paths.mapNotNull { loudnessStore.track(it)?.integratedLufs }
        if (lufs.isEmpty()) return
        runCatching {
            loudnessStore.putPlaylist(
                id = playlistId,
                meanLufs = lufs.average(),
                analysedCount = lufs.size,
                totalMembers = paths.size,
            )
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
