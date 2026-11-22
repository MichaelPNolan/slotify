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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slotify.app.model.RemoteTrack
import com.slotify.app.state.AppState
import com.slotify.app.youtube.YoutubeQuery
import com.slotify.app.youtube.YoutubeRanking
import com.slotify.app.youtube.YoutubeSearchResult
import com.slotify.app.youtube.ScoredYoutubeResult
import kotlinx.coroutines.launch

/**
 * Find-and-fetch dialog for one unmatched playlist track: editable search
 * query → yt-dlp search → ranked results (title + duration proximity) →
 * click to download as opus stream copy → auto reindex + rematch.
 */
@Composable
fun FindOnYoutubeDialog(
    state: AppState,
    playlistId: String,
    track: RemoteTrack,
    onDismiss: () -> Unit,
) {
    var query by remember(track.videoKey()) { mutableStateOf(YoutubeQuery.forTrack(track)) }
    var results by remember { mutableStateOf<List<ScoredYoutubeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val status by state.youtubeStatus.collectAsState()
    val scope = rememberCoroutineScope()

    // Auto-search on open so the user sees several ranked matches immediately.
    LaunchedEffect(track.videoKey()) {
        searching = true
        error = null
        val outcome = runCatching {
            YoutubeRanking.rank(state.youtube.search(query), track)
        }
        searching = false
        outcome.onSuccess { results = it }
            .onFailure { e ->
                results = emptyList()
                error = e.message
            }
    }

    AlertDialog(
        onDismissRequest = { if (status == null) onDismiss() },
        title = { Text("Find on YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Target: ${track.name} — ${track.artistNames.ifBlank { "unknown artist" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search query") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            scope.launch {
                                searching = true
                                error = null
                                val outcome = runCatching {
                                    YoutubeRanking.rank(state.youtube.search(query), track)
                                }
                                searching = false
                                outcome.onSuccess { results = it }
                                    .onFailure { e ->
                                        results = emptyList()
                                        error = e.message
                                    }
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.width(18.dp))
                            Text(" Search")
                        }
                    },
                )

                status?.let { s ->
                    Column {
                        Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (s.contains("Download") || s.contains("Index")) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (results.isEmpty() && !searching && status == null) {
                    Text(
                        "Stream copy — the original opus audio is kept, never re-encoded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (results.isNotEmpty() && status == null) {
                    Text(
                        "Pick a match to download (${results.size} result${if (results.size == 1) "" else "s"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(results, key = { it.result.videoId }) { scored ->
                        HorizontalDivider()
                        ResultRow(
                            scored = scored,
                            enabled = status == null,
                            onClick = {
                                scope.launch {
                                    state.fetchYoutube(scored.result, playlistId)
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (status == null) "Close" else "Working…") }
        },
    )
}

@Composable
private fun ResultRow(
    scored: ScoredYoutubeResult,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val r = scored.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = r.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    r.channel,
                    r.durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        ScoreBadge(scored.score)
    }
}

@Composable
private fun ScoreBadge(score: Double) {
    val percent = (score * 100).toInt()
    val color = when {
        score >= 0.80 -> Color(0xFF66BB6A)
        score >= 0.60 -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    SuggestionChip(
        onClick = {},
        label = { Text("$percent%") },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = color,
        ),
    )
}

/** Stable identity for `remember(track…)` keys. */
private fun RemoteTrack.videoKey(): String = "${id ?: ""}|${name}|${artistNames}"
