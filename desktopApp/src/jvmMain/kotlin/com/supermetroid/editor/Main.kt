package com.supermetroid.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.RomPreferences
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.WindowConfig
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.ui.BossTabCanvas
import com.supermetroid.editor.ui.BossTabSidebar
import com.supermetroid.editor.ui.DraggableDividerVertical
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EditorTheme
import com.supermetroid.editor.ui.EditorThemeState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
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
import com.supermetroid.editor.ui.PatchEditorCanvas
import com.supermetroid.editor.ui.PatchListPanel
import com.supermetroid.editor.ui.PatternEditorCanvas
import com.supermetroid.editor.ui.RoomsTabSidebar
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.SettingsPopup
import com.supermetroid.editor.ui.SoundEditorCanvas
import com.supermetroid.editor.ui.SoundEditorState
import com.supermetroid.editor.ui.SoundListPanel
import com.supermetroid.editor.ui.SpritesTabCanvas
import com.supermetroid.editor.ui.SpritesTabSidebar
import com.supermetroid.editor.ui.TextEditorPreview
import com.supermetroid.editor.ui.TextEditorSidebar
import com.supermetroid.editor.ui.TilesetCanvas
import com.supermetroid.editor.ui.TilesetEditorState
import com.supermetroid.editor.ui.TilesTabSidebar
import com.supermetroid.editor.ui.ValidationPopup
import com.supermetroid.editor.ui.blockTypeName
import com.supermetroid.editor.ui.requestVerticalSelectionFocus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
)

