package com.supermetroid.editor

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import com.supermetroid.editor.ui.ProjectToolbar
import com.supermetroid.editor.ui.ProjectToolbarActionPadding
import com.supermetroid.editor.ui.ProjectToolbarActionMinWidth
import com.supermetroid.editor.asm.RoomAsmSourceRequest
import com.supermetroid.editor.asm.RoomAsmSourceKind
import com.supermetroid.editor.asm.AsmRoomSourceLink
import com.supermetroid.editor.ui.RoomAsmNavigation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.RomPreferences
import com.supermetroid.editor.ui.OpenProject
import com.supermetroid.editor.ui.ProjectLifecycle
import com.supermetroid.editor.ui.ProjectFileService
import com.supermetroid.editor.ui.NewProjectDialog
import com.supermetroid.editor.ui.LocateProjectRomDialog
import com.supermetroid.editor.ui.pickProjectOrRom
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.WindowConfig
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.asm.AsmEditorTarget
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.AsmPlmSourceCatalog
import com.supermetroid.editor.asm.AsmPlmPlacementTarget
import com.supermetroid.editor.ui.AsmWorkspaceCanvas
import com.supermetroid.editor.ui.AsmWorkspaceSidebar
import com.supermetroid.editor.ui.BossTabCanvas
import com.supermetroid.editor.ui.BossTabSidebar
import com.supermetroid.editor.ui.DraggableDividerVertical
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EditorTheme
import com.supermetroid.editor.ui.EditorThemeState
import com.supermetroid.editor.ui.EnemySpriteCache
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.HexEditorPanel
import com.supermetroid.editor.ui.HexEditorState
import com.supermetroid.editor.ui.EnemyTabCanvas
import com.supermetroid.editor.ui.EnemyTabSidebar
import com.supermetroid.editor.ui.FloatingEmulatorWindow
import com.supermetroid.editor.ui.FontSize
import com.supermetroid.editor.ui.ItemLocationPanel
import com.supermetroid.editor.ui.LocalEditorTheme
import com.supermetroid.editor.ui.LocalSwingWindow
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.ui.MinimapCanvas
import com.supermetroid.editor.ui.MinimapEditorState
import com.supermetroid.editor.ui.MinimapSidebar
import com.supermetroid.editor.ui.NewRoomCreationRequest
import com.supermetroid.editor.ui.PatchEditorCanvas
import com.supermetroid.editor.ui.PatchListPanel
import com.supermetroid.editor.ui.PatternEditorCanvas
import com.supermetroid.editor.ui.RoomsTabSidebar
import com.supermetroid.editor.ui.RoomsSidebarState
import com.supermetroid.editor.ui.projectAsmNavigation
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.SettingsPopup
import com.supermetroid.editor.ui.SoundEditorCanvas
import com.supermetroid.editor.ui.SoundEditorState
import com.supermetroid.editor.ui.SoundListPanel
import com.supermetroid.editor.ui.SpritesTabCanvas
import com.supermetroid.editor.ui.SpritesTabSidebar
import com.supermetroid.editor.ui.TextEditorPreview
import com.supermetroid.editor.ui.TextEditorSidebar
import com.supermetroid.editor.ui.TextEditorState
import com.supermetroid.editor.ui.TilesetCanvas
import com.supermetroid.editor.ui.TilesetEditorState
import com.supermetroid.editor.ui.TilesTabSidebar
import com.supermetroid.editor.ui.ValidationPopup
import com.supermetroid.editor.ui.blockTypeName
import com.supermetroid.editor.ui.materialTypography
import com.supermetroid.editor.ui.oneBasedRoomCoordinate
import com.supermetroid.editor.ui.roomScreenCoordinateForBlock
import com.supermetroid.editor.ui.requestVerticalSelectionFocus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mainLog = KotlinLogging.logger {}

private const val TAB_ROOMS = 0
private const val TAB_ITEMS = 1
private const val TAB_TILES = 2
private const val TAB_PATCHES = 3
private const val TAB_SOUND = 4
private const val TAB_SPRITES = 5
private const val TAB_MAP = 6
private const val TAB_TEXT = 7
private const val TAB_ENEMY = 8
private const val TAB_BOSS = 9
private const val TAB_ASM = 10

private val READ_ONLY_INSPECTABLE_TABS = setOf(
    TAB_ROOMS,
    TAB_ITEMS,
    TAB_TILES,
    TAB_PATCHES,
    TAB_SOUND,
    TAB_SPRITES,
    TAB_MAP,
    TAB_TEXT,
    TAB_ENEMY,
    TAB_BOSS,
    TAB_ASM,
)

private fun isTabAvailableForRom(tab: Int, romReadOnly: Boolean): Boolean =
    !romReadOnly || tab in READ_ONLY_INSPECTABLE_TABS

