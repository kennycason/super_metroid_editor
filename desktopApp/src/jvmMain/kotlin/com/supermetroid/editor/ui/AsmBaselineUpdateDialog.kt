package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.asm.*

private enum class AsmReviewComparison { PROJECT, VERSION, RESULT }

@Composable
internal fun AsmBaselineUpdateDialog(
    plan: AsmBaselineUpdatePlan,
    busy: Boolean,
    progress: String?,
    error: String?,
    onCancel: () -> Unit,
    onApply: (Map<String, Map<Int, String>>) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val choices = remember(plan) { mutableStateMapOf<Pair<String, Int>, String>() }
    val reviewFiles = remember(plan) {
        plan.changedFiles.sortedWith(compareByDescending<AsmBaselineFileUpdate> { it.merge.conflicts.isNotEmpty() }
            .thenByDescending { it.projectEdited }.thenBy { it.fileId })
    }
    var selectedFileId by remember(plan) { mutableStateOf(reviewFiles.firstOrNull()?.fileId) }
    val selectedFile = reviewFiles.firstOrNull { it.fileId == selectedFileId } ?: reviewFiles.firstOrNull()
    val remaining = plan.conflictCount - choices.size

    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).widthIn(max = 1180.dp).heightIn(min = 540.dp, max = 840.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Update ASM Version", fontSize = fs.heading, fontWeight = FontWeight.SemiBold)
                        Text("${plan.previousRevision} → ${plan.nextRevision}", fontSize = fs.detail,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (remaining == 0) "Ready to validate" else "$remaining conflict${if (remaining == 1) "" else "s"} unresolved",
                        fontSize = fs.body,
                        color = if (remaining == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("asm-baseline-review-status"),
                    )
                }

                val documented = plan.baselineChanges.count { it.documentationOnly }
                val sourceChanges = plan.baselineChanges.size - documented
                Text(buildList {
                    if (documented > 0) add("$documented files: comments/formatting only")
                    if (sourceChanges > 0) add("$sourceChanges files: code, data or symbol changes")
                    if (isEmpty()) add("No ASM version source changes")
                    add("${plan.editedBankCount} project-source banks")
                    if (plan.moduleCount > 0) add("${plan.moduleCount} custom modules retained")
                }.joinToString(" · "), fontSize = fs.body, modifier = Modifier.testTag("asm-baseline-summary"))
                Text(
                    "Project source includes GUI-generated room/layout ASM, not only hand edits. Select a file and review one change at a time; " +
                        "the completed merge is installed as one validated update.",
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Divider()
                if (reviewFiles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No source text differs. Only the ASM version metadata will be validated and updated.", fontSize = fs.body)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Changed files · ${reviewFiles.size}", fontSize = fs.body, fontWeight = FontWeight.SemiBold)
                            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                items(reviewFiles, key = { it.fileId }) { file ->
                                    AsmReviewFileRow(file, file.fileId == selectedFile?.fileId, busy) { selectedFileId = file.fileId }
                                }
                            }
                        }
                        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        selectedFile?.let { file ->
                            AsmFileDiffReview(
                                file = file,
                                previousRevision = plan.previousRevision,
                                nextRevision = plan.nextRevision,
                                busy = busy,
                                choices = choices,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
                Divider()

                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress ?: "Validating merged source…", fontSize = fs.detail)
                } else {
                    Text(
                        "Before installation, the complete candidate is compiled and checked and the previous ASM version is backed up.",
                        fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel", fontSize = fs.body) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy && remaining == 0,
                        modifier = Modifier.testTag("asm-baseline-apply"),
                        onClick = {
                            onApply(choices.entries.groupBy({ it.key.first }, { it.key.second to it.value })
                                .mapValues { it.value.toMap() })
                        },
                    ) { Text("Validate & apply update", fontSize = fs.body) }
                }
            }
        }
    }
}

