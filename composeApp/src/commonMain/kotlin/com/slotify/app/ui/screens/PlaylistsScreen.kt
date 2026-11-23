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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.platform.readClipboardText
import com.slotify.app.state.AppState
import com.slotify.app.state.SpotifyStatus
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(state: AppState, onOpenSettings: () -> Unit) {
    val allPlaylists by state.playlists.collectAsState()
    val playlists = allPlaylists.filterNot { it.trashed }
    val trashed = allPlaylists.filter { it.trashed }
    val importStatus by state.importStatus.collectAsState()
    val spotifyStatus by state.spotifyStatus.collectAsState()
    val spotifyName by state.spotifyDisplayName.collectAsState()
    val activePlaylistId by state.activePlaylistId.collectAsState()
    val shuffle by state.shuffleEnabled.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val playingNow = playerSnap.isPlaying
    val searchHits by state.playlistSearchResults.collectAsState()
    val searchStatus by state.playlistSearchStatus.collectAsState()
    val searchRunning by state.playlistSearchRunning.collectAsState()
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var publicQuery by rememberSaveable { mutableStateOf("") }
    var clipboardHint by rememberSaveable { mutableStateOf<String?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var deleteTrashAudio by rememberSaveable { mutableStateOf(false) }
    // Collapsed by default: the page is just the playlist list until asked.
    var showGetPlaylist by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trashOnlyCount = remember(trashed, allPlaylists) {
        if (trashed.isEmpty()) 0 else state.trashOnlyAudioPaths().size
    }

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
            // Reveals URL import and public search.
            FilledTonalButton(onClick = { showGetPlaylist = !showGetPlaylist }) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (showGetPlaylist) "Hide" else "Get Playlist")
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (showGetPlaylist) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (showGetPlaylist) {
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
                    label = { Text("Playlist URL") },
                    placeholder = { Text("https://open.spotify.com/playlist/…") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                    supportingText = {
                        val hint = clipboardHint
                        when {
                            hint != null -> Text(hint, color = MaterialTheme.colorScheme.error)
                            spotifyStatus == SpotifyStatus.CONNECTED ->
                                Text("Paste (button or Ctrl+V) · Import adds just this playlist")
                            else ->
                                Text("Public playlists work without login")
                        }
                    },
                )
                Spacer(Modifier.width(4.dp))
                // Explicit paste — some desktop IMEs / focus states swallow Ctrl+V.
                IconButton(
                    onClick = {
                        scope.launch {
                            val clip = readClipboardText()
                            when {
                                clip.isNullOrEmpty() -> {
                                    clipboardHint = "Clipboard is empty or unreadable — copy a URL first"
                                }
                                else -> {
                                    playlistUrl = clip
                                    clipboardHint = null
                                }
                            }
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = "Paste from clipboard",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(4.dp))
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

            // Search public Spotify playlists (artist / genre / …) → pick → import.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = publicQuery,
                    onValueChange = { publicQuery = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Search public playlists") },
                    placeholder = { Text("Artist name, genre…") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            if (publicQuery.isNotBlank()) {
                                // Always allowed — cancels an in-flight run and restarts.
                                state.searchPublicPlaylists(publicQuery)
                            }
                        },
                    ),
                    supportingText = {
                        searchStatus?.let {
                            Text(it, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
                Spacer(Modifier.width(4.dp))
                // Stay enabled while searching: another press cancels + uses new text.
                FilledTonalButton(
                    onClick = { state.searchPublicPlaylists(publicQuery) },
                    enabled = publicQuery.isNotBlank(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (searchRunning) "Search…" else "Search")
                }
            }
            if (searchRunning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            if (searchHits.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    searchHits.forEach { hit ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hit.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = hit.spotifyUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { state.importFromSpotifyUrl(hit.url) },
                                enabled = !searchRunning,
                            ) {
                                Text("Import")
                            }
                        }
                    }
                    TextButton(onClick = { state.clearPlaylistSearch() }) {
                        Text("Clear results")
                    }
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
            }
        } else if (playlists.isEmpty()) {
            // Still need a path when the panel is collapsed and the library is empty.
            EmptyPlaylistsHint(
                onOpenSettings = onOpenSettings,
                onImport = { showGetPlaylist = true },
                onShowGetPlaylist = { showGetPlaylist = true },
            )
        }

        // Soft-delete: mark rows in the list, then move to trash.
        val markedPlaylists = playlists.count { it.markedForDelete }
        if (markedPlaylists > 0) {
            OutlinedButton(
                onClick = { state.deleteMarkedPlaylists() },
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
                    "Move marked to trash ($markedPlaylists)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (playlists.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.openPlaylist(playlist.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (playlist.markedForDelete) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (playlist.markedForDelete) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
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
                                    color = if (playlist.markedForDelete) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = buildString {
                                        append("$matched/$total matched")
                                        if (playlist.markedForDelete) append(" · marked for delete")
                                        if (shuffle && activePlaylistId == playlist.id) append(" · shuffle")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        playlist.markedForDelete -> MaterialTheme.colorScheme.error
                                        total > 0 && matched == total -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                            // Mark / unmark for delete (same idea as track Del chip).
                            SuggestionChip(
                                onClick = { state.togglePlaylistMarkForDelete(playlist.id) },
                                label = {
                                    Text(
                                        if (playlist.markedForDelete) "Marked" else "Mark",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (playlist.markedForDelete) {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                                    },
                                    labelColor = if (playlist.markedForDelete) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                ),
                            )
                            Spacer(Modifier.width(4.dp))
                            if (matched > 0) {
                                val isThisPlaying =
                                    activePlaylistId == playlist.id && playingNow
                                IconButton(
                                    onClick = { state.togglePlaylistPlay(playlist.id) },
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) {
                                            Icons.Default.Stop
                                        } else {
                                            Icons.Default.PlayArrow
                                        },
                                        contentDescription = if (isThisPlaying) {
                                            "Stop"
                                        } else {
                                            "Play"
                                        },
                                        tint = if (isThisPlaying) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (trashed.isEmpty() && showGetPlaylist) {
            // Empty hint already shown above when panel collapsed and empty.
        }

        // ── Trash ────────────────────────────────────────────────────
        // Always visible (even at 0) so the view is discoverable on all devices.
        HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Trash (${trashed.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showTrash = !showTrash }) {
                    Text(if (showTrash) "Hide" else "Show")
                }
            }
            if (showTrash) {
                if (trashed.isEmpty()) {
                    Text(
                        text = "Trash is empty — deleted playlists will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Text(
                    text = "Deleted playlists stay here until you empty the trash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (trashOnlyCount > 0) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deleteTrashAudio,
                            onCheckedChange = { deleteTrashAudio = it },
                        )
                        Text(
                            text = "Also delete $trashOnlyCount audio file(s) used only by these playlists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        state.emptyTrash(deleteUniqueAudio = deleteTrashAudio)
                        deleteTrashAudio = false
                    },
                    enabled = trashed.isNotEmpty(),
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
                    Text("Empty trash", color = MaterialTheme.colorScheme.error)
                }
                trashed.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${playlist.matchedCount}/${playlist.resolvedTracks.size} matched · in trash",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                        TextButton(onClick = { state.restorePlaylistFromTrash(playlist.id) }) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Restore")
                        }
                    }
                }
            }
    }
}

@Composable
private fun EmptyPlaylistsHint(
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onShowGetPlaylist: () -> Unit,
) {
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
                text = "1. Get a playlist — paste its URL or search public playlists\n" +
                    "2. Add folders that hold your music\n" +
                    "3. Scan the library — playlists match automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                TextButton(onClick = onShowGetPlaylist) { Text("Get Playlist") }
                TextButton(onClick = onImport) { Text("Show import") }
            }
        }
    }
}
