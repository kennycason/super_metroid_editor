package com.supermetroid.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.RomPreferences
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.WindowConfig
import com.supermetroid.editor.procgen.TilesetProfileCache
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomValidator
import com.supermetroid.editor.ui.AppOutlinedTextField
import com.supermetroid.editor.ui.EditorTheme
import com.supermetroid.editor.ui.EditorThemeState
import com.supermetroid.editor.ui.FontSize
import com.supermetroid.editor.ui.LocalEditorTheme
import com.supermetroid.editor.ui.SettingsPopup
import com.supermetroid.editor.ui.BiomeGeneratorPanel
import com.supermetroid.editor.ui.DraggableDividerHorizontal
import com.supermetroid.editor.ui.DraggableDividerVertical
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.EnemySpriteViewer
import com.supermetroid.editor.ui.FloatingEmulatorWindow
import com.supermetroid.editor.ui.KraidSpriteEditor
import com.supermetroid.editor.ui.LocalSwingWindow
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.ui.ItemLocationPanel
import com.supermetroid.editor.ui.PatchEditorCanvas
import com.supermetroid.editor.ui.PatchListPanel
import com.supermetroid.editor.ui.PatternEditorCanvas
import com.supermetroid.editor.ui.PatternListPanel
import com.supermetroid.editor.ui.PatternThumbnailList
import com.supermetroid.editor.ui.PhantoonSpriteEditor
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.SamusSpriteViewer
import com.supermetroid.editor.ui.RoomPropertiesPanel
import com.supermetroid.editor.ui.MinimapCanvas
import com.supermetroid.editor.ui.MinimapEditorState
import com.supermetroid.editor.ui.MinimapSidebar
import com.supermetroid.editor.ui.TextEditorPreview
import com.supermetroid.editor.ui.TextEditorSidebar
import com.supermetroid.editor.ui.EnemyTabSidebar
import com.supermetroid.editor.ui.EnemyTabCanvas
import com.supermetroid.editor.ui.BossTabSidebar
import com.supermetroid.editor.ui.BossTabCanvas
import com.supermetroid.editor.ui.SoundEditorCanvas
import com.supermetroid.editor.ui.PaletteEditor
import com.supermetroid.editor.ui.SpritePaletteEditor
import com.supermetroid.editor.ui.AreaPaletteEditor
import com.supermetroid.editor.ui.SoundEditorState
import com.supermetroid.editor.ui.SoundListPanel
import com.supermetroid.editor.ui.TilesetCanvas
import com.supermetroid.editor.ui.TilesetEditorState
import com.supermetroid.editor.ui.TilesetListPanel
import com.supermetroid.editor.ui.TilesetPreview
import com.supermetroid.editor.ui.ValidationPopup
import com.supermetroid.editor.ui.blockTypeName
import com.supermetroid.editor.ui.rememberVerticalSelectionFocusRequester
import com.supermetroid.editor.ui.requestVerticalSelectionFocus
import com.supermetroid.editor.ui.verticalSelectionKeyNavigation
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

private const val BOTTOM_TAB_TILESET = 0
private const val BOTTOM_TAB_PATTERNS = 1
private const val BOTTOM_TAB_ROOM_INFO = 2
private const val BOTTOM_TAB_GENERATE = 3

private enum class SpriteSortMode(val label: String) {
    DEFAULT("Default"),
    NAME("A-Z"),
    SPECIES_ID("ID"),
}

private fun spriteMatchesQuery(
    entry: EnemySpriteGraphics.Companion.EnemySpriteEntry,
    query: String
): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true

    val lower = trimmed.lowercase()
    val compact = lower.filter { it.isLetterOrDigit() }.removePrefix("0x")
    val speciesHex = entry.speciesId.toString(16).padStart(4, '0').lowercase()
    return entry.name.lowercase().contains(lower) ||
        entry.category.lowercase().contains(lower) ||
        speciesHex.contains(compact) ||
        "a0$speciesHex".contains(compact)
}

private fun samusMatchesSpriteQuery(query: String): Boolean {
    val lower = query.trim().lowercase()
    return lower.isEmpty() || "samus".contains(lower) || "player".contains(lower)
}

