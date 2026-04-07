package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.RomParser

enum class MinimapTool { PAINT, EYEDROPPER, FILL }

/**
 * Shared state for the minimap editor.
 *
 * Edits are tracked as sparse tile changes and saved to the project JSON
 * via EditorState (same pattern as room tile edits). ROM data is never
 * modified directly — edits are applied on top of ROM data for display,
 * and written to ROM only on export.
 */
class MinimapEditorState {
    var selectedArea by mutableStateOf(0)
    var mapData by mutableStateOf(MinimapData.empty(0))
    var stationData by mutableStateOf(MapStationData.empty(0))
    var selectedTile by mutableStateOf(0x1B)
    var selectedPalette by mutableStateOf(0)
    var tool by mutableStateOf(MinimapTool.PAINT)
    var cellSize by mutableStateOf(48f)
    var showGrid by mutableStateOf(true)
    var showRoomOutlines by mutableStateOf(false)
    var showRoomTiles by mutableStateOf(false)
    var showStationOverlay by mutableStateOf(false)
    var showPixelView by mutableStateOf(true)
    var hoverX by mutableStateOf(-1)
    var hoverY by mutableStateOf(-1)
    var areaRooms by mutableStateOf<List<Room>>(emptyList())
    var selectedRoomIndex by mutableStateOf(-1)

    /** The currently selected room (null if none selected). */
    val selectedRoom: Room? get() = areaRooms.getOrNull(selectedRoomIndex)

    /** Cached 2bpp tile graphics: 256 tiles, each IntArray(64) of pixel values 0-3. */
    var tileGraphics by mutableStateOf<Array<IntArray>?>(null)

    val undoStack = mutableStateListOf<MinimapData>()
    val redoStack = mutableStateListOf<MinimapData>()
    var pendingStroke by mutableStateOf<MinimapData?>(null)

    /** The original ROM data for the current area (before edits). */
    private var romBaseline: MinimapData = MinimapData.empty(0)
    private var loadedParser: RomParser? = null

    fun loadArea(parser: RomParser, area: Int, editorState: EditorState) {
        selectedArea = area
        romBaseline = parser.readMinimapTiles(area)
        mapData = applyProjectEdits(romBaseline, editorState, area)
        stationData = parser.readMapStationData(area)
        undoStack.clear(); redoStack.clear(); pendingStroke = null
        loadedParser = parser
        refreshAreaRooms(parser, area, editorState)
        selectedRoomIndex = -1
    }