@Composable
private fun AsmReviewFileRow(file: AsmBaselineFileUpdate, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val conflicts = file.merge.conflicts.size
    val semantic = remember(file) { AsmSemanticChanges.baseline(file) }
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("asm-baseline-file-${file.fileId}"),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(file.fileId, fontFamily = FontFamily.Monospace, fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildList {
                    add(semantic.kind.label)
                    add(semantic.origin.label)
                    if (conflicts > 0) add("$conflicts conflict${if (conflicts == 1) "" else "s"}")
                    if (file.projectEdited) add("project source")
                    if (file.baselineChanged) add(if (file.documentationOnly) "comments-only update" else "version change")
                    if (isEmpty()) add("retained")
                }.joinToString(" · "),
                fontSize = fs.detail,
                color = if (conflicts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsmFileDiffReview(
    file: AsmBaselineFileUpdate,
    previousRevision: String,
    nextRevision: String,
    busy: Boolean,
    choices: MutableMap<Pair<String, Int>, String>,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val fileChoices = file.merge.conflicts.indices.mapNotNull { index -> choices[file.fileId to index]?.let { index to it } }.toMap()
    val resolved = file.merge.resolved(fileChoices)
    val defaultComparison = remember(file) {
        if (file.projectEdited && file.projectText != file.baseText) AsmReviewComparison.PROJECT else AsmReviewComparison.VERSION
    }
    var comparison by remember(file) { mutableStateOf(defaultComparison) }
    LaunchedEffect(file, resolved != null) {
        if (comparison == AsmReviewComparison.RESULT && resolved == null) comparison = defaultComparison
    }
    val before: String
    val after: String
    when (comparison) {
        AsmReviewComparison.PROJECT -> { before = file.baseText; after = file.projectText }
        AsmReviewComparison.VERSION -> { before = file.baseText; after = file.updatedText }
        AsmReviewComparison.RESULT -> { before = file.projectText; after = resolved ?: file.projectText }
    }
    val diff = remember(file, comparison, fileChoices) { AsmThreeWayMerge.compare(before, after) }
    val hunks = remember(diff) { diff.hunks }
    var hunkIndex by remember(file, comparison, fileChoices) { mutableStateOf(0) }
    val selectedHunk = hunks.getOrNull(hunkIndex.coerceIn(0, (hunks.size - 1).coerceAtLeast(0)))
    var conflictIndex by remember(file) { mutableStateOf(0) }
    val selectedConflict = file.merge.conflicts.getOrNull(
        conflictIndex.coerceIn(0, (file.merge.conflicts.size - 1).coerceAtLeast(0)),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(file.fileId, fontFamily = FontFamily.Monospace, fontSize = fs.body, fontWeight = FontWeight.SemiBold)
        val semantic = remember(file) { AsmSemanticChanges.baseline(file) }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text("${semantic.kind.label} · ${semantic.origin.label}", fontSize = fs.detail,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(semantic.detail, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(semantic.originDetail, fontSize = fs.statusBar, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AsmReviewComparison.entries.forEach { option ->
                FilterChip(
                    selected = comparison == option,
                    onClick = { comparison = option },
                    enabled = !busy && (option != AsmReviewComparison.RESULT || resolved != null),
                    label = { Text(comparisonLabel(option, previousRevision, nextRevision), fontSize = fs.detail) },
                    modifier = Modifier.testTag("asm-baseline-view-${option.name.lowercase()}"),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(comparisonDescription(comparison, previousRevision, nextRevision, file.documentationOnly),
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text("+${diff.addedLines} / -${diff.removedLines}", fontFamily = FontFamily.Monospace, fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("asm-baseline-diff-stats"))
        }
        if (hunks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { hunkIndex-- }, enabled = !busy && hunkIndex > 0,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.testTag("asm-baseline-previous-change"),
                ) { Text("Previous", fontSize = fs.detail) }
                Text(
                    "Change ${hunkIndex + 1} of ${hunks.size} · ${diffHunkRange(selectedHunk)}",
                    fontSize = fs.detail,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag("asm-baseline-change-position"),
                )
                OutlinedButton(
                    onClick = { hunkIndex++ }, enabled = !busy && hunkIndex < hunks.lastIndex,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.testTag("asm-baseline-next-change"),
                ) { Text("Next", fontSize = fs.detail) }
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("asm-baseline-diff"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (selectedConflict != null) {
                item("conflict-heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Resolve conflict ${conflictIndex + 1} of ${file.merge.conflicts.size}",
                            fontSize = fs.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { conflictIndex-- }, enabled = !busy && conflictIndex > 0) {
                            Text("Previous", fontSize = fs.detail)
                        }
                        TextButton(onClick = { conflictIndex++ }, enabled = !busy && conflictIndex < file.merge.conflicts.lastIndex) {
                            Text("Next", fontSize = fs.detail)
                        }
                    }
                }
                item("conflict-$conflictIndex") {
                    AsmConflictReview(file.fileId, conflictIndex, selectedConflict, choices, busy) {
                        if (conflictIndex < file.merge.conflicts.lastIndex) conflictIndex++
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item("diff-heading") {
                    Divider(Modifier.padding(vertical = 5.dp))
                    Text("Selected change", fontSize = fs.body, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            if (diff.approximateAlignment) item("approximate") {
                Text("No stable matching lines were available inside this region, so it is shown as one conservative replacement.",
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            }
            if (selectedHunk == null) item("empty") {
                Text("No differences in this view.", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp))
            } else {
                item("gutter-heading-$hunkIndex") { AsmDiffGutterHeader() }
                items(selectedHunk.lines) { line -> AsmDiffLineRow(line) }
            }
        }
    }
}

@Composable
private fun AsmConflictReview(
    fileId: String,
    index: Int,
    conflict: AsmMergeConflict,
    choices: MutableMap<Pair<String, Int>, String>,
    busy: Boolean,
    onResolved: () -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val key = fileId to index
    val large = remember(fileId, index) {
        listOf(conflict.base, conflict.project, conflict.updated).any { it.length > MAX_INLINE_CONFLICT_CHARS }
    }
    var draft by remember(fileId, index) { mutableStateOf(if (large) "" else conflict.project) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Conflict ${index + 1}${if (key in choices) " · resolved" else " · choose a result"}", fontSize = fs.body)
            MergeExcerpt("Current version", conflict.base)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    MergeExcerpt("Your edits", conflict.project)
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            if (!large) draft = conflict.project
                            choices[key] = conflict.project
                            onResolved()
                        },
                        modifier = Modifier.testTag("asm-baseline-keep-$fileId-$index"),
                    ) { Text("Keep project", fontSize = fs.detail) }
                }
                Column(Modifier.weight(1f)) {
                    MergeExcerpt("Updated version", conflict.updated)
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            if (!large) draft = conflict.updated
                            choices[key] = conflict.updated
                            onResolved()
                        },
                        modifier = Modifier.testTag("asm-baseline-update-$fileId-$index"),
                    ) { Text("Use updated version", fontSize = fs.detail) }
                }
            }
            if (large) {
                Text(
                    "This region has no stable line anchors for a safe automatic split. Choose a complete side, or cancel and " +
                        "edit the project source for a custom merge. Ordinary large generated insertions are reviewed as separate changes.",
                    fontSize = fs.detail,
                )
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; choices.remove(key) },
                    enabled = !busy,
                    label = { Text("Custom resolution") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.detail),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
                    minLines = 2,
                    maxLines = 6,
                )
                TextButton(enabled = !busy, onClick = { choices[key] = draft; onResolved() }) {
                    Text("Use custom resolution", fontSize = fs.detail)
                }
            }
        }
    }
}

@Composable
internal fun AsmDiffGutterHeader() {
    val fs = LocalEditorTheme.current.fontSize.value
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("±", modifier = Modifier.width(DIFF_MARKER_WIDTH), textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Old", modifier = Modifier.width(DIFF_LINE_NUMBER_WIDTH), textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("New", modifier = Modifier.width(DIFF_LINE_NUMBER_WIDTH), textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text("ASM source", fontFamily = FontFamily.Monospace, fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AsmDiffLineRow(line: AsmDiffLine) {
    val fs = LocalEditorTheme.current.fontSize.value
    val background = when (line.kind) {
        AsmDiffLineKind.ADDED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        AsmDiffLineKind.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
        AsmDiffLineKind.OMITTED -> MaterialTheme.colorScheme.surfaceVariant
        AsmDiffLineKind.CONTEXT -> MaterialTheme.colorScheme.surface
    }
    val marker = when (line.kind) {
        AsmDiffLineKind.ADDED -> "+"
        AsmDiffLineKind.REMOVED -> "-"
        AsmDiffLineKind.OMITTED -> "..."
        AsmDiffLineKind.CONTEXT -> " "
    }
    Surface(color = background, shape = MaterialTheme.shapes.extraSmall) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 21.dp).padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(marker, modifier = Modifier.width(DIFF_MARKER_WIDTH), textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = fs.detail)
            Text(line.oldLine?.toString().orEmpty(), modifier = Modifier.width(DIFF_LINE_NUMBER_WIDTH),
                textAlign = TextAlign.End, fontFamily = FontFamily.Monospace,
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(line.newLine?.toString().orEmpty(), modifier = Modifier.width(DIFF_LINE_NUMBER_WIDTH),
                textAlign = TextAlign.End, fontFamily = FontFamily.Monospace,
                fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    if (line.kind == AsmDiffLineKind.OMITTED) AnnotatedString(displayDiffLine(line.text))
                    else asmAnnotatedLine(displayDiffLine(line.text)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fs.detail,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun comparisonLabel(comparison: AsmReviewComparison, previous: String, next: String): String = when (comparison) {
    AsmReviewComparison.PROJECT -> "Project vs ${shortAsmRevision(previous)}"
    AsmReviewComparison.VERSION -> "${shortAsmRevision(next)} vs ${shortAsmRevision(previous)}"
    AsmReviewComparison.RESULT -> "Merged result"
}

private fun comparisonDescription(
    comparison: AsmReviewComparison,
    previous: String,
    next: String,
    documentationOnly: Boolean,
): String = when (comparison) {
    AsmReviewComparison.PROJECT -> "What project source changed from ${shortAsmRevision(previous)}"
    AsmReviewComparison.VERSION -> "What ${shortAsmRevision(next)} changed from ${shortAsmRevision(previous)}" +
        if (documentationOnly) " · comments/formatting only" else ""
    AsmReviewComparison.RESULT -> "What the update will change in your saved project source"
}

private fun shortAsmRevision(revision: String): String = revision.removePrefix("super-metroid-ntsc-").let {
    if (it == revision) revision else "v$it"
}

internal fun diffHunkRange(hunk: AsmDiffHunk?): String {
    if (hunk == null) return ""
    fun range(start: Int?, end: Int?): String = when {
        start == null -> "—"
        start == end -> start.toString()
        else -> "$start–$end"
    }
    return "old ${range(hunk.oldStart, hunk.oldEnd)} → new ${range(hunk.newStart, hunk.newEnd)}"
}

private fun displayDiffLine(text: String): String = when {
    text.isEmpty() -> " "
    text.length <= MAX_DIFF_LINE_CHARS -> text
    else -> text.take(MAX_DIFF_LINE_CHARS) + "…"
}

@Composable
private fun MergeExcerpt(title: String, text: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(title, fontSize = fs.detail)
    val excerpt = mergeExcerpt(text)
    val lines = excerpt.split('\n').map { asmAnnotatedLine(it) }
    val highlighted = remember(lines) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                append(line)
                if (index != lines.lastIndex) append('\n')
            }
        }
    }
    SelectionContainer {
        Text(
            highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = fs.detail,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_MERGE_EXCERPT_CHARS = 4_000
private const val MAX_INLINE_CONFLICT_CHARS = 24_000
private const val MAX_DIFF_LINE_CHARS = 2_000
private val DIFF_MARKER_WIDTH = 26.dp
private val DIFF_LINE_NUMBER_WIDTH = 52.dp

private fun mergeExcerpt(text: String): String = when {
    text.isEmpty() -> "(empty)"
    text.length <= MAX_MERGE_EXCERPT_CHARS -> text
    else -> text.take(MAX_MERGE_EXCERPT_CHARS) +
        "\n… ${text.length - MAX_MERGE_EXCERPT_CHARS} more characters in this version …"
}
