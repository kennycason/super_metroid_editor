package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.RomParser

enum class MinimapTool { PAINT, EYEDROPPER, FILL, SELECT }

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
    var tool by mutableStateOf(MinimapTool.SELECT)
    var cellSize by mutableStateOf(28f)
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

    /** Floating move buffer: tiles lifted from the map, shown as overlay at (currentX, currentY). */
    var moveBuffer by mutableStateOf<RoomMoveBuffer?>(null)
    val isMovingRoom: Boolean get() = moveBuffer != null

    /** The original ROM data for the current area (before edits). */
    private var romBaseline: MinimapData = MinimapData.empty(0)
    private var loadedParser: RomParser? = null

    fun loadArea(parser: RomParser, area: Int, editorState: EditorState) {
        selectedArea = area
        romBaseline = parser.readMinimapTiles(area)
        mapData = applyProjectEdits(romBaseline, editorState, area)
        stationData = parser.readMapStationData(area)
        undoStack.clear(); redoStack.clear(); pendingStroke = null; moveBuffer = null
        loadedParser = parser
        refreshAreaRooms(parser, area, editorState)
        selectedRoomIndex = -1
    }

    /** Rebuild areaRooms applying any header edits from the project. */
    fun refreshAreaRooms(parser: RomParser, area: Int, editorState: EditorState) {
        areaRooms = RoomRepository().getAllRooms().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val named = RoomInfo.fromRoomInfo(info, room)
            val roomKey = roomId.toString(16).uppercase().padStart(4, '0')
            val roomEdits = editorState.project.rooms[roomKey]
            val hc = roomEdits?.roomHeaderChange
            if (hc != null) {
                named.copy(
                    mapX = hc.mapX ?: named.mapX,
                    mapY = hc.mapY ?: named.mapY,
                    width = hc.width ?: named.width,
                    height = hc.height ?: named.height,
                    area = hc.area ?: named.area,
                )
            } else named
        }.filter { it.area == area }
    }

    private fun roomHexKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    // ─── Room move: buffer-based with Apply/Cancel ───

    /**
     * Start or continue moving the selected room.
     * First arrow press: lift tiles from map into buffer.
     * Subsequent presses: reposition buffer.
     */
    fun moveRoom(dx: Int, dy: Int, editorState: EditorState) {
        val room = selectedRoom ?: return
        val parser = loadedParser ?: return
        val buf = moveBuffer

        if (buf == null || buf.roomId != room.roomId) {
            // Auto-apply any pending move for a different room
            if (buf != null) applyMove(editorState)

            // First move: lift tiles into buffer, clear source in mapData
            undoStack.add(mapData); redoStack.clear()
            val saved = extractRoomTiles(mapData, room.mapX, room.mapY, room.width, room.height)
            mapData = clearRoomTiles(mapData, room.mapX, room.mapY, room.width, room.height)
            val newX = (room.mapX + dx).coerceIn(0, MinimapData.MAP_WIDTH - room.width)
            val newY = (room.mapY + dy).coerceIn(0, MinimapData.MAP_HEIGHT - room.height)
            moveBuffer = RoomMoveBuffer(room.roomId, room.mapX, room.mapY, room.width, room.height, saved, newX, newY)
        } else {
            // Subsequent move: just reposition buffer
            val newX = (buf.currentX + dx).coerceIn(0, MinimapData.MAP_WIDTH - buf.width)
            val newY = (buf.currentY + dy).coerceIn(0, MinimapData.MAP_HEIGHT - buf.height)
            moveBuffer = buf.copy(currentX = newX, currentY = newY)
        }
    }

    /** Apply the buffered move: place tiles at new position, update header. */
    fun applyMove(editorState: EditorState) {
        val buf = moveBuffer ?: return
        val parser = loadedParser ?: return

        // Place tiles — only write non-empty cells to preserve other rooms' tiles
        mapData = placeRoomTilesNonEmpty(mapData, buf.currentX, buf.currentY, buf.width, buf.height, buf.tiles)
        saveEditsToProject(editorState)

        // Update room header mapX/mapY
        val key = roomHexKey(buf.roomId)
        val existing = editorState.project.rooms[key]?.roomHeaderChange
        val change = (existing ?: RoomHeaderChange()).copy(mapX = buf.currentX, mapY = buf.currentY)
        editorState.setRoomHeaderChangeForId(buf.roomId, change)

        refreshAreaRooms(parser, selectedArea, editorState)
        moveBuffer = null
    }

    /** Cancel the buffered move: restore tiles to original position. */
    fun cancelMove(editorState: EditorState) {
        val buf = moveBuffer ?: return
        val parser = loadedParser ?: return

        // Restore mapData from undo stack (before the lift)
        mapData = undoStack.removeLastOrNull() ?: mapData
        saveEditsToProject(editorState)

        refreshAreaRooms(parser, selectedArea, editorState)
        moveBuffer = null
    }

    fun initIfNeeded(parser: RomParser, editorState: EditorState) {
        if (loadedParser !== parser) {
            tileGraphics = parser.readMinimapTileGraphics()
            loadArea(parser, 0, editorState)
        }
        // Always re-sync room list to pick up header changes (outlines reflect current positions)
        refreshAreaRooms(parser, selectedArea, editorState)
    }

    /** Select the room at the given minimap cell, if any. Auto-applies pending move. */
    fun selectRoomAt(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        // Auto-apply any pending move before selecting a different room
        if (moveBuffer != null) applyMove(editorState)
        val idx = areaRooms.indexOfFirst { r ->
            x in r.mapX until (r.mapX + r.width) && y in r.mapY until (r.mapY + r.height)
        }
        selectedRoomIndex = idx
    }

    // ─── Paint tools ───

    fun commitStroke(editorState: EditorState) {
        val pending = pendingStroke ?: return
        undoStack.add(mapData)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear(); mapData = pending; pendingStroke = null
        saveEditsToProject(editorState)
    }

    fun undo(editorState: EditorState) {
        if (moveBuffer != null) { cancelMove(editorState); return }
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

    // ─── Project persistence ───

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

    private fun applyProjectEdits(baseline: MinimapData, editorState: EditorState, area: Int): MinimapData {
        val edits = editorState.project.minimapEdits[area.toString()] ?: return baseline
        var result = baseline
        for (edit in edits) {
            result = result.withTile(edit.x, edit.y, edit.tileWord)
        }
        return result
    }
}