    /** Rebuild areaRooms applying any header edits from the project. */
    fun refreshAreaRooms(parser: RomParser, area: Int, editorState: EditorState) {
        areaRooms = RoomRepository().getAllRooms().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            // Apply header edits if present
            val roomKey = roomId.toString(16).uppercase().padStart(4, '0')
            val roomEdits = editorState.project.rooms[roomKey]
            val hc = roomEdits?.roomHeaderChange
            if (hc != null) {
                room.copy(
                    mapX = hc.mapX ?: room.mapX,
                    mapY = hc.mapY ?: room.mapY,
                    width = hc.width ?: room.width,
                    height = hc.height ?: room.height,
                    area = hc.area ?: room.area,
                )
            } else room
        }.filter { it.area == area }
    }

    private fun roomHexKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    /** Update mapX/mapY for the selected room, persisting the change. */
    fun moveRoom(dx: Int, dy: Int, editorState: EditorState) {
        val room = selectedRoom ?: return
        val parser = loadedParser ?: return
        val newX = (room.mapX + dx).coerceIn(0, MinimapData.MAP_WIDTH - room.width)
        val newY = (room.mapY + dy).coerceIn(0, MinimapData.MAP_HEIGHT - room.height)
        if (newX == room.mapX && newY == room.mapY) return
        val key = roomHexKey(room.roomId)
        val existing = editorState.project.rooms[key]?.roomHeaderChange
        val change = (existing ?: RoomHeaderChange()).copy(mapX = newX, mapY = newY)
        editorState.setRoomHeaderChangeForId(room.roomId, change)
        refreshAreaRooms(parser, selectedArea, editorState)
    }

    /** Set mapX/mapY for the selected room to absolute values. */
    fun setRoomPosition(x: Int, y: Int, editorState: EditorState) {
        val room = selectedRoom ?: return
        val parser = loadedParser ?: return
        val clampedX = x.coerceIn(0, MinimapData.MAP_WIDTH - 1)
        val clampedY = y.coerceIn(0, MinimapData.MAP_HEIGHT - 1)
        val key = roomHexKey(room.roomId)
        val existing = editorState.project.rooms[key]?.roomHeaderChange
        val change = (existing ?: RoomHeaderChange()).copy(mapX = clampedX, mapY = clampedY)
        editorState.setRoomHeaderChangeForId(room.roomId, change)
        refreshAreaRooms(parser, selectedArea, editorState)
    }

    fun initIfNeeded(parser: RomParser, editorState: EditorState) {
        if (loadedParser !== parser) {
            tileGraphics = parser.readMinimapTileGraphics()
            loadArea(parser, 0, editorState)
        }
    }

    fun commitStroke(editorState: EditorState) {
        val pending = pendingStroke ?: return
        undoStack.add(mapData)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear(); mapData = pending; pendingStroke = null
        saveEditsToProject(editorState)
    }

    fun undo(editorState: EditorState) {
        if (undoStack.isEmpty()) return
        redoStack.add(mapData); mapData = undoStack.removeLast(); pendingStroke = null
        saveEditsToProject(editorState)
    }

    fun redo(editorState: EditorState) {
        if (redoStack.isEmpty()) return
        undoStack.add(mapData); mapData = redoStack.removeLast(); pendingStroke = null
        saveEditsToProject(editorState)
    }

    fun paintTile(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = MinimapData.makeTileWord(selectedTile, selectedPalette)
        undoStack.add(mapData); redoStack.clear()
        mapData = mapData.withTile(x, y, word)
        saveEditsToProject(editorState)
    }

    fun sampleTile(x: Int, y: Int) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = mapData.getTile(x, y)
        selectedTile = MinimapData.tileIndex(word)
        selectedPalette = MinimapData.tilePalette(word)
    }

    fun fillTile(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = MinimapData.makeTileWord(selectedTile, selectedPalette)
        val target = mapData.getTile(x, y)
        if (target != word) {
            undoStack.add(mapData); redoStack.clear()
            mapData = floodFillMinimap(mapData, x, y, target, word)
            saveEditsToProject(editorState)
        }
    }

    val displayData: MinimapData get() = pendingStroke ?: mapData

    /** Compute sparse diff between current mapData and ROM baseline, store in project. */
    private fun saveEditsToProject(editorState: EditorState) {
        val key = selectedArea.toString()
        val edits = mutableListOf<MinimapTileEdit>()
        for (y in 0 until MinimapData.MAP_HEIGHT) {
            for (x in 0 until MinimapData.MAP_WIDTH) {
                val current = mapData.getTile(x, y)
                val original = romBaseline.getTile(x, y)
                if (current != original) {
                    edits.add(MinimapTileEdit(x, y, current))
                }
            }
        }
        if (edits.isEmpty()) {
            editorState.project.minimapEdits.remove(key)
        } else {
            editorState.project.minimapEdits[key] = edits.toMutableList()
        }
        editorState.markDirty()
    }

    /** Apply project edits on top of ROM baseline data. */
    private fun applyProjectEdits(baseline: MinimapData, editorState: EditorState, area: Int): MinimapData {
        val edits = editorState.project.minimapEdits[area.toString()] ?: return baseline
        var result = baseline
        for (edit in edits) {
            result = result.withTile(edit.x, edit.y, edit.tileWord)
        }
        return result
    }
}

internal fun floodFillMinimap(data: MinimapData, startX: Int, startY: Int, target: Int, replacement: Int): MinimapData {
    val tiles = data.tiles.copyOf()
    val queue = ArrayDeque<Pair<Int, Int>>()
    queue.add(startX to startY)
    val visited = mutableSetOf<Pair<Int, Int>>()
    while (queue.isNotEmpty()) {
        val (x, y) = queue.removeFirst()
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) continue
        val key = x to y
        if (key in visited) continue
        visited.add(key)
        if (tiles[y * MinimapData.MAP_WIDTH + x] != target) continue
        tiles[y * MinimapData.MAP_WIDTH + x] = replacement
        queue.add(x - 1 to y); queue.add(x + 1 to y); queue.add(x to y - 1); queue.add(x to y + 1)
    }
    return data.copy(tiles = tiles)
}