private fun isTabAvailableForRom(tab: Int, romReadOnly: Boolean): Boolean =
    !romReadOnly || tab in READ_ONLY_INSPECTABLE_TABS


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

    fun pickDefaultRoom(allRooms: List<RoomInfo>, romPath: String): RoomInfo? {
        val romKey = File(romPath).name
        val lastRoomId = AppConfig.load().lastRoomPerRom[romKey]
        if (lastRoomId != null) {
            val found = allRooms.firstOrNull { it.id == lastRoomId }
            if (found != null) return found
        }
        return allRooms
            .filter { it.handle != "debugRoom" }
            .minByOrNull { it.getRoomIdAsInt() }
    }

    fun saveLastRoom(romPath: String, room: RoomInfo) {
        val romKey = File(romPath).name
        AppConfig.update {
            copy(lastRoomPerRom = lastRoomPerRom + (romKey to room.id))
        }
    }

    suspend fun loadRomParser(path: String): RomParser = withContext(Dispatchers.IO) {
        RomParser.loadRom(path)
    }

    // Load requested ROM on startup.
    LaunchedEffect(Unit) {
        // Auto-load requested ROM first, then fall back to last ROM if available.
        val bootRomPath = RomPreferences.getLastRomPath()
        if (bootRomPath != null) {
            try {
                romLoadInFlight = true
                TilesetProfileCache.invalidate()
                val parser = loadRomParser(bootRomPath)
                val catalog = parser.roomCatalog
                romParser = parser
                rooms = catalog.rooms
                romFileName = File(bootRomPath).nameWithoutExtension
                romLoadMessage = catalog.loadNotice(File(bootRomPath).name)
                romLoadMessageIsError = false
                RomPreferences.setLastRomPath(bootRomPath)
                if (catalog.editable) {
                    editorState.initForRom(bootRomPath)
                } else {
                    editorState.initForReadOnlyRom(bootRomPath)
                }
                if (selectedRoom == null) {
                    selectedRoom = pickDefaultRoom(rooms, bootRomPath)
                }
            } catch (e: Exception) {
                mainLog.error(e) { "Failed to auto-load ROM: ${e.message}" }
                romLoadMessage = e.message ?: "Failed to auto-load ROM."
                romLoadMessageIsError = true
                romParser = null
                rooms = emptyList()
                selectedRoom = null
            } finally {
                romLoadInFlight = false
            }
        }
    }
    
    val appSettings = remember { AppConfig.load() }
    var showRoomItemNames by remember { mutableStateOf(appSettings.roomEditorShowItemNames) }
    var showRoomEnemyNames by remember { mutableStateOf(appSettings.roomEditorShowEnemyNames) }
    var showRoomFlatSlopeSurfaces by remember { mutableStateOf(appSettings.roomEditorShowFlatSlopeSurfaces) }
    val windowState = rememberWindowState(
        width = appSettings.window.width.dp,
        height = appSettings.window.height.dp,
        position = if (appSettings.window.x >= 0 && appSettings.window.y >= 0)
            WindowPosition(appSettings.window.x.dp, appSettings.window.y.dp)
        else WindowPosition.PlatformDefault
    )

    Window(
        onCloseRequest = {
            AppConfig.update {
                copy(window = WindowConfig(
                    x = windowState.position.x.value.toInt(),
                    y = windowState.position.y.value.toInt(),
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt()
                ))
            }
            exitApplication()
        },
        state = windowState,
        title = "Super Metroid Editor",
        icon = painterResource("app_icon.png"),
        onPreviewKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.S &&
                (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)) {
                val parser = romParser
                if (parser?.roomCatalog?.editable == true) {
                    editorState.saveProject(parser)
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
        MaterialTheme(colorScheme = editorThemeState.theme.value.colorScheme) {
            var emulatorEnabled by remember { mutableStateOf(false) }
            val emulatorWorkspaceState = remember { EmulatorWorkspaceState() }
            var settingsOpen by remember { mutableStateOf(false) }
            var validationOpen by remember { mutableStateOf(false) }
            var validationIssues by remember { mutableStateOf<List<RomValidator.Issue>?>(null) }
            var validationTimeMs by remember { mutableStateOf(0L) }
            var romLoadMessageDetailsOpen by remember { mutableStateOf(false) }
            var romLoadMessageDismissedFor by remember { mutableStateOf<String?>(null) }
            val fs = editorThemeState.fontSize.value
            val romCatalog = romParser?.roomCatalog
            val romEditable = romCatalog?.editable == true
            val romReadOnly = romCatalog?.readOnly == true
            LaunchedEffect(romLoadMessage) {
                romLoadMessageDetailsOpen = false
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                // Top bar: Open ROM + status (left) | Save + Export (right)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = !romLoadInFlight,
                            shape = RoundedCornerShape(6.dp),
                            onClick = {
                                val fileDialog = FileDialog(null as Frame?, "Open Super Metroid ROM", FileDialog.LOAD)
                                fileDialog.setFilenameFilter { _, name ->
                                    name.endsWith(".smc", ignoreCase = true) || 
                                    name.endsWith(".sfc", ignoreCase = true)
                                }
                                fileDialog.isVisible = true
                                val selectedFile = fileDialog.file
                                if (selectedFile != null) {
                                    val file = File(fileDialog.directory, selectedFile)
                                    scope.launch {
                                        romLoadInFlight = true
                                        try {
                                            TilesetProfileCache.invalidate()
                                            val parser = loadRomParser(file.absolutePath)
                                            val catalog = parser.roomCatalog
                                            romParser = parser
                                            rooms = catalog.rooms
                                            romFileName = file.nameWithoutExtension
                                            romLoadMessage = catalog.loadNotice(file.name)
                                            romLoadMessageIsError = false
                                            RomPreferences.setLastRomPath(file.absolutePath)
                                            if (catalog.editable) {
                                                editorState.initForRom(file.absolutePath)
                                            } else {
                                                editorState.initForReadOnlyRom(file.absolutePath)
                                            }
                                            selectedRoom = pickDefaultRoom(rooms, file.absolutePath)
                                        } catch (e: Exception) {
                                            mainLog.error(e) { "Failed to load selected ROM: ${e.message}" }
                                            romParser = null
                                            romFileName = null
                                            rooms = emptyList()
                                            selectedRoom = null
                                            romLoadMessage = e.message ?: "Failed to load selected ROM."
                                            romLoadMessageIsError = true
                                        } finally {
                                            romLoadInFlight = false
                                        }
                                    }
                                }
                            }
                        ) { Text(if (romLoadInFlight) "Loading ROM..." else "Open ROM...", fontSize = fs.body) }
                        if (romFileName != null) {
                            Text(
                                "Loaded: $romFileName${if (romReadOnly) " (read-only)" else ""}",
                                fontSize = fs.detail,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Right side: EMU toggle + Save + Export
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { emulatorEnabled = !emulatorEnabled },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (emulatorEnabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Gamepad,
                                contentDescription = "Toggle emulator",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("EMU", fontSize = fs.detail)
                        }
                        Box {
                            Button(
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
                            ) { Text("Validate", fontSize = fs.body) }
                            if (validationOpen && validationIssues != null) {
                                ValidationPopup(
                                    issues = validationIssues!!,
                                    scanTimeMs = validationTimeMs,
                                    onDismiss = { validationOpen = false }
                                )
                            }
                        }
                        Button(
                            onClick = { editorState.saveProject(romParser) },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(if (editorState.dirty) "Save*" else "Save", fontSize = fs.body) }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToRom(it) }
                            },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Export ROM", fontSize = fs.body) }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToIps(it) }
                            },
                            enabled = romEditable,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Export IPS", fontSize = fs.body) }
                        Box {
                            OutlinedButton(
                                onClick = { settingsOpen = !settingsOpen },
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
                                    showRoomEnemyNames = showRoomEnemyNames,
                                    showRoomFlatSlopeSurfaces = showRoomFlatSlopeSurfaces,
                                    onShowRoomItemNamesChange = { enabled ->
                                        showRoomItemNames = enabled
                                        AppConfig.update { copy(roomEditorShowItemNames = enabled) }
                                    },
                                    onShowRoomEnemyNamesChange = { enabled ->
                                        showRoomEnemyNames = enabled
                                        AppConfig.update { copy(roomEditorShowEnemyNames = enabled) }
                                    },
                                    onShowRoomFlatSlopeSurfacesChange = { enabled ->
                                        showRoomFlatSlopeSurfaces = enabled
                                        AppConfig.update { copy(roomEditorShowFlatSlopeSurfaces = enabled) }
                                    },
                                )
                            }
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
                var leftColumnWidthDp by remember { mutableStateOf(330f) }
                var tilesetHeightDp by remember { mutableStateOf(Float.NaN) }
                var leftTab by remember { mutableStateOf(0) }
                LaunchedEffect(romReadOnly, leftTab) {
                    if (!isTabAvailableForRom(leftTab, romReadOnly)) leftTab = TAB_ROOMS
                }
                var roomKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
                var itemKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
                var soundKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
                val mainContentFocusRequester = remember { FocusRequester() }
                var selectedSpriteIdx by remember { mutableStateOf(-1) } // -1 = Samus
                val tilesetEditorState = remember { TilesetEditorState() }
                fun refreshCurrentEditorTilesetGrid() {
                    tilesetEditorState.refreshGrid(editorState.editorTileGraphics)
                }
                fun reloadPaletteBackedViews() {
                    val parser = romParser ?: return
                    val id = editorState.editorTilesetId
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            editorState.reloadCurrentRoomTileGraphics(parser)
                            editorState.loadEditorTileset(id, parser)
                        }
                        if (ok) {
                            refreshCurrentEditorTilesetGrid()
                        }
                    }
                }
                val soundEditorState = remember { SoundEditorState() }
                val minimapEditorState = remember { MinimapEditorState() }
                var tilesetSubTab by remember { mutableStateOf(0) } // 0 = Tilesets, 1 = Patterns, 2 = Palette
                // Auto-switch to Palette tab when user samples a tile
                val sampledRow = editorState.sampledPaletteRow
                if (sampledRow >= 0 && leftTab == TAB_TILES) {
                    tilesetSubTab = 2
                }
                LaunchedEffect(leftTab, soundEditorState.isPianoRollOpen) {
                    if (leftTab == TAB_ROOMS || leftTab == TAB_ITEMS || (leftTab == TAB_SOUND && !soundEditorState.isPianoRollOpen)) {
                        requestVerticalSelectionFocus(mainContentFocusRequester)
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
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                val tabNames = listOf("Rooms", "Items", "Tiles", "Patches", "Sound", "Sprites", "Map", "Text", "Enemy", "Boss")
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
                                                leftTab = idx
                                                if (!romReadOnly && (idx == TAB_PATCHES || idx == TAB_ITEMS)) {
                                                    editorState.seedDefaultPatches()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                                            onRoomSelected = { room ->
                                                selectedRoom = room
                                                val romPath = RomPreferences.getLastRomPath()
                                                if (romPath != null) saveLastRoom(romPath, room)
                                            },
                                            romParser = romParser,
                                            editorState = editorState,
                                            tilesetHeightDp = tilesetHeightDp,
                                            onTilesetHeightChange = { tilesetHeightDp = it },
                                            onSeedPatterns = { editorState.seedBuiltInPatterns(romParser) },
                                            onNavigateToMap = { leftTab = TAB_MAP },
                                            onKeyboardNavigatorChanged = { roomKeyboardNavigator = it },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    TAB_ITEMS -> ItemLocationPanel(
                                        rooms = rooms,
                                        selectedRoom = selectedRoom,
                                        romParser = romParser,
                                        editorState = editorState,
                                        onRoomSelected = { room ->
                                            selectedRoom = room
                                            val romPath = RomPreferences.getLastRomPath()
                                            if (romPath != null) saveLastRoom(romPath, room)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        onKeyboardNavigatorChanged = { itemKeyboardNavigator = it },
                                    )
                                    TAB_TILES -> TilesTabSidebar(
                                        tilesetSubTab = tilesetSubTab,
                                        onSubTabChange = { tilesetSubTab = it },
                                        romParser = romParser,
                                        editorState = editorState,
                                        tilesetEditorState = tilesetEditorState,
                                        selectedRoom = selectedRoom,
                                        tilesetHeightDp = tilesetHeightDp,
                                        onTilesetHeightChange = { tilesetHeightDp = it },
                                        onSeedPatterns = { editorState.seedBuiltInPatterns(romParser) },
                                        onReloadPaletteBackedViews = ::reloadPaletteBackedViews,
                                        onRefreshTilesetGrid = ::refreshCurrentEditorTilesetGrid,
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
                                    )
                                    TAB_SPRITES -> SpritesTabSidebar(
                                        selectedSpriteIdx = selectedSpriteIdx,
                                        onSelectSprite = { selectedSpriteIdx = it },
                                    )
                                    TAB_MAP -> MinimapSidebar(
                                        state = minimapEditorState,
                                        romParser = romParser,
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_TEXT -> TextEditorSidebar(
                                        romParser = romParser,
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_ENEMY -> EnemyTabSidebar(editorState = editorState, romParser = romParser)
                                    TAB_BOSS -> BossTabSidebar(editorState = editorState, romParser = romParser)
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
                                            onRoomSelected = { r ->
                                                selectedRoom = r
                                                val romPath = RomPreferences.getLastRomPath()
                                                if (romPath != null) saveLastRoom(romPath, r)
                                            },
                                            roomKeyboardNavigationEnabled = leftTab == TAB_ROOMS,
                                            showItemNames = showRoomItemNames,
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
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    TAB_PATCHES -> PatchEditorCanvas(
                                        editorState = editorState,
                                        romParser = romParser,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_SOUND -> SoundEditorCanvas(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
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
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    TAB_ENEMY -> EnemyTabCanvas(editorState = editorState, romParser = romParser, modifier = Modifier.fillMaxSize())
                                    TAB_BOSS -> BossTabCanvas(editorState = editorState, romParser = romParser, modifier = Modifier.fillMaxSize())
                                }
                                }
                            }

                            // ─── Bottom status bar ───────────────────────
                            run {
                                val es = editorState
                                val statusTs = es.statusMessageTimestamp
                                var showTransient by remember { mutableStateOf(false) }
                                LaunchedEffect(statusTs) {
                                    if (statusTs > 0L) {
                                        showTransient = true
                                        delay(4000)
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
                                        if (showTransient && es.statusMessage.isNotEmpty()) {
                                            Text(
                                                es.statusMessage,
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            val chunkX = hx / 16
                                            val chunkY = hy / 16
                                            val doorHint = if (hType == 0x9) {
                                                val bts = es.readBts(hx, hy)
                                                val door = es.doorEntries.getOrNull(bts)
                                                val destName = if (door != null) rooms.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }?.name ?: "" else ""
                                                val modKey = if (System.getProperty("os.name", "").lowercase().contains("mac")) "Cmd" else "Ctrl"
                                                val dest = if (destName.isNotEmpty()) " → $destName" else ""
                                                "  Door[$bts]${door?.directionName?.let { " $it" } ?: ""}$dest  ($modKey+click to follow)"
                                            } else ""
                                            Text(
                                                "chunk($chunkX,$chunkY)  tile($hx,$hy)  #$hIdx 0x${hType.toString(16).uppercase()} ${blockTypeName(hType)}$doorHint",
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        } else {
                                            Spacer(Modifier.width(1.dp))
                                        }

                                        val brush = es.brush
                                        if ((leftTab == TAB_ROOMS || leftTab == TAB_ITEMS) && brush != null) {
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
                            rooms = rooms,
                            onClose = { emulatorEnabled = false },
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
                }
            }
            }
        }
        }
    }
}
