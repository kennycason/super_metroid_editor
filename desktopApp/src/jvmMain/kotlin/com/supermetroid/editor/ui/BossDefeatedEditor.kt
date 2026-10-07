package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch

@Composable
fun BossDefeatedEditor(
    patch: SmPatch,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val stored = patch.configData ?: mutableMapOf()

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Boss Defeated Flags", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Mark bosses as already defeated. Rooms load in their post-boss state. " +
            "All four main bosses (Kraid, Phantoon, Ridley, Draygon) must be defeated to unlock Tourian.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Main Bosses (required for Tourian)", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF5722))
                Spacer(Modifier.height(8.dp))

                for (flag in BOSS_FLAG_DEFS.filter { it.key in setOf("kraid", "phantoon", "ridley", "draygon") }) {
                    BossCheckRow(flag, stored, editorState, patch.id)
                }

                Spacer(Modifier.height(16.dp))
                Text("Mini-Bosses", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4CAF50))
                Spacer(Modifier.height(8.dp))

                for (flag in BOSS_FLAG_DEFS.filter { it.key !in setOf("kraid", "phantoon", "ridley", "draygon") }) {
                    BossCheckRow(flag, stored, editorState, patch.id)
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            for (flag in BOSS_FLAG_DEFS) {
                                editorState.setPatchConfigData(patch.id, flag.key, 1)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Defeat All", fontSize = LocalEditorTheme.current.fontSize.value.body) }

                    OutlinedButton(
                        onClick = {
                            for (flag in BOSS_FLAG_DEFS) {
                                editorState.setPatchConfigData(patch.id, flag.key, 0)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Clear All", fontSize = LocalEditorTheme.current.fontSize.value.body) }
                }
            }
        }
    }
}

@Composable
private fun BossCheckRow(
    flag: BossFlagDef,
    stored: Map<String, Int>,
    editorState: EditorState,
    patchId: String
) {
    val checked = (stored[flag.key] ?: 0) != 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = {
                editorState.setPatchConfigData(patchId, flag.key, if (it) 1 else 0)
            },
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4CAF50),
                uncheckedColor = Color(0xFF888888),
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            flag.name,
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
