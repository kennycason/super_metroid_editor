package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit

/** Compact ownership badge whose exact fail-closed reason remains one click away. */
@Composable
internal fun AsmOnlyReasonBadge(
    reason: String,
    subject: String,
    fontSize: TextUnit? = null,
    color: Color = Color(0xFFFFC857),
) {
    var explanationVisible by remember(reason) { mutableStateOf(false) }
    Text(
        "ASM only ⓘ",
        fontSize = fontSize ?: LocalEditorTheme.current.fontSize.value.detail,
        color = color,
        modifier = Modifier.clickable { explanationVisible = true },
    )
    if (explanationVisible) {
        AlertDialog(
            onDismissRequest = { explanationVisible = false },
            title = { Text("Why this $subject is ASM-only") },
            text = {
                Text(
                    "$reason\n\nThe visual editor is showing the available source/ROM data and will not overwrite unsupported ASM."
                )
            },
            confirmButton = {
                TextButton(onClick = { explanationVisible = false }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}
