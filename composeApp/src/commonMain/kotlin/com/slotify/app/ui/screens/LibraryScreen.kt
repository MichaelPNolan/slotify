package com.slotify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.model.LocalTrack
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.ui.AddToPlaylistDialog
import com.slotify.app.ui.MarkChip
import com.slotify.app.ui.formatMs
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(state: AppState) {
    val library by state.library.collectAsState()
    val scanStatus by state.scanStatus.collectAsState()
    val nowPlaying by state.nowPlaying.collectAsState()
    val playedKeys by state.playedKeys.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val playlists by state.playlists.collectAsState()
    val settings by state.settings.collectAsState()
    val followEnabled by state.libraryFollow.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var query by remember { mutableStateOf("") }
    var addTarget by remember { mutableStateOf<LocalTrack?>(null) }

    // User drag on the list releases auto-follow until the title is tapped again.
    LaunchedEffect(isDragged) {
        if (isDragged && followEnabled) state.releaseLibraryFollow()
    }

    val filtered = remember(library, query) {
        if (query.isBlank()) library
        else {
            val q = query.trim().lowercase()
            library.filter {
                it.displayTitle.lowercase().contains(q) || it.displayArtist.lowercase().contains(q)
            }
        }
    }

    // While following, recenter whenever the current song (or list) changes.
    LaunchedEffect(followEnabled, nowPlaying?.path, filtered) {
        if (!followEnabled) return@LaunchedEffect
        val path = nowPlaying?.path ?: return@LaunchedEffect
        val idx = filtered.indexOfFirst { it.filePath == path }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${library.size} file(s)" +
                        if (query.isNotBlank()) " · ${filtered.size} match(es)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = {
                    val path = nowPlaying?.path
                    if (path == null) {
                        state.showMessage("Nothing playing")
                        return@FilledTonalButton
                    }
                    val idx = filtered.indexOfFirst { it.filePath == path }
                    if (idx < 0) {
                        state.showMessage("Playing track is hidden by search")
                    } else {
                        scope.launch { listState.animateScrollToItem(idx) }
                    }
                },
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find current")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { state.scanLibrary() }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan")
            }
        }

        scanStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (status.startsWith("Scanning") || status.startsWith("Reading")) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search title or artist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        if (filtered.isEmpty()) {
            Text(
                text = if (library.isEmpty()) {
                    "Add folders in Settings, then press Scan to index your music."
                } else {
                    "Nothing matches \"$query\""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                itemsIndexed(filtered, key = { _, it -> it.filePath }) { index, track ->
                    val isCurrent = nowPlaying?.path == track.filePath
                    val isPlayed = track.filePath in playedKeys
                    val markDimmed = track.mark != null
                    val titleColor = when {
                        isCurrent -> MaterialTheme.colorScheme.surface
                        track.mark == RowMark.DELETE -> MaterialTheme.colorScheme.error
                        isPlayed || markDimmed ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val subColor = when {
                        isCurrent -> MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        track.mark == RowMark.DELETE ->
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        isPlayed || markDimmed ->
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.onSurface
                                else Color.Transparent,
                            )
                            .clickable { state.requestLibraryPick(filtered, index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MarkChip(
                            mark = track.mark,
                            onClick = { state.cycleLibraryRowMark(track.filePath) },
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(track.displayArtist)
                                    track.album?.let { append(" · ").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = subColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = track.durationMs?.let { formatMs(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = subColor,
                        )
                        IconButton(
                            onClick = { addTarget = track },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add to playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        val showStop = isCurrent && playerSnap.isPlaying
                        IconButton(
                            onClick = {
                                if (isCurrent) state.togglePlay()
                                else state.requestLibraryPick(filtered, index)
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (showStop) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (showStop) "Stop" else "Play",
                                tint = if (showStop) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    addTarget?.let { track ->
        AddToPlaylistDialog(
            track = track,
            playlists = playlists.filterNot { it.trashed },
            lastAddedId = settings.lastAddedPlaylistId,
            onCreate = { name ->
                state.createPlaylistWithTrack(name, track)
                addTarget = null
            },
            onAdd = { playlistId ->
                state.addToPlaylist(playlistId, track)
                addTarget = null
            },
            onDismiss = { addTarget = null },
        )
    }
}
