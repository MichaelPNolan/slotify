package com.slotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack

/** Create-new / add-to-existing confirmation for one library song (S8).
 *  The playlist list is a bounded LazyColumn — it scrolls natively, so every
 *  playlist stays reachable on small windows. Last-added-to sorts to the top. */
@Composable
fun AddToPlaylistDialog(
    track: LocalTrack,
    playlists: List<AppPlaylist>,
    lastAddedId: String? = null,
    onCreate: (name: String) -> Unit,
    onAdd: (playlistId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    val sorted = remember(playlists, lastAddedId) {
        playlists.sortedWith(compareByDescending { it.id == lastAddedId })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "“${track.displayTitle}” — create a new playlist or pick an existing one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New playlist name") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { onCreate(newName.trim()) },
                    enabled = newName.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create new playlist") }
                HorizontalDivider()
                if (sorted.isEmpty()) {
                    Text(
                        text = "No existing playlists yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // heightIn FIRST so the LazyColumn gets a hard bound and
                    // scrolls — putting verticalScroll on an outer Column made
                    // the list clip instead of scroll.
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(sorted, key = { it.id }) { pl ->
                            val isLast = pl.id == lastAddedId
                            Text(
                                text = buildString {
                                    append(pl.name)
                                    append(" (${pl.resolvedTracks.size})")
                                    if (isLast) append(" · last added")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(pl.id) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
