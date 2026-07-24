package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes

/**
 * Sprite palette editor for Samus suits and beams.
 *
 * Shows palette preview swatches for each region, allows applying
 * color effects, and editing individual colors via the HSV picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpritePaletteEditor(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    if (romParser == null) {
        Text("Load a ROM to edit sprite palettes.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp))
        return
    }

    var editVersion by remember { mutableStateOf(0) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var selectedColorIdx by remember { mutableStateOf(-1) }
    var showHsvPicker by remember { mutableStateOf(false) }

    // Load or get cached colors for a region
    fun getColors(regionId: String): IntArray? {
        return editorState.readSpritePalette(regionId, romParser)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text("Sprite Palettes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Samus, beams, bosses, enemies", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val hasAnyOverride = SpritePalettes.REGIONS.any { editorState.hasSpritePaletteOverride(it.id) }
            if (hasAnyOverride) {
                OutlinedButton(onClick = {
                    var changed = false
                    for (region in SpritePalettes.REGIONS) {
                        changed = editorState.resetSpritePaletteOverride(region.id) || changed
                    }
                    if (changed) {
                        selectedRegionId = null
                        selectedColorIdx = -1
                        showHsvPicker = false
                        editVersion++
                    }
                }) {
                    Text("Reset All", fontSize = 10.sp, color = Color(0xFFEF5350))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        fun targetSpriteRegionIds(): List<String> =
            if (selectedRegionId != null) listOf(selectedRegionId!!)
            else SpritePalettes.REGIONS.map { it.id }

        fun applySpriteEffect(effectId: String) {
            val effect = PaletteEffects.findEffect(effectId) ?: return
            for (regionId in targetSpriteRegionIds()) {
                val colors = getColors(regionId) ?: continue
                effect.apply(colors)
                editorState.saveSpritePaletteOverride(regionId, colors)
                editorState.setPaletteEffect(regionId, effect.id)
            }
            editVersion++
        }

        @Composable
        fun PaletteRegionSection(title: String, regions: List<SpritePalettes.PaletteRegion>) {
            if (regions.isEmpty()) return
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            for (region in regions) {
                PaletteRegionCard(
                    region = region,
                    colors = getColors(region.id),
                    isSelected = selectedRegionId == region.id,
                    hasOverride = editorState.hasSpritePaletteOverride(region.id),
                    selectedColorIdx = if (selectedRegionId == region.id) selectedColorIdx else -1,
                    onSelect = {
                        selectedRegionId = if (selectedRegionId == region.id) null else region.id
                        selectedColorIdx = -1
                        showHsvPicker = false
                    },
                    onColorClick = { idx ->
                        selectedRegionId = region.id
                        selectedColorIdx = idx
                        showHsvPicker = true
                    },
                    onReset = {
                        if (editorState.resetSpritePaletteOverride(region.id)) {
                            editVersion++
                        }
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // ─── Random buttons ───────────────────────────────────────
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = {
                for (regionId in targetSpriteRegionIds()) {
                    val colors = getColors(regionId) ?: continue
                    PaletteEffects.randomEffect(colors)
                    editorState.saveSpritePaletteOverride(regionId, colors)
                    editorState.setPaletteEffect(regionId, "random_palette")
                }
                editVersion++
            }) { Text("Random Palette", fontSize = 10.sp) }
            OutlinedButton(onClick = {
                for (regionId in targetSpriteRegionIds()) {
                    val colors = getColors(regionId) ?: continue
                    PaletteEffects.randomEffect(colors)
                    PaletteEffects.mutate(colors)
                    editorState.saveSpritePaletteOverride(regionId, colors)
                    editorState.setPaletteEffect(regionId, "full_random")
                }
                editVersion++
            }) { Text("Full Random", fontSize = 10.sp) }
            OutlinedButton(onClick = { applySpriteEffect("psychedelic-randomize") }) {
                Text("Psychedelic", fontSize = 10.sp)
            }
            OutlinedButton(onClick = { applySpriteEffect("mathematical-randomize") }) {
                Text("Mathematical", fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // ─── Effects bar ───────────────────────────────────────────
        val scopeLabel = if (selectedRegionId != null) {
            SpritePalettes.findRegion(selectedRegionId!!)?.name ?: "Selected"
        } else "All Sprites"
        Text("Effects — applies to: $scopeLabel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))

        // Determine which effects are active for the current scope
        val activeEffects = run {
            val checkRegions = if (selectedRegionId != null) listOf(selectedRegionId!!)
            else SpritePalettes.REGIONS.map { it.id }
            val effectCounts = mutableMapOf<String, Int>()
            for (regionId in checkRegions) {
                val eid = editorState.getPaletteEffect(regionId)
                if (eid != null) effectCounts[eid] = (effectCounts[eid] ?: 0) + 1
            }
            effectCounts.filterValues { it == checkRegions.size }.keys
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (effect in PaletteEffects.EFFECTS) {
                val isActive = effect.id in activeEffects
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        val targetRegions = if (selectedRegionId != null)
                            listOf(selectedRegionId!!)
                        else
                            SpritePalettes.REGIONS.map { it.id }

                        for (regionId in targetRegions) {
                            val colors = getColors(regionId) ?: continue
                            effect.apply(colors)
                            editorState.saveSpritePaletteOverride(regionId, colors)
                            editorState.setPaletteEffect(regionId, effect.id)
                        }
                        editVersion++
                    }
                ) {
                    Text(
                        effect.name,
                        fontSize = 9.sp,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        // ─── Palette regions ───────────────────────────────────────
        @Suppress("UNUSED_VARIABLE")
        val ev = editVersion // force recomposition

        PaletteRegionSection("Samus", SpritePalettes.SAMUS_REGIONS)
        Spacer(modifier = Modifier.height(8.dp))
        PaletteRegionSection("Beams", SpritePalettes.BEAM_REGIONS)
        Spacer(modifier = Modifier.height(8.dp))
        PaletteRegionSection("Bosses", SpritePalettes.BOSSES_REGIONS)
        Spacer(modifier = Modifier.height(8.dp))
        PaletteRegionSection("Enemies", SpritePalettes.ENEMIES_REGIONS)

        // ─── Color editor (HSV picker) for selected color ──────────
        if (showHsvPicker && selectedRegionId != null && selectedColorIdx >= 0) {
            val region = SpritePalettes.findRegion(selectedRegionId!!)
            val colors = getColors(selectedRegionId!!)
            if (region != null && colors != null && selectedColorIdx < colors.size) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Edit Color — ${region.name} #$selectedColorIdx",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))

                val currentBgr = colors[selectedColorIdx]
                HsvColorPicker(
                    bgr555 = currentBgr,
                    onColorChanged = { newBgr ->
                        val updated = getColors(selectedRegionId!!) ?: return@HsvColorPicker
                        updated[selectedColorIdx] = newBgr
                        editorState.saveSpritePaletteOverride(selectedRegionId!!, updated)
                        editorState.clearPaletteEffect(selectedRegionId!!) // manual edit overrides effect
                        editVersion++
                    }
                )
            }
        }
    }
}

@Composable
private fun PaletteRegionCard(
    region: SpritePalettes.PaletteRegion,
    colors: IntArray?,
    isSelected: Boolean,
    hasOverride: Boolean,
    selectedColorIdx: Int,
    onSelect: () -> Unit,
    onColorClick: (Int) -> Unit,
    onReset: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF4FC3F7) else Color.Transparent
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(region.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (hasOverride) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(modified)", fontSize = 9.sp, color = Color(0xFFFFD54F))
                    }
                }
                if (hasOverride) {
                    TextButton(
                        onClick = onReset,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Text("Reset", fontSize = 9.sp)
                    }
                }
            }

            // Show first 16 colors (row 0 = base palette)
            if (colors != null) {
                val previewColors = colors.take(16)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    for ((i, bgr555) in previewColors.withIndex()) {
                        val argb = SpritePalettes.bgr555ToArgb(bgr555)
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        val isSelectedColor = i == selectedColorIdx
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(r, g, b), RoundedCornerShape(2.dp))
                                .then(
                                    if (isSelectedColor)
                                        Modifier.border(2.dp, Color.White, RoundedCornerShape(2.dp))
                                    else Modifier
                                )
                                .clickable { onColorClick(i) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Area palette editor — shows all 29 tileset palettes (8×16 grids)
 * with effect buttons and per-color editing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AreaPaletteEditor(
    romParser: RomParser?,
    editorState: EditorState,
    onCurrentTilesetPaletteChanged: (reloadCurrentTileset: Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (romParser == null) {
        Text("Load a ROM to edit area palettes.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp))
        return
    }

    var editVersion by remember { mutableStateOf(0) }
    var selectedTileset by remember { mutableStateOf(-1) }
    var selectedColorIdx by remember { mutableStateOf(-1) }
    var showHsvPicker by remember { mutableStateOf(false) }

    fun getColors(tilesetId: Int): IntArray? {
        return editorState.readTilesetPalette(tilesetId, romParser)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text("Area Palettes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("All 29 tileset palettes (8×16 colors each)", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val hasAnyOverride = (0 until 29).any { editorState.hasCustomPalette(it) }
            if (hasAnyOverride) {
                OutlinedButton(onClick = {
                    var changed = false
                    for (i in 0 until 29) {
                        changed = editorState.resetPaletteOverride(i) || changed
                    }
                    if (changed) {
                        selectedTileset = -1
                        selectedColorIdx = -1
                        showHsvPicker = false
                        editVersion++
                        onCurrentTilesetPaletteChanged(true)
                    }
                }) {
                    Text("Reset All", fontSize = 10.sp, color = Color(0xFFEF5350))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        fun targetTilesets(): List<Int> =
            if (selectedTileset >= 0) listOf(selectedTileset) else (0 until 29).toList()

        fun notifyCurrentTilesetIfAffected(targets: List<Int>, reloadCurrentTileset: Boolean) {
            if (editorState.editorTilesetId in targets || editorState.currentTilesetId in targets) {
                onCurrentTilesetPaletteChanged(reloadCurrentTileset)
            }
        }

        fun applyAreaEffect(effectId: String) {
            val effect = PaletteEffects.findEffect(effectId) ?: return
            val targets = targetTilesets()
            for (tsId in targets) {
                val colors = getColors(tsId) ?: continue
                effect.apply(colors)
                editorState.saveTilesetPaletteFromColors(tsId, colors)
                editorState.setPaletteEffect("tileset:$tsId", effect.id)
            }
            notifyCurrentTilesetIfAffected(targets, reloadCurrentTileset = false)
            editVersion++
        }

        // Random buttons
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = {
                val targets = targetTilesets()
                for (tsId in targets) {
                    val colors = getColors(tsId) ?: continue
                    PaletteEffects.randomEffect(colors)
                    editorState.saveTilesetPaletteFromColors(tsId, colors)
                    editorState.setPaletteEffect("tileset:$tsId", "random_palette")
                }
                notifyCurrentTilesetIfAffected(targets, reloadCurrentTileset = false)
                editVersion++
            }) { Text("Random Palette", fontSize = 10.sp) }
            OutlinedButton(onClick = {
                val targets = targetTilesets()
                for (tsId in targets) {
                    val colors = getColors(tsId) ?: continue
                    PaletteEffects.randomEffect(colors)
                    PaletteEffects.mutate(colors)
                    editorState.saveTilesetPaletteFromColors(tsId, colors)
                    editorState.setPaletteEffect("tileset:$tsId", "full_random")
                }
                notifyCurrentTilesetIfAffected(targets, reloadCurrentTileset = false)
                editVersion++
            }) { Text("Full Random", fontSize = 10.sp) }
            OutlinedButton(onClick = { applyAreaEffect("psychedelic-randomize") }) {
                Text("Psychedelic", fontSize = 10.sp)
            }
            OutlinedButton(onClick = { applyAreaEffect("mathematical-randomize") }) {
                Text("Mathematical", fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Effects bar
        val areaScopeLabel = if (selectedTileset >= 0) SpritePalettes.tilesetName(selectedTileset) else "All Tilesets"
        Text("Effects — applies to: $areaScopeLabel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))

        // Determine which effects are active for the current scope
        val areaActiveEffects = run {
            val checkIds = if (selectedTileset >= 0) listOf(selectedTileset) else (0 until 29).toList()
            val effectCounts = mutableMapOf<String, Int>()
            for (tsId in checkIds) {
                val eid = editorState.getPaletteEffect("tileset:$tsId")
                if (eid != null) effectCounts[eid] = (effectCounts[eid] ?: 0) + 1
            }
            effectCounts.filterValues { it == checkIds.size }.keys
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (effect in PaletteEffects.EFFECTS) {
                val isActive = effect.id in areaActiveEffects
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        val targets = if (selectedTileset >= 0) listOf(selectedTileset) else (0 until 29).toList()
                        for (tsId in targets) {
                            val colors = getColors(tsId) ?: continue
                            effect.apply(colors)
                            editorState.saveTilesetPaletteFromColors(tsId, colors)
                            editorState.setPaletteEffect("tileset:$tsId", effect.id)
                        }
                        notifyCurrentTilesetIfAffected(targets, reloadCurrentTileset = false)
                        editVersion++
                    }
                ) {
                    Text(effect.name, fontSize = 9.sp,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        // Tileset palette cards
        @Suppress("UNUSED_VARIABLE")
        val ev = editVersion
        @Suppress("UNUSED_VARIABLE")
        val pv = editorState.paletteVersion

        for (tsId in 0 until 29) {
            val colors = getColors(tsId)
            val isSelected = selectedTileset == tsId
            val hasOverride = editorState.hasCustomPalette(tsId)
            val borderColor = if (isSelected) Color(0xFF4FC3F7) else Color.Transparent
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                    .clickable {
                        selectedTileset = if (selectedTileset == tsId) -1 else tsId
                        selectedColorIdx = -1
                        showHsvPicker = false
                    }
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$tsId: ${SpritePalettes.tilesetName(tsId)}",
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            if (hasOverride) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("(modified)", fontSize = 9.sp, color = Color(0xFFFFD54F))
                            }
                        }
                        if (hasOverride) {
                            TextButton(
                                onClick = {
                                    if (editorState.resetPaletteOverride(tsId)) {
                                        notifyCurrentTilesetIfAffected(listOf(tsId), reloadCurrentTileset = true)
                                        editVersion++
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                            ) { Text("Reset", fontSize = 9.sp) }
                        }
                    }

                    // 8×16 color grid
                    if (colors != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            for (row in 0 until 8) {
                                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    for (col in 0 until 16) {
                                        val idx = row * 16 + col
                                        val bgr = colors[idx]
                                        val argb = SpritePalettes.bgr555ToArgb(bgr)
                                        val r = (argb shr 16) and 0xFF
                                        val g = (argb shr 8) and 0xFF
                                        val b = argb and 0xFF
                                        val isSel = isSelected && idx == selectedColorIdx
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .background(Color(r, g, b), RoundedCornerShape(1.dp))
                                                .then(
                                                    if (isSel) Modifier.border(2.dp, Color.White, RoundedCornerShape(1.dp))
                                                    else Modifier
                                                )
                                                .clickable {
                                                    selectedTileset = tsId
                                                    selectedColorIdx = idx
                                                    showHsvPicker = true
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // HSV picker for selected color
        if (showHsvPicker && selectedTileset >= 0 && selectedColorIdx >= 0) {
            val colors = getColors(selectedTileset)
            if (colors != null && selectedColorIdx < colors.size) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Edit Color — ${SpritePalettes.tilesetName(selectedTileset)} [${selectedColorIdx / 16},${selectedColorIdx % 16}]",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                HsvColorPicker(
                    bgr555 = colors[selectedColorIdx],
                    onColorChanged = { newBgr ->
                        val updated = getColors(selectedTileset) ?: return@HsvColorPicker
                        updated[selectedColorIdx] = newBgr
                        editorState.saveTilesetPaletteFromColors(selectedTileset, updated)
                        editorState.clearPaletteEffect("tileset:$selectedTileset")
                        notifyCurrentTilesetIfAffected(listOf(selectedTileset), reloadCurrentTileset = false)
                        editVersion++
                    }
                )
            }
        }
    }
}
