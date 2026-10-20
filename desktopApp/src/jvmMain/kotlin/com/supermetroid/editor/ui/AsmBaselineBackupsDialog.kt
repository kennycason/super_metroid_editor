package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmBaselineBackup
import com.supermetroid.editor.asm.AsmBaselineBackupList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatAsmBackupSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Size unavailable"
    if (bytes < 1024) return "$bytes B"
    var size = bytes.toDouble()
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", size, units[unit])
}

@Composable
internal fun AsmBaselineBackupsDialog(
    listing: AsmBaselineBackupList?, busy: Boolean, error: String?, message: String?,
    onClose: () -> Unit, onRefresh: () -> Unit,
    onReveal: (AsmBaselineBackup) -> Unit, onDelete: (AsmBaselineBackup) -> Unit,
    deleting: Boolean = false,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var selected by remember(listing) { mutableStateOf<AsmBaselineBackup?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault()) }
    fun date(entry: AsmBaselineBackup) = if (entry.createdAtMillis > 0) dateFormat.format(Instant.ofEpochMilli(entry.createdAtMillis)) else "Date unavailable"
    AlertDialog(
        onDismissRequest = { if (!deleting) onClose() },
        modifier = Modifier.width(650.dp),
        title = { Text("Previous ASM versions", fontSize = fs.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Local ASM copies kept before baseline updates. These are not full-project restore points.", fontSize = fs.body)
                Text("Sizes include the companion build cache, when present. Your active source and custom modules are separate.", fontSize = fs.detail)
                listing?.notice?.let { Text(it, fontSize = fs.detail) }
                if (listing != null && listing.entries.isEmpty()) Text(
                    "No previous ASM versions yet. A copy is kept when a baseline update is applied.",
                    fontSize = fs.body, modifier = Modifier.testTag("asm-backups-empty"))
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listing?.entries.orEmpty(), key = { it.id }) { entry ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${entry.revision ?: "Unknown baseline"} · ${date(entry)}", fontSize = fs.body)
                                Text("${formatAsmBackupSize(entry.bytes)} · ${entry.id.take(8)}", fontSize = fs.detail)
                                entry.problem?.let { Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.error) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !busy, onClick = { onReveal(entry) }, modifier = Modifier.testTag("asm-backup-open-${entry.id}")) {
                                        Text("Open folder", fontSize = fs.detail)
                                    }
                                    TextButton(enabled = !busy && entry.problem == null, onClick = { selected = entry },
                                        modifier = Modifier.testTag("asm-backup-delete-${entry.id}")) {
                                        Text("Delete…", fontSize = fs.detail)
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !deleting, onClick = onClose,
            modifier = Modifier.testTag("asm-backups-close")) { Text("Close") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onRefresh) { Text("Refresh") } },
    )
    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text("Delete this ASM backup?", fontSize = fs.heading) },
            text = {
                Text("${entry.revision ?: "Unknown baseline"} · ${date(entry)} · ${entry.id.take(8)}\n\n" +
                    "Permanently removes this previous ASM copy and its companion build cache (${formatAsmBackupSize(entry.bytes)}). " +
                    "Your active project is not changed. This cannot be undone.", fontSize = fs.body)
            },
            confirmButton = {
                Button(enabled = !busy, onClick = { selected = null; onDelete(entry) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("asm-backup-delete-confirm")) { Text("Delete backup") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { selected = null }, modifier = Modifier.testTag("asm-backup-delete-cancel")) { Text("Keep backup") }
            },
        )
    }
}
