package com.slotify.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slotify.app.playback.PlaybackMode
import com.slotify.app.playback.isArtistMode
import com.slotify.app.state.AppState

@Composable
fun PlayerBar(state: AppState) {
    val nowPlaying by state.nowPlaying.collectAsState()
    val snapshot by state.player.snapshot.collectAsState()
    val playbackMode by state.playbackMode.collectAsState()
    val now = nowPlaying ?: return

    var seekValue by remember { mutableStateOf<Float?>(null) }
    val duration = snapshot.durationMs.coerceAtLeast(0L)
    val position = (seekValue?.let { (it * duration).toLong() } ?: snapshot.positionMs)
        .coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                seekValue?.let { fraction ->
                    if (duration > 0) state.seekTo((fraction * duration).toLong())
                }
                seekValue = null
            },
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = now.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${now.artist} · ${formatMs(position)} / ${formatMs(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { state.cyclePlaybackMode() }) {
                    if (playbackMode == PlaybackMode.LINEAR) {
                        // Two non-crossing lines: clearly "straight / not shuffled".
                        Box(modifier = Modifier.semantics { contentDescription = "Mode: linear" }) {
                            LinearModeIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Box {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = when (playbackMode) {
                                    PlaybackMode.SHUFFLE -> "Mode: shuffle"
                                    PlaybackMode.ARTIST_2 -> "Mode: artist shuffle, 2 per artist"
                                    PlaybackMode.ARTIST_ALL -> "Mode: artist shuffle, all per artist"
                                    PlaybackMode.LINEAR -> "Mode: linear"
                                },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            if (playbackMode.isArtistMode()) {
                                Text(
                                    text = if (playbackMode == PlaybackMode.ARTIST_2) "2" else "★",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 6.dp, y = 5.dp),
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { state.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(onClick = { state.togglePlay() }) {
                    Icon(
                        imageVector = if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { state.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
        }
        snapshot.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** Two parallel non-crossing lines — the "linear / not shuffled" mode glyph. */
@Composable
private fun LinearModeIcon(tint: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val left = 3.dp.toPx()
        val right = 21.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(left, 9.dp.toPx()),
            end = Offset(right, 9.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(left, 15.dp.toPx()),
            end = Offset(right, 15.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
