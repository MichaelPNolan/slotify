package com.slotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.state.AppState

/**
 * "Up next" queue sheet — tap a row to jump there, arrows reorder, ✕ drops an
 * upcoming entry. The playing row stays pinned (mode rebuilds own it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(state: AppState, onDismiss: () -> Unit) {
    val queue by state.queueView.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Up next",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val up = queue.upcoming.size
                Text(
                    text = if (up == 1) "1 track" else "$up tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (queue.items.isEmpty()) {
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    val current = queue.items.getOrNull(queue.currentIndex)
                    if (current != null) {
                        item(key = "now") {
                            QueueRow(
                                title = current.title,
                                artist = current.artist,
                                isPlaying = true,
                                onClick = {},
                            )
                        }
                        item(key = "hdr") {
                            Text(
                                text = "Next",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 6.dp,
                                ),
                            )
                        }
                    }
                    items(queue.upcoming, key = { it.index }) { (index, entry) ->
                        val upcoming = queue.upcoming
                        val pos = upcoming.indexOfFirst { it.index == index }
                        QueueRow(
                            title = entry.title,
                            artist = entry.artist,
                            isPlaying = false,
                            onClick = { state.playQueueItem(index) },
                            onMoveUp = if (pos > 0) {
                                { state.moveQueueItem(index, upcoming[pos - 1].index) }
                            } else {
                                null
                            },
                            onMoveDown = if (pos < upcoming.lastIndex) {
                                { state.moveQueueItem(index, upcoming[pos + 1].index) }
                            } else {
                                null
                            },
                            onRemove = { state.removeQueueItem(index) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}

@Composable
private fun QueueRow(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !isPlaying) { onClick() }.padding(
            horizontal = 16.dp,
            vertical = 4.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = if (isPlaying) "Playing" else null,
            tint = if (isPlaying) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move up",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move down",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove from queue",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
