package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.ProjectileSprites
import com.supermetroid.editor.rom.CinematicSprites
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomVisualAssets
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.NativeSpriteAssets
import com.supermetroid.editor.rom.TourianStatueSprites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class VisualSpriteWorkspace(val index: Int, val name: String, val group: String)

internal val VISUAL_SPRITE_WORKSPACES = listOf(
    VisualSpriteWorkspace(-2, "Beams and projectiles", "Weapons"),
    VisualSpriteWorkspace(-3, "Title, intro and ending", "Scenes"),
    VisualSpriteWorkspace(-4, "Shared sprites and effects", "Effects"),
    VisualSpriteWorkspace(-6, "Golden Four statue", "Statue"),
)

internal fun visualSpriteNames(workspace: VisualSpriteWorkspace): List<String> = when (workspace.index) {
    -6 -> listOf("Golden Four statue") + TourianStatueSprites.PARTS.map { it.name }
    -2 -> ProjectileSprites.WEAPONS.map { it.name }
    -3 -> CinematicSprites.SEQUENCES.map { it.name } + RomVisualAssets.ASSETS.filter { it.group == workspace.group }.map { it.name }
    else -> RomVisualAssets.ASSETS.filter { it.group == workspace.group }.map { it.name }
}

internal fun visualSpriteTargets(parser: RomParser, workspace: VisualSpriteWorkspace, selected: Int, direction: Int = 2): List<NativeSpriteTarget> {
    if (workspace.index == -6) {
        val part = TourianStatueSprites.PARTS.getOrNull(selected - 1)
        val row = part?.let { (it.palette - TourianStatueSprites.PALETTE) / 32 } ?: 0
        return listOf(NativeSpriteTarget(NativeSpriteAssets.key(TourianStatueSprites.TILES), TourianStatueSprites.PALETTE, 3, row))
    }
    if (workspace.index == -2) {
        val weapon = ProjectileSprites.WEAPONS.getOrNull(selected) ?: return emptyList()
        val beam = weapon.beam
        return buildList {
            if (beam != null) {
                val nativeBeam = ProjectileSprites.nativeBeamMask(beam)
                val graphics = 0x9A0000 or parser.readUInt16At(parser.snesToPc(0x90C3B1 + nativeBeam * 2))
                val palette = 0x900000 or parser.readUInt16At(parser.snesToPc(0x90C3C9 + nativeBeam * 2))
                add(NativeSpriteTarget(NativeSpriteAssets.key(graphics), palette, 1))
            }
            val row = ProjectileSprites(parser).paletteRow(weapon, direction)
            if (beam != null && row == 6) {
                val nativeBeam = ProjectileSprites.nativeBeamMask(beam)
                val palette = 0x900000 or parser.readUInt16At(parser.snesToPc(0x90C3C9 + nativeBeam * 2))
                add(NativeSpriteTarget("9AD200", palette, 1))
            } else add(NativeSpriteTarget("9AD200", 0x9A8100, 8, row))
        }
    }
    if (workspace.index == -3 && selected in CinematicSprites.SEQUENCES.indices) {
        val sequence = CinematicSprites.SEQUENCES[selected]
        return buildList {
            add(NativeSpriteTarget(NativeSpriteAssets.key(sequence.graphics), sequence.palette, 16, 8 + sequence.paletteBase))
            if (sequence.graphics == 0x95E4C2) add(NativeSpriteTarget("9AD200", sequence.palette, 16, 15))
        }
    }
    val offset = if (workspace.index == -3) CinematicSprites.SEQUENCES.size else 0
    val asset = RomVisualAssets.ASSETS.filter { it.group == workspace.group }.getOrNull(selected - offset) ?: return emptyList()
    val row = when (asset.graphics) { 0x9580D8 -> 9; 0x95E4C2 -> 15; 0x97B957 -> 13; 0x97D7FC -> 14; else -> 0 }
    return listOf(NativeSpriteTarget(NativeSpriteAssets.key(asset.graphics), asset.palette, asset.paletteColors / 16, row))
}

