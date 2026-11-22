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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.slotify.app.state.AppState
import com.slotify.app.state.SpotifyStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: AppState, pkceAwait: suspend (String) -> String) {
    val settings by state.settings.collectAsState()
    val spotifyStatus by state.spotifyStatus.collectAsState()
    val spotifyName by state.spotifyDisplayName.collectAsState()
    val spotifyError by state.spotifyError.collectAsState()
    val scanStatus by state.scanStatus.collectAsState()
    val importStatus by state.importStatus.collectAsState()
    val library by state.library.collectAsState()

    var clientId by remember(settings.spotifyClientId) { mutableStateOf(settings.spotifyClientId) }
    var spDc by remember { mutableStateOf("") }
    var newRoot by remember { mutableStateOf("") }
    var jsonPath by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }

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
                    text = "Metadata only — Slotify never downloads Spotify audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Spotify client ID") },
                    singleLine = true,
                    supportingText = {
                        Text("Free app at developer.spotify.com/dashboard · redirect URI http://127.0.0.1:43821/callback")
                    },
                )
                Button(
                    onClick = { state.connectOAuth(clientId.trim(), pkceAwait) },
                    enabled = clientId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Spotify (browser)")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Playlist URLs work without signing in (public embed). " +
                        "For private playlists, Slotify reads the Floorp/Firefox sp_dc cookie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { state.connectWithBrowserSession() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check browser session cookie") }
                Text(
                    text = "Reads sp_dc from Floorp/Firefox on this machine. Metadata only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = spDc,
                    onValueChange = { spDc = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("sp_dc cookie value (manual)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text("Optional: DevTools → Application → Cookies → open.spotify.com → copy sp_dc")
                    },
                )
                OutlinedButton(
                    onClick = { state.connectCookie(spDc.trim()) },
                    enabled = spDc.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect with cookie") }
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

        HorizontalDivider()

        // ── JSON import ──────────────────────────────────────────────
        SectionTitle("Import playlist from file")
        OutlinedTextField(
            value = jsonPath,
            onValueChange = { jsonPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Path to playlist JSON") },
            singleLine = true,
            supportingText = {
                Text("""Schema: {"name":"…","tracks":[{"name":"…","artists":["…"],"durationMs":123000}]}""")
            },
        )
        OutlinedButton(
            onClick = { state.importFromJsonFile(jsonPath.trim()) },
            enabled = jsonPath.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import file")
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
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
