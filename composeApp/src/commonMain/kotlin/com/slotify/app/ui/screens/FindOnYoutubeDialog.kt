package com.slotify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.slotify.app.youtube.searchWithFallbacks
import kotlinx.coroutines.launch

/**
 * Find-and-fetch dialog for one unmatched playlist track: editable search
 * query → yt-dlp search (with fallback phrasings when the API returns 0) →
 * ranked results → click to download as opus stream copy.
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
    var emptyNote by remember { mutableStateOf<String?>(null) }
    var lastQueryUsed by remember { mutableStateOf<String?>(null) }
    var searchSeq by remember { mutableStateOf(0) }
    val status by state.youtubeStatus.collectAsState()
    val scope = rememberCoroutineScope()

    // Auto-search on open (and whenever searchSeq bumps from the Search button).
    LaunchedEffect(track.videoKey(), searchSeq) {
        state.clearYoutubeStatusIfIdle()
        searching = true
        error = null
        emptyNote = null
        val attempt = searchWithFallbacks(state.youtube, query, track)
        searching = false
        lastQueryUsed = attempt.query
        if (attempt.error != null && attempt.results.isEmpty()) {
            results = emptyList()
            error = attempt.error.message ?: "Search failed"
        } else {
            results = YoutubeRanking.rank(attempt.results, track)
            if (results.isEmpty()) {
                emptyNote = "No YouTube results for “${attempt.query}” — try a different phrase"
            } else if (!attempt.query.equals(query.trim(), ignoreCase = true)) {
                emptyNote = null
                // Surface that we had to retry with another phrasing.
                error = null
                // Keep a soft note under the field via emptyNote only when empty;
                // when non-empty, prepend a note in the status area below.
                lastQueryUsed = attempt.query
            }
        }
    }

    // Download runs on AppState’s scope — closing the dialog never cancels it.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Find on YouTube", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = buildString {
                        append("Target: ")
                        append(track.name)
                        append(" · artist: ")
                        append(track.artistNames.ifBlank { "unknown artist" })
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Query uses this track’s artist only — not the playlist name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search query") },
                    placeholder = { Text(YoutubeQuery.forTrack(track)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            if (!searching) searchSeq++
                        },
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { if (!searching) searchSeq++ },
                        enabled = !searching && query.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }

                status?.let { s ->
                    val failed = s.contains("failed", ignoreCase = true) ||
                        s.contains("HTTP", ignoreCase = true) ||
                        s.contains("Error", ignoreCase = true)
                    Column {
                        Text(
                            s,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (!failed && (s.contains("Download") || s.contains("Index"))) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                emptyNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (lastQueryUsed != null && results.isNotEmpty() &&
                    !lastQueryUsed.equals(query.trim(), ignoreCase = true)
                ) {
                    Text(
                        text = "Results from “$lastQueryUsed”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (results.isEmpty() && !searching && status == null && error == null && emptyNote == null) {
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
                    // Block only while a download/index is in flight. Terminal batch
                    // lines (“Batch stopped”) must not grey out every result forever.
                    val busy = com.slotify.app.youtube.youtubeStatusBlocksDownload(status)
                    items(results, key = { it.result.videoId }) { scored ->
                        HorizontalDivider()
                        ResultRow(
                            scored = scored,
                            enabled = !busy && !searching,
                            onClick = {
                                scope.launch {
                                    state.fetchYoutube(scored.result, playlistId, remoteTrack = track)
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
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
