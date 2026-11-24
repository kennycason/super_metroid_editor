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
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry

internal const val TEXT_MANAGED_INTRO_SECTION_ID = "text_section_managed_intro"
internal const val TEXT_CREDITS_SECTION_ID = "text_section_credits"

internal fun textCategorySectionId(category: TextCategory): String =
    "text_section_${category.name.lowercase()}"

internal data class TextEditorSectionTarget(
    val id: String,
    val title: String,
    val entryIds: List<String>,
)

internal fun textEditorSectionTargets(
    entries: List<TextEntry>,
    managedIntro: Boolean,
    managedIntroSceneCount: Int,
): List<TextEditorSectionTarget> = buildList {
    if (managedIntro && managedIntroSceneCount > 0) {
        add(TextEditorSectionTarget(
            TEXT_MANAGED_INTRO_SECTION_ID,
            "Managed Intro Scenes",
            List(managedIntroSceneCount) { "managed_intro_$it" },
        ))
    }
    TextCategory.entries
        .filterNot { it == TextCategory.ITEM_NAME || it == TextCategory.CREDITS || managedIntro && it == TextCategory.INTRO_STORY }
        .forEach { category ->
            val ids = entries.filter { it.category == category }.map(TextEntry::id)
            if (ids.isNotEmpty()) add(TextEditorSectionTarget(textCategorySectionId(category), category.displayName, ids))
        }
    entries.filter { it.category == TextCategory.ITEM_NAME }.map(TextEntry::id).takeIf(List<String>::isNotEmpty)?.let { ids ->
        add(TextEditorSectionTarget(textCategorySectionId(TextCategory.ITEM_NAME), "Item Pickup Names", ids))
    }
    add(TextEditorSectionTarget(TEXT_CREDITS_SECTION_ID, "End Credits", emptyList()))
}

class TextEditorState {
    var selectedEntryId by mutableStateOf<String?>(null)
        private set
    var navigationSerial by mutableStateOf(0L)
        private set

    fun select(entryId: String?) {
        selectedEntryId = entryId
        navigationSerial++
    }

