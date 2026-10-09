package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.supermetroid.editor.asm.AsmAsset
import com.supermetroid.editor.asm.AsmAddressQuery
import com.supermetroid.editor.asm.AsmAddressSpace
import com.supermetroid.editor.asm.AsmBrowserMode
import com.supermetroid.editor.asm.AsmEditorLink
import com.supermetroid.editor.asm.AsmEditorTarget
import com.supermetroid.editor.asm.AsmInstructionCategory
import com.supermetroid.editor.asm.AsmInstructionInfo
import com.supermetroid.editor.asm.AsmInstructionReference
import com.supermetroid.editor.asm.AsmLibrary
import com.supermetroid.editor.asm.AsmLibraryGuide
import com.supermetroid.editor.asm.AsmLabel
import com.supermetroid.editor.asm.AsmLabelUsage
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceIndex
import com.supermetroid.editor.asm.AsmRomDiffRange
import com.supermetroid.editor.asm.AsmRomPreview
import com.supermetroid.editor.asm.AsmRomPreviewView
import com.supermetroid.editor.asm.AsmSemanticBridge
import com.supermetroid.editor.asm.AsmSemanticIndex
import com.supermetroid.editor.asm.AsmSourceFile
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.AsmWorkspaceKind
import com.supermetroid.editor.asm.formatPcOffset
import com.supermetroid.editor.asm.formatSnesAddress
import com.supermetroid.editor.asm.parseAsmAddressQuery
import com.supermetroid.editor.asm.pcToSnesLoRom
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

private data class AsmSearchResult(
    val title: String,
    val detail: String,
    val fileId: String? = null,
    val lineIndex: Int = 0,
    val assetPath: String? = null,
)

private data class AsmLibrarySelection(
    val guideId: String? = null,
    val mnemonic: String? = null,
)

@Composable
internal fun AsmWorkspaceSidebar(
    state: AsmWorkspaceState,
    romParser: RomParser?,
    romName: String?,
    projectFilePath: String,
    projectAsmEnabled: Boolean,
    onProjectAsmEnabled: () -> Unit,
    buildRomPreview: (() -> AsmRomPreview?)? = null,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val scope = rememberCoroutineScope()
    val workspace = state.workspace
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.searchFocusSerial) {
        if (state.searchFocusSerial > 0L) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    fun download() {
        val parser = romParser ?: return
        scope.launch { state.download(parser.copyRomData(), romName ?: "loaded ROM") }
    }

    fun syncAssets() {
        val parser = romParser ?: return
        scope.launch { state.refreshAssets(parser.copyRomData(), romName ?: "loaded ROM") }
    }

    fun previewRom() {
        val build = buildRomPreview ?: return
        scope.launch { state.buildRomPreview(build) }
    }

    fun enableProjectAsm() {
        scope.launch {
            if (state.enableProjectWorkspace(projectFilePath)) onProjectAsmEnabled()
        }
    }

    Column(modifier = modifier.padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            AsmModeButton("Source", state.browserMode == AsmBrowserMode.SOURCE) {
                state.showSourceBrowser()
            }
            AsmModeButton("Assets", state.browserMode == AsmBrowserMode.ASSETS) {
                state.showAssetBrowser()
            }
            AsmModeButton("Library", state.browserMode == AsmBrowserMode.LIBRARY) {
                state.showLibraryBrowser()
            }
            AsmModeButton("ROM", state.browserMode == AsmBrowserMode.ROM) {
                state.showRomBrowser()
            }
        }
        if (state.hasProjectWorkspace) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                AsmModeButton("Reference", state.workspaceKind == AsmWorkspaceKind.REFERENCE) {
                    state.showReferenceWorkspace()
                }
                AsmModeButton("Project", state.workspaceKind == AsmWorkspaceKind.PROJECT) {
                    state.showProjectWorkspace()
                }
            }
        }

        if ((workspace != null || state.browserMode == AsmBrowserMode.LIBRARY) && state.browserMode != AsmBrowserMode.ROM) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                singleLine = true,
                textStyle = TextStyle(fontSize = fs.body),
                placeholder = if (state.browserMode == AsmBrowserMode.SOURCE) {
                    {
                        Text(
                            "\$8F:805A or PC:07805A",
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
                label = {
                    Text(
                        when (state.browserMode) {
                            AsmBrowserMode.SOURCE -> "Find bank, label, or address"
                            AsmBrowserMode.ASSETS -> "Find extracted asset"
                            AsmBrowserMode.LIBRARY -> "Find lesson or instruction"
                            AsmBrowserMode.ROM -> ""
                        },
                        fontSize = fs.detail,
                    )
                },
            )
            Spacer(Modifier.height(6.dp))
        }

        when (state.browserMode) {
            AsmBrowserMode.SOURCE -> workspace?.let {
                AsmSourceTree(state, it.index, Modifier.weight(1f).fillMaxWidth())
            } ?: Spacer(Modifier.weight(1f))
            AsmBrowserMode.ASSETS -> workspace?.let {
                AsmAssetTree(state, it.index.assets, Modifier.weight(1f).fillMaxWidth())
            } ?: Spacer(Modifier.weight(1f))
            AsmBrowserMode.LIBRARY -> AsmLibraryTree(state, Modifier.weight(1f).fillMaxWidth())
            AsmBrowserMode.ROM -> AsmRomPreviewTree(
                state = state,
                romAvailable = romParser != null && buildRomPreview != null,
                onBuild = ::previewRom,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))
        AsmWorkspaceControls(
            state = state,
            romAvailable = romParser != null,
            projectFilePath = projectFilePath,
            projectAsmEnabled = projectAsmEnabled,
            onDownload = ::download,
            onSyncAssets = ::syncAssets,
            onEnableProjectAsm = ::enableProjectAsm,
        )
    }
}

