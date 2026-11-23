package com.slotify.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.state.SpotifyStatus
import com.slotify.app.ui.LegalDisclaimerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val spotifyStatus by state.spotifyStatus.collectAsState()
    val spotifyName by state.spotifyDisplayName.collectAsState()
    val spotifyError by state.spotifyError.collectAsState()
    val scanStatus by state.scanStatus.collectAsState()
    val importStatus by state.importStatus.collectAsState()
    val library by state.library.collectAsState()

    var newRoot by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var showLegalNotice by remember { mutableStateOf(false) }
    var showDeleteMarked by remember { mutableStateOf(false) }
    val markedForDelete = library.count { it.mark == RowMark.DELETE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ── Spotify ──────────────────────────────────────────────────
        SectionTitle("Spotify connection")
        // Persistent failure display — snackbars vanish; this doesn't.
        spotifyError?.let { err ->
            Text(
                text = "Last error: $err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (spotifyStatus) {
            SpotifyStatus.CONNECTED -> {
                Text(
                    text = "Connected${spotifyName?.let { " as $it" } ?: ""}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { state.disconnectSpotify() }) { Text("Disconnect") }
            }

            SpotifyStatus.CONNECTING -> Text("Connecting…", color = MaterialTheme.colorScheme.primary)

            else -> {
                Text(
                    text = "Metadata only — Slotify never downloads Spotify audio. " +
                        "Playlist URLs and public search work without signing in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
        SectionTitle("Import playlist URL")
        OutlinedTextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("https://open.spotify.com/playlist/…") },
            singleLine = true,
            supportingText = {
                Text("Public playlists need no sign-in; private ones use your Floorp session cookie")
            },
        )
        OutlinedButton(
            onClick = { state.importFromSpotifyUrl(playlistUrl.trim()) },
            enabled = playlistUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import this playlist") }
        importStatus?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        // ── Library ──────────────────────────────────────────────────
        SectionTitle("Music library (${library.size} indexed)")
        Text(
            text = "Folders scanned for matching. On Android use the same kind of roots " +
                "as desktop (e.g. /sdcard/Music on Android) — Slotify’s own folder is " +
                "under Music and is included automatically after you copy files there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        settings.libraryRoots.forEach { root ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = root,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    state.updateSettings { s -> s.copy(libraryRoots = s.libraryRoots - root) }
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove folder")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newRoot,
                onValueChange = { newRoot = it },
                modifier = Modifier.weight(1f),
                label = { Text("Add folder path") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val path = newRoot.trim().ifBlank { return@OutlinedButton }
                    state.updateSettings { s ->
                        if (path in s.libraryRoots) s else s.copy(libraryRoots = s.libraryRoots + path)
                    }
                    newRoot = ""
                },
            ) { Text("Add") }
        }
        Button(
            onClick = { state.scanLibrary() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan library & match playlists") }
        scanStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (markedForDelete > 0) {
            OutlinedButton(
                onClick = { showDeleteMarked = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Delete marked from library ($markedForDelete)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()

        // ── Music folder: export/import playlists (symlink stage = desktop only)
        SectionTitle("Music folder")
        if (com.slotify.app.data.supportsSymlinkStaging()) {
            Text(
                text = "Symlink every matched playlist file into the Slotify directory " +
                    "so a folder copy carries only playlist tracks (not the whole Music tree). " +
                    "Copy with “follow symbolic links” (cp -L / rsync -L) so the phone gets real files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { state.stagePlaylistFilesAsSymlinks() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stage playlist files as links into Slotify")
            }
        }
        Text(
            text = com.slotify.app.data.portableJsonExportDir(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (com.slotify.app.data.supportsSymlinkStaging()) {
                "Playlists live in app data, not the music folder, unless you export them. " +
                    "Export writes playlists.json + manual_links.json to the Music folder above."
            } else {
                "Export copies playlists.json + manual_links.json to the Music folder above. " +
                    "Use Pick file… below to import on this device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { state.exportPlaylistsToMusicDir() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export playlists")
        }

        HorizontalDivider()

        // ── JSON import — system file picker only ────────────────────
        SectionTitle("Import playlist from file")
        Text(
            text = "Choose playlists.json (and pins) with the system file picker. " +
                "This is the only import path — safer on Android scoped storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { state.pickAndImportPlaylistJson() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pick file…")
        }

        HorizontalDivider()
        SectionTitle("About")
        Text(
            text = "Slotify — playlists in, your own files out. " +
                "Desktop target for development; Android build shares this code. " +
                "Fuzzy matching ported from Parachord (MIT).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showLegalNotice = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Responsible use & legal notice") }
    }

    if (showLegalNotice) {
        LegalDisclaimerDialog(onDismiss = { showLegalNotice = false })
    }

    if (showDeleteMarked) {
        AlertDialog(
            onDismissRequest = { showDeleteMarked = false },
            title = { Text("Delete $markedForDelete marked file(s)?") },
            text = {
                Text(
                    text = "Removes the marked songs' files from disk and drops them " +
                        "from the library index. Playlists keep their rows, but those " +
                        "tracks become unmatched. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteMarkedLibraryFiles()
                    showDeleteMarked = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMarked = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
