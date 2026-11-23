package com.slotify.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.slotify.app.model.RowMark

/** Narrow single-letter row mark: m (none) → s → d (red) → m. Shared by
 *  playlist detail rows and library rows (S7). */
@Composable
fun MarkChip(mark: RowMark?, onClick: () -> Unit) {
    val (label, color) = when (mark) {
        null -> "m" to MaterialTheme.colorScheme.outline
        RowMark.SKIP -> "s" to MaterialTheme.colorScheme.secondary
        RowMark.DELETE -> "d" to MaterialTheme.colorScheme.error
    }
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.18f),
            labelColor = color,
        ),
    )
}
