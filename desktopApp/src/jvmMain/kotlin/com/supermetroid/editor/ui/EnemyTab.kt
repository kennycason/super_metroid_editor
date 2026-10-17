package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.asm.AsmEnemyTuningSection

// Shared sub-tab state (survives recomposition across sidebar/canvas)
private var enemySubTab = mutableStateOf(0)
private val tuningSelection = mutableStateOf(0 to 0)
internal fun selectEnemyTuning(speciesId: Int, section: AsmEnemyTuningSection) {
    enemySubTab.value = when (section) {
        AsmEnemyTuningSection.STATS -> 0
        AsmEnemyTuningSection.DROPS -> 1
        AsmEnemyTuningSection.VULNERABILITIES -> 2
    }
    tuningSelection.value = speciesId to (tuningSelection.value.second + 1)
}
internal fun selectedEnemyTuningSpecies(): Int = tuningSelection.value.first

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun enemyTuningFocus(speciesId: Int, highlighted: Boolean? = null): Modifier {
    val selection = tuningSelection.value
    val requester = remember(speciesId) { BringIntoViewRequester() }
    LaunchedEffect(selection) { if (selection.first == speciesId) requester.bringIntoView() }
    return Modifier.bringIntoViewRequester(requester).then(if (highlighted ?: (selection.first == speciesId))
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier)
}

@Composable
internal fun EnemyTabSidebar(
    editorState: EditorState,
    modifier: Modifier = Modifier,
    onNavigateToAsm: ((AsmEnemyTuningSection) -> Unit)? = null,
) {
    var subTab by enemySubTab
    val subTabs = listOf("Stats" to AsmEnemyTuningSection.STATS,
        "Drop Rates" to AsmEnemyTuningSection.DROPS,
        "Vulnerabilities" to AsmEnemyTuningSection.VULNERABILITIES)

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        subTabs.forEachIndexed { idx, (name, section) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { subTab = idx }
                    .then(if (subTab == idx) Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(6.dp)
                    ) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    name, fontSize = LocalEditorTheme.current.fontSize.value.body,
                    color = if (subTab == idx) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (subTab == idx) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (editorState.asmNavigationEnabled && onNavigateToAsm != null) Text(
                    "ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClickLabel = "Open $name ASM") { onNavigateToAsm(section) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun EnemyTabCanvas(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: ((Int) -> Unit)? = null,
) {
    val subTab by enemySubTab
    val configType = when (subTab) { 0 -> "enemy_stats"; 1 -> "enemy_drops"; 2 -> "enemy_vuln"; else -> "enemy_stats" }
    val patch = remember(editorState.project.projectId, configType) { editorState.findOrCreateConfigPatch(configType) }

    when (subTab) {
        0 -> EnemyStatsEditor(patch, editorState, romParser, modifier, onNavigateToAsm)
        1 -> EnemyDropRateEditor(patch, editorState, romParser, modifier, onNavigateToAsm)
        2 -> EnemyVulnerabilityEditor(patch, editorState, romParser, modifier, onNavigateToAsm)
    }
}
