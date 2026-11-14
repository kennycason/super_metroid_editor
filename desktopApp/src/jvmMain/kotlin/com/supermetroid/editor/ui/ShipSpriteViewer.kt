package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.ShipSpritemap
import com.supermetroid.editor.rom.NativeSpriteAssets

@Composable
internal fun ShipSpriteViewer(parser: RomParser?, editor: EditorState, modifier: Modifier = Modifier) {
    if (parser == null) { Text("Load a ROM to view and edit the ship.", modifier = modifier.padding(16.dp)); return }
    var selected by remember { mutableStateOf(0) }
    var editing by remember(parser, System.identityHashCode(editor.project)) { mutableStateOf<NativeSpriteEditorState.Sheet?>(null) }
    editing?.let { sheet ->
        NativeSpritePixelEditing(parser, editor, sheet, { rom -> ShipSpritemap(rom).animations().getOrNull(selected) }, { editing = null }, modifier)
        return
    }
    val animations = remember(parser, editor.editVersion, editor.asmVisualSourceRevision) {
        editor.nativeSprites.previewParser(parser).let {
            ShipSpritemap(it).animations(
                editor.loadEnemyTileData(it, ShipSpritemap.TOP_SPECIES),
                editor.loadEnemyPalette(it, ShipSpritemap.TOP_SPECIES),
            )
        }
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Samus' Ship", fontSize = LocalEditorTheme.current.fontSize.value.heading, color = MaterialTheme.colorScheme.onSurface)
        SpriteOptionSelector(animations.map { it.name }, selected, { selected = it }, Modifier.fillMaxWidth())
        NativeSpriteSourceControls(parser, editor, NativeSpriteTarget("ADB600", NativeSpriteAssets.shipPalette(parser), 1), { editing = it })
        NativeSpriteAnimationPlayer(animations.getOrNull(selected), Modifier.weight(1f), previewSize = 440)
    }
}
