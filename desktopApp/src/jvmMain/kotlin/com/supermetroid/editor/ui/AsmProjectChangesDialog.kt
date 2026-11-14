package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.asm.*

private enum class AsmProjectChangeComparison {
    BASELINE_TO_SAVED,
    SAVED_TO_UNSAVED,
    BASELINE_TO_CURRENT,
}

/** Semantic review of source already owned by this project. Changes are already
 * active; Restore operates only on complete, guarded ownership transactions. */
@Composable
internal fun AsmProjectChangesDialog(
    changes: List<AsmProjectSourceChange>,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit,
    assetChanges: List<AsmProjectAssetChange> = emptyList(),
    restoring: Boolean = false,
    error: String? = null,
    onOpenVisualEditor: (AsmEditorTarget) -> Unit = {},
    onRestore: (AsmProjectRestoreRequest) -> Unit = {},
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var selectedFileId by remember(changes) { mutableStateOf(changes.firstOrNull()?.fileId) }
    var selectedAssetPath by remember(assetChanges) {
        mutableStateOf(if (changes.isEmpty()) assetChanges.firstOrNull()?.assetPath else null)
    }
    val selected = if (selectedAssetPath == null) changes.firstOrNull { it.fileId == selectedFileId } else null
    val selectedAsset = assetChanges.firstOrNull { it.assetPath == selectedAssetPath }
    val savedOverrides = changes.count(AsmProjectSourceChange::savedBankOverride)
    val modules = changes.count(AsmProjectSourceChange::projectModule)
    val unsaved = changes.count(AsmProjectSourceChange::unsavedBuffer)
    val selectedRestore = selected?.restoreRequest ?: selectedAsset?.restoreRequest
    val selectedRestoreBlocked = selected?.restoreBlockedReason ?: selectedAsset?.restoreBlockedReason
    var confirmRestore by remember(selectedRestore) { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f)
                .widthIn(max = 1180.dp).heightIn(min = 520.dp, max = 820.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Project ASM Changes", fontSize = fs.heading, fontWeight = FontWeight.SemiBold)
                        Text(
                            buildList {
                                if (savedOverrides > 0) add("$savedOverrides saved bank override${if (savedOverrides == 1) "" else "s"}")
                                if (modules > 0) add("$modules project module${if (modules == 1) "" else "s"}")
                                if (unsaved > 0) add("$unsaved unsaved buffer${if (unsaved == 1) "" else "s"}")
                                if (assetChanges.isNotEmpty()) add("${assetChanges.size} data asset${if (assetChanges.size == 1) "" else "s"}")
                                if (isEmpty()) add("No project ASM changes")
                            }.joinToString(" · "),
                            fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("asm-project-changes-summary"),
                        )
                    }
                }
                Text(
                    "Source and data overrides may come from direct ASM work or reciprocal Visual Editor changes.",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Divider()

                if (changes.isEmpty() && assetChanges.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("This project matches its immutable ASM baseline.", fontSize = fs.body)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Project files · ${changes.size + assetChanges.size}", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
                            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                items(changes, key = AsmProjectSourceChange::fileId) { change ->
                                    AsmProjectChangeFileRow(
                                        change = change,
                                        selected = change.fileId == selected?.fileId,
                                        onClick = {
                                            selectedFileId = change.fileId
                                            selectedAssetPath = null
                                        },
                                    )
                                }
                                items(assetChanges, key = AsmProjectAssetChange::assetPath) { change ->
                                    AsmProjectAssetChangeRow(
                                        change = change,
                                        selected = change.assetPath == selectedAssetPath,
                                        onClick = {
                                            selectedAssetPath = change.assetPath
                                            selectedFileId = null
                                        },
                                    )
                                }
                            }
                        }
                        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        selected?.let { change ->
                            AsmProjectSourceDiff(change, onOpenVisualEditor, Modifier.weight(1f).fillMaxHeight())
                        }
                        selectedAsset?.let { change ->
                            AsmProjectAssetSummary(change, onOpenVisualEditor, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }

                Divider()
                selectedRestoreBlocked?.let {
                    Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("asm-project-changes-restore-blocked"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", fontSize = fs.body) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !restoring && selectedRestore != null,
                        onClick = { confirmRestore = true },
                        modifier = Modifier.testTag("asm-project-changes-restore"),
                    ) { Text(if (restoring) "Restoring…" else "Restore selected", fontSize = fs.body) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = selected != null,
                        onClick = { selected?.let { onOpenSource(it.fileId) } },
                        modifier = Modifier.testTag("asm-project-changes-open-source"),
                    ) { Text("Open source", fontSize = fs.body) }
                }
            }
        }
    }
    if (confirmRestore && selectedRestore != null) {
        AlertDialog(
            onDismissRequest = { if (!restoring) confirmRestore = false },
            title = { Text("Restore ${selectedRestore.label}?") },
            text = { Text(restoreTransactionDescription(selectedRestore)) },
            confirmButton = {
                Button(
                    enabled = !restoring,
                    onClick = { confirmRestore = false; onRestore(selectedRestore) },
                    modifier = Modifier.testTag("asm-project-changes-restore-confirm"),
                ) { Text("Restore transaction") }
            },
            dismissButton = {
                TextButton(enabled = !restoring, onClick = { confirmRestore = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AsmProjectAssetChangeRow(
    change: AsmProjectAssetChange,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("asm-project-change-asset-${change.assetPath}"),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                change.assetPath,
                fontFamily = FontFamily.Monospace,
                fontSize = fs.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${change.semantic.kind.label} · ${change.semantic.origin.label}",
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AsmProjectAssetSummary(
    change: AsmProjectAssetChange,
    onOpenVisualEditor: (AsmEditorTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(change.assetPath, fontFamily = FontFamily.Monospace, fontSize = fs.body, fontWeight = FontWeight.SemiBold)
        AsmSemanticChangeHeader(change.semantic, onOpenVisualEditor)
        AsmProjectAllocations(change.allocations)
        Text(
            if (change.managed) {
                "Managed ASM asset · ${change.size} bytes · native envelope ${change.originalCapacity} bytes"
            } else {
                "Binary ASM data · ${change.size} bytes · ${change.changedBytes} changed"
            },
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (change.firstChangedOffsets.isNotEmpty()) {
            Text("First changed offsets", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
            Text(
                change.firstChangedOffsets.joinToString("  ") { "+\$${it.toString(16).uppercase().padStart(4, '0')}" },
                fontFamily = FontFamily.Monospace,
                fontSize = fs.detail,
            )
        }
        Text(
            "Restore scope · " + if (change.managed) {
                "managed manifest + authored asset + source marker${if (change.inlineSource) "" else " + native asset override"}"
            } else {
                "complete binary asset"
            },
            fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (change.managed) {
            Text(
                "${change.sourceLabel} moved from " +
                    (if (change.inlineSource) "its fixed inline source" else change.originalAssetPath) +
                    " because its encoded data no longer " +
                    "fit the native envelope. " + (change.compiledAddress?.let {
                        "Current build: ${com.supermetroid.editor.asm.formatSnesAddress(it)}."
                    } ?: "Its address will be chosen and validated by the next build."),
                fontFamily = FontFamily.Monospace,
                fontSize = fs.detail,
            )
        }
        Text(
            if (change.managed) {
                "This authored project asset is consumed by generated checked placement source. " +
                    "Restore it from its Visual Editor to return to the native declaration."
            } else {
                "This portable data override is consumed directly by the project's incbin source. " +
                    "Open its visual editor to inspect or restore it."
            },
            fontSize = fs.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AsmProjectChangeFileRow(
    change: AsmProjectSourceChange,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("asm-project-change-file-${change.fileId}"),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                change.fileId,
                fontFamily = FontFamily.Monospace,
                fontSize = fs.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildList {
                    add(change.semantic.kind.label)
                    add(change.semantic.origin.label)
                    if (change.savedBankOverride) add("Saved bank override")
                    if (change.unsavedBuffer) add("Unsaved buffer")
                    if (change.projectModule && !change.enabled) add("Disabled module")
                }.joinToString(" · "),
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsmProjectSourceDiff(
    change: AsmProjectSourceChange,
    onOpenVisualEditor: (AsmEditorTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val defaultComparison = remember(change) {
        if (change.unsavedBuffer) AsmProjectChangeComparison.SAVED_TO_UNSAVED
        else AsmProjectChangeComparison.BASELINE_TO_SAVED
    }
    var comparison by remember(change) { mutableStateOf(defaultComparison) }
    val before: String
    val after: String
    when (comparison) {
        AsmProjectChangeComparison.BASELINE_TO_SAVED -> {
            before = change.baselineText
            after = change.savedText
        }
        AsmProjectChangeComparison.SAVED_TO_UNSAVED -> {
            before = change.savedText
            after = change.workingText
        }
        AsmProjectChangeComparison.BASELINE_TO_CURRENT -> {
            before = change.baselineText
            after = change.workingText
        }
    }
    val diff = remember(change, comparison) { AsmThreeWayMerge.compare(before, after) }
    val hunks = remember(diff) { diff.hunks }
    var hunkIndex by remember(change, comparison) { mutableStateOf(0) }
    val selectedHunk = hunks.getOrNull(hunkIndex.coerceIn(0, (hunks.size - 1).coerceAtLeast(0)))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(change.fileId, fontFamily = FontFamily.Monospace, fontSize = fs.body, fontWeight = FontWeight.SemiBold)
        AsmSemanticChangeHeader(change.semantic, onOpenVisualEditor)
        AsmProjectAllocations(change.allocations)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AsmProjectChangeComparison.entries.forEach { option ->
                FilterChip(
                    selected = comparison == option,
                    onClick = { comparison = option },
                    enabled = option != AsmProjectChangeComparison.SAVED_TO_UNSAVED || change.unsavedBuffer,
                    label = { Text(projectComparisonLabel(option), fontSize = fs.detail) },
                    modifier = Modifier.testTag("asm-project-change-view-${option.name.lowercase()}"),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                projectComparisonDescription(change, comparison),
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "+${diff.addedLines} / -${diff.removedLines}",
                fontFamily = FontFamily.Monospace,
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("asm-project-change-diff-stats"),
            )
        }
        if (hunks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { hunkIndex-- },
                    enabled = hunkIndex > 0,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.testTag("asm-project-change-previous"),
                ) { Text("Previous", fontSize = fs.detail) }
                Text(
                    "Change ${hunkIndex + 1} of ${hunks.size} · ${diffHunkRange(selectedHunk)}",
                    fontSize = fs.detail,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag("asm-project-change-position"),
                )
                OutlinedButton(
                    onClick = { hunkIndex++ },
                    enabled = hunkIndex < hunks.lastIndex,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.testTag("asm-project-change-next"),
                ) { Text("Next", fontSize = fs.detail) }
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("asm-project-change-diff"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (diff.approximateAlignment) item("approximate") {
                Text(
                    "No stable matching lines were available inside this region, so it is shown as one conservative replacement.",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
            if (selectedHunk == null) item("empty") {
                Text(
                    "No differences in this view.",
                    fontSize = fs.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                item("gutter-heading-$hunkIndex") { AsmDiffGutterHeader() }
                items(selectedHunk.lines) { line -> AsmDiffLineRow(line) }
            }
        }
    }
}

@Composable
private fun AsmSemanticChangeHeader(
    semantic: AsmSemanticChangeSummary,
    onOpenVisualEditor: (AsmEditorTarget) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${semantic.kind.label} · ${semantic.origin.label}", fontSize = fs.detail,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(semantic.detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(semantic.originDetail, fontSize = fs.statusBar, color = MaterialTheme.colorScheme.onSurfaceVariant)
            semantic.editorLinks.forEachIndexed { index, link ->
                TextButton(
                    onClick = { onOpenVisualEditor(link.target) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("asm-project-changes-visual-$index"),
                ) { Text("${link.title} · ${link.detail}", fontSize = fs.detail) }
            }
        }
    }
}

@Composable
private fun AsmProjectAllocations(allocations: List<AsmProjectAllocationSummary>) {
    if (allocations.isEmpty()) return
    val fs = LocalEditorTheme.current.fontSize.value
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Allocation${if (allocations.size == 1) "" else "s"}", fontSize = fs.detail,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
            allocations.forEach { allocation ->
                Text(
                    "${allocation.label} · ${allocation.size} B at ${formatSnesAddress(allocation.snesAddress)} · ${allocation.kind}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = fs.detail,
                )
                allocation.constraint?.let {
                    Text(it, fontSize = fs.statusBar, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun restoreTransactionDescription(request: AsmProjectRestoreRequest): String = when (request) {
    is AsmProjectRestoreRequest.SourceFile ->
        "Restore this complete source file to the immutable project baseline. A newer editor draft or disk edit will cancel the operation."
    is AsmProjectRestoreRequest.ProjectModule ->
        "Delete this complete project module and update its build-order metadata. A newer editor draft or disk edit will cancel the operation."
    is AsmProjectRestoreRequest.DirectAsset ->
        "Restore this complete fixed-envelope data asset to the immutable project baseline. A newer asset edit will cancel the operation."
    is AsmProjectRestoreRequest.ManagedAsset ->
        "Atomically remove this managed allocation, authored asset and manifest record, restore its source declaration, and restore the native asset when applicable. A newer source or asset edit will cancel the operation."
}

private fun projectComparisonLabel(comparison: AsmProjectChangeComparison): String = when (comparison) {
    AsmProjectChangeComparison.BASELINE_TO_SAVED -> "Baseline → saved"
    AsmProjectChangeComparison.SAVED_TO_UNSAVED -> "Saved → unsaved"
    AsmProjectChangeComparison.BASELINE_TO_CURRENT -> "Baseline → current"
}

private fun projectComparisonDescription(
    change: AsmProjectSourceChange,
    comparison: AsmProjectChangeComparison,
): String = when (comparison) {
    AsmProjectChangeComparison.BASELINE_TO_SAVED -> if (change.projectModule) {
        "The saved project module as a whole-file addition"
    } else {
        "What the saved project bank changed from its immutable baseline"
    }
    AsmProjectChangeComparison.SAVED_TO_UNSAVED -> "What is currently open but not saved"
    AsmProjectChangeComparison.BASELINE_TO_CURRENT -> if (change.projectModule) {
        "The complete current project module"
    } else {
        "The complete current result compared with the immutable baseline"
    }
}
