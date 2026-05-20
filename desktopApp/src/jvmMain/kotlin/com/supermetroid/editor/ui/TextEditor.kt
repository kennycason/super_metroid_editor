package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry

@Composable
fun TextEditorSidebar(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier,
) {
    val parser = romParser ?: return
    val entries = remember(parser) { TextData.readAllText(parser.getRomData()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.padding(8.dp).verticalScroll(scrollState)) {
        Text("Text Editor", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit in-game text strings. Changes are applied on ROM export.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Group entries by category
        for (category in TextCategory.entries) {
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            Text(category.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))

            for (entry in categoryEntries) {
                val isSelected = entry.id == selectedId
                val editedText = editorState.project.textEdits[entry.id]
                val displayText = editedText ?: entry.text
                val isModified = editedText != null

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clickable { selectedId = if (isSelected) null else entry.id }
                        .then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.label,
                                fontSize = 11.sp,
                                fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                                color = if (isModified) Color(0xFFFFCC00) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "$${entry.snesAddress.toString(16).uppercase()}",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            displayText.replace('\n', ' ').take(40),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun TextEditorPreview(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier,
) {
    val parser = romParser ?: return
    val entries = remember(parser) { TextData.readAllText(parser.getRomData()) }

    // Find which entry is being edited via project state
    // For now show all entries with editable fields
    val scrollState = rememberScrollState()

    Column(modifier = modifier.padding(16.dp).verticalScroll(scrollState)) {
        Text("In-Game Text", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit text displayed in-game. All text is uppercase only (ROM limitation).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        for (category in TextCategory.entries) {
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            Text(category.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            for (entry in categoryEntries) {
                TextEntryEditor(entry, editorState)
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(4.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TextEntryEditor(entry: TextEntry, editorState: EditorState) {
    val editedText = editorState.project.textEdits[entry.id]
    var currentText by remember(entry.id, editedText) {
        mutableStateOf(editedText ?: entry.text)
    }
    val isModified = editedText != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (isModified) {
                        Spacer(Modifier.width(6.dp))
                        Text("modified", fontSize = 9.sp, color = Color(0xFFFFCC00),
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Text(
                    "$${entry.snesAddress.toString(16).uppercase().padStart(6, '0')}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Max length hint
            val maxHint = when (entry.category) {
                TextCategory.AREA_NAME -> "${entry.maxLength} chars max"
                TextCategory.ESCAPE_TEXT -> "Preserve line breaks"
                TextCategory.ITEM_NAME -> "${entry.maxLength} chars max"
                TextCategory.INTRO_STORY -> "Read-only (complex encoding)"
            }
            Text(maxHint, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))

            val singleLine = entry.category == TextCategory.AREA_NAME || entry.category == TextCategory.ITEM_NAME
            val isReadOnly = entry.category == TextCategory.INTRO_STORY

            TextField(
                value = currentText,
                onValueChange = { newText ->
                    if (isReadOnly) return@TextField
                    val cleaned = when (entry.category) {
                        TextCategory.AREA_NAME -> {
                            val upper = newText.uppercase()
                            val filtered = upper.filter { it in 'A'..'Z' || it == ' ' }
                            filtered.take(entry.maxLength)
                        }
                        TextCategory.ESCAPE_TEXT -> {
                            newText.uppercase()
                        }
                        TextCategory.ITEM_NAME -> {
                            newText.uppercase().take(entry.maxLength)
                        }
                        TextCategory.INTRO_STORY -> currentText // no change
                    }
                    currentText = cleaned
                    // Save to project (or remove if back to original)
                    if (cleaned == entry.text) {
                        editorState.project.textEdits.remove(entry.id)
                    } else {
                        editorState.project.textEdits[entry.id] = cleaned
                    }
                    editorState.markDirty()
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                readOnly = isReadOnly,
                singleLine = singleLine,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(4.dp),
            )

            // Reset button
            if (isModified) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reset to original",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        currentText = entry.text
                        editorState.project.textEdits.remove(entry.id)
                        editorState.markDirty()
                    }
                )
            }
        }
    }
}
