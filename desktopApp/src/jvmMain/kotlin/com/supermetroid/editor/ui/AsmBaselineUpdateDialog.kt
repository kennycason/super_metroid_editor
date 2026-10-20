package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmBaselineUpdatePlan

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
    val conflicts = remember(plan) { plan.files.flatMap { file -> file.merge.conflicts.mapIndexed { index, conflict -> Triple(file.fileId, index, conflict) } } }
    var details by remember(plan) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        modifier = Modifier.width(960.dp),
        title = { Text("Update ASM baseline", fontSize = fs.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${plan.previousRevision} → ${plan.nextRevision}", fontSize = fs.body, fontFamily = FontFamily.Monospace)
                val documented = plan.baselineChanges.count { it.documentationOnly }
                val sourceChanges = plan.baselineChanges.size - documented
                Text(buildList {
                    if (documented > 0) add("$documented files: comments/formatting only")
                    if (sourceChanges > 0) add("$sourceChanges files: code, data or symbol changes")
                    if (isEmpty()) add("No baseline source text changes")
                }.joinToString(" · "), fontSize = fs.body, modifier = Modifier.testTag("asm-baseline-summary"))
                Text("Your edits in ${plan.editedBankCount} banks and ${plan.moduleCount} custom modules are carried forward. Module order and on/off settings are retained.", fontSize = fs.detail)
                if (conflicts.isEmpty()) {
                    Text("No changes need review. Your edits merged without conflicts.", fontSize = fs.body)
                    TextButton(onClick = { details = !details }, modifier = Modifier.testTag("asm-baseline-details-toggle")) {
                        Text(if (details) "Hide update details" else "Show update details", fontSize = fs.detail)
                    }
                    if (details) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(plan.changedFiles) { file ->
                            val detail = when {
                                file.fileId.startsWith(com.supermetroid.editor.asm.ASM_PROJECT_MODULE_PREFIX) -> "Module symbol references updated"
                                !file.baselineChanged -> "Your edits retained; baseline unchanged"
                                file.documentationOnly -> "Comments/formatting only"
                                else -> "Code, data or symbols changed"
                            }
                            Text("${file.fileId} · $detail", fontFamily = FontFamily.Monospace, fontSize = fs.detail)
                        }
                    }
                } else {
                    val remaining = plan.conflictCount - choices.size
                    Text(if (remaining == 0) "Review complete. Ready for build validation." else
                        "$remaining changes need your review. Your current project has not been replaced.", fontSize = fs.body)
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(conflicts, key = { "${it.first}:${it.second}" }) { (file, index, conflict) ->
                            val key = file to index
                            var draft by remember(plan, key) { mutableStateOf(conflict.project) }
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$file · conflict ${index + 1}" + if (key in choices) " · resolved" else "", fontSize = fs.body)
                                    MergeExcerpt("Old baseline", conflict.base)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            MergeExcerpt("Your edits", conflict.project)
                                            TextButton(enabled = !busy, onClick = { draft = conflict.project; choices[key] = draft },
                                                modifier = Modifier.testTag("asm-baseline-keep-$file-$index")) { Text("Keep project") }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            MergeExcerpt("Updated baseline", conflict.updated)
                                            TextButton(enabled = !busy, onClick = { draft = conflict.updated; choices[key] = draft }) { Text("Use updated base") }
                                        }
                                    }
                                    OutlinedTextField(value = draft, onValueChange = { draft = it; choices.remove(key) }, enabled = !busy,
                                        label = { Text("Resolution (editable)") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.detail),
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), minLines = 2, maxLines = 7)
                                    TextButton(enabled = !busy, onClick = { choices[key] = draft }) { Text("Use this resolution") }
                                }
                            }
                        }
                    }
                }
                Text("The candidate is compiled and checked before installation; a previous ASM copy is kept. Build validation does not verify gameplay.", fontSize = fs.detail)
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress ?: "Validating merged source…", fontSize = fs.detail) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && choices.size == plan.conflictCount, modifier = Modifier.testTag("asm-baseline-apply"),
                onClick = { onApply(choices.entries.groupBy({ it.key.first }, { it.key.second to it.value }).mapValues { it.value.toMap() }) }) {
                Text("Validate & apply", fontSize = fs.body)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun MergeExcerpt(title: String, text: String) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(title, fontSize = fs.detail)
    SelectionContainer {
        Text(text.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = fs.detail,
            modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
    }
}
