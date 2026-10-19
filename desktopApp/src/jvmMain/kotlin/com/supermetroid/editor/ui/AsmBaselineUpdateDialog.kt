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
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        modifier = Modifier.width(960.dp),
        title = { Text("Update project ASM baseline", fontSize = fs.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${plan.previousRevision} → ${plan.nextRevision}", fontSize = fs.body, fontFamily = FontFamily.Monospace)
                Text("${plan.changedFiles.size} affected files · ${plan.conflictCount - choices.size} unresolved conflicts", fontSize = fs.body)
                Text("Non-overlapping edits merge automatically. Modules, order and enablement are preserved. The current project stays active until the resolved candidate builds; a backup is kept before installation.", fontSize = fs.detail)
                if (conflicts.isEmpty()) {
                    Text("No textual conflicts. The merged candidate still needs compiler validation.", fontSize = fs.body)
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(plan.changedFiles) { file -> Text(file.fileId, fontFamily = FontFamily.Monospace, fontSize = fs.detail) }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                            TextButton(enabled = !busy, onClick = { draft = conflict.project; choices[key] = draft }) { Text("Keep project") }
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
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress ?: "Validating merged source…", fontSize = fs.detail) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && choices.size == plan.conflictCount,
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
