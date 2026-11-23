package com.slotify.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ResetPlayedDialog(
    onReset: () -> Unit,
    onJustPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset all played already?") },
        text = {
            Text(
                text = "Reset clears the played marks so any song can repeat. " +
                    "“Just play this” plays the picked song and keeps everything marked.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text("Reset all played") }
        },
        dismissButton = {
            TextButton(onClick = onJustPlay) { Text("Just play this") }
        },
    )
}
