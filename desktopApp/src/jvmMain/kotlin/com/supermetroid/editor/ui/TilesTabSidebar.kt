package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

/**
 * Left sidebar for the Tiles tab: sub-tabs for Tilesets, Patterns, and Palette.
 *
 * [tilesetSubTab] and [onSubTabChange] are provided by the caller so the right canvas can
 * also react to the active sub-tab (PatternEditorCanvas vs TilesetCanvas).
 */
@Composable
internal fun TilesTabSidebar(
    tilesetSubTab: Int,
    onSubTabChange: (Int) -> Unit,
    romParser: RomParser?,
    editorState: EditorState,
    tilesetEditorState: TilesetEditorState,
    selectedRoom: RoomInfo?,
    tilesetHeightDp: Float,
    onTilesetHeightChange: (Float) -> Unit,
    onSeedPatterns: () -> Unit,
    onReloadPaletteBackedViews: () -> Unit,
    onRefreshTilesetGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tilesetSubTab, modifier = Modifier.fillMaxWidth().height(26.dp)) {
            Tab(selected = tilesetSubTab == 0, onClick = { onSubTabChange(0) }, modifier = Modifier.height(26.dp)) {
                Text("Tilesets", fontSize = fs.tabLabel)
            }
            Tab(selected = tilesetSubTab == 1, onClick = { onSeedPatterns(); onSubTabChange(1) }, modifier = Modifier.height(26.dp)) {
                Text("Patterns", fontSize = fs.tabLabel)
            }
            Tab(selected = tilesetSubTab == 2, onClick = { onSubTabChange(2) }, modifier = Modifier.height(26.dp)) {
                Text("Palette", fontSize = fs.tabLabel)
            }
        }
        when (tilesetSubTab) {
            0 -> TilesetListPanel(
                romParser = romParser,
                editorState = editorState,
                tilesetEditorState = tilesetEditorState,
                modifier = Modifier.fillMaxSize()
            )
            1 -> {
                SidebarVerticalSplit(
                    bottomPaneHeightDp = tilesetHeightDp,
                    onBottomPaneHeightChange = onTilesetHeightChange,
                    modifier = Modifier.fillMaxSize(),
                    topPane = { topModifier ->
                        PatternListPanel(editorState = editorState, modifier = topModifier)
                    },
                    bottomPane = { bottomModifier ->
                        TilesetPreview(
                            room = selectedRoom,
                            romParser = romParser,
                            editorState = editorState,
                            modifier = bottomModifier,
                        )
                    },
                )
            }
            2 -> PaletteSubTab(
                romParser = romParser,
                editorState = editorState,
                onReloadPaletteBackedViews = onReloadPaletteBackedViews,
                onRefreshTilesetGrid = onRefreshTilesetGrid,
            )
        }
    }
}

@Composable
private fun PaletteSubTab(
    romParser: RomParser?,
    editorState: EditorState,
    onReloadPaletteBackedViews: () -> Unit,
    onRefreshTilesetGrid: () -> Unit,
) {
    var paletteCategory by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            for ((idx, label) in listOf("Environment", "Samus / Beams", "Area").withIndex()) {
                val selected = paletteCategory == idx
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { paletteCategory = idx }
                ) {
                    Text(
                        label, fontSize = 10.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        when (paletteCategory) {
            0 -> {
                val currentTilesetId = editorState.editorTilesetId.takeIf { it >= 0 }
                PaletteEditor(
                    tileGraphics = editorState.editorTileGraphics,
                    tilesetId = currentTilesetId?.toString(),
                    hasCustomPalette = currentTilesetId != null && editorState.hasCustomPalette(currentTilesetId),
                    sampledPaletteRow = editorState.sampledPaletteRow,
                    sampledPaletteCol = editorState.sampledPaletteCol,
                    onPaletteSaved = { currentTilesetId?.let { editorState.savePaletteOverride(it) } },
                    onPaletteReset = {
                        if (currentTilesetId != null && editorState.resetPaletteOverride(currentTilesetId)) {
                            onReloadPaletteBackedViews()
                        }
                    },
                    onRefreshNeeded = {
                        onRefreshTilesetGrid()
                        val clearedEffect = currentTilesetId?.let { editorState.clearPaletteEffect("tileset:$it") } ?: false
                        if (!clearedEffect) editorState.paletteVersion++
                    },
                    onColorSelected = { row, col ->
                        editorState.sampledPaletteRow = row
                        editorState.sampledPaletteCol = col
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            1 -> SpritePaletteEditor(romParser = romParser, editorState = editorState, modifier = Modifier.fillMaxSize())
            2 -> AreaPaletteEditor(
                romParser = romParser,
                editorState = editorState,
                onCurrentTilesetPaletteChanged = { reloadCurrentTileset ->
                    if (reloadCurrentTileset) onReloadPaletteBackedViews() else onRefreshTilesetGrid()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
