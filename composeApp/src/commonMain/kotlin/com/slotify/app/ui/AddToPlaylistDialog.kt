package com.slotify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.slotify.app.model.AppPlaylist
import com.slotify.app.model.LocalTrack

/** Create-new / add-to-existing confirmation for one library song (S8). */
@Composable
fun AddToPlaylistDialog(
    track: LocalTrack,
    playlists: List<AppPlaylist>,
    onCreate: (name: String) -> Unit,
    onAdd: (playlistId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                if (playlists.isEmpty()) {
                    Text(
                        text = "No existing playlists yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                playlists.forEach { pl ->
                    TextButton(
                        onClick = { onAdd(pl.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "${pl.name} (${pl.resolvedTracks.size})", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