@Composable
private fun AsmWorkspaceControls(
    state: AsmWorkspaceState,
    romAvailable: Boolean,
    projectFilePath: String,
    projectAsmEnabled: Boolean,
    onDownload: () -> Unit,
    onSyncAssets: () -> Unit,
    onEnableProjectAsm: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val referenceReady = state.hasReferenceWorkspace
    val projectReady = state.hasProjectWorkspace
    var expanded by remember { mutableStateOf(false) }
    val showDetails = expanded || !referenceReady || state.busy || state.error != null
    val summary = when {
        state.busy -> state.progress ?: "Updating ASM workspace…"
        projectReady -> {
            val modified = state.projectModifiedFileIds.size
            val unsaved = state.unsavedSourceFileIds.size
            "Project ASM enabled" + when {
                unsaved > 0 -> " · $unsaved unsaved"
                modified > 0 -> " · $modified edited"
                else -> " · clean"
            }
        }
        projectAsmEnabled -> "Project ASM needs repair"
        else -> "Project ASM disabled"
    }
    val summaryColor = when {
        state.error != null || projectAsmEnabled && !projectReady -> MaterialTheme.colorScheme.error
        projectReady -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = if (projectReady) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    summary,
                    fontSize = fs.body,
                    fontWeight = FontWeight.SemiBold,
                    color = summaryColor,
                    modifier = Modifier.weight(1f),
                )
                Text(if (showDetails) "▾" else "▸", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!projectReady && referenceReady && !state.busy) {
                Spacer(Modifier.height(7.dp))
                Button(
                    onClick = onEnableProjectAsm,
                    enabled = projectFilePath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = ASM_CONTROL_SHAPE,
                ) {
                    Text(
                        if (projectAsmEnabled) "Repair Project ASM" else "Enable Project ASM",
                        fontSize = fs.body,
                    )
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(5.dp)) {
                    Row(Modifier.padding(7.dp), verticalAlignment = Alignment.Top) {
                        Text(error, fontSize = fs.detail, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        IconButton(onClick = state::dismissError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            if (showDetails && !state.busy) {
                Spacer(Modifier.height(7.dp))
                Divider()
                Spacer(Modifier.height(7.dp))
                if (!referenceReady) {
                    Text("ASM reference", fontSize = fs.detail, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Button(
                        onClick = onDownload,
                        enabled = romAvailable,
                        modifier = Modifier.fillMaxWidth(),
                        shape = ASM_CONTROL_SHAPE,
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download ASM Reference", fontSize = fs.body)
                    }
                    Text(
                        "Downloads pinned source and derives .bin assets from the open ROM; the ROM itself is never copied.",
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    AsmReferenceStatus(state)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onSyncAssets,
                            enabled = romAvailable,
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.ContentPadding,
                            shape = ASM_CONTROL_SHAPE,
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sync assets", fontSize = fs.detail)
                        }
                        OutlinedButton(
                            onClick = onDownload,
                            enabled = romAvailable,
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.ContentPadding,
                            shape = ASM_CONTROL_SHAPE,
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Redownload", fontSize = fs.detail)
                        }
                    }
                }
                if (projectReady) {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(7.dp))
                    Text("Project ASM enabled", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${state.projectModifiedFileIds.size} saved edits · ${state.unsavedSourceFileIds.size} unsaved buffers",
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Original snapshot ${AsmReferenceContract.COMMIT.take(12)} · compilation is not enabled yet",
                        fontSize = fs.detail,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (referenceReady) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (projectAsmEnabled) {
                            "Repair recreates the local working tree and reapplies saved source overrides."
                        } else {
                            "Enable Project ASM to create an isolated editable workspace for this project."
                        },
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AsmReferenceStatus(state: AsmWorkspaceState) {
    val fs = LocalEditorTheme.current.fontSize.value
    val metadata = state.referenceMetadata
    val background = when {
        metadata == null -> MaterialTheme.colorScheme.surfaceVariant
        state.referenceAssetsMatchCurrentRom -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    Surface(color = background, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(
                when {
                    metadata == null -> "Reference not downloaded"
                    state.referenceAssetsMatchCurrentRom -> "Reference source + assets ready"
                    else -> "Source ready · assets belong to another ROM"
                },
                fontSize = fs.body,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "InsaneFirebat/sm_disassembly · ${AsmReferenceContract.COMMIT.take(12)}" +
                    (metadata?.let { " · ${it.assetCount} assets" } ?: ""),
                fontSize = fs.detail,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            metadata?.romName?.let {
                Text("Assets: $it", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RowScope.AsmModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = fs.body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AsmRomPreviewTree(
    state: AsmWorkspaceState,
    romAvailable: Boolean,
    onBuild: () -> Unit,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val preview = state.romPreview
    Column(modifier) {
        Button(
            onClick = onBuild,
            enabled = romAvailable && !state.previewBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = ASM_CONTROL_SHAPE,
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    state.previewBusy -> "Building result…"
                    preview == null -> "Build SMEDIT Result"
                    else -> "Refresh SMEDIT Result"
                },
                fontSize = fs.body,
            )
        }
        if (state.previewBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        state.error?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Row(Modifier.padding(7.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        error,
                        fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = state::dismissError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        Text(
            "Runs the real export transaction in memory. No ROM or project file is written.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 7.dp),
        )

        AsmRomViewRow(state, AsmRomPreviewView.LOADED_ROM, preview != null)
        AsmRomViewRow(state, AsmRomPreviewView.SMEDIT_RESULT, preview != null)
        AsmRomViewRow(state, AsmRomPreviewView.DIFF, preview != null)

        if (preview == null) {
            Spacer(Modifier.weight(1f))
            Text(
                "Loaded ROM is the file you opened, even when it is already a hack. SMEDIT Result includes pending project edits, enabled patches, generated data, and relocations.",
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            Divider(Modifier.padding(vertical = 7.dp))
            Text(
                "${preview.changedByteCount} changed bytes · ${preview.diffRanges.size} ranges · " +
                    "${preview.writeReport.owners.size} owners",
                fontSize = fs.detail,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            TreeHeading("Changed ranges")
            if (preview.diffRanges.isEmpty()) {
                Text("No byte differences", fontSize = fs.detail, modifier = Modifier.padding(8.dp))
            } else {
                AsmSidebarScrollPane(Modifier.weight(1f).fillMaxWidth()) {
                    items(preview.diffRanges.size, key = { preview.diffRanges[it].pcOffset }) { index ->
                        val range = preview.diffRanges[index]
                        val selected = state.romPreviewView == AsmRomPreviewView.DIFF &&
                            state.selectedRomDiffIndex == index
                        Column(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else Color.Transparent,
                                )
                                .clickable { state.selectRomDiff(index) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                romRangeTitle(range),
                                fontSize = fs.body,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                range.owners.firstOrNull() ?: "ROM expansion / unowned delta",
                                fontSize = fs.detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AsmRomViewRow(state: AsmWorkspaceState, view: AsmRomPreviewView, enabled: Boolean) {
    val fs = LocalEditorTheme.current.fontSize.value
    val selected = state.browserMode == AsmBrowserMode.ROM && state.romPreviewView == view
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else Color.Transparent,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { state.selectRomPreviewView(view) },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(view.title, fontSize = fs.body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(
                when (view) {
                    AsmRomPreviewView.LOADED_ROM -> "Bytes from the ROM you opened"
                    AsmRomPreviewView.SMEDIT_RESULT -> "Validated in-memory export result"
                    AsmRomPreviewView.DIFF -> "Only bytes that would change"
                },
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun romRangeTitle(range: AsmRomDiffRange): String {
    val pc = formatPcOffset(range.pcOffset)
    val snes = pcToSnesLoRom(range.pcOffset)?.let(::formatSnesAddress) ?: "unmapped"
    return "$snes · $pc · ${range.length} B"
}

@Composable
private fun AsmLibraryTree(state: AsmWorkspaceState, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val query = state.query.trim()
    val guides = remember(query) {
        AsmLibrary.guides.filter { guide ->
            query.isEmpty() || guide.title.contains(query, true) || guide.summary.contains(query, true) ||
                guide.keywords.any { it.contains(query, true) }
        }
    }
    val instructions = remember(query) {
        AsmInstructionReference.instructions.values.filter { instruction ->
            query.isEmpty() || instruction.mnemonic.contains(query, true) ||
                instruction.name.contains(query, true) || instruction.summary.contains(query, true) ||
                instruction.category.displayName.contains(query, true)
        }
    }
    val listState = rememberLazyListState()
    val selections = remember(guides, instructions) {
        buildList {
            guides.forEach { add(AsmLibrarySelection(guideId = it.id)) }
            AsmInstructionCategory.entries.forEach { category ->
                instructions.filter { it.category == category }.forEach {
                    add(AsmLibrarySelection(mnemonic = it.mnemonic))
                }
            }
        }
    }
    val selectedSelectionIndex = remember(selections, state.selectedLibraryPageId, state.selectedInstruction) {
        selections.indexOfFirst { selection ->
            if (state.selectedInstruction != null) selection.mnemonic == state.selectedInstruction
            else selection.guideId == state.selectedLibraryPageId
        }
    }
    val selectedLazyIndex = remember(guides, instructions, state.selectedLibraryPageId, state.selectedInstruction) {
        librarySidebarIndex(
            guides = guides,
            instructions = instructions,
            selectedGuideId = state.selectedLibraryPageId,
            selectedMnemonic = state.selectedInstruction,
        )
    }
    val libraryFocusRequester = rememberVerticalSelectionFocusRequester(
        enabled = selections.isNotEmpty(),
        requestFocusOnReady = query.isBlank(),
    )
    val listModifier = modifier.verticalSelectionKeyNavigation(
        focusRequester = libraryFocusRequester,
        itemCount = selections.size,
        selectedIndex = selectedSelectionIndex,
        enabled = selections.isNotEmpty(),
        onSelectIndex = { index ->
            selections[index].guideId?.let(state::openLibraryGuide)
                ?: selections[index].mnemonic?.let(state::openLibraryInstruction)
        },
    )

    LaunchedEffect(selectedLazyIndex, state.navigationSerial) {
        selectedLazyIndex?.let { listState.revealItemIfNeeded(it) }
    }

    AsmSidebarScrollPane(listModifier, listState) {
        if (guides.isNotEmpty()) item { TreeHeading("Learn") }
        items(guides, key = { "guide:${it.id}" }) { guide ->
            val selected = state.selectedInstruction == null && state.selectedLibraryPageId == guide.id
            Column(
                Modifier.fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable {
                        requestVerticalSelectionFocus(libraryFocusRequester)
                        state.openLibraryGuide(guide.id)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(guide.title, fontSize = fs.body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                Text(
                    guide.summary,
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AsmInstructionCategory.entries.forEach { category ->
            val categoryInstructions = instructions.filter { it.category == category }
            if (categoryInstructions.isNotEmpty()) {
                item { TreeHeading(category.displayName) }
                items(categoryInstructions, key = { "instruction:${it.mnemonic}" }) { instruction ->
                    val selected = state.selectedInstruction == instruction.mnemonic
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                            .clickable {
                                requestVerticalSelectionFocus(libraryFocusRequester)
                                state.openLibraryInstruction(instruction.mnemonic)
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            instruction.mnemonic,
                            fontSize = fs.body,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(48.dp),
                        )
                        Text(instruction.name, fontSize = fs.detail, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (guides.isEmpty() && instructions.isEmpty()) {
            item { Text("No Library entry matches", fontSize = fs.detail, modifier = Modifier.padding(8.dp)) }
        }
    }
}

private suspend fun LazyListState.revealItemIfNeeded(index: Int) {
    if (layoutInfo.totalItemsCount == 0) {
        snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
    }
    val initialLayout = layoutInfo
    if (index !in 0 until initialLayout.totalItemsCount) return

    initialLayout.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
        revealVisibleItem(item.index)
        return
    }

    val visibleItems = initialLayout.visibleItemsInfo
    val viewportSize = initialLayout.viewportEndOffset - initialLayout.viewportStartOffset
    val estimatedItemSize = visibleItems
        .map { it.size }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toInt()
        ?.coerceAtLeast(1)
        ?: 1
    val targetIsBelow = visibleItems.lastOrNull()?.index?.let { index > it } ?: true
    val targetOffset = if (targetIsBelow) -(viewportSize - estimatedItemSize).coerceAtLeast(0) else 0
    animateScrollToItem(index, targetOffset)
    revealVisibleItem(index)
}

private suspend fun LazyListState.revealVisibleItem(index: Int) {
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = when {
        item.offset < layout.viewportStartOffset -> item.offset - layout.viewportStartOffset
        item.offset + item.size > layout.viewportEndOffset -> item.offset + item.size - layout.viewportEndOffset
        else -> 0
    }
    if (delta != 0) animateScrollBy(delta.toFloat())
}

private fun librarySidebarIndex(
    guides: List<AsmLibraryGuide>,
    instructions: List<AsmInstructionInfo>,
    selectedGuideId: String,
    selectedMnemonic: String?,
): Int? {
    var index = 0
    if (guides.isNotEmpty()) {
        index++ // Learn heading
        guides.forEach { guide ->
            if (selectedMnemonic == null && guide.id == selectedGuideId) return index
            index++
        }
    }
    AsmInstructionCategory.entries.forEach { category ->
        val categoryInstructions = instructions.filter { it.category == category }
        if (categoryInstructions.isNotEmpty()) {
            index++ // Category heading
            categoryInstructions.forEach { instruction ->
                if (instruction.mnemonic == selectedMnemonic) return index
                index++
            }
        }
    }
    return null
}

@Composable
private fun AsmSourceTree(state: AsmWorkspaceState, index: AsmReferenceIndex, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val query = state.query.trim()
    val results = remember(index, query) {
        if (query.isEmpty()) emptyList() else sourceSearch(index, query)
    }
    AsmSidebarScrollPane(modifier) {
        if (query.isNotEmpty()) {
            items(results, key = { "${it.fileId}:${it.assetPath}:${it.lineIndex}:${it.title}" }) { result ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        result.assetPath?.let(state::openAsset)
                            ?: result.fileId?.let { state.openSource(it, result.lineIndex) }
                    }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(result.title, fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(result.detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (results.isEmpty()) item { Text("No source or address matches", fontSize = fs.detail, modifier = Modifier.padding(8.dp)) }
        } else {
            val banks = index.files.filter(AsmSourceFile::isBank)
            val references = index.files.filterNot(AsmSourceFile::isBank)
            item { TreeHeading("Banks · select a chapter to expand") }
            banks.forEach { source ->
                val expanded = state.expandedSourceFileId == source.id
                item(key = source.id) {
                    SourceFileRow(
                        state = state,
                        source = source,
                        expandable = true,
                        expanded = expanded,
                        onClick = { state.toggleSourceChapter(source.id) },
                    )
                }
                if (expanded) {
                    source.sections.forEach { section ->
                        item(key = "${source.id}:${section.lineIndex}") {
                            Row(
                                Modifier.fillMaxWidth().clickable { state.openSource(source.id, section.lineIndex) }
                                    .padding(start = 22.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                section.address?.let {
                                    Text(it, fontSize = fs.detail, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(section.title, fontSize = fs.detail, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { TreeHeading("Build & symbols") }
            references.forEach { source ->
                item(key = source.id) {
                    SourceFileRow(state = state, source = source, onClick = { state.openSource(source.id) })
                }
            }
        }
    }
}

@Composable
private fun SourceFileRow(
    state: AsmWorkspaceState,
    source: AsmSourceFile,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val selected = state.selectedFileId == source.id && state.selectedAssetPath == null
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (expandable) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = fs.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.displayName,
                    fontSize = fs.body,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                when {
                    state.hasUnsavedSourceChanges(source.id) -> Text(
                        "UNSAVED",
                        fontSize = fs.statusBar,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    source.id in state.projectModifiedFileIds -> Text(
                        "EDITED",
                        fontSize = fs.statusBar,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (source.description.isNotBlank()) {
                Text(source.description, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TreeHeading(label: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(
        label.uppercase(),
        fontSize = fs.detail,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

@Composable
private fun AsmSidebarScrollPane(
    modifier: Modifier,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    AsmSidebarScrollPane(modifier, listState, content)
}

@Composable
private fun AsmSidebarScrollPane(
    modifier: Modifier,
    listState: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = ASM_SCROLLBAR_SIZE),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ASM_SCROLLBAR_SIZE),
        )
    }
}

@Composable
private fun AsmAssetTree(state: AsmWorkspaceState, assets: List<AsmAsset>, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val query = state.query.trim()
    val categories = remember(assets) { assets.groupBy(AsmAsset::category).toSortedMap() }
    val visible = remember(assets, query, state.selectedAssetCategory) {
        when {
            query.isNotEmpty() -> assets.filter { it.range.path.contains(query, ignoreCase = true) }.take(500)
            state.selectedAssetCategory != null -> categories[state.selectedAssetCategory].orEmpty()
            else -> emptyList()
        }
    }
    AsmSidebarScrollPane(modifier) {
        if (query.isEmpty() && state.selectedAssetCategory == null) {
            categories.forEach { (category, categoryAssets) ->
                item(key = category) {
                    Row(
                        Modifier.fillMaxWidth().clickable { state.selectedAssetCategory = category }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Text(category, fontSize = fs.body, modifier = Modifier.weight(1f))
                        Text(categoryAssets.size.toString(), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            if (query.isEmpty()) {
                item {
                    TextButton(onClick = { state.selectedAssetCategory = null }) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("All asset groups", fontSize = fs.detail)
                    }
                }
            }
            items(visible, key = { it.range.path }) { asset ->
                val selected = state.selectedAssetPath == asset.range.path
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
                        .clickable { state.openAsset(asset.range.path) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(asset.range.path, fontSize = fs.detail, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "PC \$${asset.range.pcOffset.toString(16).uppercase().padStart(6, '0')} · ${asset.range.length} bytes",
                        fontSize = fs.statusBar,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (visible.isEmpty()) item { Text("No asset matches", fontSize = fs.detail, modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
internal fun AsmWorkspaceCanvas(
    state: AsmWorkspaceState,
    romParser: RomParser? = null,
    onOpenInEditor: (AsmEditorTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val workspace = state.workspace
    val semanticIndex = remember(romParser) { romParser?.let(AsmSemanticBridge::buildIndex) }
    if (state.browserMode == AsmBrowserMode.ROM) {
        AsmRomPreviewCanvas(state, modifier)
        return
    }
    if (state.browserMode == AsmBrowserMode.LIBRARY) {
        AsmLibraryCanvas(state, workspace?.index, modifier)
        return
    }
    if (workspace == null) {
        AsmWelcomeCanvas(state, modifier)
        return
    }
    val asset = workspace.index.asset(state.selectedAssetPath)
    if (asset != null) {
        AsmAssetCanvas(state, asset, workspace.metadata.romName, semanticIndex, onOpenInEditor, modifier)
    } else {
        val source = workspace.index.file(state.selectedFileId)
        if (source == null) AsmWelcomeCanvas(state, modifier)
        else AsmSourceCanvas(state, workspace.index, source, semanticIndex, onOpenInEditor, modifier)
    }
}

@Composable
private fun AsmRomPreviewCanvas(state: AsmWorkspaceState, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val preview = state.romPreview
    if (preview == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(0.68f),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ROM Comparison", fontSize = fs.display, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Build SMEDIT Result to compare the ROM you opened with the fully validated bytes SMEDIT would export. The preview never writes a ROM.",
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    when (state.romPreviewView) {
        AsmRomPreviewView.LOADED_ROM -> AsmWholeRomCanvas(state, preview, AsmRomPreviewView.LOADED_ROM, modifier)
        AsmRomPreviewView.SMEDIT_RESULT -> AsmWholeRomCanvas(state, preview, AsmRomPreviewView.SMEDIT_RESULT, modifier)
        AsmRomPreviewView.DIFF -> AsmRomDiffCanvas(state, preview, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsmWholeRomCanvas(
    state: AsmWorkspaceState,
    preview: AsmRomPreview,
    view: AsmRomPreviewView,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bytes = preview.bytes(view)
    val bodySize = (bytes.size - preview.headerSize).coerceAtLeast(0)
    val rowCount = (bodySize + 15) / 16
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val minimumContentWidth = remember(fs.body) {
        (ASM_ADDRESS_GUTTER_WIDTH.value + 34f + ASM_HEX_COLUMNS * fs.body.value * MONOSPACE_CHARACTER_WIDTH).dp
    }
    val detail = when (view) {
        AsmRomPreviewView.LOADED_ROM -> "The file opened in SMEDIT; no assumption that it is vanilla"
        AsmRomPreviewView.SMEDIT_RESULT -> "Exact validated output of the current transactional export plan"
        AsmRomPreviewView.DIFF -> ""
    }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(
            state = state,
            title = view.title,
            detail = detail,
            trailing = "${bodySize} bytes${if (preview.headerSize > 0) " · 512-byte copier header hidden" else ""} · read-only",
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                AssetFact("View", view.title)
                AssetFact("ROM body", "$bodySize bytes")
                AssetFact("Changed bytes", preview.changedByteCount.toString())
                AssetFact("Validated writes", preview.writeReport.totalWrites.toString())
            }
        }
        AsmScrollableTextPane(
            listState = listState,
            horizontalState = horizontal,
            minimumContentWidth = minimumContentWidth,
            focusKey = "rom:${view.name}:${System.identityHashCode(preview)}",
            onFind = {},
            onBack = state::goBack,
            onForward = state::goForward,
            modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground()),
        ) {
            items(rowCount, key = { it }) { rowIndex ->
                val pcOffset = rowIndex * 16
                val count = minOf(16, bodySize - pcOffset)
                val values = (0 until count).map { bytes[preview.headerSize + pcOffset + it].toInt() and 0xFF }
                val hex = values.joinToString(" ") { "%02X".format(it) }.padEnd(47)
                val ascii = values.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "." }
                val snes = pcToSnesLoRom(pcOffset)?.let(::formatSnesAddress) ?: "--:----"
                Text(
                    "$snes  ${formatPcOffset(pcOffset)}  $hex  |$ascii|",
                    fontSize = fs.body,
                    fontFamily = FontFamily.Monospace,
                    color = codeForeground(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsmRomDiffCanvas(state: AsmWorkspaceState, preview: AsmRomPreview, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val range = preview.diffRanges.getOrNull(state.selectedRomDiffIndex)
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val minimumContentWidth = remember(fs.body) {
        (ASM_ADDRESS_GUTTER_WIDTH.value + 72f + ASM_HEX_COLUMNS * fs.body.value * MONOSPACE_CHARACTER_WIDTH * 2f).dp
    }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(
            state = state,
            title = AsmRomPreviewView.DIFF.title,
            detail = "Loaded ROM → SMEDIT Result; generated by the validated export transaction",
            trailing = "${preview.changedByteCount} changed bytes · ${preview.diffRanges.size} ranges",
        )
        if (range == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("The current SMEDIT Result is byte-identical to the Loaded ROM.", fontSize = fs.body)
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AssetFact("Range", romRangeTitle(range))
                    AssetFact("Owner", range.owners.joinToString().ifBlank { "ROM expansion / unowned delta" })
                    AssetFact("Kind", range.kinds.joinToString { it.name.lowercase().replace('_', ' ') }.ifBlank { "structural" })
                    pcToSnesLoRom(range.pcOffset)?.let { address ->
                        if (state.workspace == null) {
                            AssetFact("ASM context", "${formatSnesAddress(address)} · download source to open")
                        } else {
                            AssetFact("ASM context", formatSnesAddress(address)) { state.openAddress(address) }
                        }
                    }
                }
            }
            if (range.labels.isNotEmpty()) {
                Text(
                    range.labels.joinToString(" · "),
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val rowCount = (range.length + 15) / 16
            AsmScrollableTextPane(
                listState = listState,
                horizontalState = horizontal,
                minimumContentWidth = minimumContentWidth,
                focusKey = "diff:${range.pcOffset}:${System.identityHashCode(preview)}",
                onFind = {},
                onBack = state::goBack,
                onForward = state::goForward,
                modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground()),
            ) {
                items(rowCount, key = { it }) { rowIndex ->
                    val pcOffset = range.pcOffset + rowIndex * 16
                    val count = minOf(16, range.endInclusive - pcOffset + 1)
                    val loaded = (0 until count).map { preview.loadedByte(pcOffset + it) }
                    val result = (0 until count).map { preview.resultByte(pcOffset + it) }
                    val loadedHex = loaded.joinToString(" ") { it?.let { value -> "%02X".format(value) } ?: "--" }.padEnd(47)
                    val resultHex = result.joinToString(" ") { it?.let { value -> "%02X".format(value) } ?: "--" }.padEnd(47)
                    val snes = pcToSnesLoRom(pcOffset)?.let(::formatSnesAddress) ?: "--:----"
                    Text(
                        "$snes  ${formatPcOffset(pcOffset)}  $loadedHex  →  $resultHex",
                        fontSize = fs.body,
                        fontFamily = FontFamily.Monospace,
                        color = codeForeground(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private data class AsmInstructionUsage(
    val fileId: String,
    val fileName: String,
    val lineIndex: Int,
    val source: String,
)

private data class AsmInstructionUsageReport(
    val total: Int,
    val examples: List<AsmInstructionUsage>,
)

@Composable
private fun AsmLibraryCanvas(
    state: AsmWorkspaceState,
    index: AsmReferenceIndex?,
    modifier: Modifier,
) {
    val instruction = AsmInstructionReference.find(state.selectedInstruction)
    val guide = if (instruction == null) AsmLibrary.guide(state.selectedLibraryPageId) ?: AsmLibrary.guides.first() else null
    val title = instruction?.let { "${it.mnemonic} — ${it.name}" } ?: guide!!.title
    val detail = instruction?.category?.displayName ?: "SNES ASM field guide"
    val listState = rememberLazyListState()

    LaunchedEffect(state.navigationSerial, state.selectedLibraryPageId) {
        listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(
            state = state,
            title = title,
            detail = detail,
            trailing = if (instruction == null) "SMEDIT Library" else "65C816 instruction",
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer(Modifier.fillMaxSize().padding(end = ASM_SCROLLBAR_SIZE)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (instruction != null) {
                        item { AsmInstructionOverview(instruction) }
                        state.selectedInstructionToken?.substringAfter('.', "")?.takeIf(String::isNotEmpty)?.let { suffix ->
                            item { AsmInstructionSuffixCallout(suffix) }
                        }
                        item { AsmInstructionForms(instruction.commonForms) }
                        item { AsmLibraryExample(instruction.example, state::openLibraryInstruction) }
                        item {
                            AsmLibraryCallout("Watch for", instruction.practicalNote)
                        }
                        item {
                            AsmInstructionSourceUsages(
                                report = remember(index, instruction.mnemonic) {
                                    findInstructionUsages(index, instruction.mnemonic)
                                },
                                hasSource = index != null,
                                onOpen = { state.openSource(it.fileId, it.lineIndex) },
                            )
                        }
                        item { AsmLibraryAttribution() }
                    } else if (guide != null) {
                        item {
                            Text(
                                guide.summary,
                                fontSize = LocalEditorTheme.current.fontSize.value.heading,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        guide.sections.forEach { section ->
                            item(key = "${guide.id}:${section.title}") {
                                AsmGuideSection(section, state::openLibraryInstruction)
                            }
                        }
                        item { AsmLibraryAttribution() }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ASM_SCROLLBAR_SIZE),
            )
        }
    }
}

@Composable
private fun AsmInstructionSuffixCallout(suffix: String) {
    val normalized = suffix.uppercase()
    val explanation = when (normalized) {
        "B" -> ".B tells Asar to use the byte-length form. Depending on the operand, that commonly means an 8-bit immediate or direct-page address."
        "W" -> ".W tells Asar to use the word-length form. Depending on the operand, that commonly means a 16-bit immediate or absolute address."
        "L" -> ".L tells Asar to use the long form, commonly carrying a full 24-bit address. It does not mean 32-bit arithmetic."
        else -> ".$normalized is an assembler suffix attached to this instruction. Check the assembler convention before changing it."
    }
    AsmLibraryCallout("You selected .$normalized", explanation)
}

@Composable
private fun AsmInstructionOverview(instruction: com.supermetroid.editor.asm.AsmInstructionInfo) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(instruction.summary, fontSize = fs.heading)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsmFactChip("Category", instruction.category.displayName)
            AsmFactChip("Flags affected", instruction.flags)
        }
    }
}

@Composable
private fun AsmFactChip(label: String, value: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(label, fontSize = fs.statusBar, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = fs.body, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AsmInstructionForms(forms: List<String>) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Common forms", fontSize = fs.heading, fontWeight = FontWeight.Bold)
        Surface(color = codeBackground(), shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                forms.forEach { form ->
                    Text(form, fontSize = fs.body, fontFamily = FontFamily.Monospace, color = codeForeground())
                }
            }
        }
        Text(
            "These are the forms you are most likely to meet, not an exhaustive opcode table.",
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AsmGuideSection(
    section: com.supermetroid.editor.asm.AsmLibrarySection,
    onInstructionClick: (String) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(section.title, fontSize = fs.heading, fontWeight = FontWeight.Bold)
        section.paragraphs.forEach { paragraph ->
            Text(paragraph, fontSize = fs.body, color = MaterialTheme.colorScheme.onSurface)
        }
        section.example?.let { AsmLibraryExample(it, onInstructionClick) }
    }
}

@Composable
private fun AsmLibraryExample(
    example: com.supermetroid.editor.asm.AsmCodeExample,
    onInstructionClick: (String) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val localLabels = remember(example.code) {
        Regex("(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z0-9_]+):")
            .findAll(example.code)
            .map { it.groupValues[1] }
            .toSet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(example.title, fontSize = fs.body, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Surface(color = codeBackground(), shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                val codeLines = example.code.lines()
                val annotatedLines = mutableListOf<AnnotatedString>()
                for (line in codeLines) {
                    annotatedLines += asmAnnotatedLine(line, knownLabels = localLabels)
                }
                val annotated = buildAnnotatedString {
                    annotatedLines.forEachIndexed { index, line ->
                        append(line)
                        if (index != annotatedLines.lastIndex) append('\n')
                    }
                }
                AsmSelectableLinkedText(
                    text = annotated,
                    style = TextStyle(
                        fontSize = fs.body,
                        fontFamily = FontFamily.Monospace,
                        color = codeForeground(),
                    ),
                    onAnnotationClick = { annotation ->
                        if (annotation.tag == "instruction") onInstructionClick(annotation.item)
                    },
                )
            }
        }
        Text(example.explanation, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AsmLibraryCallout(title: String, text: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = fs.body, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Text(text, fontSize = fs.body)
        }
    }
}

@Composable
private fun AsmInstructionSourceUsages(
    report: AsmInstructionUsageReport,
    hasSource: Boolean,
    onOpen: (AsmInstructionUsage) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("In Super Metroid", fontSize = fs.heading, fontWeight = FontWeight.Bold)
        when {
            !hasSource -> Text(
                "Download the ASM reference to see real usages and jump directly into the source.",
                fontSize = fs.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            report.total == 0 -> Text(
                "No direct usage was found in the downloaded source.",
                fontSize = fs.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    "${report.total} direct ${if (report.total == 1) "usage" else "usages"} found. Select an example to open it in context.",
                    fontSize = fs.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report.examples.forEach { usage ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(usage) },
                    ) {
                        Column(Modifier.padding(9.dp)) {
                            Text(
                                "${usage.fileName} · line ${usage.lineIndex + 1}",
                                fontSize = fs.detail,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                usage.source.trim(),
                                fontSize = fs.body,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AsmLibraryAttribution() {
    val fs = LocalEditorTheme.current.fontSize.value
    Divider()
    Text(
        "Reference facts checked against undisbeliever's 65816 Opcodes (CC BY-SA 4.0), the Asar manual, and WDC's W65C816S documentation. Explanations and examples are written for SMEDIT.",
        fontSize = fs.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun findInstructionUsages(index: AsmReferenceIndex?, mnemonic: String): AsmInstructionUsageReport {
    if (index == null) return AsmInstructionUsageReport(0, emptyList())
    val pattern = Regex(
        "^\\s*(?:[A-Za-z_][A-Za-z0-9_.]*:\\s*)?${Regex.escape(mnemonic)}(?:\\.[bBwWlL])?\\b",
        RegexOption.IGNORE_CASE,
    )
    var total = 0
    val examples = mutableListOf<AsmInstructionUsage>()
    index.files.forEach { source ->
        source.lines.forEachIndexed { lineIndex, line ->
            val code = line.substringBefore(';')
            if (pattern.containsMatchIn(code)) {
                total++
                if (examples.size < ASM_LIBRARY_USAGE_EXAMPLES) {
                    examples += AsmInstructionUsage(source.id, source.displayName, lineIndex, code)
                }
            }
        }
    }
    return AsmInstructionUsageReport(total, examples)
}

@Composable
private fun AsmWelcomeCanvas(state: AsmWorkspaceState, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.68f),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Super Metroid ASM", fontSize = fs.display, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.busy) state.progress ?: "Preparing the reference…"
                    else "Choose Download ASM Reference on the left. SMEDIT will open the pinned annotated source as banks and functional sections, then derive every required binary asset from your already-loaded ROM.",
                    fontSize = fs.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AsmSourceCanvas(
    state: AsmWorkspaceState,
    index: AsmReferenceIndex,
    source: AsmSourceFile,
    semanticIndex: AsmSemanticIndex?,
    onOpenInEditor: (AsmEditorTarget) -> Unit,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val addressAnchorsByLine = remember(index, source.id) {
        source.lines.indices.mapNotNull { lineIndex ->
            index.addressAtlas.exactAt(source.id, lineIndex).firstOrNull()?.let { lineIndex to it }
        }.toMap()
    }
    val minimumContentWidth = remember(source.id, fs.body) {
        val longestLine = source.lines.maxOfOrNull(String::length)?.coerceAtMost(MAX_MEASURED_CODE_COLUMNS) ?: 0
        (ASM_LINE_NUMBER_WIDTH.value + ASM_ADDRESS_GUTTER_WIDTH.value + 24f +
            longestLine * fs.body.value * MONOSPACE_CHARACTER_WIDTH).dp
    }
    LaunchedEffect(state.navigationSerial, source.id) {
        if (source.lines.isNotEmpty()) listState.scrollToItem(state.selectedLineIndex.coerceIn(source.lines.indices))
    }
    val exactSelectionAddress = addressAnchorsByLine[state.selectedLineIndex]
    val selectionAddress = exactSelectionAddress ?: index.addressAtlas.contextAt(source.id, state.selectedLineIndex)
    val addressSummary = selectionAddress?.pcOffset?.let { pcOffset ->
        val prefix = if (exactSelectionAddress != null) "" else "Near "
        "$prefix${formatSnesAddress(selectionAddress.snesAddress)} · ${formatPcOffset(pcOffset)} · "
    }.orEmpty()
    val editorLinks = remember(semanticIndex, exactSelectionAddress?.snesAddress) {
        val address = exactSelectionAddress?.snesAddress
        if (semanticIndex == null || address == null) emptyList()
        else semanticIndex.linksFor(address)
    }
    val referenceLabel = remember(index, source.id, state.selectedLineIndex, state.activeReferenceSymbol) {
        index.label(state.activeReferenceSymbol)
            ?: index.labelsAt(source.id, state.selectedLineIndex)
                .maxByOrNull { index.usagesFor(it).size }
    }
    val labelUsages = remember(index, referenceLabel) {
        referenceLabel?.let(index::usagesFor).orEmpty()
    }
    var confirmRestore by remember(source.id) { mutableStateOf(false) }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore original source?", fontSize = fs.heading) },
            text = {
                Text(
                    "This replaces the saved project copy of ${source.displayName} with its immutable original snapshot. Unsaved changes in this file will also be discarded.",
                    fontSize = fs.body,
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    scope.launch { state.restoreOriginalSource(source.id) }
                }, shape = ASM_CONTROL_SHAPE) { Text("Restore original", fontSize = fs.body) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel", fontSize = fs.body) }
            },
        )
    }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(
            state,
            source.displayName,
            source.description,
            "$addressSummary${source.lines.size} lines · " + when {
                state.workspaceKind == AsmWorkspaceKind.REFERENCE -> "read-only reference"
                source.id in state.projectModifiedFileIds -> "project source · modified"
                else -> "project source · editable"
            },
        )
        if (state.workspaceKind == AsmWorkspaceKind.PROJECT) {
            AsmSourceEditBar(
                state = state,
                source = source,
                onSave = { scope.launch { state.saveSource(source.id) } },
                onRestoreOriginal = { confirmRestore = true },
            )
            AsmEditableSourcePane(
                state = state,
                source = source,
                modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground()),
            )
        } else {
            AsmEditorLinkBar(editorLinks, onOpenInEditor)
            if (referenceLabel != null) {
                AsmReferencesBar(
                    state = state,
                    index = index,
                    label = referenceLabel,
                    usages = labelUsages,
                )
            }
            AsmScrollableTextPane(
                listState = listState,
                horizontalState = horizontal,
                minimumContentWidth = minimumContentWidth,
                focusKey = source.id,
                onFind = state::requestSearchFocus,
                onBack = state::goBack,
                onForward = state::goForward,
                modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground()),
            ) {
                items(source.lines.size, key = { it }) { lineIndex ->
                    val selected = lineIndex == state.selectedLineIndex
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.11f) else Color.Transparent)
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        DisableSelection {
                            Row(Modifier.clickable { state.selectSourceLine(lineIndex) }) {
                                Text(
                                    (lineIndex + 1).toString(),
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(ASM_LINE_NUMBER_WIDTH).padding(end = 10.dp),
                                )
                                Text(
                                    addressAnchorsByLine[lineIndex]?.let { formatSnesAddress(it.snesAddress) }.orEmpty(),
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.width(ASM_ADDRESS_GUTTER_WIDTH).padding(end = 10.dp),
                                )
                            }
                        }
                        val annotated = asmAnnotatedLine(source.lines[lineIndex], source.id, lineIndex, index)
                        Box(Modifier.weight(1f)) {
                            AsmSelectableLinkedText(
                                text = annotated,
                                style = TextStyle(
                                    fontSize = fs.body,
                                    fontFamily = FontFamily.Monospace,
                                    color = codeForeground(),
                                ),
                                onAnnotationClick = { annotation ->
                                    when (annotation.tag) {
                                        "label" -> state.openLabel(source.id, lineIndex, annotation.item)
                                        "definition" -> state.showReferences(source.id, lineIndex, annotation.item)
                                        "asset" -> state.openAssetReference(annotation.item)
                                        "instruction" -> state.showInstruction(annotation.item)
                                        "address" -> annotation.item.toIntOrNull()?.let(state::openAddress)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AsmSourceEditBar(
    state: AsmWorkspaceState,
    source: AsmSourceFile,
    onSave: () -> Unit,
    onRestoreOriginal: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val unsaved = state.hasUnsavedSourceChanges(source.id)
    val modified = source.id in state.projectModifiedFileIds
    Surface(
        color = if (unsaved) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f)
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when {
                    unsaved -> "Unsaved buffer"
                    modified -> "Saved project edit"
                    else -> "Matches original snapshot"
                },
                fontSize = fs.detail,
                fontWeight = FontWeight.SemiBold,
                color = if (unsaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { state.discardSourceBuffer(source.id) },
                enabled = unsaved && !state.busy,
                shape = ASM_CONTROL_SHAPE,
            ) {
                Text("Revert buffer", fontSize = fs.detail)
            }
            Button(onClick = onSave, enabled = unsaved && !state.busy, shape = ASM_CONTROL_SHAPE) {
                Text("Save", fontSize = fs.detail)
            }
            OutlinedButton(
                onClick = onRestoreOriginal,
                enabled = (modified || unsaved) && !state.busy,
                shape = ASM_CONTROL_SHAPE,
            ) {
                Text("Restore original", fontSize = fs.detail)
            }
        }
    }
}

@Composable
private fun AsmEditableSourcePane(
    state: AsmWorkspaceState,
    source: AsmSourceFile,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val density = LocalDensity.current
    var pageStartLine by remember(source.id, state.editSessionSerial) {
        mutableStateOf((state.selectedLineIndex / ASM_EDIT_PAGE_LINES) * ASM_EDIT_PAGE_LINES)
    }
    LaunchedEffect(source.id, state.selectedLineIndex) {
        val targetPage = (state.selectedLineIndex / ASM_EDIT_PAGE_LINES) * ASM_EDIT_PAGE_LINES
        if (state.selectedLineIndex !in pageStartLine until (pageStartLine + ASM_EDIT_PAGE_LINES)) {
            pageStartLine = targetPage
        }
    }
    val pageKey = AsmEditPageKey(source.id, state.editSessionSerial, state.navigationSerial, pageStartLine)
    val page = remember(pageKey) {
        asmSourceEditPage(state.sourceEditText(source.id), pageStartLine, ASM_EDIT_PAGE_LINES)
    }
    val initialCursorLine = state.selectedLineIndex
        .takeIf { it in page.startLine until (page.startLine + page.lineCount) }
        ?.minus(page.startLine)
        ?: 0
    var fieldValue by remember(pageKey) {
        val cursor = asmLineStartOffset(page.text, initialCursorLine)
        mutableStateOf(TextFieldValue(page.text, selection = TextRange(cursor)))
    }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineCount = fieldValue.text.count { it == '\n' } + 1
    val fullText = page.merge(fieldValue.text)
    val totalLineCount = fullText.count { it == '\n' } + 1
    val displayedEndLine = (page.startLine + lineCount).coerceAtMost(totalLineCount)
    val longestLine = fieldValue.text.lineSequence().maxOfOrNull(String::length)
        ?.coerceAtMost(MAX_MEASURED_CODE_COLUMNS) ?: 0
    val lineHeight = fs.body * 1.4f
    LaunchedEffect(pageKey) {
        val targetWithinPage = initialCursorLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        if (targetWithinPage > 0) {
            snapshotFlow { vertical.maxValue }.first { it > 0 }
            val lineHeightPx = with(density) { lineHeight.toPx() }
            vertical.scrollTo((targetWithinPage * lineHeightPx).toInt().coerceAtMost(vertical.maxValue))
        }
    }
    val syntaxColors = AsmEditSyntaxColors(
        foreground = codeForeground(),
        comment = if (isDarkCodeTheme()) Color(0xFF789879) else Color(0xFF4D7A50),
        number = if (isDarkCodeTheme()) Color(0xFFFFB86C) else Color(0xFF9A4F00),
        directive = if (isDarkCodeTheme()) Color(0xFFC792EA) else Color(0xFF7B1FA2),
        string = if (isDarkCodeTheme()) Color(0xFFC3E88D) else Color(0xFF397B24),
        definition = if (isDarkCodeTheme()) Color(0xFFFFCB6B) else Color(0xFF8A6100),
    )
    val transformation = remember(syntaxColors) {
        VisualTransformation { input ->
            TransformedText(asmEditableHighlight(input.text, syntaxColors), OffsetMapping.Identity)
        }
    }
    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OutlinedButton(
                    onClick = { pageStartLine = (page.startLine - ASM_EDIT_PAGE_LINES).coerceAtLeast(0) },
                    enabled = page.startLine > 0,
                    shape = ASM_CONTROL_SHAPE,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text("Previous", fontSize = fs.detail) }
                Text(
                    "Lines ${page.startLine + 1}–$displayedEndLine of $totalLineCount",
                    fontSize = fs.detail,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { pageStartLine = page.startLine + lineCount },
                    enabled = displayedEndLine < totalLineCount,
                    shape = ASM_CONTROL_SHAPE,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text("Next", fontSize = fs.detail) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentWidth = maxOf(
                maxWidth - ASM_SCROLLBAR_SIZE,
                (ASM_LINE_NUMBER_WIDTH.value + 28f + longestLine * fs.body.value * MONOSPACE_CHARACTER_WIDTH).dp,
            )
            Row(
                Modifier.fillMaxSize()
                    .padding(end = ASM_SCROLLBAR_SIZE, bottom = ASM_SCROLLBAR_SIZE)
                    .horizontalScroll(horizontal)
                    .verticalScroll(vertical)
                    .width(contentWidth)
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    buildString {
                        repeat(lineCount) { index ->
                            append(page.startLine + index + 1)
                            if (index != lineCount - 1) append('\n')
                        }
                    },
                    fontSize = fs.body,
                    lineHeight = lineHeight,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(ASM_LINE_NUMBER_WIDTH).padding(end = 10.dp),
                )
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        fieldValue = updated
                        state.updateSourceEditText(source.id, page.merge(updated.text))
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        fontSize = fs.body,
                        lineHeight = lineHeight,
                        fontFamily = FontFamily.Monospace,
                        color = syntaxColors.foreground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vertical),
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(bottom = ASM_SCROLLBAR_SIZE)
                    .fillMaxHeight()
                    .width(ASM_SCROLLBAR_SIZE),
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(end = ASM_SCROLLBAR_SIZE)
                    .fillMaxWidth()
                    .height(ASM_SCROLLBAR_SIZE),
            )
        }
    }
}

private data class AsmEditPageKey(
    val fileId: String,
    val editSessionSerial: Long,
    val navigationSerial: Long,
    val startLine: Int,
)

internal data class AsmSourceEditPage(
    val startLine: Int,
    val text: String,
    val prefix: String,
    val suffix: String,
    val totalLineCount: Int,
) {
    val lineCount: Int get() = text.count { it == '\n' } + 1
    fun merge(updatedPageText: String): String = prefix + updatedPageText + suffix
}

internal fun asmSourceEditPage(text: String, requestedStartLine: Int, maxLines: Int): AsmSourceEditPage {
    require(maxLines > 0) { "maxLines must be positive" }
    val starts = mutableListOf(0)
    text.forEachIndexed { index, character ->
        if (character == '\n') starts += index + 1
    }
    val startLine = requestedStartLine.coerceIn(0, starts.lastIndex)
    val endLineExclusive = (startLine + maxLines).coerceAtMost(starts.size)
    val startOffset = starts[startLine]
    val endOffset = if (endLineExclusive < starts.size) starts[endLineExclusive] - 1 else text.length
    return AsmSourceEditPage(
        startLine = startLine,
        text = text.substring(startOffset, endOffset),
        prefix = text.substring(0, startOffset),
        suffix = text.substring(endOffset),
        totalLineCount = starts.size,
    )
}

private fun asmLineStartOffset(text: String, lineIndex: Int): Int {
    if (lineIndex <= 0) return 0
    var remaining = lineIndex
    text.forEachIndexed { index, character ->
        if (character == '\n' && --remaining == 0) return index + 1
    }
    return text.length
}

@Composable
private fun AsmReferencesBar(
    state: AsmWorkspaceState,
    index: AsmReferenceIndex,
    label: AsmLabel,
    usages: List<AsmLabelUsage>,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember(label.symbolId) { mutableStateOf(false) }
    val currentIndex = usages.indexOfFirst {
        it.fileId == state.selectedFileId && it.lineIndex == state.selectedLineIndex
    }
    val previous = when {
        usages.isEmpty() -> null
        currentIndex <= 0 -> usages.last()
        else -> usages[currentIndex - 1]
    }
    val next = when {
        usages.isEmpty() -> null
        currentIndex < 0 || currentIndex == usages.lastIndex -> usages.first()
        else -> usages[currentIndex + 1]
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "References",
                    fontSize = fs.detail,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label.name,
                    fontSize = fs.body,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${usages.size} ${if (usages.size == 1) "usage" else "usages"}",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                TextButton(
                    onClick = { previous?.let { state.openReference(label, it) } },
                    enabled = previous != null,
                ) { Text("Prev", fontSize = fs.detail) }
                TextButton(
                    onClick = { next?.let { state.openReference(label, it) } },
                    enabled = next != null,
                ) { Text("Next", fontSize = fs.detail) }
                TextButton(
                    onClick = { expanded = !expanded },
                    enabled = usages.isNotEmpty(),
                ) { Text(if (expanded) "Hide" else "Show all", fontSize = fs.detail) }
            }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
                    items(usages, key = { "${it.fileId}:${it.lineIndex}:${it.column}" }) { usage ->
                        val selected = usage.fileId == state.selectedFileId && usage.lineIndex == state.selectedLineIndex
                        val source = index.file(usage.fileId)
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().clickable { state.openReference(label, usage) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "${source?.displayName ?: usage.fileId}:${usage.lineIndex + 1}",
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(150.dp),
                                )
                                Text(
                                    usage.sourceLine,
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Source text must remain selectable while its annotated symbols remain navigable.
 *
 * ClickableText owns the primary press gesture, which competes with SelectionContainer's
 * drag and double-click recognizers. This observer watches the initial pointer pass without
 * consuming it, then treats only an unmoved primary-button release as a link activation.
 */
@Composable
private fun AsmSelectableLinkedText(
    text: AnnotatedString,
    style: TextStyle,
    onAnnotationClick: (AnnotatedString.Range<String>) -> Unit,
) {
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = text,
        style = style,
        softWrap = false,
        onTextLayout = { layoutResult = it },
        modifier = Modifier.pointerInput(text) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                var releasePosition = down.position
                var releaseMouseEvent: MouseEvent? = null
                var released = false
                var movedBeyondClick = false

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    releasePosition = change.position
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        movedBeyondClick = true
                    }
                    if (!change.pressed) {
                        releaseMouseEvent = event.nativeEvent as? MouseEvent
                        released = true
                        break
                    }
                }

                val isPrimaryRelease = releaseMouseEvent?.button?.let { it == MouseEvent.BUTTON1 } ?: true
                if (released && !movedBeyondClick && isPrimaryRelease) {
                    val offset = layoutResult?.getOffsetForPosition(releasePosition) ?: return@awaitEachGesture
                    text.getStringAnnotations(start = offset, end = offset)
                        .firstOrNull()
                        ?.let(onAnnotationClick)
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun AsmScrollableTextPane(
    listState: LazyListState,
    horizontalState: ScrollState,
    minimumContentWidth: Dp,
    focusKey: Any,
    onFind: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var isPanning by remember { mutableStateOf(false) }
    var lastPanX by remember { mutableStateOf(0f) }
    var lastPanY by remember { mutableStateOf(0f) }

    LaunchedEffect(focusKey) {
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(modifier) {
        val contentWidth = maxOf(maxWidth - ASM_SCROLLBAR_SIZE, minimumContentWidth)
        Box(
            Modifier.fillMaxSize()
                .padding(end = ASM_SCROLLBAR_SIZE, bottom = ASM_SCROLLBAR_SIZE)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command = event.isCtrlPressed || event.isMetaPressed
                    when {
                        command && event.key == Key.F -> {
                            onFind()
                            true
                        }
                        (event.isAltPressed && event.key == Key.DirectionLeft) ||
                            (command && event.key == Key.LeftBracket) -> {
                            onBack()
                            true
                        }
                        (event.isAltPressed && event.key == Key.DirectionRight) ||
                            (command && event.key == Key.RightBracket) -> {
                            onForward()
                            true
                        }
                        else -> false
                    }
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    val native = event.nativeEvent as? MouseEvent
                    // Primary presses must remain unhandled so SelectionContainer can take focus.
                    when {
                        event.buttons.isBackPressed || native?.button == MOUSE_BACK_BUTTON -> {
                            onBack()
                            event.changes.forEach { it.consume() }
                        }
                        event.buttons.isForwardPressed || native?.button == MOUSE_FORWARD_BUTTON -> {
                            onForward()
                            event.changes.forEach { it.consume() }
                        }
                        native?.button == MouseEvent.BUTTON2 -> {
                            isPanning = true
                            event.changes.firstOrNull()?.position?.let { position ->
                                lastPanX = position.x
                                lastPanY = position.y
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (isPanning) {
                        val native = event.nativeEvent as? MouseEvent
                        if (native != null && (native.modifiersEx and InputEvent.BUTTON2_DOWN_MASK) == 0) {
                            isPanning = false
                        } else {
                            event.changes.firstOrNull()?.let { change ->
                                val position = change.position
                                horizontalState.dispatchRawDelta(lastPanX - position.x)
                                listState.dispatchRawDelta(lastPanY - position.y)
                                lastPanX = position.x
                                lastPanY = position.y
                                change.consume()
                            }
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Release) { event ->
                    val native = event.nativeEvent as? MouseEvent
                    if (native == null || native.button == MouseEvent.BUTTON2) isPanning = false
                }
                .onPointerEvent(PointerEventType.Exit) { isPanning = false }
                .horizontalScroll(horizontalState),
        ) {
            SelectionContainer(
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd)
                .padding(bottom = ASM_SCROLLBAR_SIZE)
                .fillMaxHeight()
                .width(ASM_SCROLLBAR_SIZE),
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalState),
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(end = ASM_SCROLLBAR_SIZE)
                .fillMaxWidth()
                .height(ASM_SCROLLBAR_SIZE),
        )
    }
}

@Composable
private fun AsmNavigationHeader(state: AsmWorkspaceState, title: String, detail: String, trailing: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::goBack, enabled = state.canGoBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowBack, "Back", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = state::goForward, enabled = state.canGoForward, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowForward, "Forward", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = fs.heading, fontWeight = FontWeight.Bold)
                if (detail.isNotBlank()) Text(detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Divider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsmAssetCanvas(
    state: AsmWorkspaceState,
    asset: AsmAsset,
    romName: String,
    semanticIndex: AsmSemanticIndex?,
    onOpenInEditor: (AsmEditorTarget) -> Unit,
    modifier: Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bytesResult = remember(asset.file.absolutePath, asset.file.lastModified()) { runCatching { asset.file.readBytes() } }
    val bytes = bytesResult.getOrNull()
    val rows = remember(bytes) { bytes?.asList()?.chunked(16).orEmpty() }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val sourceResolution = remember(state.workspace, asset.range.snesAddress) {
        state.workspace?.index?.addressAtlas?.resolve(
            AsmAddressQuery(asset.range.snesAddress, AsmAddressSpace.SNES),
        )
    }
    val sourceAnchor = sourceResolution?.sourceAnchor
    val sourceIsExact = sourceResolution?.exactSourceAnchors?.isNotEmpty() == true
    val editorLinks = remember(semanticIndex, asset.range.snesAddress, asset.range.path) {
        semanticIndex?.linksFor(asset.range.snesAddress, asset.range.path).orEmpty()
    }
    val minimumContentWidth = remember(fs.body) {
        (24f + ASM_HEX_COLUMNS * fs.body.value * MONOSPACE_CHARACTER_WIDTH).dp
    }
    Column(modifier.fillMaxSize()) {
        AsmNavigationHeader(state, asset.range.path, asset.category, "${asset.range.length} bytes · read-only")
        AsmEditorLinkBar(editorLinks, onOpenInEditor)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssetFact("ROM source", romName)
                AssetFact("PC range", "0x${asset.range.pcOffset.hex(6)}–0x${(asset.range.endExclusive - 1).hex(6)}")
                AssetFact("SNES start", formatSnesAddress(asset.range.snesAddress))
                if (sourceAnchor != null) {
                    AssetFact(
                        label = "Source context",
                        value = "${if (sourceIsExact) "Exact" else "Near"} ${formatSnesAddress(sourceAnchor.snesAddress)}",
                        onClick = { state.openSource(sourceAnchor.fileId, sourceAnchor.lineIndex) },
                    )
                }
                AssetFact("File", if (asset.file.isFile) "Extracted" else "Missing")
            }
        }
        if (bytes == null) {
            Text(
                bytesResult.exceptionOrNull()?.message ?: "This asset has not been extracted.",
                fontSize = fs.body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            AsmScrollableTextPane(
                listState = listState,
                horizontalState = horizontal,
                minimumContentWidth = minimumContentWidth,
                focusKey = asset.range.path,
                onFind = state::requestSearchFocus,
                onBack = state::goBack,
                onForward = state::goForward,
                modifier = Modifier.weight(1f).fillMaxWidth().background(codeBackground()),
            ) {
                items(rows.size) { rowIndex ->
                    val offset = rowIndex * 16
                    val row = rows[rowIndex]
                    val hex = row.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }.padEnd(47)
                    val ascii = row.joinToString("") { byte ->
                        val value = byte.toInt() and 0xFF
                        if (value in 0x20..0x7E) value.toChar().toString() else "."
                    }
                    Text(
                        "${offset.hex(6)}  $hex  |$ascii|",
                        fontSize = fs.body,
                        fontFamily = FontFamily.Monospace,
                        color = codeForeground(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsmEditorLinkBar(
    links: List<AsmEditorLink>,
    onOpenInEditor: (AsmEditorTarget) -> Unit,
) {
    if (links.isEmpty()) return
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Open in SMEDIT",
                fontSize = fs.detail,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
            )
            for (link in links) {
                OutlinedButton(
                    onClick = { onOpenInEditor(link.target) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                    shape = ASM_CONTROL_SHAPE,
                ) {
                    Column {
                        Text(link.title, fontSize = fs.detail, fontWeight = FontWeight.SemiBold)
                        Text(
                            link.detail,
                            fontSize = fs.statusBar,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetFact(label: String, value: String, onClick: (() -> Unit)? = null) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    ) {
        Text(label, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = fs.body,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (onClick == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            textDecoration = if (onClick == null) null else TextDecoration.Underline,
        )
    }
}

private fun sourceSearch(index: AsmReferenceIndex, query: String): List<AsmSearchResult> {
    parseAsmAddressQuery(query)?.let { return addressSearch(index, it) }
    val results = mutableListOf<AsmSearchResult>()
    index.files.forEach { source ->
        if (source.displayName.contains(query, true) || source.description.contains(query, true) || source.id.contains(query, true)) {
            results += AsmSearchResult(source.displayName, source.description.ifBlank { source.id }, source.id, 0)
        }
        source.sections.asSequence().filter { it.title.contains(query, true) || it.address?.contains(query, true) == true }
            .take(80).forEach { section ->
                results += AsmSearchResult(section.title, "${source.displayName} · ${section.address ?: "line ${section.lineIndex + 1}"}", source.id, section.lineIndex)
            }
    }
    index.labels.asSequence().filter { it.name.contains(query, true) }.take(120).forEach { label ->
        val source = index.file(label.fileId) ?: return@forEach
        results += AsmSearchResult(label.name, "${source.displayName} · line ${label.lineIndex + 1}", label.fileId, label.lineIndex)
    }
    return results.take(250)
}

private fun addressSearch(index: AsmReferenceIndex, query: AsmAddressQuery): List<AsmSearchResult> {
    val resolution = index.addressAtlas.resolve(query)
    val formattedSnes = formatSnesAddress(query.snesAddress)
    val formattedPc = formatPcOffset(query.pcOffset)
    val results = mutableListOf<AsmSearchResult>()

    resolution.exactSourceAnchors
        // One high-quality landing per file keeps org/section/recorded aliases
        // from turning a single address result into visual noise.
        .distinctBy { it.fileId }
        .forEach { anchor ->
            val source = index.file(anchor.fileId) ?: return@forEach
            results += AsmSearchResult(
                title = anchor.label ?: formattedSnes,
                detail = "Exact source · $formattedSnes · $formattedPc · ${source.displayName}",
                fileId = anchor.fileId,
                lineIndex = anchor.lineIndex,
            )
        }

    resolution.containingAsset?.let { asset ->
        val offset = resolution.assetDelta ?: 0
        results += AsmSearchResult(
            title = asset.range.path,
            detail = "Extracted asset · $formattedSnes · $formattedPc · +0x${offset.hex(4)}",
            assetPath = asset.range.path,
        )
    }

    if (resolution.exactSourceAnchors.isEmpty()) {
        resolution.nearestSourceAnchor?.let { anchor ->
            val source = index.file(anchor.fileId) ?: return@let
            val delta = resolution.sourceDelta ?: 0
            results += AsmSearchResult(
                title = formattedSnes,
                detail = "Nearest source anchor ${formatSnesAddress(anchor.snesAddress)} +0x${delta.hex(4)} · $formattedPc · ${source.displayName}",
                fileId = anchor.fileId,
                lineIndex = anchor.lineIndex,
            )
        }
    }
    return results
}

@Composable
private fun asmAnnotatedLine(
    line: String,
    fileId: String? = null,
    lineIndex: Int = 0,
    index: AsmReferenceIndex? = null,
    knownLabels: Set<String> = emptySet(),
): AnnotatedString {
    val commentColor = if (isDarkCodeTheme()) Color(0xFF789879) else Color(0xFF4D7A50)
    val numberColor = if (isDarkCodeTheme()) Color(0xFFFFB86C) else Color(0xFF9A4F00)
    val directiveColor = if (isDarkCodeTheme()) Color(0xFFC792EA) else Color(0xFF7B1FA2)
    val labelColor = if (isDarkCodeTheme()) Color(0xFF82AAFF) else Color(0xFF1455A0)
    val stringColor = if (isDarkCodeTheme()) Color(0xFFC3E88D) else Color(0xFF397B24)
    val definitionColor = if (isDarkCodeTheme()) Color(0xFFFFCB6B) else Color(0xFF8A6100)
    val foreground = codeForeground()
    val commentStart = line.indexOf(';').let { if (it < 0) line.length else it }
    val code = line.substring(0, commentStart)
    val tokenRegex = Regex("\"[^\"]*\"|\\$[0-9A-Fa-f]{2}:[0-9A-Fa-f]{4}|\\$[0-9A-Fa-f]+|%[01]+|\\b[0-9]+\\b|\\.?[A-Za-z_][A-Za-z0-9_.]*")
    val matches = tokenRegex.findAll(code).toList()
    val definition = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z0-9_]+):").find(code)?.groupValues?.get(1)
        ?: Regex("^\\s*!([A-Za-z_][A-Za-z0-9_]*)\\s*=").find(code)?.groupValues?.get(1)
    val instructionMatch = matches.firstOrNull { match ->
        match.value != definition && AsmInstructionReference.find(match.value.substringBefore('.')) != null
    }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            append(code.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith('"') -> {
                    val reference = token.removeSurrounding("\"")
                    val asset = index?.resolveAsset(reference)
                    if (asset != null) pushStringAnnotation("asset", reference)
                    pushStyle(SpanStyle(color = if (asset != null) labelColor else stringColor, textDecoration = if (asset != null) TextDecoration.Underline else null))
                    append(token)
                    pop()
                    if (asset != null) pop()
                }
                token == definition -> {
                    val navigableDefinition = index != null && fileId != null &&
                        index.definition(fileId, lineIndex, token) != null
                    if (navigableDefinition) pushStringAnnotation("definition", token)
                    pushStyle(
                        SpanStyle(
                            color = definitionColor,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (navigableDefinition) TextDecoration.Underline else null,
                        ),
                    )
                    append(token)
                    pop()
                    if (navigableDefinition) pop()
                }
                match == instructionMatch -> {
                    pushStringAnnotation("instruction", token.uppercase())
                    pushStyle(SpanStyle(color = directiveColor, fontWeight = FontWeight.SemiBold)); append(token); pop(); pop()
                }
                token.startsWith('$') || token.startsWith('%') || token.firstOrNull()?.isDigit() == true -> {
                    val parsedAddress = if (token.startsWith('$')) parseAsmAddressQuery(token) else null
                    val navigableAddress = if (parsedAddress != null && index != null) {
                        val resolution = index.addressAtlas.resolve(parsedAddress)
                        parsedAddress.takeIf {
                            resolution.exactSourceAnchors.isNotEmpty() ||
                                resolution.containingAsset != null || resolution.nearestSourceAnchor != null
                        }
                    } else null
                    if (navigableAddress != null) {
                        pushStringAnnotation("address", navigableAddress.snesAddress.toString())
                    }
                    pushStyle(
                        SpanStyle(
                            color = numberColor,
                            textDecoration = if (navigableAddress != null) TextDecoration.Underline else null,
                        ),
                    )
                    append(token)
                    pop()
                    if (navigableAddress != null) pop()
                }
                token.lowercase() in ASM_DIRECTIVES -> {
                    pushStyle(SpanStyle(color = directiveColor)); append(token); pop()
                }
                token in knownLabels -> {
                    pushStyle(SpanStyle(color = labelColor)); append(token); pop()
                }
                index != null && fileId != null && index.resolveLabel(fileId, lineIndex, token) != null -> {
                    pushStringAnnotation("label", token)
                    pushStyle(SpanStyle(color = labelColor, textDecoration = TextDecoration.Underline)); append(token); pop(); pop()
                }
                else -> {
                    pushStyle(SpanStyle(color = foreground)); append(token); pop()
                }
            }
            cursor = match.range.last + 1
        }
        append(code.substring(cursor))
        if (commentStart < line.length) {
            pushStyle(SpanStyle(color = commentColor)); append(line.substring(commentStart)); pop()
        }
    }
}

@Composable
private fun isDarkCodeTheme(): Boolean = LocalEditorTheme.current.theme.value == EditorTheme.DARK

@Composable
private fun codeBackground(): Color = if (isDarkCodeTheme()) Color(0xFF11131A) else Color(0xFFF8F8F5)

@Composable
private fun codeForeground(): Color = if (isDarkCodeTheme()) Color(0xFFD8DEE9) else Color(0xFF262626)

private fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')

private data class AsmEditSyntaxColors(
    val foreground: Color,
    val comment: Color,
    val number: Color,
    val directive: Color,
    val string: Color,
    val definition: Color,
)

private fun asmEditableHighlight(text: String, colors: AsmEditSyntaxColors): AnnotatedString {
    val lines = text.split('\n')
    return buildAnnotatedString {
        lines.forEachIndexed { lineIndex, line ->
        val commentStart = asmCommentStart(line)
        val code = line.substring(0, commentStart)
        val matches = ASM_EDIT_TOKEN_REGEX.findAll(code).toList()
        val definition = ASM_EDIT_DEFINITION_REGEX.find(code)?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        val instruction = matches.firstOrNull { match ->
            match.value != definition && AsmInstructionReference.find(match.value.substringBefore('.')) != null
        }
        var cursor = 0
        matches.forEach { match ->
            append(code.substring(cursor, match.range.first))
            val token = match.value
            val style = when {
                token.startsWith('"') -> SpanStyle(color = colors.string)
                token == definition -> SpanStyle(color = colors.definition, fontWeight = FontWeight.SemiBold)
                match == instruction -> SpanStyle(color = colors.directive, fontWeight = FontWeight.SemiBold)
                token.startsWith('$') || token.startsWith('%') || token.firstOrNull()?.isDigit() == true ->
                    SpanStyle(color = colors.number)
                token.lowercase() in ASM_DIRECTIVES -> SpanStyle(color = colors.directive)
                else -> SpanStyle(color = colors.foreground)
            }
            pushStyle(style)
            append(token)
            pop()
            cursor = match.range.last + 1
        }
        append(code.substring(cursor))
        if (commentStart < line.length) {
            pushStyle(SpanStyle(color = colors.comment))
            append(line.substring(commentStart))
            pop()
        }
            if (lineIndex != lines.lastIndex) append('\n')
        }
    }
}

private fun asmCommentStart(line: String): Int {
    var quoted = false
    var escaped = false
    line.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
            character == ';' && !quoted -> return index
        }
    }
    return line.length
}

private val ASM_EDIT_TOKEN_REGEX =
    Regex("\"(?:\\\\.|[^\"])*\"|\\$[0-9A-Fa-f]{2}:[0-9A-Fa-f]{4}|\\$[0-9A-Fa-f]+|%[01]+|\\b[0-9]+\\b|\\.?[A-Za-z_][A-Za-z0-9_.]*")
private val ASM_EDIT_DEFINITION_REGEX =
    Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z0-9_]+):|^\\s*!([A-Za-z_][A-Za-z0-9_]*)\\s*=")

private val ASM_SCROLLBAR_SIZE = 10.dp
private val ASM_CONTROL_SHAPE = RoundedCornerShape(5.dp)
private const val ASM_EDIT_PAGE_LINES = 500
private val ASM_LINE_NUMBER_WIDTH = 60.dp
private val ASM_ADDRESS_GUTTER_WIDTH = 92.dp
private const val MONOSPACE_CHARACTER_WIDTH = 0.64f
private const val MAX_MEASURED_CODE_COLUMNS = 800
private const val ASM_HEX_COLUMNS = 76
private const val ASM_LIBRARY_USAGE_EXAMPLES = 8
private const val MOUSE_BACK_BUTTON = 4
private const val MOUSE_FORWARD_BUTTON = 5

private val ASM_DIRECTIVES = setOf(
    "org", "warnpc", "incsrc", "incbin", "lorom", "hirom", "math", "reset", "print", "assert",
    "if", "else", "elseif", "endif", "while", "endwhile", "macro", "endmacro", "namespace", "struct", "endstruct",
    "db", "dw", "dl", "dd", "fill", "fillbyte", "pad", "padbyte", "skip", "base", "table", "cleartable",
)
