@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry

class TextEditorState {
    var selectedEntryId by mutableStateOf<String?>(null)
        private set
    var navigationSerial by mutableStateOf(0L)
        private set

    fun select(entryId: String?) {
        selectedEntryId = entryId
        navigationSerial++
    }

    fun toggle(entryId: String) {
        select(entryId.takeUnless { it == selectedEntryId })
    }
}

@Composable
fun TextEditorSidebar(
    romParser: RomParser?,
    editorState: EditorState,
    textEditorState: TextEditorState,
    onNavigateToAsm: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val asmLink = editorState.projectAsmNavigation(onNavigateToAsm)
    val fs = LocalEditorTheme.current.fontSize.value
    val parser = romParser ?: return
    val editVersion = editorState.editVersion
    val sourceRevision = editorState.asmVisualSourceRevision
    val entries = remember(parser, editVersion, sourceRevision) { editorState.textEntries(parser) }
    val introRevision = editorState.introSequenceVersion
    val patchRevision = editorState.patchVersion
    val managedIntro = remember(patchRevision, introRevision) {
        editorState.project.patches.any { it.enabled && it.configType == INTRO_SEQUENCE_CONFIG_TYPE }
    }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.padding(8.dp).verticalScroll(scrollState)) {
        Text("Text Editor", fontWeight = FontWeight.Bold, fontSize = fs.heading)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit in-game text strings. Changes are applied on ROM export.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (managedIntro) {
            Text("Managed Intro Scenes", fontWeight = FontWeight.Bold, fontSize = fs.body,
                color = MaterialTheme.colorScheme.primary)
            Text("Shared with Patches › Intro Sequence Manager.", fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            editorState.project.introSequence.scenes.forEachIndexed { index, scene ->
                val id = "managed_intro_$index"
                val isSelected = id == textEditorState.selectedEntryId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clickable { textEditorState.toggle(id) }
                        .then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Text("${index + 1}. ${scene.name}", fontSize = fs.body, fontWeight = FontWeight.Bold)
                        Text(scene.text.replace('\n', ' ').take(40), fontSize = fs.detail, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        for (category in TextCategory.entries) {
            if (managedIntro && category == TextCategory.INTRO_STORY) continue
            if (category == TextCategory.CREDITS) continue // handled separately below
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            Text(category.displayName, fontWeight = FontWeight.Bold, fontSize = fs.body,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))

            for (entry in categoryEntries) {
                val isSelected = entry.id == textEditorState.selectedEntryId
                val status = editorState.textEntryEditingStatus(entry)
                val editedText = editorState.project.textEdits[entry.id].takeUnless { status.sourceManaged }
                val displayText = editedText ?: entry.text
                val isModified = status.modified || editedText != null
                val bringIntoViewRequester = remember(entry.id) { BringIntoViewRequester() }
                androidx.compose.runtime.LaunchedEffect(
                    isSelected,
                    textEditorState.navigationSerial,
                ) {
                    if (isSelected) bringIntoViewRequester.bringIntoView()
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .clickable { textEditorState.toggle(entry.id) }
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
                                fontSize = fs.body,
                                fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                                color = if (isModified) Color(0xFFFFCC00) else MaterialTheme.colorScheme.onSurface
                            )
                            if (entry.writable && asmLink != null) {
                                AsmNavigationLink(entry.snesAddress, asmLink, label = "ASM")
                            } else {
                                Text(
                                    if (entry.writable) "$${entry.snesAddress.toString(16).uppercase()}" else "unmapped",
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            displayText.replace('\n', ' ').take(40),
                            fontSize = fs.detail,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (status.sourceManaged && status.message != null) {
                            Text(
                                if (status.editable) status.message else "ASM only · ${status.message}",
                                fontSize = fs.detail,
                                color = if (status.editable) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // Credits sidebar summary
        val creditsGroups = remember(editVersion) { editorState.project.creditsGroups.toList() }
        Text("End Credits", fontWeight = FontWeight.Bold, fontSize = fs.body,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        if (creditsGroups.isEmpty()) {
            Text("No custom credits", fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for ((idx, group) in creditsGroups.withIndex()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Text(group.header.ifBlank { "Group ${idx + 1}" }, fontSize = fs.body,
                            fontWeight = FontWeight.Bold, color = Color(0xFFFFCC00))
                        Text("${group.names.size} name${if (group.names.size != 1) "s" else ""}",
                            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun TextEditorPreview(
    romParser: RomParser?,
    editorState: EditorState,
    textEditorState: TextEditorState,
    onNavigateToAsm: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val asmLink = editorState.projectAsmNavigation(onNavigateToAsm)
    val parser = romParser ?: return
    val editVersion = editorState.editVersion
    val sourceRevision = editorState.asmVisualSourceRevision
    val entries = remember(parser, editVersion, sourceRevision) { editorState.textEntries(parser) }
    val introRevision = editorState.introSequenceVersion
    val patchRevision = editorState.patchVersion
    val managedIntro = remember(patchRevision, introRevision) {
        editorState.project.patches.any { it.enabled && it.configType == INTRO_SEQUENCE_CONFIG_TYPE }
    }

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

        if (managedIntro) {
            Text("Managed Intro Scenes", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary)
            Text("These fields and the Intro Sequence Manager edit the same scene data.",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            editorState.project.introSequence.scenes.forEachIndexed { index, scene ->
                TextEntryEditor(
                    entry = TextEntry(
                        id = "managed_intro_$index",
                        label = "${index + 1}. ${scene.name}",
                        category = TextCategory.INTRO_STORY,
                        pcOffset = -1,
                        snesAddress = 0,
                        maxLength = 900,
                        text = scene.text,
                        rawBytes = ByteArray(0),
                    ),
                    editorState = editorState,
                    selected = textEditorState.selectedEntryId == "managed_intro_$index",
                    navigationSerial = textEditorState.navigationSerial,
                    onNavigateToAsm = null,
                    managedIntroIndex = index,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        for (category in TextCategory.entries.filterNot { it == TextCategory.ITEM_NAME || it == TextCategory.CREDITS || managedIntro && it == TextCategory.INTRO_STORY }) {
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            Text(category.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            for (entry in categoryEntries) {
                TextEntryEditor(
                    entry = entry,
                    editorState = editorState,
                    selected = entry.id == textEditorState.selectedEntryId,
                    navigationSerial = textEditorState.navigationSerial,
                    onNavigateToAsm = asmLink,
                )
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
                TextEntryEditor(
                    entry = entry,
                    editorState = editorState,
                    selected = entry.id == textEditorState.selectedEntryId,
                    navigationSerial = textEditorState.navigationSerial,
                    onNavigateToAsm = asmLink,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        CreditsGroupsEditor(
            romParser = parser,
            editorState = editorState,
        )
    }
}

@Composable
private fun CreditsGroupsEditor(
    romParser: RomParser,
    editorState: EditorState,
) {
    val editVersion = editorState.editVersion
    val groups = remember(editVersion) { editorState.project.creditsGroups.toList() }
    val parsed = remember(romParser) { CreditsData.parseCredits(romParser) }
    val freeRows = parsed?.freeRowIndices?.size ?: 0
    val usedRows = groups.sumOf { 1 + it.names.size * 2 }
    val remainingRows = (freeRows - usedRows).coerceAtLeast(0)

    Text("End Credits", fontWeight = FontWeight.Bold, fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary)
    Text(
        "Custom credit groups appended after the original ending credits scroll. " +
            "A-Z and spaces only. $remainingRows of $freeRows tilemap rows remain.",
        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    editorState.creditsSourceProblem?.let { problem ->
        Text(
            "ASM credits draft is invalid; the last valid visual model is retained. $problem",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (parsed != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "${parsed.sections.size} original sections in ROM",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (usedRows > freeRows) {
            Text(
                "Credits exceed the ROM row budget by ${usedRows - freeRows}; remove names or groups before export.",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        Text(
            "Credits data is unsupported or unreadable; export is blocked.",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(8.dp))

    for ((groupIdx, group) in groups.withIndex()) {
        CreditsGroupEditor(
            group = group,
            groupIndex = groupIdx,
            editorState = editorState,
            availableRows = freeRows,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (groups.size < CreditsData.MAX_CUSTOM_GROUPS && remainingRows >= 3) {
        Surface(
            modifier = Modifier.fillMaxWidth()
                .clickable { editorState.addCreditsGroup(freeRows) },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "+ Add Credit Group",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "(${groups.size}/${CreditsData.MAX_CUSTOM_GROUPS})",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreditsGroupEditor(
    group: com.supermetroid.editor.data.ProjectCreditsGroup,
    groupIndex: Int,
    editorState: EditorState,
    availableRows: Int,
) {
    val editVersion = editorState.editVersion
    var headerText by remember(editVersion, groupIndex) { mutableStateOf(group.header) }
    val names = remember(editVersion, groupIndex) { group.names.toList() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Header row
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Group ${groupIndex + 1}", fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.width(60.dp))
                TextField(
                    value = headerText,
                    onValueChange = { newVal ->
                        val cleaned = newVal.uppercase().filter { it in 'A'..'Z' || it == ' ' }
                            .take(CreditsData.MAX_HEADER_LENGTH)
                        if (editorState.updateCreditsGroupHeader(groupIndex, cleaned)) {
                            headerText = cleaned
                        }
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                    ),
                    singleLine = true,
                    placeholder = { Text("SECTION HEADER", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(4.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Remove", fontSize = 9.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { editorState.removeCreditsGroup(groupIndex) })
            }

            Spacer(Modifier.height(4.dp))
            Text("Names (centered, large font):", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))

            // Name rows
            for ((nameIdx, name) in names.withIndex()) {
                CreditsNameRow(
                    name = name,
                    groupIndex = groupIndex,
                    nameIndex = nameIdx,
                    canRemove = names.size > 1,
                    editorState = editorState,
                )
                Spacer(Modifier.height(2.dp))
            }

            // Add name button
            val rowsUsed = editorState.project.creditsGroups.sumOf { 1 + it.names.size * 2 }
            if (names.size < CreditsData.MAX_NAMES_PER_GROUP && rowsUsed + 2 <= availableRows) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        "+ Add Name",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { editorState.addCreditsName(groupIndex, availableRows) },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${names.size}/${CreditsData.MAX_NAMES_PER_GROUP}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Footer info
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "${CreditsData.MAX_HEADER_LENGTH} max header · ${CreditsData.MAX_NAME_LENGTH} max name · A-Z + space",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreditsNameRow(
    name: String,
    groupIndex: Int,
    nameIndex: Int,
    canRemove: Boolean,
    editorState: EditorState,
) {
    val editVersion = editorState.editVersion
    var nameText by remember(editVersion, groupIndex, nameIndex) { mutableStateOf(name) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${nameIndex + 1}.", fontSize = 10.sp, modifier = Modifier.width(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(
            value = nameText,
            onValueChange = { newVal ->
                val cleaned = newVal.uppercase().filter { it in 'A'..'Z' || it == ' ' }
                    .take(CreditsData.MAX_NAME_LENGTH)
                if (editorState.updateCreditsGroupName(groupIndex, nameIndex, cleaned)) {
                    nameText = cleaned
                }
            },
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            placeholder = { Text("PERSON NAME", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(4.dp),
        )
        if (canRemove) {
            Spacer(Modifier.width(4.dp))
            Text("x", fontSize = 9.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { editorState.removeCreditsName(groupIndex, nameIndex) })
        }
    }
}

@Composable
private fun TextEntryEditor(
    entry: TextEntry,
    editorState: EditorState,
    selected: Boolean,
    navigationSerial: Long,
    onNavigateToAsm: ((Int) -> Unit)?,
    managedIntroIndex: Int? = null,
) {
    val editVersion = editorState.editVersion // observe edit changes for recomposition
    val introVersion = editorState.introSequenceVersion
    val sourceRevision = editorState.asmVisualSourceRevision
    val sourceStatus = if (managedIntroIndex == null) editorState.textEntryEditingStatus(entry) else null
    val managedText = managedIntroIndex?.let { editorState.project.introSequence.scenes.getOrNull(it)?.text }
    val editedText = if (managedIntroIndex == null) {
        editorState.project.textEdits[entry.id].takeUnless { sourceStatus?.sourceManaged == true }
    } else managedText
    var currentText by remember(entry.id, entry.text, editedText, editVersion, introVersion, sourceRevision) {
        mutableStateOf(editedText ?: entry.text)
    }
    val isModified = sourceStatus?.modified == true || currentText != entry.text
    val singleLine = entry.category in listOf(TextCategory.AREA_NAME, TextCategory.ITEM_NAME, TextCategory.UI_MESSAGE)
    val isReadOnly = !entry.writable || sourceStatus?.editable == false
    val bringIntoViewRequester = remember(entry.id) { BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(selected, navigationSerial) {
        if (selected) bringIntoViewRequester.bringIntoView()
    }

    val textField = @Composable { modifier: Modifier ->
        TextField(
            value = currentText,
            onValueChange = { newText ->
                if (isReadOnly) return@TextField
                val cleaned = when (entry.category) {
                    TextCategory.AREA_NAME -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' }.take(entry.maxLength)
                    TextCategory.ESCAPE_TEXT -> newText.uppercase().filter {
                        it in 'A'..'Z' || it == ' ' || it == '!' || it == '\n'
                    }
                    TextCategory.ITEM_NAME -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' || it == '-' }.take(entry.maxLength)
                    TextCategory.UI_MESSAGE -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' || it == '.' }.take(entry.maxLength)
                    TextCategory.INTRO_STORY -> {
                        val allowed = newText.uppercase().filter {
                            if (managedIntroIndex != null) it == '\n' || it in ' '..'~'
                            else it in 'A'..'Z' || it in '0'..'9' || it in " \n.,\'!"
                        }
                        if (managedIntroIndex != null) allowed.take(entry.maxLength)
                        else {
                            var visible = 0
                            allowed.takeWhile { character -> character == '\n' || visible++ < entry.maxLength }
                        }
                    }
                    TextCategory.CREDITS -> newText.uppercase().filter { it in 'A'..'Z' || it == ' ' }.take(entry.maxLength)
                }
                if (managedIntroIndex != null) {
                    if (editorState.setIntroSceneText(managedIntroIndex, cleaned)) currentText = cleaned
                } else {
                    if (editorState.updateTextEntry(entry, cleaned)) currentText = cleaned
                }
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

    Surface(
        modifier = Modifier.fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                else Modifier
            ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
    ) {
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
                Text(if (managedIntroIndex != null) "Managed intro scene" else "\$${entry.snesAddress.toString(16).uppercase().padStart(6, '0')}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (managedIntroIndex == null && entry.writable && onNavigateToAsm != null) {
                    Spacer(Modifier.width(4.dp))
                    AsmNavigationLink(entry.snesAddress, onNavigateToAsm, label = "ASM")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (entry.category == TextCategory.ESCAPE_TEXT && sourceStatus?.sourceManaged == true) {
                        "${entry.text.count { it == '\n' } + 1} fixed lines"
                    } else "${entry.maxLength} max",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sourceStatus?.message?.let { message ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sourceStatus.editable) message else "ASM only · $message",
                        fontSize = 9.sp,
                        color = if (sourceStatus.editable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (sourceStatus?.message == null) Spacer(Modifier.weight(1f))
                if (isModified) {
                    Text("Reset", fontSize = 9.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable {
                        if (managedIntroIndex != null) {
                            if (editorState.setIntroSceneText(managedIntroIndex, entry.text)) currentText = entry.text
                        } else editorState.resetTextEntry(entry)
                    })
                }
            }
        }
    }
}
