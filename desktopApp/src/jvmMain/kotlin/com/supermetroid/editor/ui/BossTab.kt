package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser

private var bossSubTab = mutableStateOf(0)

@Composable
fun BossTabSidebar(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var subTab by bossSubTab
    val subTabs = listOf("Boss Stats", "Defeated Flags", "Phantoon", "Kraid")

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
fun BossTabCanvas(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val subTab by bossSubTab

    when (subTab) {
        0 -> {
            val patch = editorState.findOrCreateConfigPatch("boss_stats")
            BossStatsEditor(patch, editorState, romParser, modifier)
        }
        1 -> {
            val patch = editorState.findOrCreateConfigPatch("boss_defeated")
            BossDefeatedEditor(patch, editorState, modifier)
        }
        2 -> {
            val patch = editorState.findOrCreateConfigPatch("phantoon")
            PhantoonEditor(patch, editorState, romParser, modifier)
        }
        3 -> {
            val patch = editorState.findOrCreateConfigPatch(KRAID_CONFIG_TYPE)
            KraidEditor(patch, editorState, romParser, modifier)
        }
    }
}