internal fun renderVisualSprite(parser: RomParser, workspace: VisualSpriteWorkspace, selected: Int, direction: Int, paletteRow: Int): SpriteAnimation? {
    if (workspace.index == -6) {
        val renderer = TourianStatueSprites(parser)
        val frame = if (selected == 0) renderer.renderComplete() else {
            val sprite = renderer.renderObjects(TourianStatueSprites.PARTS.getOrNull(selected - 1)) ?: return null
            com.supermetroid.editor.rom.SpriteAnimationFrame(sprite.pixels, sprite.width, sprite.height, label = visualSpriteNames(workspace).getOrNull(selected).orEmpty())
        } ?: return null
        return SpriteAnimation(frame.label, listOf(frame), loop = false)
    }
    if (workspace.index == -2) return ProjectileSprites.WEAPONS.getOrNull(selected)?.let { ProjectileSprites(parser).render(it, direction) }
    if (workspace.index == -3 && selected in CinematicSprites.SEQUENCES.indices) return CinematicSprites(parser).render(CinematicSprites.SEQUENCES[selected])
    val offset = if (workspace.index == -3) CinematicSprites.SEQUENCES.size else 0
    val asset = RomVisualAssets.ASSETS.filter { it.group == workspace.group }.getOrNull(selected - offset) ?: return null
    return RomVisualAssets.render(parser, asset, paletteRow)?.let { SpriteAnimation(asset.name, listOf(it), loop = false) }
}

@Composable
internal fun VisualSpriteViewer(workspace: VisualSpriteWorkspace, parser: RomParser?, editor: EditorState, modifier: Modifier = Modifier) {
    if (parser == null) {
        Text("Load a ROM to view and edit sprites.", modifier = modifier.padding(16.dp))
        return
    }
    var selected by remember(workspace.index) { mutableStateOf(0) }
    var direction by remember { mutableStateOf(2) }
    val names = remember(workspace) { visualSpriteNames(workspace) }
    val targets = remember(parser, workspace, selected, direction) { visualSpriteTargets(parser, workspace, selected, direction) }
    var selectedSource by remember(workspace, selected) { mutableStateOf(0) }
    val target = targets.getOrNull(selectedSource)
    var paletteRow by remember(workspace, selected) { mutableStateOf(targets.firstOrNull()?.defaultRow ?: 0) }
    var editing by remember(parser, System.identityHashCode(editor.project), workspace) { mutableStateOf<NativeSpriteEditorState.Sheet?>(null) }
    val renderPreview: (RomParser) -> SpriteAnimation? = { rom -> renderVisualSprite(rom, workspace, selected, direction, paletteRow) }
    editing?.let { sheet ->
        NativeSpritePixelEditing(parser, editor, sheet, renderPreview, { editing = null }, modifier)
        return
    }
    val effective = remember(parser, editor.editVersion, editor.paletteVersion) { runCatching { editor.nativeSprites.previewParser(parser) } }
    val animation by produceState<SpriteAnimation?>(null, effective, workspace.index, selected, direction, paletteRow) {
        value = null
        value = withContext(Dispatchers.Default) {
            effective.getOrNull()?.let(renderPreview)
        }
    }
    val fs = LocalEditorTheme.current.fontSize.value
    val focusRequester = rememberVerticalSelectionFocusRequester()
    Row(modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(230.dp).fillMaxHeight()
            .verticalSelectionKeyNavigation(focusRequester = focusRequester, itemCount = names.size, selectedIndex = selected, onSelectIndex = { selected = it })
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workspace.name, fontSize = fs.body)
            names.forEachIndexed { index, name ->
                SpriteMenuItem(name, selected == index, { requestVerticalSelectionFocus(focusRequester); selected = index })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(animation?.name ?: workspace.name, fontSize = fs.heading)
            if (workspace.index == -6) Text("OBJ pieces are editable here; the Kraid/Draygon background sculpture belongs to room tileset 21.", fontSize = fs.detail)
            if (workspace.index == -2) {
                SpriteOptionSelector(ProjectileSprites.DIRECTIONS, direction, { direction = it }, Modifier.fillMaxWidth())
            }
            if (targets.size > 1) SpriteOptionSelector(targets.map { it.name }, selectedSource, { selectedSource = it }, Modifier.fillMaxWidth())
            if (target != null) NativeSpriteSourceControls(parser, editor, target, { editing = it }, { paletteRow = it })
            effective.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
            NativeSpriteAnimationPlayer(animation, Modifier.weight(1f), previewSize = 540)
        }
    }
}
