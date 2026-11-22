package com.slotify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.state.AppState
import com.slotify.app.state.SpotifyStatus

@Composable
fun PlaylistsScreen(state: AppState, onOpenSettings: () -> Unit) {
    val playlists by state.playlists.collectAsState()
    val importStatus by state.importStatus.collectAsState()
    val spotifyStatus by state.spotifyStatus.collectAsState()
    val spotifyName by state.spotifyDisplayName.collectAsState()
    var playlistUrl by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Playlists", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (playlists.isNotEmpty()) {
                FilledTonalButton(onClick = { state.importFromSpotify() }) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }

        // Paste a playlist URL → import that playlist only.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = playlistUrl,
                onValueChange = { playlistUrl = it },
                modifier = Modifier.weight(1f),
                label = { Text("Paste playlist URL") },
                placeholder = { Text("https://open.spotify.com/playlist/…") },
                singleLine = true,
                supportingText = {
                    Text(
                        when (spotifyStatus) {
                            SpotifyStatus.CONNECTED ->
                                "Import just this playlist (metadata only)"
                            else ->
                                "Public playlists work without login; private ones use your Floorp session"
                        },
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    state.importFromSpotifyUrl(playlistUrl.trim())
                },
                enabled = playlistUrl.isNotBlank() &&
                    (importStatus == null || !(importStatus!!.startsWith("Importing") || importStatus!!.startsWith("Fetching") || importStatus!!.startsWith("Connecting") || importStatus!!.startsWith("Starting"))),
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import")
            }
        }

        val status = importStatus
        when {
            status != null -> {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (status.startsWith("Importing") || status.startsWith("Starting")) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            spotifyStatus == SpotifyStatus.CONNECTED -> {
                Text(
                    text = "Connected as ${spotifyName ?: "you"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            playlists.isEmpty() -> {
                EmptyPlaylistsHint(onOpenSettings = onOpenSettings, onImport = { state.importFromSpotify() })
            }
        }

        if (playlists.isNotEmpty()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.openPlaylist(playlist.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            val total = playlist.resolvedTracks.size
                            val matched = playlist.matchedCount
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$matched/$total matched",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (total > 0 && matched == total) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                playlist.remote.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (matched > 0) {
                                IconButton(onClick = { state.playPlaylist(playlist.id, 0) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaylistsHint(onOpenSettings: () -> Unit, onImport: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("No playlists yet", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "1. Connect your Spotify account in Settings\n" +
                    "2. Add folders that hold your music\n" +
                    "3. Scan the library — playlists match automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                TextButton(onClick = onImport) { Text("Import now") }
            }
        }
    }
}