private fun sortSpriteEntries(
    entries: List<EnemySpriteGraphics.Companion.EnemySpriteEntry>,
    mode: SpriteSortMode
): List<EnemySpriteGraphics.Companion.EnemySpriteEntry> =
    when (mode) {
        SpriteSortMode.DEFAULT -> entries
        SpriteSortMode.NAME -> entries.sortedWith(compareBy({ it.name.lowercase() }, { it.speciesId }))
        SpriteSortMode.SPECIES_ID -> entries.sortedBy { it.speciesId }
    }

fun main() = application {
    val roomRepository = remember { RoomRepository() }
    val scope = rememberCoroutineScope()
    var romParser by remember { mutableStateOf<RomParser?>(null) }
    var romFileName by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    var romLoadInFlight by remember { mutableStateOf(false) }
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

    // Load rooms on startup
    LaunchedEffect(Unit) {
        rooms = withContext(Dispatchers.IO) { roomRepository.getAllRooms() }

        // Auto-load requested ROM first, then fall back to last ROM if available.
        val bootRomPath = RomPreferences.getLastRomPath()
        if (bootRomPath != null) {
            try {
                romLoadInFlight = true
                TilesetProfileCache.invalidate()
                romParser = loadRomParser(bootRomPath)
                romFileName = File(bootRomPath).nameWithoutExtension
                RomPreferences.setLastRomPath(bootRomPath)
                editorState.initForRom(bootRomPath)
                if (selectedRoom == null) {
                    selectedRoom = pickDefaultRoom(rooms, bootRomPath)
                }
            } catch (e: Exception) {
                mainLog.error(e) { "Failed to auto-load ROM: ${e.message}" }
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
                editorState.saveProject(romParser)
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
            val fs = editorThemeState.fontSize.value
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
                                            romParser = loadRomParser(file.absolutePath)
                                            romFileName = file.nameWithoutExtension
                                            RomPreferences.setLastRomPath(file.absolutePath)
                                            editorState.initForRom(file.absolutePath)
                                            selectedRoom = pickDefaultRoom(rooms, file.absolutePath)
                                        } catch (e: Exception) {
                                            mainLog.error(e) { "Failed to load selected ROM: ${e.message}" }
                                        } finally {
                                            romLoadInFlight = false
                                        }
                                    }
                                }
                            }
                        ) { Text(if (romLoadInFlight) "Loading ROM..." else "Open ROM...", fontSize = fs.body) }
                        if (romFileName != null) {
                            Text("Loaded: $romFileName", fontSize = fs.detail, color = MaterialTheme.colorScheme.onBackground)
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
                                        val roomIds = RoomRepository().getAllRooms().map { it.getRoomIdAsInt() }
                                        validationIssues = RomValidator.validate(parser, roomIds, editorState.project)
                                        validationTimeMs = System.currentTimeMillis() - start
                                        validationOpen = true
                                    }
                                },
                                enabled = romParser != null,
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
                            enabled = romParser != null,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(if (editorState.dirty) "Save*" else "Save", fontSize = fs.body) }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToRom(it) }
                            },
                            enabled = romParser != null,
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("Export ROM", fontSize = fs.body) }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToIps(it) }
                            },
                            enabled = romParser != null,
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
                
                // Main content: resizable left column + right canvas
                var leftColumnWidthDp by remember { mutableStateOf(330f) }
                var tilesetHeightInitialized by remember { mutableStateOf(false) }
                var tilesetHeightDp by remember { mutableStateOf(400f) }
                var leftTab by remember { mutableStateOf(0) }
                var itemKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
                var soundKeyboardNavigator by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
                val mainContentFocusRequester = remember { FocusRequester() }
                var selectedSpriteIdx by remember { mutableStateOf(-1) } // -1 = Samus
                var spriteSearchQuery by remember { mutableStateOf("") }
                var spriteSortMode by remember { mutableStateOf(SpriteSortMode.DEFAULT) }
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
                var bottomPaneTab by remember { mutableStateOf(BOTTOM_TAB_TILESET) }
                var tilesetSubTab by remember { mutableStateOf(0) } // 0 = Tilesets, 1 = Patterns, 2 = Palette
                // Auto-switch to Palette tab when user samples a tile
                val sampledRow = editorState.sampledPaletteRow
                if (sampledRow >= 0 && leftTab == TAB_TILES) {
                    tilesetSubTab = 2
                }
                LaunchedEffect(leftTab, soundEditorState.isPianoRollOpen) {
                    if (leftTab == TAB_ITEMS || (leftTab == TAB_SOUND && !soundEditorState.isPianoRollOpen)) {
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
                    if (!tilesetHeightInitialized) {
                        tilesetHeightDp = (maxHeight.value * 0.65f).coerceIn(120f, 700f)
                        tilesetHeightInitialized = true
                    }
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
                                    Text(
                                        text = name,
                                        fontSize = fs.tabLabel,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                        modifier = Modifier
                                            .clickable {
                                                leftTab = idx
                                                if (idx == TAB_PATCHES || idx == TAB_ITEMS) editorState.seedDefaultPatches()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            key(leftTab) {
                            when (leftTab) {
                                TAB_ROOMS -> {
                                    // Top: room list
                                    RoomListView(
                                        rooms = rooms,
                                        selectedRoom = selectedRoom,
                                        romParser = romParser,
                                        editorState = editorState,
                                        onRoomSelected = { room ->
                                            selectedRoom = room
                                            val romPath = RomPreferences.getLastRomPath()
                                            if (romPath != null) saveLastRoom(romPath, room)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DraggableDividerHorizontal(
                                        onDelta = { dy ->
                                            tilesetHeightDp = (tilesetHeightDp - dy).coerceIn(120f, 700f)
                                        }
                                    )
                                    // Bottom: sub-tabs [Tileset | Patterns]
                                    Column(
                                        modifier = Modifier.fillMaxWidth().height(tilesetHeightDp.dp)
                                    ) {
                                        TabRow(
                                            selectedTabIndex = bottomPaneTab,
                                            modifier = Modifier.fillMaxWidth().height(26.dp)
                                        ) {
                                            Tab(selected = bottomPaneTab == BOTTOM_TAB_TILESET, onClick = { bottomPaneTab = BOTTOM_TAB_TILESET },
                                                modifier = Modifier.height(26.dp)) {
                                                Text("Tileset", fontSize = fs.tabLabel)
                                            }
                                            Tab(selected = bottomPaneTab == BOTTOM_TAB_PATTERNS, onClick = {
                                                bottomPaneTab = BOTTOM_TAB_PATTERNS
                                                editorState.seedBuiltInPatterns(romParser)
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
                                                room = selectedRoom,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            BOTTOM_TAB_PATTERNS -> PatternThumbnailList(
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            BOTTOM_TAB_ROOM_INFO -> {
                                                val rp = romParser
                                                val sr = selectedRoom
                                                if (rp != null && sr != null) {
                                                    val roomHeader = remember(sr) { rp.readRoomHeader(sr.getRoomIdAsInt()) }
                                                    if (roomHeader != null) {
                                                        RoomPropertiesPanel(
                                                            room = roomHeader,
                                                            romParser = rp,
                                                            editorState = editorState,
                                                            modifier = Modifier.fillMaxSize(),
                                                            onNavigateToMap = { leftTab = TAB_MAP },
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
                                                editorState = editorState,
                                                romParser = romParser,
                                                rooms = rooms,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        }
                                    }
                                }
                                TAB_ITEMS -> {
                                    ItemLocationPanel(
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
                                }
                                TAB_TILES -> {
                                    // Tilesets tab: sub-tabs [Tilesets | Patterns | Palette]
                                    TabRow(
                                        selectedTabIndex = tilesetSubTab,
                                        modifier = Modifier.fillMaxWidth().height(26.dp)
                                    ) {
                                        Tab(selected = tilesetSubTab == 0, onClick = { tilesetSubTab = 0 },
                                            modifier = Modifier.height(26.dp)) {
                                            Text("Tilesets", fontSize = fs.tabLabel)
                                        }
                                        Tab(selected = tilesetSubTab == 1, onClick = {
                                            tilesetSubTab = 1
                                            editorState.seedBuiltInPatterns(romParser)
                                        }, modifier = Modifier.height(26.dp)) {
                                            Text("Patterns", fontSize = fs.tabLabel)
                                        }
                                        Tab(selected = tilesetSubTab == 2, onClick = { tilesetSubTab = 2 },
                                            modifier = Modifier.height(26.dp)) {
                                            Text("Palette", fontSize = fs.tabLabel)
                                        }
                                    }
                                    key(tilesetSubTab) {
                                    when (tilesetSubTab) {
                                        0 -> TilesetListPanel(
                                            romParser = romParser,
                                            editorState = editorState,
                                            tilesetEditorState = tilesetEditorState,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        1 -> {
                                            PatternListPanel(
                                                editorState = editorState,
                                                modifier = Modifier.weight(1f)
                                            )
                                            DraggableDividerHorizontal(
                                                onDelta = { dy ->
                                                    tilesetHeightDp = (tilesetHeightDp - dy).coerceIn(120f, 700f)
                                                }
                                            )
                                            TilesetPreview(
                                                room = selectedRoom,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(tilesetHeightDp.dp)
                                            )
                                        }
                                        2 -> {
                                            // Palette tab: sub-selector for Environment vs Samus/Beams
                                            var paletteCategory by remember { mutableStateOf(0) } // 0=Environment, 1=Samus/Beams, 2=Area
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    for ((idx, label) in listOf("Environment", "Samus / Beams", "Area").withIndex()) {
                                                        val selected = paletteCategory == idx
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = if (selected) MaterialTheme.colorScheme.primary
                                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                            modifier = Modifier.clickable { paletteCategory = idx }
                                                        ) {
                                                            Text(
                                                                label,
                                                                fontSize = 10.sp,
                                                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                                            onPaletteSaved = {
                                                                currentTilesetId?.let { editorState.savePaletteOverride(it) }
                                                            },
                                                            onPaletteReset = {
                                                                if (currentTilesetId != null) {
                                                                    if (editorState.resetPaletteOverride(currentTilesetId)) {
                                                                        reloadPaletteBackedViews()
                                                                    }
                                                                }
                                                            },
                                                            onRefreshNeeded = {
                                                                refreshCurrentEditorTilesetGrid()
                                                                val clearedEffect = currentTilesetId
                                                                    ?.let { editorState.clearPaletteEffect("tileset:$it") }
                                                                    ?: false
                                                                if (!clearedEffect) {
                                                                    editorState.paletteVersion++
                                                                }
                                                            },
                                                            onColorSelected = { row, col ->
                                                                editorState.sampledPaletteRow = row
                                                                editorState.sampledPaletteCol = col
                                                            },
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    1 -> {
                                                        SpritePaletteEditor(
                                                            romParser = romParser,
                                                            editorState = editorState,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    2 -> {
                                                        AreaPaletteEditor(
                                                            romParser = romParser,
                                                            editorState = editorState,
                                                            onCurrentTilesetPaletteChanged = { reloadCurrentTileset ->
                                                                if (reloadCurrentTileset) {
                                                                    reloadPaletteBackedViews()
                                                                } else {
                                                                    refreshCurrentEditorTilesetGrid()
                                                                }
                                                            },
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    }
                                }
                                TAB_PATCHES -> {
                                    PatchListPanel(
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                TAB_SOUND -> {
                                    SoundListPanel(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        modifier = Modifier.fillMaxSize(),
                                        onKeyboardNavigatorChanged = { soundKeyboardNavigator = it },
                                    )
                                }
                                TAB_SPRITES -> {
                                    val entries = EnemySpriteGraphics.EDITOR_ENEMIES
                                    val spriteNavigationFocusRequester = rememberVerticalSelectionFocusRequester(
                                        requestFocusKey = leftTab
                                    )
                                    val indexBySpecies = remember(entries) {
                                        entries.mapIndexed { index, entry -> entry.speciesId to index }.toMap()
                                    }
                                    val categories = remember(entries) { entries.map { it.category }.distinct() }
                                    val showSamus = samusMatchesSpriteQuery(spriteSearchQuery)
                                    val visibleGroups = remember(entries, categories, spriteSearchQuery, spriteSortMode) {
                                        val filtered = entries.filter { spriteMatchesQuery(it, spriteSearchQuery) }
                                        categories.mapNotNull { category ->
                                            val items = sortSpriteEntries(
                                                filtered.filter { it.category == category },
                                                spriteSortMode
                                            )
                                            if (items.isEmpty()) null else category to items
                                        }
                                    }
                                    val visibleSelectionKeys = remember(showSamus, visibleGroups, indexBySpecies) {
                                        buildList {
                                            if (showSamus) add(-1)
                                            visibleGroups.forEach { (_, items) ->
                                                items.forEach { entry ->
                                                    indexBySpecies[entry.speciesId]?.let(::add)
                                                }
                                            }
                                        }
                                    }
                                    val selectedVisibleIndex = visibleSelectionKeys.indexOf(selectedSpriteIdx)
                                    LaunchedEffect(leftTab, visibleSelectionKeys) {
                                        if (
                                            leftTab == TAB_SPRITES &&
                                            visibleSelectionKeys.isNotEmpty() &&
                                            selectedSpriteIdx !in visibleSelectionKeys
                                        ) {
                                            selectedSpriteIdx = visibleSelectionKeys.first()
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(8.dp)
                                            .verticalSelectionKeyNavigation(
                                                focusRequester = spriteNavigationFocusRequester,
                                                itemCount = visibleSelectionKeys.size,
                                                selectedIndex = selectedVisibleIndex,
                                                onSelectIndex = { index ->
                                                    visibleSelectionKeys.getOrNull(index)?.let { selectedSpriteIdx = it }
                                                }
                                            )
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        AppOutlinedTextField(
                                            value = spriteSearchQuery,
                                            onValueChange = { spriteSearchQuery = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = "Search sprites",
                                            singleLine = true,
                                            fontSize = fs.body
                                        )
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            SpriteSortMode.values().forEach { mode ->
                                                val selected = spriteSortMode == mode
                                                val colors = if (selected) {
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                } else {
                                                    ButtonDefaults.outlinedButtonColors()
                                                }
                                                val buttonModifier = Modifier.height(28.dp)
                                                val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                                if (selected) {
                                                    Button(
                                                        onClick = {
                                                            requestVerticalSelectionFocus(spriteNavigationFocusRequester)
                                                            spriteSortMode = mode
                                                        },
                                                        modifier = buttonModifier,
                                                        contentPadding = contentPadding,
                                                        colors = colors
                                                    ) {
                                                        Text(mode.label, fontSize = fs.detail)
                                                    }
                                                } else {
                                                    OutlinedButton(
                                                        onClick = {
                                                            requestVerticalSelectionFocus(spriteNavigationFocusRequester)
                                                            spriteSortMode = mode
                                                        },
                                                        modifier = buttonModifier,
                                                        contentPadding = contentPadding,
                                                        colors = colors
                                                    ) {
                                                        Text(mode.label, fontSize = fs.detail)
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            "${visibleSelectionKeys.size}/${entries.size + 1} sprites",
                                            fontSize = fs.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(4.dp))

                                        // Samus at the top
                                        if (showSamus) {
                                            Text("Player", fontSize = fs.body,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(2.dp))
                                            Surface(
                                                modifier = Modifier.fillMaxWidth()
                                                    .clickable {
                                                        requestVerticalSelectionFocus(spriteNavigationFocusRequester)
                                                        selectedSpriteIdx = -1
                                                    },
                                                color = if (selectedSpriteIdx == -1) MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("Samus", fontSize = fs.body,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                    color = if (selectedSpriteIdx == -1) MaterialTheme.colorScheme.onPrimaryContainer
                                                            else MaterialTheme.colorScheme.onSurface)
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }

                                        if (!showSamus && visibleGroups.isEmpty()) {
                                            Text("No sprites match this search.", fontSize = fs.body,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        for ((category, items) in visibleGroups) {
                                            Text(category, fontSize = fs.body,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(2.dp))
                                            for (entry in items) {
                                                val idx = indexBySpecies[entry.speciesId] ?: continue
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .clickable {
                                                            requestVerticalSelectionFocus(spriteNavigationFocusRequester)
                                                            selectedSpriteIdx = idx
                                                        },
                                                    color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.primaryContainer
                                                            else MaterialTheme.colorScheme.surface,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(entry.name, fontSize = fs.body,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                        color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.onPrimaryContainer
                                                                else MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
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
                                            editorState = editorState,
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
                                    TAB_SPRITES -> {
                                        if (selectedSpriteIdx == -1) {
                                            SamusSpriteViewer(
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                        val entries = com.supermetroid.editor.rom.EnemySpriteGraphics.EDITOR_ENEMIES
                                        val selected = entries.getOrNull(selectedSpriteIdx) ?: entries.first()
                                        if (selected.speciesId == 0xE4BF) {
                                            PhantoonSpriteEditor(
                                                editorState = editorState,
                                                romParser = romParser,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else if (selected.speciesId == 0xE2BF) {
                                            KraidSpriteEditor(
                                                editorState = editorState,
                                                romParser = romParser,
                                                showOamComponents = true,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            EnemySpriteViewer(
                                                entry = selected,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        } // else (enemy sprites)
                                    }
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