/** Floating move buffer: holds lifted tiles while the user repositions a room on the minimap. */
data class RoomMoveBuffer(
    val roomId: Int,
    val origX: Int, val origY: Int,
    val width: Int, val height: Int,
    val tiles: Array<IntArray>,
    val currentX: Int, val currentY: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomMoveBuffer) return false
        return roomId == other.roomId && origX == other.origX && origY == other.origY &&
            width == other.width && height == other.height && currentX == other.currentX && currentY == other.currentY
    }
    override fun hashCode(): Int = roomId * 31 + currentX * 17 + currentY
}

/** Extract room tiles into a 2D array. */
internal fun extractRoomTiles(data: MinimapData, x: Int, y: Int, w: Int, h: Int): Array<IntArray> =
    Array(h) { ry -> IntArray(w) { rx ->
        val mx = x + rx; val my = y + ry
        if (mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT) data.getTile(mx, my) else 0
    }}

/** Clear tiles within a room's bounds. */
internal fun clearRoomTiles(data: MinimapData, x: Int, y: Int, w: Int, h: Int): MinimapData {
    val tiles = data.tiles.copyOf()
    for (ry in 0 until h) for (rx in 0 until w) {
        val mx = x + rx; val my = y + ry
        if (mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT)
            tiles[my * MinimapData.MAP_WIDTH + mx] = 0
    }
    return data.copy(tiles = tiles)
}

/** Place saved tiles at a new position. Only writes non-empty tiles to preserve underlying data. */
internal fun placeRoomTilesNonEmpty(data: MinimapData, x: Int, y: Int, w: Int, h: Int, saved: Array<IntArray>): MinimapData {
    val tiles = data.tiles.copyOf()
    for (ry in 0 until h) for (rx in 0 until w) {
        val mx = x + rx; val my = y + ry
        val tile = saved[ry][rx]
        if (tile != 0 && mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT)
            tiles[my * MinimapData.MAP_WIDTH + mx] = tile
    }
    return data.copy(tiles = tiles)
}

/** Shift tiles within a room's bounds by (dx, dy). Clears old positions, writes to new. */
internal fun shiftRoomTiles(data: MinimapData, roomX: Int, roomY: Int, roomW: Int, roomH: Int, dx: Int, dy: Int): MinimapData {
    val saved = extractRoomTiles(data, roomX, roomY, roomW, roomH)
    val cleared = clearRoomTiles(data, roomX, roomY, roomW, roomH)
    return placeRoomTilesNonEmpty(cleared, roomX + dx, roomY + dy, roomW, roomH, saved)
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
