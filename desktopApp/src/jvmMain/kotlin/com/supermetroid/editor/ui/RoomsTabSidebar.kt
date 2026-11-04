package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

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
    asmWorkspaceState: AsmWorkspaceState,
    sidebarState: RoomsSidebarState,
    onCreateRoom: (NewRoomCreationRequest) -> String?,
    onDeleteRoom: (Int) -> String?,
    onWorkspaceChanged: () -> Unit,
    tilesetHeightDp: Float,
    onTilesetHeightChange: (Float) -> Unit,
    onSeedPatterns: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToAsm: ((Int) -> Unit)? = null,
    onNavigateToInitialScrolls: (() -> Unit)? = null,
    onNavigateToLayoutSource: (() -> Unit)? = null,
    onNavigateToObjects: (() -> Unit)? = null,
    onNavigateToRoomProperties: ((String?) -> Unit)? = null,
    onNavigateToSelectors: ((String?) -> Unit)? = null,
    onNavigateToFx: ((String, Int?, com.supermetroid.editor.data.ProjectSourceDoorReference?) -> Unit)? = null,
    onNavigateToEnemies: ((String, Boolean) -> Unit)? = null,
    onNavigateToDoors: ((Int) -> Unit)? = null,
    onOpenAsmSource: ((String, Int) -> Unit)? = null,
    onReassignRoomArea: ((Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onKeyboardNavigatorChanged: (((Int) -> Boolean)?) -> Unit = {},
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val bottomPaneTab = sidebarState.selectedTab.ordinal

    SidebarVerticalSplit(
        bottomPaneHeightDp = tilesetHeightDp,
        onBottomPaneHeightChange = onTilesetHeightChange,
        modifier = modifier,
        topPane = { topModifier ->
            RoomListView(
                rooms = rooms,
                selectedRoom = selectedRoom,
                romParser = romParser,
                editorState = editorState,
                onCreateRoom = onCreateRoom,
                onDeleteRoom = onDeleteRoom,
                onRoomSelected = onRoomSelected,
                modifier = topModifier,
                onKeyboardNavigatorChanged = onKeyboardNavigatorChanged,
            )
        },
        bottomPane = { bottomModifier ->
            Column(modifier = bottomModifier) {
                TabRow(selectedTabIndex = bottomPaneTab, modifier = Modifier.fillMaxWidth().height(26.dp)) {
                    Tab(
                        selected = bottomPaneTab == BOTTOM_TAB_TILESET,
                        onClick = { sidebarState.select(RoomSidebarTab.TILESET) },
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("Tileset", fontSize = fs.tabLabel)
                    }
                    Tab(
                        selected = bottomPaneTab == BOTTOM_TAB_PATTERNS,
                        onClick = {
                            onSeedPatterns()
                            sidebarState.select(RoomSidebarTab.PATTERNS)
                        },
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("Patterns", fontSize = fs.tabLabel)
                    }
                    Tab(
                        selected = bottomPaneTab == BOTTOM_TAB_ROOM_INFO,
                        onClick = { sidebarState.select(RoomSidebarTab.ROOM_INFO) },
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("Room Info", fontSize = fs.tabLabel)
                    }
                    Tab(
                        selected = bottomPaneTab == BOTTOM_TAB_GENERATE,
                        onClick = { sidebarState.select(RoomSidebarTab.GENERATE) },
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text("Generate", fontSize = fs.tabLabel)
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    key(bottomPaneTab) {
                        when (bottomPaneTab) {
                            BOTTOM_TAB_TILESET -> TilesetPreview(
                                room = selectedRoom,
                                romParser = romParser,
                                editorState = editorState,
                                modifier = Modifier.fillMaxSize(),
                                verticalScrollState = sidebarState.verticalScroll(RoomSidebarTab.TILESET),
                                horizontalScrollState = sidebarState.tilesetHorizontalScroll,
                            )
                            BOTTOM_TAB_PATTERNS -> PatternThumbnailList(
                                editorState = editorState,
                                modifier = Modifier.fillMaxSize(),
                                scrollState = sidebarState.verticalScroll(RoomSidebarTab.PATTERNS),
                            )
                            BOTTOM_TAB_ROOM_INFO -> {
                                val rp = romParser
                                val sr = selectedRoom
                                if (rp != null && sr != null) {
                                    val roomHeader = remember(sr, rp) {
                                        rp.readRoomHeader(sr.getRoomIdAsInt())?.copy(name = sr.name, handle = sr.handle)
                                    }
                                    if (roomHeader != null) {
                                        val stateId = editorState.currentStateId
                                        val diagnostics = asmWorkspaceState.displayedDiagnostics
                                        val snapshot = remember(
                                            roomHeader,
                                            rp,
                                            editorState.editVersion,
                                            stateId,
                                            editorState.scrollVersion,
                                            editorState.objectPreviewProblem,
                                            editorState.initialScrollPreviewProblem,
                                            editorState.fxPreviewProblem,
                                            editorState.enemyPreviewProblem,
                                            editorState.doorPreviewProblem,
                                            editorState.selectorPreviewProblem,
                                            editorState.roomPropertiesPreviewProblem,
                                            editorState.layoutPreviewProblem,
                                            editorState.selectorProjectionStatus,
                                            asmWorkspaceState.projectWorkspace,
                                            asmWorkspaceState.sourceBuildRequired,
                                            asmWorkspaceState.sourceBufferRevision,
                                            asmWorkspaceState.buildReport,
                                            diagnostics,
                                        ) {
                                            buildRoomRuntimeSnapshot(roomHeader, rp, editorState, asmWorkspaceState)
                                        }
                                        val navigation = RoomRuntimeNavigation(
                                            openHeader = onNavigateToRoomProperties?.let { open -> { open(null) } },
                                            openSelectors = onNavigateToSelectors?.let { open -> { open(stateId) } },
                                            openLayout = onNavigateToLayoutSource,
                                            openObjects = onNavigateToObjects,
                                            openScrolls = onNavigateToInitialScrolls,
                                            openFx = stateId?.let { id -> onNavigateToFx?.let { open -> { open(id, null, null) } } },
                                            openEnemies = stateId?.let { id -> onNavigateToEnemies?.let { open -> { open(id, false) } } },
                                            openDoors = onNavigateToDoors?.takeIf { editorState.doorEntries.isNotEmpty() }
                                                ?.let { open -> { open(0) } },
                                            openSaves = onNavigateToObjects,
                                            openSource = onOpenAsmSource,
                                        )
                                        RoomPropertiesPanel(
                                            room = roomHeader,
                                            romParser = rp,
                                            editorState = editorState,
                                            modifier = Modifier.fillMaxSize(),
                                            runtimeSnapshot = snapshot,
                                            runtimeNavigation = navigation,
                                            onNavigateToMap = onNavigateToMap,
                                            onNavigateToAsm = onNavigateToAsm,
                                            onNavigateToInitialScrolls = onNavigateToInitialScrolls,
                                            onNavigateToLayoutSource = onNavigateToLayoutSource,
                                            onNavigateToSelectors = onNavigateToSelectors,
                                            onNavigateToFx = onNavigateToFx,
                                            onNavigateToEnemies = onNavigateToEnemies,
                                            onNavigateToDoors = onNavigateToDoors,
                                            onWorkspaceChanged = onWorkspaceChanged,
                                            onReassignRoomArea = onReassignRoomArea,
                                            scrollState = sidebarState.verticalScroll(RoomSidebarTab.ROOM_INFO),
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                "Could not parse room header",
                                                fontSize = fs.detail,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Select a room",
                                            fontSize = fs.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            BOTTOM_TAB_GENERATE -> BiomeGeneratorPanel(
                                editorState = editorState,
                                romParser = romParser,
                                rooms = rooms,
                                modifier = Modifier.fillMaxSize(),
                                scrollState = sidebarState.verticalScroll(RoomSidebarTab.GENERATE),
                            )
                        }
                    }
                }
            }
        },
    )
}
