package com.slotify.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slotify.app.state.AppState
import com.slotify.app.state.Tab
import com.slotify.app.ui.screens.LibraryScreen
import com.slotify.app.ui.screens.PlaylistDetailScreen
import com.slotify.app.ui.screens.PlaylistsScreen
import com.slotify.app.ui.screens.SettingsScreen

private val SlotifyColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    secondary = Color(0xFFFFB74D),
    tertiary = Color(0xFF81C784),
)

/**
 * Root composable.
 */
@Composable
fun App(state: AppState) {
    val ui by state.ui.collectAsState()
    val settings by state.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveableStateHolder = rememberSaveableStateHolder()
    // First run shows the legal notice until acknowledged; Settings can re-open it.
    var showLegalNotice by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    MaterialTheme(colorScheme = SlotifyColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Full-window shell: fills the desktop tile (no fixed phone column).
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                        PlayerBar(state)
                        // Visible on every tab, even when nothing is playing.
                        VolumeControl(state)
                        NavigationBar {
                            NavigationBarItem(
                                selected = ui.tab == Tab.PLAYLISTS,
                                onClick = { state.selectTab(Tab.PLAYLISTS) },
                                icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                label = { Text("Playlists") },
                            )
                            NavigationBarItem(
                                selected = ui.tab == Tab.LIBRARY,
                                onClick = { state.selectTab(Tab.LIBRARY) },
                                icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                                label = { Text("Library") },
                            )
                            NavigationBarItem(
                                selected = ui.tab == Tab.SETTINGS,
                                onClick = { state.selectTab(Tab.SETTINGS) },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when {
                        ui.tab == Tab.SETTINGS -> SettingsScreen(state = state)
                        ui.tab == Tab.LIBRARY -> LibraryScreen(state = state)
                        // SaveableStateProvider keeps each playlist's scroll
                        // position alive while the user visits Settings/Library.
                        ui.detailPlaylistId != null -> {
                            val detailId = ui.detailPlaylistId!!
                            saveableStateHolder.SaveableStateProvider(key = detailId) {
                                PlaylistDetailScreen(
                                    state = state,
                                    playlistId = detailId,
                                )
                            }
                        }

                        else -> PlaylistsScreen(
                            state = state,
                            onOpenSettings = { state.selectTab(Tab.SETTINGS) },
                        )
                    }
                    if (!settings.disclaimerAccepted || showLegalNotice) {
                        LegalDisclaimerDialog(onDismiss = {
                            showLegalNotice = false
                            if (!settings.disclaimerAccepted) {
                                state.updateSettings { it.copy(disclaimerAccepted = true) }
                            }
                        })
                    }
                    val pendingPick by state.pendingResetPick.collectAsState()
                    pendingPick?.let {
                        ResetPlayedDialog(
                            onReset = { state.resolvePendingResetPick(reset = true) },
                            onJustPlay = { state.resolvePendingResetPick(reset = false) },
                            onDismiss = { state.cancelPendingResetPick() },
                        )
                    }
                }
            }
        }
    }
}
