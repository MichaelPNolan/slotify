package com.slotify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Responsible-use / copyright notice. Shown automatically on first run
 * (until acknowledged in Settings) and on demand from Settings.
 */
@Composable
fun LegalDisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Responsible Use, Copyright and Offline Playback")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Paragraph(
                    "This application is an independent, non-commercial music player. " +
                        "It is provided without advertising, subscription charges or revenue from music distribution.",
                    "Its primary purpose is to organise and play music already stored on your device. " +
                        "Additional features help identify recordings, locate publicly accessible sources and provide playback options.",
                    "The application does not own, claim ownership of, or grant licences to music " +
                        "recordings discovered through external services.",
                )

                Section("Permitted and encouraged uses")
                Paragraph("Users are encouraged to use the application for:")
                Bullets(
                    "Playing music files they already own or are legally entitled to use.",
                    "Playing recordings that are in the public domain.",
                    "Playing recordings released under licences that permit downloading and offline playback.",
                    "Playing recordings for which the copyright holder has expressly authorised copying.",
                    "Identifying music and locating its original source.",
                    "Streaming recordings through their authorised playback services.",
                    "Creating temporary copies where the user has an applicable legal right or permission to do so.",
                )

                Section("Copyrighted recordings")
                Paragraph(
                    "Music being publicly accessible on YouTube does not mean that it is free of " +
                        "copyright or available for unrestricted downloading.",
                    "Users should distinguish between:",
                )
                Numbered(
                    "Public-domain music and recordings.",
                    "Copyrighted recordings with permission for downloading.",
                    "Copyrighted recordings for which a personal-use exception may apply under local law.",
                    "Copyrighted recordings that must be accessed through authorised streaming or playback.",
                )
                Paragraph(
                    "In Thailand, Section 32 of the Copyright Act provides certain exceptions for " +
                        "personal use, subject to conditions concerning normal exploitation of the work " +
                        "and the legitimate rights of copyright owners. Whether a particular download " +
                        "qualifies is dependent on its circumstances.",
                )

                Section("Temporary offline playback")
                Paragraph(
                    "Temporary storage does not automatically make an otherwise unauthorised download lawful.",
                    "Offline conversion should only be used where the user has the necessary permission " +
                        "or a valid legal basis. Where this cannot be established, users should use the " +
                        "original streaming service instead.",
                    "Users must not redistribute, publish, sell, share or make downloaded recordings " +
                        "available to other people without appropriate rights.",
                )

                Section("YouTube and other external services")
                Paragraph(
                    "YouTube provides its own authorised offline playback facilities. Its Terms of " +
                        "Service restrict downloading and reproducing content outside the facilities it " +
                        "expressly permits, unless the relevant permissions or applicable law allow it.",
                    "The application is independent of YouTube and is not endorsed by or affiliated " +
                        "with Google or YouTube.",
                    "Users should respect YouTube's Terms of Service, applicable API policies, copyright " +
                        "restrictions and any limitations imposed by the original content provider.",
                )

                Section("User responsibility")
                Paragraph(
                    "Users are responsible for ensuring that their use of recordings complies with " +
                        "applicable copyright law and the terms governing the services from which " +
                        "recordings originate.",
                    "The application is intended to facilitate personal music organisation and playback, " +
                        "not unauthorised music distribution or copyright infringement.",
                    "When permission or legal status is uncertain, use the original streaming source " +
                        "rather than creating an offline copy.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I understand") }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Paragraph(vararg lines: String) {
    lines.forEach { line ->
        Text(text = line, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Bullets(vararg items: String) {
    items.forEach { item ->
        Text(text = "•  $item", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Numbered(vararg items: String) {
    items.forEachIndexed { i, item ->
        Text(text = "${i + 1}.  $item", style = MaterialTheme.typography.bodyMedium)
    }
}