    internal fun ensureSelection(targets: List<TextEditorSectionTarget>) {
        val selected = selectedEntryId
        if (targets.isNotEmpty() && targets.none { it.id == selected || selected != null && selected in it.entryIds }) {
            select(targets.first().id)
        }
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
    val sectionTargets = remember(entries, managedIntro, editorState.project.introSequence.scenes.size) {
        textEditorSectionTargets(entries, managedIntro, editorState.project.introSequence.scenes.size)
    }
    val originalCreditsCount = remember(parser) { CreditsData.parseCredits(parser)?.sections?.size }
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

        Text("Sections", fontWeight = FontWeight.Bold, fontSize = fs.body,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        TextSectionQuickNavigation(
            targets = sectionTargets,
            textEditorState = textEditorState,
            originalCreditsCount = originalCreditsCount,
            customCreditsCount = editorState.project.creditsGroups.size,
        )
        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Entries", fontWeight = FontWeight.Bold, fontSize = fs.body)
        Spacer(Modifier.height(6.dp))

        if (managedIntro) {
            Text("Managed Intro Scenes", fontWeight = FontWeight.Bold, fontSize = fs.body,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable {
                    textEditorState.select(TEXT_MANAGED_INTRO_SECTION_ID)
                })
            Text("Shared with Patches › Intro Sequence Manager.", fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            editorState.project.introSequence.scenes.forEachIndexed { index, scene ->
                val id = "managed_intro_$index"
                val isSelected = id == textEditorState.selectedEntryId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clickable { textEditorState.select(id) }
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
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable {
                    textEditorState.select(textCategorySectionId(category))
                })
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
                        .clickable { textEditorState.select(entry.id) }
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable {
                textEditorState.select(TEXT_CREDITS_SECTION_ID)
            })
        Spacer(Modifier.height(4.dp))
        if (creditsGroups.isEmpty()) {
            Text("No custom credits", fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for ((idx, group) in creditsGroups.withIndex()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clickable { textEditorState.select(TEXT_CREDITS_SECTION_ID) },
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
private fun TextSectionQuickNavigation(
    targets: List<TextEditorSectionTarget>,
    textEditorState: TextEditorState,
    originalCreditsCount: Int?,
    customCreditsCount: Int,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    targets.forEach { target ->
        val selected = textEditorState.selectedEntryId
        val active = selected == target.id || selected != null && selected in target.entryIds
        val detail = if (target.id == TEXT_CREDITS_SECTION_ID) {
            listOfNotNull(originalCreditsCount?.let { "$it original" }, "$customCreditsCount custom")
                .joinToString(" · ")
        } else {
            "${target.entryIds.size} ${if (target.entryIds.size == 1) "entry" else "entries"}"
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                .clickable { textEditorState.select(target.id) }
                .then(if (active) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(4.dp),
                ) else Modifier),
            color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(target.title, fontSize = fs.body,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                Text(detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val sectionTargets = remember(entries, managedIntro, editorState.project.introSequence.scenes.size) {
        textEditorSectionTargets(entries, managedIntro, editorState.project.introSequence.scenes.size)
    }
    androidx.compose.runtime.LaunchedEffect(sectionTargets) {
        textEditorState.ensureSelection(sectionTargets)
    }

    val scrollState = rememberScrollState()

    Column(modifier = modifier.padding(16.dp).verticalScroll(scrollState)) {
        Text("In-Game Text", fontWeight = FontWeight.Bold, fontSize = fs.heading)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit text displayed in-game. All text is uppercase only (ROM limitation).",
            fontSize = fs.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (managedIntro) {
            TextSectionHeading(
                id = TEXT_MANAGED_INTRO_SECTION_ID,
                title = "Managed Intro Scenes",
                textEditorState = textEditorState,
            )
            Text("These fields and the Intro Sequence Manager edit the same scene data.",
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            TextSectionHeading(
                id = textCategorySectionId(category),
                title = category.displayName,
                textEditorState = textEditorState,
            )
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
            TextSectionHeading(
                id = textCategorySectionId(TextCategory.ITEM_NAME),
                title = "Item Pickup Names",
                textEditorState = textEditorState,
            )
            Text("Displayed when Samus collects an item.",
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            textEditorState = textEditorState,
        )
    }
}

@Composable
private fun TextSectionHeading(
    id: String,
    title: String,
    textEditorState: TextEditorState,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val requester = remember(id) { BringIntoViewRequester() }
    val selected = textEditorState.selectedEntryId == id
    androidx.compose.runtime.LaunchedEffect(selected, textEditorState.navigationSerial) {
        if (selected) requester.bringIntoView()
    }
    Text(
        title,
        fontWeight = FontWeight.Bold,
        fontSize = fs.heading,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
    )
}

@Composable
private fun CreditsGroupsEditor(
    romParser: RomParser,
    editorState: EditorState,
    textEditorState: TextEditorState,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val editVersion = editorState.editVersion
    val groups = remember(editVersion) { editorState.project.creditsGroups.toList() }
    val parsed = remember(romParser) { CreditsData.parseCredits(romParser) }
    val freeRows = parsed?.freeRowIndices?.size ?: 0
    val usedRows = groups.sumOf { 1 + it.names.size * 2 }
    val remainingRows = (freeRows - usedRows).coerceAtLeast(0)

    TextSectionHeading(
        id = TEXT_CREDITS_SECTION_ID,
        title = "End Credits",
        textEditorState = textEditorState,
    )
    Text(
        "Browse the original ROM credits and add custom groups after them. " +
            "Custom text uses A-Z and spaces only. $remainingRows of $freeRows tilemap rows remain.",
        fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    editorState.creditsSourceProblem?.let { problem ->
        Text(
            "ASM credits draft is invalid; the last valid visual model is retained. $problem",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (parsed != null) {
        Spacer(Modifier.height(8.dp))
        NativeCreditsBrowser(parsed.sections)
        Spacer(Modifier.height(8.dp))
        CreditsInsertionBoundary(parsed.sections.size)
        Spacer(Modifier.height(8.dp))
        if (usedRows > freeRows) {
            Text(
                "Credits exceed the ROM row budget by ${usedRows - freeRows}; remove names or groups before export.",
                fontSize = fs.detail, color = MaterialTheme.colorScheme.error,
            )
        }
    } else {
        Text(
            "Credits data is unsupported or unreadable; export is blocked.",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
    }

    Text("Custom credit groups", fontWeight = FontWeight.Bold, fontSize = fs.body)
    Text(
        "Editable · shown in the order below",
        fontSize = fs.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (groups.isEmpty()) {
        Text(
            "No custom groups yet.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    fontSize = fs.body,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "(${groups.size}/${CreditsData.MAX_CUSTOM_GROUPS})",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NativeCreditsBrowser(sections: List<CreditsData.CreditSection>) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Original ROM credits", fontWeight = FontWeight.Bold, fontSize = fs.body)
                    Text(
                        "${sections.size} sections · read-only",
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "Hide" else "View",
                    fontSize = fs.body,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Native sections may use specialized instruction layouts, so they remain view-only here. " +
                        "Use ASM for changes outside the managed custom-group format.",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                sections.forEachIndexed { index, section ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(
                                section.header.ifBlank { "Section ${index + 1}" },
                                fontSize = fs.body,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (section.names.isEmpty()) {
                                Text(
                                    "No decoded names",
                                    fontSize = fs.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                section.names.forEach { name ->
                                    Text(
                                        name,
                                        fontSize = fs.body,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsInsertionBoundary(originalSectionCount: Int) {
    val fs = LocalEditorTheme.current.fontSize.value
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Divider(Modifier.weight(1f))
        Text(
            "CUSTOM CREDITS INSERT HERE",
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = fs.detail,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Divider(Modifier.weight(1f))
    }
    Text(
        "After all $originalSectionCount original sections, before the final ending sequence.",
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        fontSize = fs.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CreditsGroupEditor(
    group: com.supermetroid.editor.data.ProjectCreditsGroup,
    groupIndex: Int,
    editorState: EditorState,
    availableRows: Int,
) {
    val fs = LocalEditorTheme.current.fontSize.value
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
                Text("Group ${groupIndex + 1}", fontWeight = FontWeight.Bold, fontSize = fs.body,
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
                        fontFamily = FontFamily.Monospace, fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                    ),
                    singleLine = true,
                    placeholder = { Text("SECTION HEADER", fontSize = fs.body, fontFamily = FontFamily.Monospace,
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
                Text("Remove", fontSize = fs.detail, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { editorState.removeCreditsGroup(groupIndex) })
            }

            Spacer(Modifier.height(4.dp))
            Text("Names (centered, large font):", fontSize = fs.detail,
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
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { editorState.addCreditsName(groupIndex, availableRows) },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${names.size}/${CreditsData.MAX_NAMES_PER_GROUP}",
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Footer info
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "${CreditsData.MAX_HEADER_LENGTH} max header · ${CreditsData.MAX_NAME_LENGTH} max name · A-Z + space",
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val fs = LocalEditorTheme.current.fontSize.value
    val editVersion = editorState.editVersion
    var nameText by remember(editVersion, groupIndex, nameIndex) { mutableStateOf(name) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${nameIndex + 1}.", fontSize = fs.detail, modifier = Modifier.width(20.dp),
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
                fontFamily = FontFamily.Monospace, fontSize = fs.body,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            placeholder = { Text("PERSON NAME", fontSize = fs.body, fontFamily = FontFamily.Monospace,
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
            Text("x", fontSize = fs.detail, color = MaterialTheme.colorScheme.error,
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
    val fs = LocalEditorTheme.current.fontSize.value
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
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.body, color = MaterialTheme.colorScheme.onSurface),
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
                    Text(entry.label, fontWeight = FontWeight.Bold, fontSize = fs.body, modifier = Modifier.width(120.dp))
                    if (isModified) Text("*", fontSize = fs.body, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold)
                    textField(Modifier.weight(1f))
                }
            } else {
                // Multi-line: label above field
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label, fontWeight = FontWeight.Bold, fontSize = fs.body)
                    if (isModified) { Spacer(Modifier.width(4.dp)); Text("*", fontSize = fs.body, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(4.dp))
                textField(Modifier.fillMaxWidth())
            }
            // Footer: address + max + reset
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (managedIntroIndex != null) "Managed intro scene" else "\$${entry.snesAddress.toString(16).uppercase().padStart(6, '0')}", fontSize = fs.detail, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (managedIntroIndex == null && entry.writable && onNavigateToAsm != null) {
                    Spacer(Modifier.width(4.dp))
                    AsmNavigationLink(entry.snesAddress, onNavigateToAsm, label = "ASM")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (entry.category == TextCategory.ESCAPE_TEXT && sourceStatus?.sourceManaged == true) {
                        "${entry.text.count { it == '\n' } + 1} fixed lines"
                    } else "${entry.maxLength} max",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sourceStatus?.message?.let { message ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sourceStatus.editable) message else "ASM only · $message",
                        fontSize = fs.detail,
                        color = if (sourceStatus.editable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (sourceStatus?.message == null) Spacer(Modifier.weight(1f))
                if (isModified) {
                    Text("Reset", fontSize = fs.detail, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable {
                        if (managedIntroIndex != null) {
                            if (editorState.setIntroSceneText(managedIntroIndex, entry.text)) currentText = entry.text
                        } else editorState.resetTextEntry(entry)
                    })
                }
            }
        }
    }
}