private fun floatingWindowConfig(windowState: WindowState): WindowConfig? {
    val position = windowState.position
    val size = windowState.size
    if (windowState.placement != WindowPlacement.Floating || !position.isSpecified ||
        !size.width.isSpecified || !size.height.isSpecified
    ) {
        return null
    }
    return WindowConfig(
        x = position.x.value.toInt(),
        y = position.y.value.toInt(),
        width = size.width.value.toInt(),
        height = size.height.value.toInt(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
fun main() = application {
    val scope = rememberCoroutineScope()
    var romParser by remember { mutableStateOf<RomParser?>(null) }
    var romFileName by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    var romLoadInFlight by remember { mutableStateOf(false) }
    var romLoadMessage by remember { mutableStateOf<String?>(null) }
    var romLoadMessageIsError by remember { mutableStateOf(false) }
    val editorState = remember { EditorState() }
    val asmWorkspaceState = remember { AsmWorkspaceState() }
    var showNewProject by remember { mutableStateOf(false) }
    var newProjectRom by remember { mutableStateOf<File?>(null) }
    var missingProjectRom by remember { mutableStateOf<OpenProject?>(null) }
    var projectDialogError by remember { mutableStateOf<String?>(null) }
    var pendingProjectAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestProjectAction(action: () -> Unit) {
        if (editorState.dirty || asmWorkspaceState.unsavedSourceFileIds.isNotEmpty()) pendingProjectAction = action
        else action()
    }

    fun projectPreferenceKey(romPath: String): String =
        ProjectLifecycle.preferenceKey(editorState.project, editorState.projectFilePath, romPath)

    fun pickDefaultRoom(allRooms: List<RoomInfo>, romPath: String): RoomInfo? {
        val romKey = projectPreferenceKey(romPath)
        val lastRoomId = AppConfig.load().lastRoomPerProject[romKey]
        if (lastRoomId != null) {
            val found = allRooms.firstOrNull { it.id == lastRoomId }
            if (found != null) return found
        }
        return allRooms
            .filter { it.handle != "debugRoom" }
            .minByOrNull { it.getRoomIdAsInt() }
    }

    fun saveLastRoom(romPath: String, room: RoomInfo) {
        val romKey = projectPreferenceKey(romPath)
        AppConfig.update {
            copy(lastRoomPerProject = lastRoomPerProject + (romKey to room.id))
        }
    }

    suspend fun loadRomParser(path: String): RomParser = withContext(Dispatchers.IO) {
        RomParser.loadRom(path)
    }

    suspend fun openProjectOrRom(file: File) {
        romLoadInFlight = true
        projectDialogError = null
        try {
            val projectFile = withContext(Dispatchers.IO) { ProjectLifecycle.projectBeside(file) }
            val open = projectFile?.let { withContext(Dispatchers.IO) { OpenProject(it, ProjectFileService.loadProject(it)) } }
            val base = open?.project?.romPath?.let(::File) ?: file
            if (open != null) {
                val problem = withContext(Dispatchers.IO) {
                    runCatching {
                        ProjectLifecycle.verify(open.project, base)
                        require(base.canonicalFile.parentFile == open.file.canonicalFile.parentFile) {
                            "Import the base ROM into this project's folder before continuing."
                        }
                    }.exceptionOrNull()
                }
                if (problem != null) {
                    missingProjectRom = open
                    projectDialogError = problem.message
                    return
                }
            }
            val parser = loadRomParser(base.absolutePath)
            val catalog = withContext(Dispatchers.IO) { parser.roomCatalog }
            if (open == null && catalog.editable) {
                newProjectRom = base
                showNewProject = true
                return
            }
            // Validate project-owned room allocation before replacing the active
            // editor state. A damaged project must not leave its edits paired
            // with the previously open ROM after an unsuccessful OPEN.
            if (open != null && open.project.newRooms.isNotEmpty()) withContext(Dispatchers.IO) {
                val data = parser.copyRomData()
                com.supermetroid.editor.rom.ProjectNewRoomMaterializer(open.project, RomParser(data), data).materialize()
            }
            TilesetProfileCache.invalidate()
            if (open != null && catalog.editable) {
                editorState.initForProject(open.file, open.project.copy(
                    baseRom = open.project.baseRom ?: withContext(Dispatchers.IO) { ProjectLifecycle.identity(base) },
                ))
                romParser = editorState.prepareWorkspaceParser(parser)
                RomPreferences.setLastProjectPath(open.file.absolutePath)
            } else {
                require(open == null) { "This project's ROM cannot be edited. Open the ROM separately for read-only inspection." }
                editorState.initForReadOnlyRom(base.absolutePath)
                romParser = parser
                RomPreferences.clearLastProjectPath()
            }
            rooms = romParser!!.roomCatalog.rooms
            selectedRoom = pickDefaultRoom(rooms, base.absolutePath)
            romFileName = open?.file?.nameWithoutExtension ?: base.nameWithoutExtension
            romLoadMessage = catalog.loadNotice(base.name)
            romLoadMessageIsError = false
            RomPreferences.setLastRomPath(base.absolutePath)
            missingProjectRom = null
            showNewProject = false
        } catch (e: Exception) {
            mainLog.error(e) { "Failed to open project/ROM: ${e.message}" }
            projectDialogError = e.message
            romLoadMessage = e.message ?: "Failed to open project/ROM."
            romLoadMessageIsError = true
        } finally { romLoadInFlight = false }
    }

    LaunchedEffect(Unit) {
        (RomPreferences.getLastProjectPath() ?: RomPreferences.getLastRomPath())?.let { openProjectOrRom(File(it)) }
    }
    
    val appSettings = remember { AppConfig.load() }
    val roomsSidebarState = remember { RoomsSidebarState(appSettings.roomSidebarTab) }
    var showRoomItemNames by remember { mutableStateOf(appSettings.roomEditorShowItemNames) }
    var showRoomMetaNames by remember { mutableStateOf(appSettings.roomEditorShowMetaNames) }
    var highlightRoomItems by remember { mutableStateOf(appSettings.roomEditorHighlightItems) }
    var showRoomEnemyNames by remember { mutableStateOf(appSettings.roomEditorShowEnemyNames) }
    var showRoomFlatSlopeSurfaces by remember { mutableStateOf(appSettings.roomEditorShowFlatSlopeSurfaces) }
    val windowState = rememberWindowState(
        width = appSettings.window.width.dp,
        height = appSettings.window.height.dp,
        position = if (appSettings.window.x >= 0 && appSettings.window.y >= 0)
            WindowPosition(appSettings.window.x.dp, appSettings.window.y.dp)
        else WindowPosition.PlatformDefault
    )
    LaunchedEffect(windowState) {
        snapshotFlow { floatingWindowConfig(windowState) }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { window ->
                delay(400)
                AppConfig.update { copy(window = window) }
            }
    }

    Window(
        onCloseRequest = {
            floatingWindowConfig(windowState)?.let { window ->
                AppConfig.update { copy(window = window) }
            }
            exitApplication()
        },
        state = windowState,
        title = "Super Metroid Editor",
        icon = painterResource("app_icon.png"),
        onPreviewKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.S &&
                (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)) {
                if (asmWorkspaceState.isProjectSourceEditable) {
                    scope.launch {
                        editorState.setProjectRomBuildMode(ProjectRomBuildMode.ASM_SOURCE)
                        if (asmWorkspaceState.saveSource()) {
                            editorState.saveProjectAsync()
                        }
                    }
                } else {
                    val parser = romParser
                    if (parser?.roomCatalog?.editable == true) {
                        scope.launch {
                            if (asmWorkspaceState.saveAllSources()) {
                                if (editorState.project.asmWorkspace.enabled) editorState.saveProjectAsync(parser, true)
                                else editorState.saveProject(parser)
                            }
                        }
                    }
                }
                true
            } else false
        }
    ) {
        val editorThemeState = remember {
            val settings = AppConfig.load()
            val state = EditorThemeState()
            state.theme.value = EditorTheme.entries.find { it.name == settings.theme } ?: EditorTheme.DARK
            state.fontSize.value = FontSize.entries.find { it.name == settings.fontSize } ?: FontSize.MEDIUM
            state
        }
        CompositionLocalProvider(
            LocalSwingWindow provides window,
            LocalEditorTheme provides editorThemeState
        ) {
        MaterialTheme(
            colorScheme = editorThemeState.theme.value.colorScheme,
            typography = editorThemeState.fontSize.value.materialTypography(),
        ) {
            var emulatorEnabled by remember { mutableStateOf(false) }
            val emulatorWorkspaceState = remember { EmulatorWorkspaceState() }
            var hexEditorEnabled by remember { mutableStateOf(false) }
            val hexEditorState = remember { HexEditorState() }
            var settingsOpen by remember { mutableStateOf(false) }
            var validationOpen by remember { mutableStateOf(false) }
            var validationIssues by remember { mutableStateOf<List<RomValidator.Issue>?>(null) }
            var validationTimeMs by remember { mutableStateOf(0L) }
            var romLoadMessageDetailsOpen by remember { mutableStateOf(false) }
            var romLoadMessageDismissedFor by remember { mutableStateOf<String?>(null) }
            val fs = editorThemeState.fontSize.value
            if (showNewProject) NewProjectDialog(newProjectRom, romLoadInFlight, projectDialogError,
                onDismiss = { showNewProject = false; projectDialogError = null },
                onCreate = { name, parent, base -> scope.launch {
                    romLoadInFlight = true
                    projectDialogError = null
                    try {
                        val created = withContext(Dispatchers.IO) { ProjectLifecycle.create(name, parent, base) }
                        openProjectOrRom(created.file)
                    } catch (e: Exception) { projectDialogError = e.message }
                    finally { romLoadInFlight = false }
                } },
            )
            missingProjectRom?.let { open -> LocateProjectRomDialog(open, romLoadInFlight, projectDialogError,
                onDismiss = { missingProjectRom = null; projectDialogError = null },
                onLocate = { base -> scope.launch {
                    romLoadInFlight = true
                    projectDialogError = null
                    try {
                        withContext(Dispatchers.IO) { ProjectLifecycle.importBase(open, base) }
                        openProjectOrRom(open.file)
                    } catch (e: Exception) { projectDialogError = e.message }
                    finally { romLoadInFlight = false }
                } },
            ) }
            pendingProjectAction?.let { action ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingProjectAction = null },
                    title = { Text("Leave unsaved changes?") },
                    text = { Text("Project edits or ASM buffers have not been saved. Cancel to save them first.") },
                    confirmButton = { Button(onClick = { pendingProjectAction = null; action() }) { Text("Continue") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingProjectAction = null }) { Text("Cancel") } },
                )
            }
            val romCatalog = romParser?.roomCatalog
            val romEditable = romCatalog?.editable == true
            val romReadOnly = romCatalog?.readOnly == true
            LaunchedEffect(romLoadMessage) {
                romLoadMessageDetailsOpen = false
            }

            var leftTab by remember { mutableStateOf(TAB_ROOMS) }
            var roomKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
            var itemKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
            var soundKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
            val mainContentFocusRequester = remember { FocusRequester() }
            var selectedSpriteIdx by remember { mutableStateOf(-1) } // -1 = Samus
            val tilesetEditorState = remember { TilesetEditorState() }
            val soundEditorState = remember { SoundEditorState() }
            val minimapEditorState = remember { MinimapEditorState() }
            val textEditorState = remember { TextEditorState() }
            var tilesetSubTab by remember { mutableStateOf(0) } // 0 = Tilesets, 1 = Patterns, 2 = Palette
            val navigationHistory = remember { EditorNavigationHistory<EditorNavigationLocation>() }
            LaunchedEffect(editorState.projectFilePath, editorState.project.romPath) { navigationHistory.clear() }

            fun captureNavigationLocation() = EditorNavigationLocation(
                tab = leftTab,
                roomId = selectedRoom?.getRoomIdAsInt(),
                tilesetSubTab = tilesetSubTab,
                tilesetId = editorState.editorTilesetId,
                spriteIndex = selectedSpriteIdx,
                soundTrackId = soundEditorState.selectedTrackId,
                minimapArea = minimapEditorState.selectedArea,
                minimapRoomId = minimapEditorState.selectedRoom?.roomId,
                textEntryId = textEditorState.selectedEntryId,
                asmLocation = asmWorkspaceState.locationSnapshot(),
            )

            fun refreshCurrentEditorTilesetGrid() {
                tilesetEditorState.refreshGrid(editorState.editorTileGraphics)
            }

            fun restoreNavigationLocation(location: EditorNavigationLocation) {
                val parser = romParser
                if (location.tab == TAB_ROOMS || location.tab == TAB_ITEMS) {
                    selectedRoom = location.roomId?.let { roomId ->
                        rooms.firstOrNull { it.getRoomIdAsInt() == roomId }
                    } ?: selectedRoom
                }
                if (location.tab == TAB_TILES) {
                    tilesetSubTab = location.tilesetSubTab
                    if (location.tilesetId >= 0 && location.tilesetId != editorState.editorTilesetId && parser != null) {
                        scope.launch {
                            val loaded = withContext(Dispatchers.Default) {
                                editorState.loadEditorTileset(location.tilesetId, parser)
                            }
                            if (loaded) refreshCurrentEditorTilesetGrid()
                        }
                    }
                }
                if (location.tab == TAB_SPRITES) selectedSpriteIdx = location.spriteIndex
                if (location.tab == TAB_SOUND) {
                    SpcData.KNOWN_TRACKS.firstOrNull { it.id == location.soundTrackId }
                        ?.let(soundEditorState::selectTrack)
                }
                if (location.tab == TAB_MAP && parser != null) {
                    minimapEditorState.loadArea(
                        parser = parser,
                        area = location.minimapArea,
                        editorState = editorState,
                        selectRoomId = location.minimapRoomId,
                    )
                }
                if (location.tab == TAB_TEXT) textEditorState.select(location.textEntryId)
                if (location.tab == TAB_ASM) {
                    location.asmLocation?.let(asmWorkspaceState::restoreLocation)
                }
                leftTab = location.tab.takeIf { isTabAvailableForRom(it, romReadOnly) } ?: TAB_ROOMS
            }

            fun navigate(action: () -> Unit) {
                val previous = captureNavigationLocation()
                action()
                if (captureNavigationLocation() != previous) {
                    navigationHistory.recordDeparture(previous)
                }
            }

            fun selectRoomFromNavigation(room: RoomInfo) {
                navigate {
                    selectedRoom = room
                    RomPreferences.getLastRomPath()?.let { saveLastRoom(it, room) }
                }
            }

            val roomAsmNavigation = RoomAsmNavigation(editorState, asmWorkspaceState,
                roomName = { selectedRoom?.name ?: "Room ${editorState.currentRoomId.toString(16).uppercase()}" },
                navigate = ::navigate, showAsm = { leftTab = TAB_ASM })

            fun openAsmAddress(snesAddress: Int) {
                if (!editorState.asmNavigationEnabled) return
                if (snesAddress ushr 16 == 0x83) {
                    val doorIndex = editorState.doorEntries.indexOfFirst { it.doorDefPtr == (snesAddress and 0xFFFF) }
                    if (doorIndex >= 0) {
                        roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS, door = doorIndex))
                        return
                    }
                }
                navigate {
                    val managedLine = if (editorState.hasManagedPlms() && snesAddress ushr 16 == 0x8F &&
                        snesAddress and 0xFFFF == editorState.currentPlmSourcePointer()) {
                        editorState.currentPlmStateId()?.let { editorState.asmPlmSourceBridge
                            ?.managedView(editorState.currentRoomId, it)?.document?.lineIndex }
                    } else null
                    val sourceLine = managedLine ?: if (snesAddress ushr 16 == 0x8F) editorState.asmPlmSourceBridge
                        ?.takeIf { snesAddress and 0xFFFF in it.catalog.consumers }
                        ?.sourceLine(snesAddress and 0xFFFF) else null
                    if (sourceLine != null) {
                        asmWorkspaceState.showProjectWorkspace()
                        asmWorkspaceState.openSource("bank_8F.asm", sourceLine)
                    } else asmWorkspaceState.openAddress(snesAddress)
                    leftTab = TAB_ASM
                }
            }

            fun openPlmPlacement(entry: RomParser.PlmEntry) {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PLMS, plm = entry))
            }

            fun openScrollCommands(entry: RomParser.PlmEntry) {
                if (!editorState.asmNavigationEnabled) return
                val stateId = editorState.currentPlmStateId() ?: return
                val target = editorState.asmPlmSourceBridge?.scrollTarget(editorState.currentRoomId, stateId, entry) ?: return
                when (target) {
                    is AsmPlmPlacementTarget.Source -> navigate {
                        asmWorkspaceState.showProjectWorkspace()
                        asmWorkspaceState.openSource("bank_8F.asm", target.lineIndex, columnIndex = target.columnIndex)
                        leftTab = TAB_ASM
                    }
                    is AsmPlmPlacementTarget.Unavailable -> editorState.postStatus(target.reason)
                }
            }

            fun openInitialScrolls() {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.INITIAL_SCROLLS))
            }

            fun openLayoutSource() {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT))
            }

            fun openRoomProperties(stateId: String?) {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES, stateId))
            }

            fun openRoomSelectors(stateId: String?) {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.SELECTORS, stateId))
            }

            fun openFx(stateId: String, door: Int?, sourceDoor: com.supermetroid.editor.data.ProjectSourceDoorReference?) {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.FX, stateId, door = door, sourceDoor = sourceDoor))
            }

            fun openSaveSpawn(entry: RomParser.PlmEntry) {
                roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.SAVE_SPAWN, plm = entry))
            }

            val editorAsmAddressLink = editorState.projectAsmNavigation(::openAsmAddress)
            val editorEnemySectionLink = editorState.projectAsmNavigation<(com.supermetroid.editor.asm.AsmEnemyTuningSection) -> Unit> { section ->
                if (editorState.asmNavigationEnabled) runCatching {
                    val bridge = requireNotNull(editorState.enemyTuningBridge) { "Wait for enemy source indexing." }
                    val (file, line) = bridge.sectionLine(section)
                    navigate {
                        asmWorkspaceState.showProjectWorkspace()
                        asmWorkspaceState.openSource(file, line)
                        leftTab = TAB_ASM
                    }
                }.onFailure { editorState.postStatus("Enemy source unavailable: ${it.message}") }
            }
            val editorPlmPlacementLink = editorState.projectAsmNavigation(::openPlmPlacement)
            val editorScrollCommandsLink = editorState.projectAsmNavigation(::openScrollCommands)
            val editorInitialScrollsLink = editorState.projectAsmNavigation(::openInitialScrolls)
            val editorLayoutSourceLink = editorState.projectAsmNavigation { openLayoutSource() }
            val editorRoomPropertiesLink = editorState.projectAsmNavigation<(String?) -> Unit> { openRoomProperties(it) }
            val editorSelectorsLink = editorState.projectAsmNavigation<(String?) -> Unit> { openRoomSelectors(it) }
            val editorFxLink = editorState.projectAsmNavigation(::openFx)
            val editorSaveSpawnLink = editorState.projectAsmNavigation(::openSaveSpawn)
            fun openEnemySource(entry: RomParser.EnemyEntry?, graphics: Boolean = false, requestedState: String? = null) {
                roomAsmNavigation.open(RoomAsmSourceRequest(
                    if (graphics) RoomAsmSourceKind.ENEMY_GRAPHICS else RoomAsmSourceKind.ENEMIES,
                    requestedState, enemy = entry))
            }
            val editorEnemyPlacementLink = editorState.projectAsmNavigation<(RomParser.EnemyEntry) -> Unit> { openEnemySource(it) }
            val editorEnemiesLink = editorState.projectAsmNavigation<(String, Boolean) -> Unit> { state, graphics -> openEnemySource(null, graphics, state) }
            val editorDoorsLink = editorState.projectAsmNavigation<(Int) -> Unit> { index -> roomAsmNavigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.DOORS, door = index)) }

            fun openAsmEditorTarget(target: AsmEditorTarget) {
                navigate {
                    when (target) {
                        is AsmEditorTarget.Room -> {
                            val roomId = target.resolveRoomId(editorState.project) ?: run {
                                editorState.postStatus("This project room is no longer available. No room was opened.")
                                return@navigate
                            }
                            if (target.layoutId != null || target.stateId != null)
                                editorState.requestLayoutNavigation(roomId, target.layoutId, target.stateId)
                            rooms.firstOrNull { it.getRoomIdAsInt() == roomId }?.let { room ->
                                selectedRoom = room
                                RomPreferences.getLastRomPath()?.let { saveLastRoom(it, room) }
                                leftTab = TAB_ROOMS
                            }
                        }
                        is AsmEditorTarget.Tileset -> {
                            val parser = romParser ?: return@navigate
                            val tilesetId = target.tilesetId ?: editorState.editorTilesetId
                            leftTab = TAB_TILES
                            tilesetSubTab = if (target.palette) 2 else 0
                            scope.launch {
                                val loaded = withContext(Dispatchers.Default) {
                                    editorState.loadEditorTileset(tilesetId, parser)
                                }
                                if (loaded) refreshCurrentEditorTilesetGrid()
                            }
                        }
                        is AsmEditorTarget.Sprite -> {
                            selectedSpriteIdx = target.speciesId?.let { speciesId ->
                                EnemySpriteGraphics.EDITOR_ENEMIES.indexOfFirst { it.speciesId == speciesId }
                                    .takeIf { it >= 0 }
                            } ?: -1
                            leftTab = TAB_SPRITES
                        }
                        is AsmEditorTarget.EnemyTuning -> {
                            com.supermetroid.editor.ui.selectEnemyTuning(target.speciesId, target.section)
                            leftTab = TAB_ENEMY
                        }
                        is AsmEditorTarget.BeamDamage -> {
                            com.supermetroid.editor.ui.selectBeamDamage(target.key)
                            editorState.selectPatch(editorState.findOrCreateConfigPatch("beam_damage").id)
                            leftTab = TAB_PATCHES
                        }
                        is AsmEditorTarget.Sound -> {
                            SpcData.KNOWN_TRACKS.firstOrNull { it.songSet == target.songSet }
                                ?.let(soundEditorState::selectTrack)
                            leftTab = TAB_SOUND
                        }
                        is AsmEditorTarget.Map -> {
                            val parser = romParser ?: return@navigate
                            minimapEditorState.loadArea(
                                parser = parser,
                                area = target.area ?: minimapEditorState.selectedArea,
                                editorState = editorState,
                            )
                            leftTab = TAB_MAP
                        }
                        is AsmEditorTarget.Text -> {
                            textEditorState.select(target.entryId)
                            leftTab = TAB_TEXT
                        }
                    }
                }
            }

            fun reloadPaletteBackedViews() {
                val parser = romParser ?: return
                val id = editorState.editorTilesetId
                scope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        editorState.reloadCurrentRoomTileGraphics(parser)
                        editorState.loadEditorTileset(id, parser)
                    }
                    if (ok) refreshCurrentEditorTilesetGrid()
                }
            }

            LaunchedEffect(Unit) {
                asmWorkspaceState.loadInstalled()
            }
            LaunchedEffect(editorState.projectFilePath, editorState.project.asmWorkspace.enabled) {
                asmWorkspaceState.bindProject(
                    projectFilePath = editorState.projectFilePath,
                    enabled = editorState.project.asmWorkspace.enabled,
                    progressive = true,
                )
            }
            LaunchedEffect(editorState.romBuildInProgress, editorState.projectFilePath) {
                if (!editorState.romBuildInProgress) asmWorkspaceState.refreshBuildReport(validateSources = false)
            }
            LaunchedEffect(asmWorkspaceState.projectWorkspace?.root, romParser) {
                val owner = editorState.project
                val projectPath = editorState.projectFilePath
                editorState.objectPreviewLoading = false
                editorState.detachRoomSourceBridges()
                asmWorkspaceState.sourceSaveParticipant = null
                val projectWorkspace = asmWorkspaceState.projectWorkspace
                val parser = romParser
                if (projectWorkspace != null && parser != null) {
                    editorState.objectPreviewLoading = true
                    val catalog = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { AsmPlmSourceCatalog.load(projectWorkspace.originalDirectory, parser) }
                    }
                    if (editorState.project !== owner || editorState.projectFilePath != projectPath) return@LaunchedEffect
                    catalog.onSuccess {
                        val bridge = editorState.bindRoomSources(it, asmWorkspaceState)
                        bridge.preloadProjections()
                        if (editorState.project !== owner || editorState.projectFilePath != projectPath) return@LaunchedEffect
                        editorState.asmPlmSourceBridge = bridge
                        asmWorkspaceState.sourceSaveParticipant = bridge
                        editorState.refreshLinkedPlms()
                    }.onFailure { editorState.postStatus("Object/source linking unavailable: ${it.message}") }
                    editorState.objectPreviewLoading = false
                }
            }
            LaunchedEffect(
                asmWorkspaceState.sourceBufferRevision, editorState.asmPlmSourceBridge,
                editorState.currentRoomId, editorState.currentStateIndex,
            ) {
                kotlinx.coroutines.delay(120)
                editorState.refreshLinkedPlms()
            }
            LaunchedEffect(romParser) {
                asmWorkspaceState.observeRom(romParser?.copyRomData())
            }
            // Install project-owned sprite bytes before room composition reads the cache.
            remember(romParser, editorState.spriteVersion, editorState.paletteVersion, System.identityHashCode(editorState.project)) {
                EnemySpriteCache.setRomParser(romParser?.let { parser ->
                    runCatching { editorState.nativeSprites.previewParser(parser) }.getOrDefault(parser)
                })
            }
            LaunchedEffect(romReadOnly, leftTab) {
                if (!isTabAvailableForRom(leftTab, romReadOnly)) leftTab = TAB_ROOMS
            }
            LaunchedEffect(leftTab, soundEditorState.isPianoRollOpen) {
                if (leftTab == TAB_ROOMS || leftTab == TAB_ITEMS || (leftTab == TAB_SOUND && !soundEditorState.isPianoRollOpen)) {
                    requestVerticalSelectionFocus(mainContentFocusRequester)
                }
            }

            suspend fun saveAsmForExecution(): Boolean {
                if (!editorState.project.asmWorkspace.enabled) return true
                editorState.setProjectRomBuildMode(ProjectRomBuildMode.ASM_SOURCE)
                if (!asmWorkspaceState.saveAllSources()) return false
                return editorState.saveProjectAsync()
            }

            fun buildAsmProject() {
                val parser = romParser ?: return
                scope.launch {
                    if (saveAsmForExecution()) {
                        val succeeded = asmWorkspaceState.validateProjectBuild {
                            editorState.buildRomPreview(parser)
                        }
                        if (!succeeded && editorState.project.asmWorkspace.enabled &&
                            editorState.project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE
                        ) {
                            navigate {
                                asmWorkspaceState.showBuildFailure()
                                leftTab = TAB_ASM
                            }
                        }
                    }
                }
            }

            fun runProjectInEmulator() {
                scope.launch {
                    if (!saveAsmForExecution()) return@launch
                    emulatorWorkspaceState.requestRun()
                    emulatorEnabled = true
                }
            }

            // Auto-switch to Palette when sampling a color from the tiles canvas.
            val sampledRow = editorState.sampledPaletteRow
            if (sampledRow >= 0 && leftTab == TAB_TILES) tilesetSubTab = 2

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                // Project controls stay left; actions stay right and wrap whole controls.
                ProjectToolbar(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    leadingContent = {
                        Button(
                            enabled = !romLoadInFlight && !editorState.romBuildInProgress && !asmWorkspaceState.previewBusy && !asmWorkspaceState.busy,
                            shape = RoundedCornerShape(6.dp),
                            onClick = { requestProjectAction {
                                projectDialogError = null
                                newProjectRom = null
                                showNewProject = true
                            } },
                        ) { Text("NEW", fontSize = fs.body, softWrap = false) }
                        Button(
                            enabled = !romLoadInFlight && !editorState.romBuildInProgress && !asmWorkspaceState.previewBusy && !asmWorkspaceState.busy,
                            shape = RoundedCornerShape(6.dp),
                            onClick = { requestProjectAction {
                                pickProjectOrRom()?.let { file -> scope.launch { openProjectOrRom(file) } }
                            } },
                        ) { Text(if (romLoadInFlight) "Opening…" else "OPEN", fontSize = fs.body, softWrap = false) }
                        if (romFileName != null) {
                            Text(
                                "Loaded: $romFileName${if (romReadOnly) " (read-only)" else ""}",
                                fontSize = fs.detail,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 360.dp),
                            )
                        }
                    },
                ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    navigationHistory.goBack(captureNavigationLocation())
                                        ?.let(::restoreNavigationLocation)
                                },
                                enabled = navigationHistory.canGoBack,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    navigationHistory.goForward(captureNavigationLocation())
                                        ?.let(::restoreNavigationLocation)
                                },
                                enabled = navigationHistory.canGoForward,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ArrowForward,
                                    contentDescription = "Forward",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (editorState.project.asmWorkspace.enabled) {
                            val buildFailed = asmWorkspaceState.buildReport?.let {
                                !it.succeeded && !it.validationOnly
                            } == true && !asmWorkspaceState.previewBusy && !editorState.romBuildInProgress
                            OutlinedButton(
                                onClick = ::buildAsmProject,
                                modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                                contentPadding = ProjectToolbarActionPadding,
                                enabled = romEditable && !asmWorkspaceState.busy &&
                                    !asmWorkspaceState.previewBusy && !editorState.romBuildInProgress,
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (buildFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                    contentColor = if (buildFailed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                if (asmWorkspaceState.previewBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    when {
                                        asmWorkspaceState.previewBusy -> "BUILDING…"
                                        asmWorkspaceState.sourceBuildRequired -> "BUILD*"
                                        else -> "BUILD"
                                    },
                                    fontSize = fs.detail,
                                    softWrap = false,
                                )
                            }
                        }
                        Button(
                            onClick = ::runProjectInEmulator,
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            enabled = romEditable && !asmWorkspaceState.busy &&
                                !asmWorkspaceState.previewBusy && !editorState.romBuildInProgress,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            if (editorState.romBuildInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (editorState.romBuildInProgress) "RUNNING…" else "RUN", fontSize = fs.detail, softWrap = false)
                        }
                        OutlinedButton(
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            onClick = {
                                if (emulatorEnabled) {
                                    // Closing is a lifecycle boundary: release the current
                                    // session so reopening builds and boots the latest ROM.
                                    emulatorWorkspaceState.disconnectBridge()
                                    emulatorEnabled = false
                                } else {
                                    emulatorEnabled = true
                                }
                            },
                            enabled = !editorState.romBuildInProgress,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (emulatorEnabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Gamepad,
                                contentDescription = "Toggle emulator window",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("EMU", fontSize = fs.detail, softWrap = false)
                        }
                        OutlinedButton(
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            onClick = { hexEditorEnabled = !hexEditorEnabled },
                            enabled = emulatorEnabled,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (hexEditorEnabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Text("HEX", fontSize = fs.detail, softWrap = false)
                        }
                        Box {
                            Button(
                                modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                                contentPadding = ProjectToolbarActionPadding,
                                onClick = {
                                    val parser = romParser
                                    if (parser != null) {
                                        val start = System.currentTimeMillis()
                                        val roomIds = rooms.map { it.getRoomIdAsInt() }
                                        validationIssues = RomValidator.validate(parser, roomIds, editorState.project)
                                        validationTimeMs = System.currentTimeMillis() - start
                                        validationOpen = true
                                    }
                                },
                                enabled = romEditable,
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Validate", fontSize = fs.body, softWrap = false) }
                            if (validationOpen && validationIssues != null) {
                                ValidationPopup(
                                    issues = validationIssues!!,
                                    scanTimeMs = validationTimeMs,
                                    onDismiss = { validationOpen = false }
                                )
                            }
                        }
                        Button(
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            onClick = {
                                scope.launch {
                                    if (asmWorkspaceState.unsavedSourceFileIds.isNotEmpty()) {
                                        editorState.setProjectRomBuildMode(ProjectRomBuildMode.ASM_SOURCE)
                                    }
                                    if (asmWorkspaceState.saveAllSources()) {
                                        val parser = romParser
                                        if (editorState.project.asmWorkspace.enabled) editorState.saveProjectAsync(parser, true)
                                        else editorState.saveProject(parser)
                                    }
                                }
                            },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            val hasUnsavedAsm = asmWorkspaceState.unsavedSourceFileIds.isNotEmpty()
                            Text(if (editorState.dirty || hasUnsavedAsm) "Save*" else "Save", fontSize = fs.body, softWrap = false)
                        }
                        Button(
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            onClick = {
                                romParser?.let { editorState.exportToRom(it) }
                            },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Export ROM", fontSize = fs.body, softWrap = false) }
                        Button(
                            modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                            contentPadding = ProjectToolbarActionPadding,
                            onClick = {
                                romParser?.let { editorState.exportToIps(it) }
                            },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Export IPS", fontSize = fs.body, softWrap = false) }
                        Box {
                            OutlinedButton(
                                onClick = { settingsOpen = !settingsOpen },
                                modifier = Modifier.defaultMinSize(minWidth = ProjectToolbarActionMinWidth),
                                contentPadding = ProjectToolbarActionPadding,
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (settingsOpen) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            if (settingsOpen) {
                                SettingsPopup(
                                    onDismiss = { settingsOpen = false },
                                    emulatorWorkspaceState = emulatorWorkspaceState,
                                    editorState = editorState,
                                    showRoomItemNames = showRoomItemNames,
                                    showRoomMetaNames = showRoomMetaNames,
                                    highlightRoomItems = highlightRoomItems,
                                    showRoomEnemyNames = showRoomEnemyNames,
                                    showRoomFlatSlopeSurfaces = showRoomFlatSlopeSurfaces,
                                    onShowRoomItemNamesChange = { enabled ->
                                        showRoomItemNames = enabled
                                        AppConfig.update { copy(roomEditorShowItemNames = enabled) }
                                    },
                                    onShowRoomMetaNamesChange = { enabled ->
                                        showRoomMetaNames = enabled
                                        AppConfig.update { copy(roomEditorShowMetaNames = enabled) }
                                    },
                                    onHighlightRoomItemsChange = { enabled ->
                                        highlightRoomItems = enabled
                                        AppConfig.update { copy(roomEditorHighlightItems = enabled) }
                                    },
                                    onShowRoomEnemyNamesChange = { enabled ->
                                        showRoomEnemyNames = enabled
                                        AppConfig.update { copy(roomEditorShowEnemyNames = enabled) }
                                    },
                                    onShowRoomFlatSlopeSurfacesChange = { enabled ->
                                        showRoomFlatSlopeSurfaces = enabled
                                        AppConfig.update { copy(roomEditorShowFlatSlopeSurfaces = enabled) }
                                    },
                                    onWindowSizeRequest = { width, height ->
                                        windowState.isMinimized = false
                                        windowState.placement = WindowPlacement.Floating
                                        windowState.size = DpSize(width.dp, height.dp)
                                        AppConfig.update {
                                            copy(window = window.copy(width = width, height = height))
                                        }
                                    },
                                )
                            }
                        }
                }
                romLoadMessage?.takeIf { romLoadMessageDismissedFor != it }?.let { message ->
                    val noticeColor = if (romLoadMessageIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                    val noticeContentColor = if (romLoadMessageIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    val noticeTitle = if (romLoadMessageIsError) {
                        "ROM load issue"
                    } else {
                        "Expanded ROM loaded read-only"
                    }
                    val noticeSummary = message
                        .lineSequence()
                        .filter { it.isNotBlank() && !it.startsWith("Read-only expanded ROM layout loaded") }
                        .firstOrNull()
                        ?: message.lineSequence().firstOrNull().orEmpty()
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = noticeColor,
                            contentColor = noticeContentColor,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        noticeTitle,
                                        fontSize = fs.body,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        noticeSummary,
                                        fontSize = fs.detail,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                TextButton(
                                    onClick = { romLoadMessageDetailsOpen = !romLoadMessageDetailsOpen },
                                ) {
                                    Text(if (romLoadMessageDetailsOpen) "Hide" else "Details", fontSize = fs.detail)
                                }
                                IconButton(
                                    onClick = {
                                        romLoadMessageDismissedFor = message
                                        romLoadMessageDetailsOpen = false
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss ROM compatibility notice",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (romLoadMessageDetailsOpen) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    message,
                                    fontSize = fs.detail,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
                
                // Main content: resizable left column + right canvas
                var leftColumnWidthDp by remember { mutableStateOf(appSettings.mainSidebarWidthDp) }
                var tilesetHeightDp by remember {
                    mutableStateOf(
                        appSettings.sidebarBottomPaneHeightDp.takeIf { it.isFinite() && it > 0f }
                            ?: Float.NaN,
                    )
                }
                var asmProblemsPaneHeightDp by remember {
                    mutableStateOf(appSettings.asmProblemsPaneHeightDp.coerceIn(96f, 420f))
                }
                LaunchedEffect(leftColumnWidthDp, tilesetHeightDp, asmProblemsPaneHeightDp) {
                    delay(300)
                    val persistedBottomHeight = tilesetHeightDp.takeIf { it.isFinite() && it > 0f }
                    AppConfig.update {
                        copy(
                            mainSidebarWidthDp = leftColumnWidthDp,
                            sidebarBottomPaneHeightDp = persistedBottomHeight ?: sidebarBottomPaneHeightDp,
                            asmProblemsPaneHeightDp = asmProblemsPaneHeightDp,
                        )
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(mainContentFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            when (leftTab) {
                                TAB_ROOMS -> if (romReadOnly) {
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> roomKeyboardNavigator?.invoke(-1) ?: false
                                        Key.DirectionDown -> roomKeyboardNavigator?.invoke(1) ?: false
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                                TAB_ITEMS -> when (keyEvent.key) {
                                    Key.DirectionUp -> itemKeyboardNavigator?.invoke(-1) ?: false
                                    Key.DirectionDown -> itemKeyboardNavigator?.invoke(1) ?: false
                                    else -> false
                                }
                                TAB_SOUND -> {
                                    if (soundEditorState.isPianoRollOpen) {
                                        false
                                    } else {
                                        when (keyEvent.key) {
                                            Key.DirectionUp -> soundKeyboardNavigator?.invoke(-1) ?: false
                                            Key.DirectionDown -> soundKeyboardNavigator?.invoke(1) ?: false
                                            else -> false
                                        }
                                    }
                                }
                                else -> false
                            }
                        }
                ) {
                    val maxLeftWidth = maxWidth.value - 100f
                    val windowDensity = LocalDensity.current
                    val emulatorContainerSize = with(windowDensity) {
                        IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                    }
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // ── Left column ──
                        Column(
                            modifier = Modifier
                                .width(leftColumnWidthDp.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                val tabNames = listOf("Rooms", "Items", "Tiles", "Patches", "Sound", "Sprites", "Map", "Text", "Enemy", "Boss", "ASM")
                                tabNames.forEachIndexed { idx, name ->
                                    val selected = leftTab == idx
                                    val tabEnabled = isTabAvailableForRom(idx, romReadOnly)
                                    Text(
                                        text = name,
                                        fontSize = fs.tabLabel,
                                        color = when {
                                            !tabEnabled -> MaterialTheme.colorScheme.outlineVariant
                                            selected -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                        modifier = Modifier
                                            .clickable(enabled = tabEnabled) {
                                                if (leftTab != idx) navigate { leftTab = idx }
                                                if (!romReadOnly && (idx == TAB_PATCHES || idx == TAB_ITEMS)) {
                                                    editorState.seedDefaultPatches()
                                                }
                                            }
                                            .padding(horizontal = 7.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                key(leftTab) {
                                when (leftTab) {
                                    TAB_ROOMS -> {
                                        RoomsTabSidebar(
                                            rooms = rooms,
                                            selectedRoom = selectedRoom,
                                            onRoomSelected = ::selectRoomFromNavigation,
                                            romParser = romParser,
                                            editorState = editorState,
                                            sidebarState = roomsSidebarState,
                                            onCreateRoom = { request ->
                                                runCatching {
                                                    val parser = checkNotNull(romParser) { "Open a ROM first" }
                                                    val (created, workspace) = editorState.createNewRoomWorkspace(request, parser)
                                                    romParser = workspace
                                                    rooms = workspace.roomCatalog.rooms
                                                    selectedRoom = editorState.projectRoomInfos().first { it.handle == created.handle }
                                                }.exceptionOrNull()?.message
                                            },
                                            onDeleteRoom = { roomId ->
                                                runCatching {
                                                    val parser = checkNotNull(romParser) { "Open a ROM first" }
                                                    val oldIndex = rooms.indexOfFirst { it.getRoomIdAsInt() == roomId }
                                                    editorState.deleteProjectRoom(roomId, parser)
                                                    val workspace = editorState.prepareWorkspaceParser()
                                                    romParser = workspace
                                                    rooms = workspace.roomCatalog.rooms
                                                    selectedRoom = rooms.getOrNull(oldIndex.coerceAtMost(rooms.lastIndex))
                                                        ?: rooms.firstOrNull()
                                                }.exceptionOrNull()?.message
                                            },
                                            onWorkspaceChanged = {
                                                val selectedHandle = selectedRoom?.handle
                                                val workspace = editorState.prepareWorkspaceParser()
                                                romParser = workspace
                                                rooms = workspace.roomCatalog.rooms
                                                selectedRoom = rooms.firstOrNull { it.handle == selectedHandle }
                                                    ?: selectedRoom
                                            },
                                            tilesetHeightDp = tilesetHeightDp,
                                            onTilesetHeightChange = { tilesetHeightDp = it },
                                            onSeedPatterns = { editorState.seedBuiltInPatterns(romParser) },
                                            onNavigateToMap = {
                                                val parser = romParser
                                                val roomId = selectedRoom?.getRoomIdAsInt()
                                                navigate {
                                                    if (parser != null && roomId != null) {
                                                        minimapEditorState.openRoom(parser, roomId, editorState)
                                                    }
                                                    leftTab = TAB_MAP
                                                }
                                            },
                                            onNavigateToAsm = editorAsmAddressLink,
                                            onNavigateToInitialScrolls = editorInitialScrollsLink,
                                            onNavigateToLayoutSource = editorLayoutSourceLink,
                                            onNavigateToRoomProperties = editorRoomPropertiesLink,
                                            onNavigateToSelectors = editorSelectorsLink,
                                            onNavigateToFx = editorFxLink,
                                            onNavigateToEnemies = editorEnemiesLink,
                                            onNavigateToDoors = editorDoorsLink,
                                            onReassignRoomArea = { roomId, targetArea ->
                                                romParser?.let { parser ->
                                                    minimapEditorState.initIfNeeded(parser, editorState)
                                                    minimapEditorState.reassignRoomArea(roomId, targetArea, editorState)
                                                }
                                            },
                                            onKeyboardNavigatorChanged = { roomKeyboardNavigator = it },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    TAB_ITEMS -> ItemLocationPanel(
                                        rooms = rooms,
                                        selectedRoom = selectedRoom,
                                        romParser = romParser,
                                        editorState = editorState,
                                        onRoomSelected = ::selectRoomFromNavigation,
                                        modifier = Modifier.fillMaxSize(),
                                        onKeyboardNavigatorChanged = { itemKeyboardNavigator = it },
                                    )
                                    TAB_TILES -> TilesTabSidebar(
                                        tilesetSubTab = tilesetSubTab,
                                        onSubTabChange = { subTab ->
                                            if (tilesetSubTab != subTab) navigate { tilesetSubTab = subTab }
                                        },
                                        romParser = romParser,
                                        editorState = editorState,
                                        tilesetEditorState = tilesetEditorState,
                                        selectedRoom = selectedRoom,
                                        tilesetHeightDp = tilesetHeightDp,
                                        onTilesetHeightChange = { tilesetHeightDp = it },
                                        onSeedPatterns = { editorState.seedBuiltInPatterns(romParser) },
                                        onReloadPaletteBackedViews = ::reloadPaletteBackedViews,
                                        onRefreshTilesetGrid = ::refreshCurrentEditorTilesetGrid,
                                        onTilesetWillSelect = {
                                            navigationHistory.recordDeparture(captureNavigationLocation())
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    TAB_PATCHES -> PatchListPanel(
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_SOUND -> SoundListPanel(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        modifier = Modifier.fillMaxSize(),
                                        onKeyboardNavigatorChanged = { soundKeyboardNavigator = it },
                                        onTrackWillSelect = {
                                            navigationHistory.recordDeparture(captureNavigationLocation())
                                        },
                                    )
                                    TAB_SPRITES -> SpritesTabSidebar(
                                        selectedSpriteIdx = selectedSpriteIdx,
                                        onSelectSprite = { spriteIndex ->
                                            if (selectedSpriteIdx != spriteIndex) navigate { selectedSpriteIdx = spriteIndex }
                                        },
                                        onNavigateToAsm = editorAsmAddressLink,
                                    )
                                    TAB_MAP -> MinimapSidebar(
                                        state = minimapEditorState,
                                        romParser = romParser,
                                        editorState = editorState,
                                        onNavigateToAsm = editorAsmAddressLink,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_TEXT -> TextEditorSidebar(
                                        romParser = romParser,
                                        editorState = editorState,
                                        textEditorState = textEditorState,
                                        onNavigateToAsm = editorAsmAddressLink,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_ENEMY -> EnemyTabSidebar(editorState = editorState,
                                        onNavigateToAsm = editorEnemySectionLink)
                                    TAB_BOSS -> BossTabSidebar(editorState = editorState, romParser = romParser)
                                    TAB_ASM -> AsmWorkspaceSidebar(
                                        state = asmWorkspaceState,
                                        romParser = romParser,
                                        romName = File(editorState.project.romPath).name,
                                        projectFilePath = editorState.projectFilePath,
                                        projectAsmEnabled = editorState.project.asmWorkspace.enabled,
                                        onProjectAsmEnabled = {
                                            editorState.enableProjectAsmWorkspace(AsmReferenceContract.COMMIT)
                                            romParser?.let(editorState::saveProject)
                                        },
                                        projectBuildMode = editorState.project.asmWorkspace.buildMode,
                                        onProjectBuildModeChanged = { mode ->
                                            editorState.setProjectRomBuildMode(mode)
                                            romParser?.let(editorState::saveProject)
                                        },
                                        buildRomPreview = romParser?.let { parser ->
                                            { editorState.buildRomPreview(parser) }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                }
                            }
                        }

                        DraggableDividerVertical(
                            onDelta = { dx ->
                                leftColumnWidthDp = (leftColumnWidthDp + dx).coerceIn(150f, maxLeftWidth)
                            }
                        )

                        // ── Right canvas + status bar ──
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                key(leftTab, tilesetSubTab, selectedSpriteIdx) {
                                when (leftTab) {
                                    TAB_ROOMS, TAB_ITEMS -> {
                                        val snap = emulatorWorkspaceState.snapshot
                                        val samusPos = if (
                                            emulatorEnabled
                                            && emulatorWorkspaceState.followLiveRoom
                                            && snap != null
                                            && snap.samusX != null
                                            && snap.samusY != null
                                            && snap.roomId != null
                                            && selectedRoom != null
                                            && snap.roomId == selectedRoom!!.getRoomIdAsInt()
                                            && !snap.doorTransition
                                        ) {
                                            Pair(snap.samusX!!.toFloat(), snap.samusY!!.toFloat())
                                        } else null

                                        val emuRunning = emulatorEnabled && emulatorWorkspaceState.isRunning
                                        MapCanvas(
                                            room = selectedRoom,
                                            romParser = romParser,
                                            editorState = editorState.takeIf { romEditable },
                                            rooms = rooms,
                                            samusPosition = samusPos,
                                            emulatorConnected = emuRunning,
                                            onMoveSamusHere = if (emuRunning) { x, y ->
                                                scope.launch { emulatorWorkspaceState.moveSamusTo(x, y) }
                                            } else null,
                                            onRoomSelected = ::selectRoomFromNavigation,
                                            onWorkspaceChanged = {
                                                val selectedHandle = selectedRoom?.handle
                                                val workspace = editorState.prepareWorkspaceParser()
                                                romParser = workspace
                                                rooms = workspace.roomCatalog.rooms
                                                selectedRoom = rooms.firstOrNull { it.handle == selectedHandle }
                                                    ?: selectedRoom
                                            },
                                            onNavigateToAsm = editorAsmAddressLink,
                                            onNavigateToDoorArrival = editorState.projectAsmNavigation<(Int, Boolean, Int?) -> Unit> { index, create, identity -> roomAsmNavigation.openArrival(index, create, identity) },
                                            onNavigateToDoorSource = editorDoorsLink,
                                            roomKeyboardNavigationEnabled = leftTab == TAB_ROOMS,
                                            onNavigateToPlmPlacement = editorPlmPlacementLink,
                                            onNavigateToScrollCommands = editorScrollCommandsLink,
                                            onNavigateToSaveSpawn = editorSaveSpawnLink,
                                            onNavigateToEnemyPlacement = editorEnemyPlacementLink,
                                            showItemNames = showRoomItemNames,
                                            showMetaNames = showRoomMetaNames,
                                            highlightItems = highlightRoomItems,
                                            showEnemyNames = showRoomEnemyNames,
                                            showFlatSlopeSurfaces = showRoomFlatSlopeSurfaces,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    TAB_TILES -> {
                                        if (tilesetSubTab == 1) {
                                            PatternEditorCanvas(
                                                editorState = editorState,
                                                romParser = romParser,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            TilesetCanvas(
                                                romParser = romParser,
                                                editorState = editorState,
                                                tilesetEditorState = tilesetEditorState,
                                                onNavigateToAsm = editorAsmAddressLink,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    TAB_PATCHES -> PatchEditorCanvas(
                                        editorState = editorState,
                                        romParser = romParser,
                                        onNavigateToAsm = editorState.projectAsmNavigation { navigate { leftTab = TAB_ASM } },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_SOUND -> SoundEditorCanvas(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        onNavigateToAsm = editorAsmAddressLink,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_SPRITES -> SpritesTabCanvas(
                                        selectedSpriteIdx = selectedSpriteIdx,
                                        romParser = romParser,
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_MAP -> MinimapCanvas(
                                        state = minimapEditorState,
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_TEXT -> TextEditorPreview(
                                        romParser = romParser,
                                        editorState = editorState,
                                        textEditorState = textEditorState,
                                        onNavigateToAsm = editorAsmAddressLink,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_ENEMY -> EnemyTabCanvas(
                                        editorState = editorState,
                                        romParser = romParser,
                                        onNavigateToAsm = editorAsmAddressLink,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    TAB_BOSS -> BossTabCanvas(editorState = editorState, romParser = romParser, modifier = Modifier.fillMaxSize())
                                    TAB_ASM -> AsmWorkspaceCanvas(
                                        state = asmWorkspaceState,
                                        romParser = romParser,
                                        onOpenInEditor = ::openAsmEditorTarget,
                                        onOpenRoomSource = editorState.projectAsmNavigation<(AsmRoomSourceLink) -> Unit> {
                                            roomAsmNavigation.openFromAsm(it)
                                        }.takeIf { !romReadOnly },
                                        onSaveProject = {
                                            editorState.setProjectRomBuildMode(ProjectRomBuildMode.ASM_SOURCE)
                                            editorState.saveProjectAsync()
                                        },
                                        onBuildProject = ::buildAsmProject,
                                        onRunInEmulator = ::runProjectInEmulator,
                                        problemsPaneHeightDp = asmProblemsPaneHeightDp,
                                        onProblemsPaneHeightChange = { asmProblemsPaneHeightDp = it },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                }
                            }

                            // ─── Bottom status bar ───────────────────────
                            run {
                                val es = editorState
                                val statusTs = es.statusMessageTimestamp
                                var showTransient by remember { mutableStateOf(false) }
                                val statusIsError = es.statusIsError
                                LaunchedEffect(statusTs, statusIsError) {
                                    if (statusTs > 0L) {
                                        showTransient = true
                                        // Export/object-edit blockers need enough time to read and act on;
                                        // ordinary confirmations remain brief.
                                        delay(if (statusIsError) 12_000 else 4_000)
                                        showTransient = false
                                    }
                                }

                                // Track emulator status changes for transient display
                                val emuStatus = emulatorWorkspaceState.statusMessage
                                val emuStatusTs = emulatorWorkspaceState.statusMessageTimestamp
                                var showEmuTransient by remember { mutableStateOf(false) }
                                LaunchedEffect(emuStatusTs) {
                                    if (emuStatusTs > 0L && emuStatus.isNotEmpty()) {
                                        showEmuTransient = true
                                        delay(4000)
                                        showEmuTransient = false
                                    }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = RectangleShape,
                                    modifier = Modifier.fillMaxWidth().height(24.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val monoFont = FontFamily.Monospace
                                        if (es.romBuildInProgress) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    strokeWidth = 1.5.dp,
                                                )
                                                Text(
                                                    es.romBuildStatus.ifBlank { "Building ROM…" },
                                                    fontSize = fs.statusBar,
                                                    fontFamily = monoFont,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                )
                                            }
                                        } else if (showTransient && es.statusMessage.isNotEmpty()) {
                                            Text(
                                                es.statusMessage,
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = if (statusIsError) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1
                                            )
                                        } else if (showEmuTransient && emuStatus.isNotEmpty()) {
                                            Text(
                                                "[EMU] $emuStatus",
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        } else if (leftTab == TAB_MAP) {
                                            val ms = minimapEditorState
                                            val mhx = ms.hoverX; val mhy = ms.hoverY
                                            if (mhx in 0 until com.supermetroid.editor.rom.MinimapData.MAP_WIDTH &&
                                                mhy in 0 until com.supermetroid.editor.rom.MinimapData.MAP_HEIGHT) {
                                                val w = ms.displayData.getTile(mhx, mhy)
                                                val idx = com.supermetroid.editor.rom.MinimapData.tileIndex(w)
                                                val pal = com.supermetroid.editor.rom.MinimapData.tilePalette(w)
                                                val tileName = com.supermetroid.editor.rom.MinimapTiles.TILE_NAMES[idx] ?: "0x${idx.toString(16).uppercase()}"
                                                val room = ms.areaRooms.firstOrNull { r ->
                                                    mhx in r.mapX until (r.mapX + r.width) && mhy in r.mapY until (r.mapY + r.height)
                                                }
                                                val roomLabel = if (room != null) "  ${room.name}" else ""
                                                Text(
                                                    "($mhx,$mhy) $tileName  Idx:0x${idx.toString(16).uppercase()} Pal:$pal$roomLabel",
                                                    fontSize = fs.statusBar,
                                                    fontFamily = monoFont,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            } else {
                                                Spacer(Modifier.width(1.dp))
                                            }
                                        } else if ((leftTab == TAB_ROOMS || leftTab == TAB_ITEMS) && es.hoverBlockX >= 0) {
                                            val hx = es.hoverBlockX
                                            val hy = es.hoverBlockY
                                            val hw = es.hoverTileWord
                                            val hIdx = hw and 0x3FF
                                            val hType = (hw shr 12) and 0xF
                                            val doorHint = if (hType == 0x9) {
                                                val bts = es.readBts(hx, hy)
                                                val door = es.doorEntries.getOrNull(bts)
                                                val destName = if (door != null) rooms.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }?.name ?: "" else ""
                                                val modKey = if (System.getProperty("os.name", "").lowercase().contains("mac")) "Cmd" else "Ctrl"
                                                val dest = if (destName.isNotEmpty()) " → $destName" else ""
                                                "  Connection ${bts + 1}${door?.directionName?.let { " travels ${it.lowercase()}" } ?: ""}$dest  ($modKey+click to follow)"
                                            } else ""
                                            Text(
                                                "screen${roomScreenCoordinateForBlock(hx, hy)}  " +
                                                    "tile${oneBasedRoomCoordinate(hx, hy)}  #$hIdx " +
                                                    "0x${hType.toString(16).uppercase()} ${blockTypeName(hType)}$doorHint",
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        } else {
                                            Spacer(Modifier.width(1.dp))
                                        }

                                        val brush = es.brush
                                        val asmSourceStatus = asmWorkspaceState.sourceStatusSummary
                                        if (asmWorkspaceState.sourceIndexFailure != null) {
                                            Text("Index unavailable · Retry", fontSize = fs.statusBar,
                                                color = MaterialTheme.colorScheme.error, maxLines = 1,
                                                modifier = Modifier.clickable { scope.launch { asmWorkspaceState.rebuildSourceIndex() } })
                                        } else if (es.objectPreviewProblem != null) {
                                            Text("Object preview out of date", fontSize = fs.statusBar,
                                                color = MaterialTheme.colorScheme.error, maxLines = 1)
                                        } else if (asmWorkspaceState.sourceIndexingInProgress || es.projectSaveInProgress || es.objectPreviewLoading) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                                Text(
                                                    if (es.objectPreviewLoading) "Loading room objects…"
                                                        else if (asmWorkspaceState.sourceIndexingInProgress) asmWorkspaceState.sourceIndexingStatus
                                                        else "Saving project…",
                                                    fontSize = fs.statusBar,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        } else if (leftTab == TAB_ASM && asmSourceStatus.isNotBlank()) {
                                            Text(
                                                asmSourceStatus,
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        } else if ((leftTab == TAB_ROOMS || leftTab == TAB_ITEMS) && brush != null) {
                                            val bt = brush.blockType
                                            Text(
                                                "${brush.cols}×${brush.rows} #${brush.primaryIndex} 0x${bt.toString(16).uppercase()} ${blockTypeName(bt)}" +
                                                    (if (brush.hFlip) " H" else "") +
                                                    (if (brush.vFlip) " V" else ""),
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Floating emulator overlay ──
                    if (emulatorEnabled) {
                        FloatingEmulatorWindow(
                            workspaceState = emulatorWorkspaceState,
                            editorState = editorState,
                            romParser = romParser,
                            containerSizePx = emulatorContainerSize,
                            density = windowDensity.density,
                            rooms = rooms,
                            onClose = {
                                emulatorWorkspaceState.disconnectBridge()
                                emulatorEnabled = false
                            },
                        )

                        // Sync map editor to emulator room when followLiveRoom is on
                        LaunchedEffect(emulatorWorkspaceState.snapshot?.roomId, emulatorWorkspaceState.followLiveRoom) {
                            emulatorWorkspaceState.roomToFollow(rooms)?.let { room ->
                                selectedRoom = room
                                val romPath = RomPreferences.getLastRomPath()
                                if (romPath != null) saveLastRoom(romPath, room)
                            }
                        }
                    }

                    // ── Floating hex editor overlay ──
                    if (hexEditorEnabled) {
                        HexEditorPanel(
                            state = hexEditorState,
                            emulatorWorkspaceState = emulatorWorkspaceState,
                            containerSizePx = emulatorContainerSize,
                            density = windowDensity.density,
                            onClose = { hexEditorEnabled = false },
                        )
                    }
                }
            }
            }
        }
        }
    }
}
