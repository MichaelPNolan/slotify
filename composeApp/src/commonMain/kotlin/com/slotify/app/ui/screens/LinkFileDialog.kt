package com.slotify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.library.collectAudioFiles
import com.slotify.app.match.Matcher
import com.slotify.app.model.RemoteTrack
import com.slotify.app.state.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LinkCandidate(
    val path: String,
    /** Filesystem name (full, may wrap in the list). */
    val displayName: String,
)

/**
 * In-app audio file picker for Link file.
 *
 * Replaces the system SAF list on purpose:
 * - filter **starts** with the Spotify artist so nearby files surface first
 * - filenames are shown in full (soft-wrap) — the system picker truncates
 */
@Composable
fun LinkFileDialog(
    state: AppState,
    playlistId: String,
    track: RemoteTrack,
    onDismiss: () -> Unit,
) {
    val artist = remember(track) {
        track.primaryArtist.trim().ifBlank { track.artistNames.trim() }
    }
    // Prefill filter with artist so the list is already narrowed on open.
    var filter by remember(track.videoKeyOrNull()) { mutableStateOf(artist) }
    var candidates by remember { mutableStateOf<List<LinkCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(track, playlistId) {
        loading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                val roots = state.settings.value.libraryRoots
                val files = collectAudioFiles(roots, skipHidden = false)
                files.map { f ->
                    LinkCandidate(
                        path = f.absolutePath,
                        displayName = f.name,
                    )
                }.sortedBy { it.displayName.lowercase() }
            }
        }.onSuccess { list ->
            candidates = list
            loading = false
        }.onFailure { e ->
            loadError = e.message ?: "Cannot list music folder"
            loading = false
        }
    }

    val filtered = remember(candidates, filter) {
        val q = filter.trim()
        if (q.isEmpty()) candidates
        else {
            val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            candidates.filter { c ->
                val hay = c.displayName + " " + c.path
                tokens.all { t -> hay.contains(t, ignoreCase = true) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Link file", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Target: ${track.name}" +
                            (artist.ifBlank { "" }.let { if (it.isEmpty()) "" else " · $it" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter (artist / title / path)") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    supportingText = {
                        Text(
                            text = when {
                                loading -> "Listing audio files…"
                                loadError != null -> loadError!!
                                else -> "Showing ${filtered.size} of ${candidates.size} · full names wrap"
                            },
                        )
                    },
                )

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp))
                        Text("Scanning music folder…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (filtered.isEmpty()) {
                    Text(
                        text = if (candidates.isEmpty()) {
                            "No audio files under the library roots — Scan library first."
                        } else {
                            "No files match “$filter” — try a shorter artist fragment."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.path }) { c ->
                            HorizontalDivider()
                            // Full filename: wrap, never ellipsize — system SAF truncates.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        state.linkTrackToFile(
                                            playlistId,
                                            Matcher.trackKey(track),
                                            c.path,
                                        )
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = c.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                )
                                if (c.path != c.displayName) {
                                    Text(
                                        text = c.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = Int.MAX_VALUE,
                                        overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Stable remember key for a remote track. */
private fun RemoteTrack.videoKeyOrNull(): String = "${id ?: ""}|${name}|${artistNames}"
