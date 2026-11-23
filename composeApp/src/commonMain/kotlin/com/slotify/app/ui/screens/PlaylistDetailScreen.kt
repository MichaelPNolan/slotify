package com.slotify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.slotify.app.match.Matcher
import com.slotify.app.model.RemoteTrack
import com.slotify.app.model.ResolvedPlaylistTrack
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.ui.MarkChip
import com.slotify.app.ui.formatMs
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlaylistDetailScreen(state: AppState, playlistId: String) {
    val playlists by state.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val youtubeStatus by state.youtubeStatus.collectAsState()
    val batchRunning by state.youtubeBatchRunning.collectAsState()
    val activePlaylistId by state.activePlaylistId.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
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
            IconButton(onClick = { state.rematchPlaylist(playlist.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Re-match")
            }
            // Playlist delete lives on the list view (mark → Delete marked),
            // not as a one-click trash icon here.
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { state.togglePlaylistPlay(playlist.id, 0) },
                enabled = playlist.matchedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                val playingThis = activePlaylistId == playlist.id && playerSnap.isPlaying
                Icon(
                    if (playingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        playingThis -> "Stop"
                        playlist.matchedCount > 0 -> "Play matches"
                        else -> "No matches yet"
                    },
                )
            }
            OutlinedButton(onClick = { state.rematchPlaylist(playlist.id) }) {
                Text("Re-match")
            }
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

        // Batch: auto-fetch unmatched rows whose best YouTube hit scores ≥ 85%.
        if (playlist.toBeSourced.isNotEmpty() && state.youtube.isSupported) {
            Spacer(Modifier.size(4.dp))
            OutlinedButton(
                onClick = { state.batchFetchYoutube(playlist.id, minScore = 0.85) },
                enabled = !batchRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (batchRunning) {
                        "Fetching ≥85% matches…"
                    } else {
                        "YouTube batch — fetch matches ≥85%"
                    },
                )
            }
            youtubeStatus?.let { s ->
                if (s.startsWith("Batch") || s.startsWith("Search") || s.contains("download")) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (batchRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
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

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                            text = "No local file yet — link a file or fetch from YouTube",
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

@Composable
private fun UnmatchedRow(
    state: AppState,
    playlistId: String,
    row: ResolvedPlaylistTrack,
    onFindOnYoutube: (com.slotify.app.model.RemoteTrack) -> Unit,
    onLinkFile: (com.slotify.app.model.RemoteTrack) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                )
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