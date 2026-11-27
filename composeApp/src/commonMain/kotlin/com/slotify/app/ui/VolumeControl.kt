package com.slotify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.slotify.app.loudness.supportsLoudnessBalance
import com.slotify.app.state.AppState
import com.slotify.app.state.loudnessTargetPlaylistId
import com.slotify.app.state.showLoudnessAnalyseButton

/**
 * Always-visible volume control (every tab). Right of the half-width slider:
 * - Playing → loudness remedy text with a live “reduction” bar behind it
 *   (Android Visualizer peak vs limiter threshold; 0 when idle).
 * - Paused → Analyse loudness / Stop analysis.
 */
@Composable
fun VolumeControl(state: AppState) {
    val volume by state.volume.collectAsState()
    val loudness by state.loudnessBar.collectAsState()
    val playerSnap by state.player.snapshot.collectAsState()
    val ui by state.ui.collectAsState()
    val activePlaylistId by state.activePlaylistId.collectAsState()
    val loudnessStatus by state.loudnessStatus.collectAsState()
    val reduction by state.player.reductionMeter.collectAsState()

    // Prefer the open playlist detail over last-played (user opened AusCockRock
    // but playback was still Thai Orathai — old bug analysed the wrong list).
    val analyseTargetId = loudnessTargetPlaylistId(
        openDetailPlaylistId = ui.detailPlaylistId,
        activePlaybackPlaylistId = activePlaylistId,
    )

    val analysisRunning = loudnessStatus != null &&
        !loudnessStatus!!.startsWith("Loudness done") &&
        !loudnessStatus!!.startsWith("Loudness cancelled") &&
        !loudnessStatus!!.startsWith("Loudness failed") &&
        !loudnessStatus!!.startsWith("All tracks")
    val stopping = loudnessStatus?.startsWith("Stopping analysis") == true
    val showAnalyse = showLoudnessAnalyseButton(
        isPlaying = playerSnap.isPlaying,
        hasActivePlaylist = analyseTargetId != null,
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
                    if (stopping) return@TextButton
                    val id = analyseTargetId ?: return@TextButton
                    if (analysisRunning) state.cancelLoudnessBackfill()
                    else state.startLoudnessBackfill(id)
                },
                enabled = !stopping,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = when {
                        stopping -> "Stopping soon"
                        analysisRunning -> "Stop analysis"
                        else -> "Analyse loudness"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        } else {
            val labelColor = when {
                loudness.boostDb > 0.05f -> MaterialTheme.colorScheme.tertiary
                loudness.limiting -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            // Bar height tracks live reduction (0..1); text sits on top.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .height(32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (reduction > 0.01f && loudness.limiting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(fraction = reduction.coerceIn(0.08f, 1f))
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(
                                    alpha = 0.40f + 0.45f * reduction,
                                ),
                            ),
                    )
                }
                Text(
                    text = loudness.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
