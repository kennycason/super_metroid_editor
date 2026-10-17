package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*

/** Shared chooser for typed incoming-door data. Opening/cancelling never adopts source. */
@Composable
internal fun IncomingDoorPicker(label: String, options: List<IncomingDoorConditionOption>, enabled: Boolean = true,
                                onSelect: (IncomingDoorConditionOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty()) { Text(label, fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}
