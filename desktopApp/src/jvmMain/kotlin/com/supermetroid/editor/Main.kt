package com.supermetroid.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
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
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorTheme
import com.supermetroid.editor.ui.EditorThemeState
import com.supermetroid.editor.ui.FontSize
import com.supermetroid.editor.ui.LocalEditorTheme
import com.supermetroid.editor.ui.SettingsPopup
import com.supermetroid.editor.ui.DraggableDividerHorizontal
import com.supermetroid.editor.ui.DraggableDividerVertical
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.EnemySpriteViewer
import com.supermetroid.editor.ui.FloatingEmulatorWindow
import com.supermetroid.editor.ui.KraidSpriteEditor
import com.supermetroid.editor.ui.LocalSwingWindow
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.ui.PatchEditorCanvas
import com.supermetroid.editor.ui.PatchListPanel
import com.supermetroid.editor.ui.PatternEditorCanvas
import com.supermetroid.editor.ui.PatternListPanel
import com.supermetroid.editor.ui.PatternThumbnailList
import com.supermetroid.editor.ui.PhantoonSpriteEditor
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.SamusSpriteViewer
import com.supermetroid.editor.ui.RoomPropertiesPanel
import com.supermetroid.editor.ui.SoundEditorCanvas
import com.supermetroid.editor.ui.PaletteEditor
import com.supermetroid.editor.ui.SoundEditorState
import com.supermetroid.editor.ui.SoundListPanel
import com.supermetroid.editor.ui.TilesetCanvas
import com.supermetroid.editor.ui.TilesetEditorState
import com.supermetroid.editor.ui.TilesetListPanel
import com.supermetroid.editor.ui.TilesetPreview
import com.supermetroid.editor.ui.blockTypeName
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                romParser = loadRomParser(bootRomPath)
                romFileName = File(bootRomPath).nameWithoutExtension
                RomPreferences.setLastRomPath(bootRomPath)
                editorState.initForRom(bootRomPath)
                if (selectedRoom == null) {
                    selectedRoom = pickDefaultRoom(rooms, bootRomPath)
                }
            } catch (e: Exception) {
                println("Failed to auto-load ROM: ${e.message}")
            } finally {
                romLoadInFlight = false
            }
        }
    }
    
    val appSettings = remember { AppConfig.load() }
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
                                            romParser = loadRomParser(file.absolutePath)
                                            romFileName = file.nameWithoutExtension
                                            RomPreferences.setLastRomPath(file.absolutePath)
                                            editorState.initForRom(file.absolutePath)
                                            selectedRoom = pickDefaultRoom(rooms, file.absolutePath)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
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
                                )
                            }
                        }
                    }
                }
                
                // Main content: resizable left column + right canvas
                var leftColumnWidthDp by remember { mutableStateOf(330f) }
                var tilesetHeightDp by remember { mutableStateOf(400f) }
                var leftTab by remember { mutableStateOf(0) }
                var selectedSpriteIdx by remember { mutableStateOf(-1) } // -1 = Samus
                val tilesetEditorState = remember { TilesetEditorState() }
                val soundEditorState = remember { SoundEditorState() }
                var bottomPaneTab by remember { mutableStateOf(0) } // 0 = Tileset, 1 = Patterns (in Rooms bottom pane)
                var tilesetSubTab by remember { mutableStateOf(0) } // 0 = Tilesets, 1 = Patterns, 2 = Palette
                // Auto-switch to Palette tab when user samples a tile
                val sampledRow = editorState.sampledPaletteRow
                if (sampledRow >= 0 && leftTab == 1) {
                    tilesetSubTab = 2
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
                            TabRow(
                                selectedTabIndex = leftTab,
                                modifier = Modifier.fillMaxWidth().height(32.dp)
                            ) {
                                Tab(selected = leftTab == 0, onClick = { leftTab = 0 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Rooms", fontSize = fs.tabLabel)
                                }
                                Tab(selected = leftTab == 1, onClick = { leftTab = 1 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Tiles", fontSize = fs.tabLabel)
                                }
                                Tab(selected = leftTab == 2, onClick = {
                                    leftTab = 2
                                    editorState.seedDefaultPatches()
                                }, modifier = Modifier.height(32.dp)) {
                                    Text("Patches", fontSize = fs.tabLabel)
                                }
                                Tab(selected = leftTab == 3, onClick = { leftTab = 3 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Sound", fontSize = fs.tabLabel)
                                }
                                Tab(selected = leftTab == 4, onClick = { leftTab = 4 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Sprites", fontSize = fs.tabLabel)
                                }
                            }

                            key(leftTab) {
                            when (leftTab) {
                                0 -> {
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
                                            Tab(selected = bottomPaneTab == 0, onClick = { bottomPaneTab = 0 },
                                                modifier = Modifier.height(26.dp)) {
                                                Text("Tileset", fontSize = fs.tabLabel)
                                            }
                                            Tab(selected = bottomPaneTab == 1, onClick = {
                                                bottomPaneTab = 1
                                                editorState.seedBuiltInPatterns(romParser)
                                            }, modifier = Modifier.height(26.dp)) {
                                                Text("Patterns", fontSize = fs.tabLabel)
                                            }
                                            Tab(selected = bottomPaneTab == 2, onClick = { bottomPaneTab = 2 },
                                                modifier = Modifier.height(26.dp)) {
                                                Text("Room Info", fontSize = fs.tabLabel)
                                            }
                                        }
                                        key(bottomPaneTab) {
                                        when (bottomPaneTab) {
                                            0 -> TilesetPreview(
                                                room = selectedRoom,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            1 -> PatternThumbnailList(
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            2 -> {
                                                val rp = romParser
                                                val sr = selectedRoom
                                                if (rp != null && sr != null) {
                                                    val roomHeader = remember(sr) { rp.readRoomHeader(sr.getRoomIdAsInt()) }
                                                    if (roomHeader != null) {
                                                        RoomPropertiesPanel(
                                                            room = roomHeader,
                                                            romParser = rp,
                                                            editorState = editorState,
                                                            modifier = Modifier.fillMaxSize()
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
                                        }
                                        }
                                    }
                                }
                                1 -> {
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
                                            // Use observable editorTilesetId so Compose recomposes when tileset changes
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
                                                        editorState.resetPaletteOverride(currentTilesetId)
                                                        // Reload tileset to restore ROM palette
                                                        editorState.editorTileGraphics?.invalidateCache()
                                                        editorState.editorTileGraphics?.loadTileset(currentTilesetId)
                                                        editorState.applyCustomGfxToTileGraphics(
                                                            editorState.editorTileGraphics!!, currentTilesetId
                                                        )
                                                        tilesetEditorState.refreshGrid(editorState.editorTileGraphics)
                                                    }
                                                },
                                                onRefreshNeeded = {
                                                    // Trigger tileset grid + pixel editor re-render
                                                    tilesetEditorState.refreshGrid(editorState.editorTileGraphics)
                                                    editorState.paletteVersion++
                                                },
                                                onColorSelected = { row, col ->
                                                    editorState.sampledPaletteRow = row
                                                    editorState.sampledPaletteCol = col
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    }
                                }
                                2 -> {
                                    PatchListPanel(
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                3 -> {
                                    SoundListPanel(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                4 -> {
                                    val entries = com.supermetroid.editor.rom.EnemySpriteGraphics.EDITOR_ENEMIES
                                    val grouped = entries.groupBy { it.category }
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(8.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Samus at the top
                                        Text("Player", fontSize = fs.body,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(2.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable { selectedSpriteIdx = -1 },
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

                                        for ((category, items) in grouped) {
                                            Text(category, fontSize = fs.body,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(2.dp))
                                            for (entry in items) {
                                                val idx = entries.indexOf(entry)
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .clickable { selectedSpriteIdx = idx },
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
                                    0 -> MapCanvas(
                                        room = selectedRoom,
                                        romParser = romParser,
                                        editorState = editorState,
                                        rooms = rooms,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    1 -> {
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
                                    2 -> PatchEditorCanvas(
                                        editorState = editorState,
                                        romParser = romParser,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    3 -> SoundEditorCanvas(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    4 -> {
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
                                        } else if (leftTab == 0 && es.hoverBlockX >= 0) {
                                            val hx = es.hoverBlockX
                                            val hy = es.hoverBlockY
                                            val hw = es.hoverTileWord
                                            val hIdx = hw and 0x3FF
                                            val hType = (hw shr 12) and 0xF
                                            val chunkX = hx / 16
                                            val chunkY = hy / 16
                                            Text(
                                                "chunk($chunkX,$chunkY)  tile($hx,$hy)  #$hIdx 0x${hType.toString(16).uppercase()} ${blockTypeName(hType)}",
                                                fontSize = fs.statusBar,
                                                fontFamily = monoFont,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        } else {
                                            Spacer(Modifier.width(1.dp))
                                        }

                                        val brush = es.brush
                                        if (leftTab == 0 && brush != null) {
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
                            onClose = { emulatorEnabled = false },
                        )
                    }
                }
            }
            }
        }
        }
    }
}
