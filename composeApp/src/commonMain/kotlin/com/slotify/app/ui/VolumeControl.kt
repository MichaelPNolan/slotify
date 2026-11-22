package com.slotify.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slotify.app.state.AppState

/**
 * Always-visible volume control, shown on every tab (bottom bar, above the
 * navigation row) — including when nothing is playing.
 * Icon click toggles mute; the slider sets app output volume live and
 * persists when the drag finishes.
 */
@Composable
fun VolumeControl(state: AppState) {
    val volume by state.volume.collectAsState()

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
                imageVector = if (volume <= 0.001f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
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
    }
}
