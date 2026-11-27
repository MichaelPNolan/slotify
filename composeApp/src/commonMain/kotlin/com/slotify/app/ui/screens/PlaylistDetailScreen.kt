package com.slotify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.slotify.app.loudness.CorrectionMode
import com.slotify.app.loudness.TrackLoudness
import com.slotify.app.loudness.recommendedBoostDb
import com.slotify.app.loudness.supportsLoudnessBalance
import com.slotify.app.match.Matcher
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.ui.CoverBackdrop
import com.slotify.app.ui.MarkChip
import com.slotify.app.ui.formatMs
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlaylistDetailScreen(state: AppState, playlistId: String) {
    val playlists by state.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val settings by state.settings.collectAsState()
    val youtubeStatus by state.youtubeStatus.collectAsState()
    val batchRunning by state.youtubeBatchRunning.collectAsState()
    val linkFillStatus by state.linkFillStatus.collectAsState()
    val linkFillRunning by state.linkFillRunning.collectAsState()
    val loudnessStatus by state.loudnessStatus.collectAsState()
    var findTarget by remember { mutableStateOf<RemoteTrack?>(null) }
    var linkTarget by remember { mutableStateOf<RemoteTrack?>(null) }
    var filter by rememberSaveable(playlistId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    findTarget?.let { target ->
        FindOnYoutubeDialog(
            state = state,
            playlistId = playlistId,
            track = target,
            onDismiss = { findTarget = null },
        )
    }
    linkTarget?.let { target ->
        LinkFileDialog(
            state = state,
            playlistId = playlistId,
            track = target,
            onDismiss = { linkTarget = null },
        )
    }

    if (playlist == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Playlist not found")
        }
        return
    }

    fun matchesFilter(row: ResolvedPlaylistTrack): Boolean {
        val q = filter.trim()
        if (q.isEmpty()) return true
        val t = row.remote
        return t.name.contains(q, ignoreCase = true) ||
            t.artistNames.contains(q, ignoreCase = true) ||
            (t.albumName?.contains(q, ignoreCase = true) == true) ||
            (row.match?.local?.displayTitle?.contains(q, ignoreCase = true) == true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.closePlaylistDetail() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = playlist.resolvedTracks.size
                val sourced = playlist.toBeSourcedCount
                Text(
                    text = buildString {
                        append("${playlist.matchedCount}/$total matched")
                        if (sourced > 0) append(" · $sourced to be sourced")
                        if (playlist.skipCount > 0) append(" · ${playlist.skipCount} skip")
                        if (playlist.deleteMarkedCount > 0) {
                            append(" · ${playlist.deleteMarkedCount} to delete")
                        }
                        append(" · ${playlist.source.name.lowercase()} import")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Playlist delete lives on the list view (mark → Delete marked),
            // not as a one-click trash icon here. Play/rematch live in the
            // bottom bar + per-row controls — no header duplicates.
        }

        // Narrow the list by title / artist / album / file name.
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text("Search in playlist") },
            placeholder = { Text("Title, artist, album…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            supportingText = {
                if (filter.isNotBlank()) {
                    val n = playlist.resolvedTracks.count { matchesFilter(it) }
                    Text("Showing $n of ${playlist.resolvedTracks.size}")
                }
            },
        )

        // Marked-for-delete bulk action.
        if (playlist.deleteMarkedCount > 0) {
            OutlinedButton(
                onClick = { state.deleteMarkedTracks(playlist.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Delete marked (${playlist.deleteMarkedCount})",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Loudness progress line only — the Analyse/Stop control lives in the
        // volume row (right of the slider) when the player is paused.

        // Batch: auto-fetch unmatched rows whose best YouTube hit scores ≥ settings min.
        // While running the same button stops the batch.
        if ((playlist.toBeSourced.isNotEmpty() || batchRunning) && state.youtube.isSupported) {
            val batchPct = (settings.youtubeBatchMinScore * 100).toInt()
            Spacer(Modifier.size(4.dp))
            OutlinedButton(
                onClick = {
                    if (batchRunning) {
                        state.stopBatchYoutube()
                    } else {
                        state.batchFetchYoutube(playlist.id)
                    }
                },
                enabled = batchRunning || playlist.toBeSourced.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    if (batchRunning) Icons.Default.Stop else Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        batchRunning -> "Stop batch"
                        else -> "YouTube batch — fetch matches ≥$batchPct%"
                    },
                )
            }
            youtubeStatus?.let { s ->
                if (s.startsWith("Batch") || s.startsWith("Search") ||
                    s.startsWith("Stopping") || s.contains("download")
                ) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.startsWith("Batch stopped") || s.startsWith("Download failed")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (batchRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            // Link-file cascade (this playlist only) — same blue/error line as YT batch.
            linkFillStatus?.let { s ->
                Text(
                    text = s,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.startsWith("Link fill failed")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (linkFillRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            loudnessStatus?.let { s ->
                val active = !s.startsWith("Loudness done") &&
                    !s.startsWith("Loudness cancelled") &&
                    !s.startsWith("Loudness failed") &&
                    !s.startsWith("All tracks")
                Text(
                    text = s,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        s.startsWith("Loudness failed") -> MaterialTheme.colorScheme.error
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (active) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        HorizontalDivider()
        Spacer(Modifier.size(4.dp))

        // One scrollable list: To be sourced + Matched stacked (phone can scroll all of it).
        val unmatchedAll = playlist.resolvedTracks.withIndex().filter { !it.value.isMatched }
        val unmatchedFiltered = unmatchedAll.filter { matchesFilter(it.value) }
        val matchedAll = playlist.resolvedTracks.withIndex().filter { it.value.isMatched }
        val matchedFiltered = matchedAll.filter { matchesFilter(it.value) }

        val listState = rememberLazyListState()
        val playlistFollowTick by state.playlistFollowTick.collectAsState()
        val activePlaylistId by state.activePlaylistId.collectAsState()
        LaunchedEffect(playlistFollowTick, nowPlayingPath(state), matchedFiltered, unmatchedFiltered) {
            if (playlistFollowTick <= 0) return@LaunchedEffect
            if (activePlaylistId != playlistId) return@LaunchedEffect
            val np = state.nowPlaying.value ?: return@LaunchedEffect
            var offset = 0
            if (unmatchedAll.isNotEmpty()) {
                offset += 1 // header
                val uIdx = unmatchedFiltered.indexOfFirst { (_, row) ->
                    rowMatchesNowPlaying(row, np.path, np.title, np.artist)
                }
                if (uIdx >= 0) {
                    listState.animateScrollToItem(offset + uIdx)
                    return@LaunchedEffect
                }
                offset += unmatchedFiltered.size + 1 // rows + divider
            }
            offset += 1 // matched header
            val mIdx = matchedFiltered.indexOfFirst { (_, row) ->
                rowMatchesNowPlaying(row, np.path, np.title, np.artist)
            }
            if (mIdx >= 0) {
                listState.animateScrollToItem(offset + mIdx)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CoverBackdrop(state)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                // ── To be sourced ────────────────────────────────────────
                if (unmatchedAll.isNotEmpty()) {
                    item(key = "hdr-unmatched") {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = if (unmatchedFiltered.size == unmatchedAll.size) {
                                    "To be sourced (${unmatchedAll.size})"
                                } else {
                                    "To be sourced (${unmatchedFiltered.size} of ${unmatchedAll.size})"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "No local file yet — link a file, fetch from YouTube, " +
                                    "or swipe the row left to remove it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (unmatchedFiltered.isEmpty()) {
                                Text(
                                    text = "No unmatched rows match your search",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(
                        unmatchedFiltered,
                        key = { (idx, row) -> "u-${row.remote.id ?: row.remote.name}-$idx" },
                    ) { (_, row) ->
                        UnmatchedRow(
                            state = state,
                            playlistId = playlist.id,
                            row = row,
                            onFindOnYoutube = { findTarget = it },
                            onLinkFile = { linkTarget = it },
                        )
                    }
                    item(key = "div-unmatched") {
                        Spacer(Modifier.size(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.size(4.dp))
                    }
                }

                // ── Matched ──────────────────────────────────────────────
                item(key = "hdr-matched") {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = if (filter.isBlank()) {
                                "Matched (${matchedAll.size})"
                            } else {
                                "Matched (${matchedFiltered.size} of ${matchedAll.size})"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (matchedAll.isNotEmpty() && state.youtube.isSupported) {
                            Text(
                                text = "Hold 5s or swipe a row left to re-fetch from YouTube",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    matchedFiltered,
                    key = { (idx, row) -> "m-${row.remote.id ?: row.remote.name}-$idx" },
                ) { (index, row) ->
                    MatchedRow(
                        state = state,
                        playlistId = playlist.id,
                        row = row,
                        originalIndex = index,
                        onRedoYoutube = {
                            scope.launch {
                                state.prepareRedoYoutubeMatch(
                                    playlistId = playlist.id,
                                    trackKey = Matcher.trackKey(row.remote),
                                )
                                findTarget = row.remote
                            }
                        },
                    )
                }
                if (matchedFiltered.isEmpty() && unmatchedFiltered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (filter.isNotBlank()) {
                                "No tracks match “$filter”"
                            } else {
                                "Playlist has no tracks"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnmatchedRow(
    state: AppState,
    playlistId: String,
    row: ResolvedPlaylistTrack,
    onFindOnYoutube: (com.slotify.app.model.RemoteTrack) -> Unit,
    onLinkFile: (com.slotify.app.model.RemoteTrack) -> Unit,
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var pendingDelete by remember { mutableStateOf(false) }

    // Swipe left → confirm → remove this row from the playlist (audio kept).
    val swipeToDelete = Modifier
        .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
        .pointerInput(playlistId, row.remote) {
            val threshold = 96.dp.toPx()
            var startX = 0f
            var swiped = false
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val pressed = change.pressed
                    if (pressed && !change.previousPressed) {
                        startX = change.position.x
                        swiped = false
                        dragOffsetX = 0f
                    }
                    if (pressed) {
                        val dx = change.position.x - startX
                        if (dx < 0f) {
                            dragOffsetX = dx.coerceAtLeast(-threshold * 1.5f)
                            if (dx <= -threshold) {
                                if (!swiped) {
                                    swiped = true
                                    change.consume()
                                    dragOffsetX = 0f
                                    pendingDelete = true
                                } else {
                                    change.consume()
                                }
                            }
                        }
                    } else if (change.previousPressed) {
                        dragOffsetX = 0f
                    }
                }
            }
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(swipeToDelete)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkChip(
            mark = row.mark,
            onClick = { state.cyclePlaylistRowMark(playlistId, Matcher.trackKey(row.remote)) },
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.remote.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(row.remote.artistNames.ifBlank { "Unknown artist" })
                row.remote.durationMs?.let { append(" · ").append(formatMs(it)) }
                append(" · to be sourced")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onLinkFile(row.remote) }) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Link file", style = MaterialTheme.typography.labelMedium)
        }
        if (state.youtube.isSupported) {
            TextButton(onClick = { onFindOnYoutube(row.remote) }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("YouTube", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Remove from playlist?") },
            text = {
                Text(
                    text = "“${row.remote.name}” will be removed from this playlist. " +
                        "The audio file itself is not deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.deletePlaylistRow(playlistId, Matcher.trackKey(row.remote))
                    pendingDelete = false
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MatchedRow(
    state: AppState,
    playlistId: String,
    row: ResolvedPlaylistTrack,
    originalIndex: Int,
    onRedoYoutube: () -> Unit,
) {
    val match = row.match ?: return
    val scope = rememberCoroutineScope()
    val settings by state.settings.collectAsState()
    val roots = settings.libraryRoots
    val activePlaylistId by state.activePlaylistId.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val nowPlaying by state.nowPlaying.collectAsState()
    val slotifyRoot = remember(settings.libraryRoots) {
        com.slotify.app.data.defaultSlotifyMusicDir().trimEnd('/')
    }
    // Prefer the symlink target so staged links still show the original source.
    val localPath = remember(match.local.filePath) {
        com.slotify.app.data.resolveSymlinkTarget(match.local.filePath).replace('\\', '/')
    }
    // Same track currently loaded in the player (for play/stop icon).
    val isCurrentTrack = activePlaylistId == playlistId &&
        nowPlaying != null &&
        (nowPlaying!!.path == localPath ||
            (nowPlaying!!.title == row.remote.name &&
                nowPlaying!!.artist == row.remote.artistNames.ifBlank { match.local.displayArtist }))
    val isPlayingThis = isCurrentTrack && playerSnap.isPlaying
    val matchedViaSlotifyLink = remember(match.local.filePath, localPath) {
        val raw = match.local.filePath.replace('\\', '/')
        val rawUnder = raw == slotifyRoot || raw.startsWith("$slotifyRoot/")
        val targetUnder = localPath == slotifyRoot || localPath.startsWith("$slotifyRoot/")
        rawUnder && !targetUnder
    }
    val underSlotify = !matchedViaSlotifyLink &&
        (localPath == slotifyRoot || localPath.startsWith("$slotifyRoot/"))
    val relPath = if (matchedViaSlotifyLink) {
        // Prefer path of the resolved original source relative to Music roots.
        com.slotify.app.model.LocalTrack(filePath = localPath).relativeToRoots(roots)
    } else {
        match.local.relativeToRoots(roots)
    }
    // Existing library (Muse etc.) — not a Slotify download/pin target.
    // Also true when a Slotify symlink resolves back to a library file.
    val isExistingLibrary = !underSlotify && relPath != null

    // Re-read chips when a backfill write bumps the store revision.
    val loudnessRevision by state.loudnessStore.revision.collectAsState()
    val loudness = remember(loudnessRevision, settings.showLoudnessChips, localPath) {
        if (settings.showLoudnessChips && supportsLoudnessBalance()) {
            state.loudnessStore.track(localPath)
        } else {
            null
        }
    }
    var showLoudnessSheet by remember { mutableStateOf(false) }
    if (showLoudnessSheet && loudness != null) {
        LoudnessDetailDialog(
            track = loudness,
            meanLufs = state.playlistMeanLufs(playlistId),
            maxBoostDb = settings.loudnessMaxBoostDb,
            balanceEnabled = settings.loudnessBalance,
            isPlaying = playerSnap.isPlaying,
            onDismiss = { showLoudnessSheet = false },
            onMode = { mode, gain, limitThreshold ->
                state.loudnessStore.setCorrection(
                    localPath,
                    mode,
                    gain,
                    customLimitThresholdDb = limitThreshold,
                )
                // Immediate live apply for the track under the cursor (not only
                // the next playAt) — slider feedback while this song is loaded.
                if (nowPlaying?.path == localPath ||
                    com.slotify.app.data.resolveSymlinkTarget(nowPlaying?.path ?: "") ==
                    localPath
                ) {
                    state.refreshLoudnessForCurrentTrack()
                }
            },
        )
    }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var redoFired by remember { mutableStateOf(false) }

    // Hold 5s → redo; swipe left past threshold → redo; short press → play.
    val gestureModifier = Modifier
        .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
        .pointerInput(playlistId, originalIndex, row.remote) {
            val threshold = 96.dp.toPx()
            val tapSlop = viewConfiguration.touchSlop
            val holdMs = 5_000L
            var downTime = 0L
            var startX = 0f
            var startY = 0f
            var holdFired = false
            var swiped = false

            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val pressed = change.pressed
                    val justDown = pressed && !change.previousPressed
                    val justUp = !pressed && change.previousPressed

                    if (justDown) {
                        downTime = change.uptimeMillis
                        startX = change.position.x
                        startY = change.position.y
                        holdFired = false
                        swiped = false
                        redoFired = false
                        dragOffsetX = 0f
                    }

                    if (pressed) {
                        val held = change.uptimeMillis - downTime
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        // Only a stationary finger may trigger hold-to-refetch —
                        // scrolling must never count as a hold.
                        val moved = abs(dx) > tapSlop || abs(dy) > tapSlop
                        if (!holdFired && !moved && held >= holdMs) {
                            holdFired = true
                            redoFired = true
                            change.consume()
                            dragOffsetX = 0f
                            onRedoYoutube()
                            continue
                        }
                        if (!holdFired && dx < 0f) {
                            dragOffsetX = dx.coerceAtLeast(-threshold * 1.5f)
                            if (dx <= -threshold) {
                                if (!swiped) {
                                    swiped = true
                                    redoFired = true
                                    change.consume()
                                    dragOffsetX = 0f
                                    onRedoYoutube()
                                } else {
                                    change.consume()
                                }
                            }
                        }
                    } else if (justUp) {
                        // Taps are handled by the content-area clickable below —
                        // chips and buttons must never trigger a pick from here.
                        dragOffsetX = 0f
                        redoFired = false
                    }
                }
            }
        }

    val dimmed = row.mark == RowMark.DELETE || row.mark == RowMark.SKIP

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(gestureModifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkChip(
            mark = row.mark,
            onClick = { state.cyclePlaylistRowMark(playlistId, Matcher.trackKey(row.remote)) },
        )
        Spacer(Modifier.width(6.dp))
        // Only the text block picks a song — chips/buttons handle themselves.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { state.requestPlaylistPick(playlistId, originalIndex) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${originalIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.remote.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (dimmed) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (loudness != null) {
                        Text(
                            text = loudness.chip0_99.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (loudness.chip0_99 < 40) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                .clickable { showLoudnessSheet = true },
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(row.remote.artistNames.ifBlank { "Unknown artist" })
                        row.remote.durationMs?.let { append(" · ").append(formatMs(it)) }
                        append(" · ").append(match.local.displayTitle)
                        if (row.mark == RowMark.SKIP) append(" · skipped in play")
                        if (row.mark == RowMark.DELETE) append(" · marked for delete")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // Existing-library hits are amber; Slotify downloads stay muted.
                    color = when {
                        row.mark == RowMark.DELETE -> MaterialTheme.colorScheme.error
                        isExistingLibrary -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Path under ~/Music only (e.g. Muse.Collection[…]/…), not absolute.
                if (isExistingLibrary) {
                    Text(
                        text = relPath.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (matchedViaSlotifyLink) {
                    // Staged symlink — show where it actually lives on disk too.
                    Text(
                        text = "→ " + (match.local.relativeToRoots(roots) ?: match.local.filePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (underSlotify) {
                    val under = localPath.removePrefix("$slotifyRoot/").substringBeforeLast('/', "")
                    Text(
                        text = if (under.isEmpty()) "Slotify" else "Slotify/$under",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (state.youtube.isSupported) {
            IconButton(onClick = onRedoYoutube) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Re-fetch from YouTube",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Same pattern as the playlist list: Stop while this row is playing.
        IconButton(onClick = { state.requestPlaylistPick(playlistId, originalIndex) }) {
            Icon(
                imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlayingThis) "Stop" else "Play",
                tint = if (isPlayingThis) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun LoudnessDetailDialog(
    track: TrackLoudness,
    meanLufs: Double?,
    maxBoostDb: Float,
    balanceEnabled: Boolean,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onMode: (CorrectionMode, Double, Double) -> Unit,
) {
    // Local drag state so the slider tracks the finger; store + audio update every tick.
    var gain by androidx.compose.runtime.remember(track.correction) {
        androidx.compose.runtime.mutableFloatStateOf(track.customGainDb.toFloat())
    }
    var thr by androidx.compose.runtime.remember(track.correction) {
        androidx.compose.runtime.mutableFloatStateOf(track.customLimitThresholdDb.toFloat())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loudness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!balanceEnabled) {
                    Text(
                        text = "Loudness balance is off in Settings — corrections will not play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Chip ${track.chip0_99}/99 · " +
                        "${"%.1f".format(track.integratedLufs)} LUFS",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "LRA ${"%.1f".format(track.loudnessRange)} LU · " +
                        "peak ${"%.1f".format(track.truePeakDbtp)} dBTP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rec = recommendedBoostDb(
                    track.integratedLufs,
                    meanLufs,
                    maxBoostDb.toDouble(),
                )
                Text(
                    text = "Recommended boost: ${"%+.1f".format(rec)} dB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                CorrectionMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = track.correction == mode,
                            onClick = {
                                onMode(mode, gain.toDouble(), thr.toDouble())
                            },
                        )
                        Text(modeLabel(mode))
                    }
                }
                if (track.correction == CorrectionMode.CUSTOM) {
                    Text(
                        text = "Custom gain: ${"%+.1f".format(gain)} dB" +
                            if (!isPlaying) " — press play to hear changes" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = gain,
                        onValueChange = { v ->
                            gain = v
                            onMode(CorrectionMode.CUSTOM, v.toDouble(), thr.toDouble())
                        },
                        valueRange = -12f..24f,
                    )
                    Text(
                        text = if (thr <= -0.05f) {
                            "Limit starts at ${"%.1f".format(thr)} dBFS " +
                                "(${fmtAbs(thr.toDouble())} dB below max)"
                        } else {
                            "Limit starts at max volume (0 dBFS)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = thr,
                        onValueChange = { v ->
                            thr = v
                            onMode(CorrectionMode.CUSTOM, gain.toDouble(), v.toDouble())
                        },
                        valueRange = -24f..0f,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun fmtAbs(db: Double): String {
    val a = kotlin.math.abs(db)
    val whole = a.toInt()
    val tenths = (((a - whole) * 10) + 0.5).toInt()
    return if (tenths == 0) "$whole" else "$whole.$tenths"
}

private fun modeLabel(mode: CorrectionMode): String = when (mode) {
    CorrectionMode.RECOMMENDED -> "Recommended"
    CorrectionMode.BYPASS -> "Bypass"
    CorrectionMode.CUSTOM -> "Custom"
}

private fun nowPlayingPath(state: AppState): String? = state.nowPlaying.value?.path

private fun rowMatchesNowPlaying(
    row: ResolvedPlaylistTrack,
    path: String?,
    title: String,
    artist: String,
): Boolean {
    val local = row.match?.local?.filePath
    if (local != null && path != null) {
        if (local == path) return true
        if (com.slotify.app.data.resolveSymlinkTarget(local) == path) return true
    }
    return row.remote.name == title &&
        (artist.isBlank() || artist == row.remote.artistNames ||
            row.remote.artistNames.contains(artist))
}