package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmTilesetGraphicsKind

@Composable
internal fun TilesetAssetRelocationDialog(
    editorState: EditorState,
    onRelocated: () -> Unit,
) {
    val proposal = editorState.pendingTilesetAssetRelocation ?: return
    val resource = when (proposal) {
        is TilesetAssetRelocationProposal.Palette -> "Tileset palette"
        is TilesetAssetRelocationProposal.NativeSpriteGraphics -> "Native sprite graphics"
        is TilesetAssetRelocationProposal.CinematicMap -> "Scene map layout"
        is TilesetAssetRelocationProposal.TypewriterText -> "Typewriter text"
        is TilesetAssetRelocationProposal.Graphics -> when (proposal.kind) {
            AsmTilesetGraphicsKind.AREA_TILES -> "Area graphics"
            AsmTilesetGraphicsKind.AREA_METATILES -> "Area metatiles"
            AsmTilesetGraphicsKind.COMMON_TILES -> "CRE graphics"
            AsmTilesetGraphicsKind.COMMON_METATILES -> "CRE metatiles"
        }
    }
    AlertDialog(
        onDismissRequest = editorState::dismissTilesetAssetRelocation,
        title = { Text("Move ASM asset to project space?") },
        text = {
            Column {
                Text("$resource no longer fits its fixed native location.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "${proposal.requiredBytes.toByteCount()} required · " +
                        "${proposal.inPlaceCapacity.toByteCount()} available",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(proposal.assetPath, style = MaterialTheme.typography.bodySmall)
                if (proposal.affectedTilesetIds.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This shared asset updates tilesets ${proposal.affectedTilesetIds.joinToString()} together.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "SMEDIT will keep the native bytes intact, place this asset in checked free space" +
                        (if (proposal is TilesetAssetRelocationProposal.TypewriterText) " in bank \$A6" else "") +
                        ", and update its symbolic ASM pointer. Cancel leaves the source unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (editorState.confirmTilesetAssetRelocation()) onRelocated()
            }) { Text("Move to project space") }
        },
        dismissButton = {
            TextButton(onClick = editorState::dismissTilesetAssetRelocation) { Text("Cancel") }
        },
    )
}

private fun Int.toByteCount(): String = "%,d B".format(this)
