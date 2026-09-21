package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun EditableHexRow(
    label: String,
    value: Int,
    byteCount: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    val hexDigits = byteCount * 2
    val maxVal = (1 shl (byteCount * 8)) - 1
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(hexDigits, '0')) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    val filtered = newText.uppercase().filter { it in "0123456789ABCDEF" }.take(hexDigits)
                    text = filtered
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull(16) ?: value
                    onValueChange(parsed.coerceIn(0, maxVal))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        } else {
            Text(
                "0x${value.toString(16).uppercase().padStart(hexDigits, '0')}$suffix",
                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
        }
    }
}

@Composable
internal fun EditableIntRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText -> text = newText.filter { it.isDigit() }.take(5) },
                singleLine = true,
                textStyle = TextStyle(fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull() ?: value
                    onValueChange(parsed.coerceIn(min, max))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        } else {
            Text(
                "$value$suffix",
                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
        }
    }
}
