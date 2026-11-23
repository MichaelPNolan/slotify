package com.slotify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slotify.app.library.isSongFileNameMisnamed
import com.slotify.app.state.FixNamesSession

/** One-case-at-a-time rename walk: current name → editable proposal → Rename. */
@Composable
fun FixSongNamesDialog(
    session: FixNamesSession,
    onRename: (newName: String) -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val case = session.current
    // Reset the editor whenever the walk advances to a different file.
    var edited by remember(case?.oldPath) { mutableStateOf(case?.proposedName ?: "") }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                text = if (session.done) {
                    "Fix song names"
                } else {
                    "Fix song names — ${session.index + 1} of ${session.cases.size}"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.done) {
                    Text(
                        text = "Done — ${session.renamedCount} file(s) renamed this session.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Reopen the mode any time to catch remaining files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (case != null) {
                    Text(
                        text = "Current file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = case.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = edited,
                        onValueChange = { edited = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("New file name") },
                        singleLine = true,
                        isError = session.error != null,
                    )
                    session.error?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (case.primaryArtist.isNotEmpty()) {
                        val editedBase = edited.trim().substringBeforeLast('.')
                        if (isSongFileNameMisnamed(editedBase, case.primaryArtist)) {
                            Text(
                                text = "Should look like “${case.primaryArtist} - <song>” — " +
                                    "otherwise this file will be offered again next time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    Text(
                        text = "Edit the name if you like. Renaming updates every playlist " +
                            "row and pin linked to this file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(edited) },
                enabled = case != null,
            ) { Text("Rename") }
        },
        dismissButton = {
            if (case != null) {
                Row {
                    TextButton(onClick = onSkip) { Text("Skip") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
