package com.slotify.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.match.Matcher
import com.slotify.app.model.RemoteTrack
import com.slotify.app.state.AppState
import com.slotify.app.ui.formatMs

@Composable
fun PlaylistDetailScreen(state: AppState, playlistId: String) {
    val playlists by state.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var findTarget by remember { mutableStateOf<RemoteTrack?>(null) }

    findTarget?.let { target ->
        FindOnYoutubeDialog(
            state = state,
            playlistId = playlistId,
            track = target,
            onDismiss = { findTarget = null },
        )
    }

    if (playlist == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Playlist not found")
        }
        return
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
                Text(
                    text = "${playlist.matchedCount}/$total matched · ${playlist.source.name.lowercase()} import",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { state.rematchPlaylist(playlist.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Re-match")
            }
            IconButton(onClick = { state.deletePlaylist(playlist.id) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { state.playPlaylist(playlist.id, 0) },
                enabled = playlist.matchedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (playlist.matchedCount > 0) "Play matches" else "No matches yet")
            }
            OutlinedButton(onClick = { state.rematchPlaylist(playlist.id) }) {
                Text("Re-match")
            }
        }

        Spacer(Modifier.size(8.dp))
        HorizontalDivider()
        Spacer(Modifier.size(4.dp))

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            itemsIndexed(playlist.resolvedTracks, key = { i, row -> "${row.remote.id ?: row.remote.name}-$i" }) {
                    index, row ->
                val match = row.match
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = match != null) {
                            if (match != null) state.playPlaylist(playlist.id, index)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.remote.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (match != null) FontWeight.Medium else FontWeight.Normal,
                            color = if (match != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = buildString {
                            append(row.remote.artistNames.ifBlank { "Unknown artist" })
                            row.remote.durationMs?.let { append(" · ").append(formatMs(it)) }
                            if (match == null) append(" · no local file")
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (match != null) {
                        if (match.isManual) {
                            SuggestionChip(
                                onClick = {
                                    state.unlinkTrackFile(playlist.id, Matcher.trackKey(row.remote))
                                },
                                label = { Text("Linked") },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                                    labelColor = MaterialTheme.colorScheme.tertiary,
                                ),
                            )
                            Spacer(Modifier.width(4.dp))
                        } else {
                            ConfidenceChip(confidence = match.confidence)
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = {
                            state.linkTrackToFileFromPicker(playlist.id, Matcher.trackKey(row.remote))
                        }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Link file", style = MaterialTheme.typography.labelMedium)
                        }
                        if (state.youtube.isSupported) {
                            TextButton(onClick = { findTarget = row.remote }) {
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
            }
        }
    }
}

@Composable
private fun ConfidenceChip(confidence: Double) {
    val percent = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.90 -> Color(0xFF66BB6A)
        confidence >= 0.80 -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    SuggestionChip(
        onClick = {},
        label = { Text("$percent%") },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = color,
        ),
    )
}
