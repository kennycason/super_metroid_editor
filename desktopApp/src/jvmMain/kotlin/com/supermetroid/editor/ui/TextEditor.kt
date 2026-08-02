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
                                if (entry.writable) "$${entry.snesAddress.toString(16).uppercase()}" else "unmapped",
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

            Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(12.dp))
        }

        // Item pickup names (editable text embedded in BG3 tilemap)
        val itemEntries = entries.filter { it.category == TextCategory.ITEM_NAME }
        if (itemEntries.isNotEmpty()) {
            Text("Item Pickup Names", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary)
            Text("Displayed when Samus collects an item.",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            for (entry in itemEntries) {
                TextEntryEditor(entry, editorState)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TextEntryEditor(entry: TextEntry, editorState: EditorState) {
    val editVersion = editorState.editVersion // observe edit changes for recomposition
    val editedText = editorState.project.textEdits[entry.id]
    var currentText by remember(entry.id, editedText, editVersion) { mutableStateOf(editedText ?: entry.text) }
    val isModified = currentText != entry.text
    val singleLine = entry.category in listOf(TextCategory.AREA_NAME, TextCategory.ITEM_NAME, TextCategory.UI_MESSAGE)
    val isReadOnly = !entry.writable

    val textField = @Composable { modifier: Modifier ->
        TextField(
            value = currentText,
            onValueChange = { newText ->
                if (isReadOnly) return@TextField
                val cleaned = when (entry.category) {
                    TextCategory.AREA_NAME -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' }.take(entry.maxLength)
                    TextCategory.ESCAPE_TEXT -> newText.uppercase()
                    TextCategory.ITEM_NAME -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' || it == '-' }.take(entry.maxLength)
                    TextCategory.UI_MESSAGE -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' || it == '.' }.take(entry.maxLength)
                    TextCategory.INTRO_STORY -> newText.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it in " \n.,\'!" }
                }
                currentText = cleaned
                if (cleaned == entry.text) editorState.project.textEdits.remove(entry.id)
                else editorState.project.textEdits[entry.id] = cleaned
                editorState.markDirty()
            },
            modifier = modifier,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            readOnly = isReadOnly, singleLine = singleLine, maxLines = if (singleLine) 1 else 10,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(4.dp),
        )
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(6.dp)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (singleLine) {
                // Compact: label + field on one row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(120.dp))
                    if (isModified) Text("*", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold)
                    textField(Modifier.weight(1f))
                }
            } else {
                // Multi-line: label above field
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    if (isModified) { Spacer(Modifier.width(4.dp)); Text("*", fontSize = 11.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(4.dp))
                textField(Modifier.fillMaxWidth())
            }
            // Footer: address + max + reset
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("\$${entry.snesAddress.toString(16).uppercase().padStart(6, '0')}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("${entry.maxLength} max", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (isModified) {
                    Text("Reset", fontSize = 9.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable {
                        currentText = entry.text; editorState.project.textEdits.remove(entry.id); editorState.markDirty()
                    })
                }
            }
        }
    }
}
