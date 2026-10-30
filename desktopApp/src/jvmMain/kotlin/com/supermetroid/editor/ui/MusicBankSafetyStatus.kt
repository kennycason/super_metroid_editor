package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class BankCheck(val report: MusicBankTransitions.Report? = null, val problem: String? = null)

/** Background, debounced preflight; never edits the project or runs a native audio render. */
@Composable
internal fun MusicBankExportCheck(parser: RomParser, project: SmEditProject, editVersion: Int) {
    if (project.musicEdits.isEmpty() && project.musicInstrumentBanks.isEmpty()) return
    val input = remember(parser, project, editVersion) { MusicBankTransitions.snapshot(project) }
    var result by remember(parser, project, editVersion) { mutableStateOf<BankCheck?>(null) }
    LaunchedEffect(parser, project, editVersion) {
        delay(150)
        result = withContext(Dispatchers.Default) {
            try {
                BankCheck(report = MusicBankTransitions.inspect(parser, input))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BankCheck(problem = error.message ?: "Cannot validate this sound bank")
            }
        }
    }
    MusicBankSafetyStatus(result?.report, result?.problem)
}

@Composable
internal fun MusicBankSafetyStatus(report: MusicBankTransitions.Report?, problem: String? = null) {
    val fs = LocalEditorTheme.current.fontSize.value
    val checking = report == null && problem == null
    val blocked = problem != null || report?.canExport == false
    var expanded by remember { mutableStateOf(false) }
    val color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        OutlinedButton(shape = SoundControlShape, onClick = { expanded = true }, enabled = !checking,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.testTag("music-bank-check")) {
            Text(when {
                checking -> "Checking bank loads…"
                blocked -> "ROM export blocked · details ▾"
                else -> "Bank-load check passed ▾"
            }, fontSize = fs.detail, color = color)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 480.dp).heightIn(max = 440.dp).testTag("music-bank-check-details")) {
            Column(Modifier.padding(12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ROM export · bank loads", fontSize = fs.body, fontWeight = FontWeight.Medium)
                when {
                    problem != null -> Text(problem, fontSize = fs.detail, color = MaterialTheme.colorScheme.error)
                    report?.canExport == true -> {
                        Text("${report.checkedTransitions} bank transitions checked", fontSize = fs.body)
                        Text("No unrestored shared resources found.", fontSize = fs.detail)
                        if (report.instrumentRestorations.isNotEmpty()) {
                            val count = report.instrumentRestorations.size
                            Text("$count ${if (count == 1) "bank" else "banks"} will reload instrument settings on export.", fontSize = fs.detail)
                        }
                        if (report.sampleDirectoryRestorations.isNotEmpty()) Text(
                            "Project sample directory entries will be reloaded between banks.", fontSize = fs.detail)
                    }
                    report != null -> {
                        Text("Shared resources are not reloaded:", fontSize = fs.detail)
                        val groups = report.issues.groupBy {
                            val slot = when (it.resource) {
                                MusicBankTransitions.Resource.COMMON_INSTRUMENT, MusicBankTransitions.Resource.INSTRUMENT -> (it.start - 0x6C00) / 6
                                MusicBankTransitions.Resource.DIRECTORY -> (it.start - 0x6D00) / 4
                                else -> -1
                            }
                            Triple(it.editedSongSet, it.resource, slot)
                        }
                        for ((key, issues) in groups) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val slot = if (key.third >= 0) " #${key.third}" else ""
                                Text("${key.second.label}$slot", fontSize = fs.body, fontWeight = FontWeight.Medium)
                                val affected = issues.map { if (it.sourceSongSet == key.first) it.targetSongSet else it.sourceSongSet }.distinct()
                                val bankName = SpcData.KNOWN_TRACKS.firstOrNull { it.songSet == key.first }?.name
                                    ?: "Bank ${MusicInstrumentBanks.key(key.first)}"
                                Text("$bankName · ${affected.size} affected ${if (affected.size == 1) "bank" else "banks"}", fontSize = fs.detail)
                                Text("SPC \$${issues.first().start.toString(16).uppercase().padStart(4, '0')} · not reloaded",
                                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("Reset the shared sound or replace the flagged import.", fontSize = fs.detail)
                        Text("Audition and SPC/N-SPC export still work.", fontSize = fs.detail)
                    }
                }
                Text("Resource check only · not a ROM-space or effect-usage guarantee", fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(shape = SoundControlShape, onClick = { expanded = false }, modifier = Modifier.testTag("music-bank-check-close")) {
                Text("Close", fontSize = fs.body)
            }
        }
    }
}
