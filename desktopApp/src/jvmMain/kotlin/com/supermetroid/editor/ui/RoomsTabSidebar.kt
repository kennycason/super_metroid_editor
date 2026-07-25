package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme

internal const val BOTTOM_TAB_TILESET = 0
internal const val BOTTOM_TAB_PATTERNS = 1
internal const val BOTTOM_TAB_ROOM_INFO = 2
internal const val BOTTOM_TAB_GENERATE = 3

/**
 * Left sidebar for the Rooms tab: room list (top) + resizable bottom pane with
 * sub-tabs for Tileset, Patterns, Room Info, and Generate.
 */
@Composable
internal fun RoomsTabSidebar(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    onRoomSelected: (RoomInfo) -> Unit,
    romParser: RomParser?,
    editorState: EditorState,
    tilesetHeightDp: Float,
    onTilesetHeightChange: (Float) -> Unit,
    onSeedPatterns: () -> Unit,
    onNavigateToMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var bottomPaneTab by remember { mutableStateOf(BOTTOM_TAB_TILESET) }

    Column(modifier = modifier.fillMaxSize()) {
        RoomListView(
            rooms = rooms,
            selectedRoom = selectedRoom,
            romParser = romParser,
            editorState = editorState,
            onRoomSelected = onRoomSelected,
            modifier = Modifier.weight(1f)
        )
        DraggableDividerHorizontal(onDelta = { dy ->
            onTilesetHeightChange((tilesetHeightDp - dy).coerceIn(120f, 700f))
        })
        Column(modifier = Modifier.fillMaxWidth().height(tilesetHeightDp.dp)) {
            TabRow(selectedTabIndex = bottomPaneTab, modifier = Modifier.fillMaxWidth().height(26.dp)) {
                Tab(selected = bottomPaneTab == BOTTOM_TAB_TILESET, onClick = { bottomPaneTab = BOTTOM_TAB_TILESET },
                    modifier = Modifier.height(26.dp)) {
                    Text("Tileset", fontSize = fs.tabLabel)
                }
                Tab(selected = bottomPaneTab == BOTTOM_TAB_PATTERNS, onClick = {
                    onSeedPatterns(); bottomPaneTab = BOTTOM_TAB_PATTERNS
                }, modifier = Modifier.height(26.dp)) {
                    Text("Patterns", fontSize = fs.tabLabel)
                }
                Tab(selected = bottomPaneTab == BOTTOM_TAB_ROOM_INFO, onClick = { bottomPaneTab = BOTTOM_TAB_ROOM_INFO },
                    modifier = Modifier.height(26.dp)) {
                    Text("Room Info", fontSize = fs.tabLabel)
                }
                Tab(selected = bottomPaneTab == BOTTOM_TAB_GENERATE, onClick = { bottomPaneTab = BOTTOM_TAB_GENERATE },
                    modifier = Modifier.height(26.dp)) {
                    Text("Generate", fontSize = fs.tabLabel)
                }
            }
            key(bottomPaneTab) {
                when (bottomPaneTab) {
                    BOTTOM_TAB_TILESET -> TilesetPreview(
                        room = selectedRoom, romParser = romParser, editorState = editorState,
                        modifier = Modifier.fillMaxSize()
                    )
                    BOTTOM_TAB_PATTERNS -> PatternThumbnailList(
                        editorState = editorState, modifier = Modifier.fillMaxSize()
                    )
                    BOTTOM_TAB_ROOM_INFO -> {
                        val rp = romParser
                        val sr = selectedRoom
                        if (rp != null && sr != null) {
                            val roomHeader = remember(sr) { rp.readRoomHeader(sr.getRoomIdAsInt()) }
                            if (roomHeader != null) {
                                RoomPropertiesPanel(
                                    room = roomHeader, romParser = rp, editorState = editorState,
                                    modifier = Modifier.fillMaxSize(), onNavigateToMap = onNavigateToMap,
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Could not parse room header", fontSize = fs.detail, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Select a room", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    BOTTOM_TAB_GENERATE -> BiomeGeneratorPanel(
                        editorState = editorState, romParser = romParser, rooms = rooms,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
