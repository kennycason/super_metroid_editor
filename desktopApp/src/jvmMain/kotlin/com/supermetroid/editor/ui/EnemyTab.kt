package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser

// Shared sub-tab state (survives recomposition across sidebar/canvas)
private var enemySubTab = mutableStateOf(0)

@Composable
fun EnemyTabSidebar(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var subTab by enemySubTab
    val subTabs = listOf("Stats", "Drop Rates", "Vulnerabilities")

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        subTabs.forEachIndexed { idx, name ->
            Text(
                name, fontSize = 12.sp,
                color = if (subTab == idx) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (subTab == idx) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { subTab = idx }
                    .then(if (subTab == idx) Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(6.dp)
                    ) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun EnemyTabCanvas(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val subTab by enemySubTab
    val configType = when (subTab) { 0 -> "enemy_stats"; 1 -> "enemy_drops"; 2 -> "enemy_vuln"; else -> "enemy_stats" }
    val patch = remember(configType) { editorState.findOrCreateConfigPatch(configType) }

    when (subTab) {
        0 -> EnemyStatsEditor(patch, editorState, romParser, modifier)
        1 -> EnemyDropRateEditor(patch, editorState, romParser, modifier)
        2 -> EnemyVulnerabilityEditor(patch, editorState, romParser, modifier)
    }
}
