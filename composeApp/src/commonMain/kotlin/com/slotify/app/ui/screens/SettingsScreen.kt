package com.slotify.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.slotify.app.loudness.supportsLoudnessBalance
import com.slotify.app.model.RowMark
import com.slotify.app.state.AppState
import com.slotify.app.ui.CoverBackdrop
import com.slotify.app.ui.FixSongNamesDialog
import com.slotify.app.ui.LegalDisclaimerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val scanStatus by state.scanStatus.collectAsState()
    val importStatus by state.importStatus.collectAsState()
    val library by state.library.collectAsState()
    val fixNames by state.fixNames.collectAsState()

    var newRoot by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var showLegalNotice by remember { mutableStateOf(false) }
    var showDeleteMarked by remember { mutableStateOf(false) }
    val markedForDelete = library.count { it.mark == RowMark.DELETE }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            CoverBackdrop(state)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ── Import playlist URL ──────────────────────────────────────
                SectionTitle("Import playlist URL")
                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://open.spotify.com/playlist/…") },
                    singleLine = true,
                    supportingText = {
                        Text("Metadata only — never Spotify audio. Public playlists need no sign-in")
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
                OutlinedButton(
                    onClick = { state.beginFixSongNames() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fix song names…")
                }

                HorizontalDivider()

                // ── YouTube batch minimum match ──────────────────────────────
                SectionTitle("YouTube batch")
                Text(
                    text = "Minimum ranked match score before the batch downloads a hit. " +
                        "Lower accepts more (riskier); higher only takes strong matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val batchPct = (settings.youtubeBatchMinScore * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Min match",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$batchPct%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = settings.youtubeBatchMinScore.toFloat(),
                    onValueChange = { v ->
                        state.updateSettings { s -> s.copy(youtubeBatchMinScore = v.toDouble()) }
                    },
                    valueRange = 0.50f..1.0f,
                    steps = 49, // 1% increments from 50–100
                    modifier = Modifier.fillMaxWidth(),
                )

                if (supportsLoudnessBalance()) {
                    HorizontalDivider()
                    // ── Loudness balance ──────────────────────────────────
                    SectionTitle("Loudness balance")
                    Text(
                        text = "Analyse songs and balance quiet tracks toward this playlist’s " +
                            "average loudness. Loud songs are limited, never cut. API 28+ only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Enable balance",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.loudnessBalance,
                            onCheckedChange = { on ->
                                state.updateSettings { s -> s.copy(loudnessBalance = on) }
                                // Re-push correction for the loaded track immediately.
                                state.refreshLoudnessForCurrentTrack()
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Max boost",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "+${settings.loudnessMaxBoostDb.toInt()} dB",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = settings.loudnessMaxBoostDb,
                        onValueChange = { v ->
                            state.updateSettings { s -> s.copy(loudnessMaxBoostDb = v) }
                        },
                        valueRange = 0f..24f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Default limit threshold (per-track Custom overrides it)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    com.slotify.app.ui.LoudnessThresholdSlider(
                        thresholdDb = settings.loudnessDefaultThresholdDb,
                        onThresholdChange = { v ->
                            state.updateSettings { s ->
                                s.copy(loudnessDefaultThresholdDb = v.coerceIn(-24f, 0f))
                            }
                            state.refreshLoudnessForCurrentTrack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Show loudness chips",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.showLoudnessChips,
                            onCheckedChange = { on ->
                                state.updateSettings { s -> s.copy(showLoudnessChips = on) }
                            },
                        )
                    }

                    HorizontalDivider()
                    SectionTitle("Dynamics engine (Android)")
                    Text(
                        text = "Off = built-in DynamicsProcessing (boost + limiter). " +
                            "On = alt LoudnessEnhancer (separate effect, boost only) — " +
                            "use to test whether session FX are audible at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Dynamics engine",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.dynamicsEngine == "alt",
                            onCheckedChange = { alt ->
                                state.updateSettings { s ->
                                    s.copy(dynamicsEngine = if (alt) "alt" else "builtin")
                                }
                            },
                        )
                    }
                    val dynStatus by state.dynamicsStatus.collectAsState()
                    Text(
                        text = dynStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // ── YouTube cookies.txt (yt-dlp auth — not Spotify) ─────────
                SectionTitle("YouTube cookies")
                val ytCookies by state.youtubeCookiesStatus.collectAsState()
                Text(
                    text = "Optional Netscape cookies.txt for yt-dlp when YouTube says " +
                        "“Sign in to confirm you’re not a bot.” Copied into app-private " +
                        "storage only — never public Downloads. Unrelated to Spotify " +
                        "browser session cookies. Contents are never shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ytCookies,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ytCookies.startsWith("Configured")) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { state.importYoutubeCookiesFromPicker() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import cookies.txt…")
                    }
                    OutlinedButton(
                        onClick = { state.clearYoutubeCookiesFromSettings() },
                        enabled = ytCookies.startsWith("Configured"),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Clear", color = MaterialTheme.colorScheme.error)
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
    }

        fixNames?.let { session ->
            FixSongNamesDialog(
                session = session,
                onRename = { state.fixNamesRename(it) },
                onSkip = { state.fixNamesSkip() },
                onClose = { state.closeFixSongNames() },
            )
        }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
