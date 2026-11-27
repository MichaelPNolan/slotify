package com.slotify.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slotify.app.loudness.supportsLoudnessBalance
import com.slotify.app.state.AppState
import com.slotify.app.state.showLoudnessAnalyseButton

/**
 * Always-visible volume control, shown on every tab (bottom bar, above the
 * navigation row) — including when nothing is playing.
 * Icon click toggles mute; the slider sets app output volume live and
 * persists when the drag finishes. Right of the (half-width) slider:
 * - Song playing → loudness remedy (Neutral / Boost / Limiting).
 * - Idle + active playlist incomplete → “Analyse loudness” (or Stop).
 */
@Composable
fun VolumeControl(state: AppState) {
    val volume by state.volume.collectAsState()
    val loudness by state.loudnessBar.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val activePlaylistId by state.activePlaylistId.collectAsState()
    val loudnessStatus by state.loudnessStatus.collectAsState()
    val loudnessRevision by state.loudnessStore.revision.collectAsState()
    val playlists by state.playlists.collectAsState()

    val analysisRunning = loudnessStatus != null &&
        !loudnessStatus!!.startsWith("Loudness done") &&
        !loudnessStatus!!.startsWith("Loudness cancelled") &&
        !loudnessStatus!!.startsWith("Loudness failed") &&
        !loudnessStatus!!.startsWith("All tracks")
    val needsAnalysis = remember(activePlaylistId, loudnessRevision, playlists) {
        state.playlistNeedsLoudnessAnalysis(activePlaylistId)
    }
    val showAnalyse = showLoudnessAnalyseButton(
        isPlaying = playerSnap.isPlaying,
        hasActivePlaylist = activePlaylistId != null,
        playlistIncomplete = needsAnalysis,
        loudnessSupported = supportsLoudnessBalance(),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            state.setVolume(if (volume > 0f) 0f else 1f)
        }) {
            Icon(
                imageVector = if (volume <= 0.001f) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (volume <= 0.001f) "Unmute" else "Mute",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        // Half of the remaining row; status / analyse button takes the other half.
        Slider(
            value = volume,
            onValueChange = { state.setVolume(it, persist = false) },
            onValueChangeFinished = { state.setVolume(state.volume.value, persist = true) },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(volume * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        if (showAnalyse) {
            TextButton(
                onClick = {
                    val id = activePlaylistId ?: return@TextButton
                    if (analysisRunning) state.cancelLoudnessBackfill()
                    else state.startLoudnessBackfill(id)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 0.dp),
            ) {
                Text(
                    text = if (analysisRunning) "Stop analysis" else "Analyse loudness",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = loudness.label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    loudness.boostDb > 0.05f -> MaterialTheme.colorScheme.tertiary
                    loudness.limiting -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                maxLines = 1,
            )
        }
    }
}
