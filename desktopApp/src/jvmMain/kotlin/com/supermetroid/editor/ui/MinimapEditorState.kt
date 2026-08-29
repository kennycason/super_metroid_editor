package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.MapStationTileEdit
import com.supermetroid.editor.data.MinimapTileEdit
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MinimapTiles
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.RomParser

enum class MinimapTool { SELECT, PAINT, FILL, ERASE, EYEDROPPER, REVEAL }

private enum class RoomPointerMoveMode { PRIMARY_DRAG, MIDDLE_DRAG }

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
    var selectedHFlip by mutableStateOf(false)
    var selectedVFlip by mutableStateOf(false)
    var tool by mutableStateOf(MinimapTool.SELECT)
        private set
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
        private set

    /** The currently selected room (null if none selected). */
    val selectedRoom: Room? get() = areaRooms.getOrNull(selectedRoomIndex)

    /** Cached 4bpp tile graphics: 256 tiles, each IntArray(64) of pixel values 0-15. */
    var tileGraphics by mutableStateOf<Array<IntArray>?>(null)

    internal val undoStack = mutableStateListOf<MinimapProjectSnapshot>()
    internal val redoStack = mutableStateListOf<MinimapProjectSnapshot>()
    var pendingStroke by mutableStateOf<MinimapData?>(null)

    /** Floating move buffer: tiles lifted from the map, shown as overlay at (currentX, currentY). */
    var moveBuffer by mutableStateOf<RoomMoveBuffer?>(null)
    val isMovingRoom: Boolean get() = moveBuffer != null
    private var pointerMoveMode by mutableStateOf<RoomPointerMoveMode?>(null)
    private var pointerGrabOffsetX: Int = 0
    private var pointerGrabOffsetY: Int = 0
    internal val isPrimaryDraggingRoom: Boolean get() = pointerMoveMode == RoomPointerMoveMode.PRIMARY_DRAG
    internal val isMiddleDraggingRoom: Boolean get() = pointerMoveMode == RoomPointerMoveMode.MIDDLE_DRAG
    internal val isPointerDraggingRoom: Boolean get() = pointerMoveMode != null

    private var loadedParser: RomParser? = null
    private var observedEditorEditVersion: Int = -1
    var areaReassignmentError by mutableStateOf<String?>(null)
        private set

    fun dismissAreaReassignmentError() {
        areaReassignmentError = null
    }

    fun loadArea(
        parser: RomParser,
        area: Int,
        editorState: EditorState,
        resetHistory: Boolean = false,
        selectRoomId: Int? = null,
    ) {
        // loadArea is also the entry point for Room Info's "Edit on Map" link.
        // That path can run before initIfNeeded, so initialize the graphics here
        // rather than relying on the minimap tab having been opened previously.
        if (loadedParser !== parser || tileGraphics == null) {
            tileGraphics = parser.readMinimapTileGraphics()
        }
        selectedArea = area
        mapData = effectiveMinimapData(parser, editorState.project, area)
        stationData = effectiveMapStationData(parser, editorState.project, area)
        if (resetHistory) {
            undoStack.clear()
            redoStack.clear()
        }
        pendingStroke = null
        moveBuffer = null
        clearPointerMoveMode()
        loadedParser = parser
        refreshAreaRooms(parser, area, editorState)
        selectedRoomIndex = selectRoomId?.let { id -> areaRooms.indexOfFirst { it.roomId == id } } ?: -1
    }

    fun openRoom(parser: RomParser, roomId: Int, editorState: EditorState) {
        clearHistoryAfterExternalEdit(editorState)
        val room = parser.readRoomHeader(roomId)?.let(editorState::applyHeaderChanges) ?: return
        loadArea(parser, room.area, editorState, selectRoomId = roomId)
        observedEditorEditVersion = editorState.editVersion
    }

    /** Rebuild areaRooms applying any header edits from the project. */
    fun refreshAreaRooms(parser: RomParser, area: Int, editorState: EditorState) {
        val selectedId = selectedRoom?.roomId
        areaRooms = parser.roomCatalog.rooms.mapNotNull { info ->
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
        selectedRoomIndex = selectedId?.let { id -> areaRooms.indexOfFirst { it.roomId == id } } ?: -1
    }

    private fun roomHexKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    private fun clearPointerMoveMode() {
        pointerMoveMode = null
        pointerGrabOffsetX = 0
        pointerGrabOffsetY = 0
    }

    private fun roomIndexAt(x: Int, y: Int): Int = areaRooms.indexOfFirst { room ->
        x in room.mapX until (room.mapX + room.width) &&
            y in room.mapY until (room.mapY + room.height)
    }

    private fun liftRoom(room: Room, targetX: Int = room.mapX, targetY: Int = room.mapY) {
        val ownershipMask = roomMapOwnershipMask(room, areaRooms)
        val saved = extractRoomTiles(mapData, room.mapX, room.mapY, room.width, room.height, ownershipMask)
        val savedReveal = extractMapStationRect(
            stationData,
            room.mapX,
            room.mapY,
            room.width,
            room.height,
            ownershipMask,
        )
        mapData = clearRoomTiles(mapData, room.mapX, room.mapY, room.width, room.height, ownershipMask)
        stationData = clearMapStationRect(
            stationData,
            room.mapX,
            room.mapY,
            room.width,
            room.height,
            ownershipMask,
        )
        moveBuffer = RoomMoveBuffer(
            roomId = room.roomId,
            origX = room.mapX,
            origY = room.mapY,
            width = room.width,
            height = room.height,
            tiles = saved,
            revealed = savedReveal,
            currentX = targetX.coerceIn(0, MinimapData.MAP_WIDTH - room.width),
            currentY = targetY.coerceIn(0, MinimapData.MAP_HEIGHT - room.height),
        )
    }

    // ─── Room move: buffer-based with Apply/Cancel ───

    /**
     * Start or continue moving the selected room.
     * First arrow press: lift tiles from map into buffer.
     * Subsequent presses: reposition buffer.
     */
    fun moveRoom(dx: Int, dy: Int, editorState: EditorState) {
        val room = selectedRoom ?: return
        if (loadedParser == null) return
        val buf = moveBuffer

        if (buf == null || buf.roomId != room.roomId) {
            // Resolve any pending move for a different room before lifting a
            // second room. A rejected move must be restored, never replaced.
            if (buf != null && !applyMove(editorState)) cancelMove(editorState)

            // First move: lift tiles into a preview buffer. History is recorded on Apply.
            liftRoom(room, room.mapX + dx, room.mapY + dy)
        } else {
            // Subsequent move: just reposition buffer
            val newX = (buf.currentX + dx).coerceIn(0, MinimapData.MAP_WIDTH - buf.width)
            val newY = (buf.currentY + dy).coerceIn(0, MinimapData.MAP_HEIGHT - buf.height)
            moveBuffer = buf.copy(currentX = newX, currentY = newY)
        }
    }

    private fun canPlaceMoveBuffer(buffer: RoomMoveBuffer): Boolean {
        val overlapsRoom = areaRooms.any { room ->
            room.roomId != buffer.roomId && rectanglesOverlap(
                buffer.currentX, buffer.currentY, buffer.width, buffer.height,
                room.mapX, room.mapY, room.width, room.height,
            )
        }
        if (overlapsRoom) return false
        return (0 until buffer.height).none { ry ->
            (0 until buffer.width).any { rx ->
                !isEmptyMinimapTile(mapData.getTile(buffer.currentX + rx, buffer.currentY + ry)) ||
                    stationData.isRevealed(buffer.currentX + rx, buffer.currentY + ry)
            }
        }
    }

    /** Apply the buffered move: place tiles at new position, update header. */
    fun applyMove(editorState: EditorState): Boolean {
        val buf = moveBuffer ?: return false
        val parser = loadedParser ?: return false
        val baseRoom = parser.readRoomHeader(buf.roomId) ?: return false
        if (buf.currentX == buf.origX && buf.currentY == buf.origY) {
            cancelMove(editorState)
            editorState.postStatus("${baseRoom.name} was left at its original map position.")
            return true
        }
        if (!canPlaceMoveBuffer(buf)) {
            editorState.postStatus("Cannot apply this move: the target overlaps another room or existing map tiles.")
            return false
        }

        pushUndo(editorState)

        // Place tiles — only write non-empty cells to preserve other rooms' tiles
        mapData = placeRoomTilesNonEmpty(mapData, buf.currentX, buf.currentY, buf.width, buf.height, buf.tiles)
        stationData = placeMapStationRect(stationData, buf.currentX, buf.currentY, buf.revealed)
        saveEditsToProject(editorState)

        // Update room header mapX/mapY
        val key = roomHexKey(buf.roomId)
        val existing = editorState.project.rooms[key]?.roomHeaderChange
        val change = (existing ?: RoomHeaderChange()).copy(
            mapX = buf.currentX.takeIf { it != baseRoom.mapX },
            mapY = buf.currentY.takeIf { it != baseRoom.mapY },
        )
        if (change == RoomHeaderChange()) {
            editorState.project.getOrCreateRoom(buf.roomId).roomHeaderChange = null
            editorState.notifyProjectMutation(parser)
        } else editorState.setRoomHeaderChangeForId(buf.roomId, change)
        observedEditorEditVersion = editorState.editVersion

        refreshAreaRooms(parser, selectedArea, editorState)
        selectedRoomIndex = areaRooms.indexOfFirst { it.roomId == buf.roomId }
        moveBuffer = null
        clearPointerMoveMode()
        editorState.postStatus("Moved ${baseRoom.name} to (${buf.currentX}, ${buf.currentY}).")
        return true
    }

    /** Cancel the buffered move: restore tiles to original position. */
    fun cancelMove(editorState: EditorState) {
        val buf = moveBuffer ?: return
        val parser = loadedParser ?: return

        mapData = effectiveMinimapData(parser, editorState.project, selectedArea)
        stationData = effectiveMapStationData(parser, editorState.project, selectedArea)

        refreshAreaRooms(parser, selectedArea, editorState)
        selectedRoomIndex = areaRooms.indexOfFirst { it.roomId == buf.roomId }
        moveBuffer = null
        clearPointerMoveMode()
    }

    /** Change tools while ensuring a floating room can never leak into another mode. */
    fun activateTool(newTool: MinimapTool, editorState: EditorState) {
        if (newTool != MinimapTool.SELECT) {
            if (moveBuffer != null) cancelMove(editorState)
            selectedRoomIndex = -1
            clearPointerMoveMode()
        }
        tool = newTool
    }

    /** Sidebar room selection is explicit and cancels any uncommitted floating preview. */
    fun selectRoomByIndex(index: Int, editorState: EditorState) {
        if (moveBuffer != null) cancelMove(editorState)
        clearPointerMoveMode()
        selectedRoomIndex = index.takeIf { it in areaRooms.indices } ?: -1
    }

    fun clearRoomSelection(editorState: EditorState) {
        if (moveBuffer != null) cancelMove(editorState)
        clearPointerMoveMode()
        selectedRoomIndex = -1
    }

    private fun beginPointerRoomDrag(
        x: Int,
        y: Int,
        mode: RoomPointerMoveMode,
        editorState: EditorState,
    ): Boolean {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return false
        if (moveBuffer != null) cancelMove(editorState)
        val index = roomIndexAt(x, y)
        if (index < 0) return false
        selectedRoomIndex = index
        val room = areaRooms[index]
        pointerGrabOffsetX = x - room.mapX
        pointerGrabOffsetY = y - room.mapY
        liftRoom(room)
        pointerMoveMode = mode
        return true
    }

    /** Begin a primary-button room drag while the Select tool is active. */
    fun beginPrimaryRoomDrag(x: Int, y: Int, editorState: EditorState): Boolean {
        if (tool != MinimapTool.SELECT) return false
        val started = beginPointerRoomDrag(x, y, RoomPointerMoveMode.PRIMARY_DRAG, editorState)
        if (!started) selectedRoomIndex = -1
        return started
    }

    /** Begin a transient middle-button room drag from any active tool. */
    fun beginMiddleRoomDrag(x: Int, y: Int, editorState: EditorState): Boolean {
        return beginPointerRoomDrag(x, y, RoomPointerMoveMode.MIDDLE_DRAG, editorState)
    }

    /** Move the active primary- or middle-drag preview to the pointer cell. */
    fun updatePointerRoomMove(x: Int, y: Int) {
        if (pointerMoveMode == null) return
        val buffer = moveBuffer ?: run {
            clearPointerMoveMode()
            return
        }
        val newX = (x - pointerGrabOffsetX).coerceIn(0, MinimapData.MAP_WIDTH - buffer.width)
        val newY = (y - pointerGrabOffsetY).coerceIn(0, MinimapData.MAP_HEIGHT - buffer.height)
        if (newX != buffer.currentX || newY != buffer.currentY) {
            moveBuffer = buffer.copy(currentX = newX, currentY = newY)
        }
    }

    private fun endPointerRoomDrag(
        x: Int,
        y: Int,
        mode: RoomPointerMoveMode,
        editorState: EditorState,
    ) {
        if (pointerMoveMode != mode) return
        updatePointerRoomMove(x, y)
        if (!applyMove(editorState)) cancelMove(editorState)
        selectedRoomIndex = -1
        clearPointerMoveMode()
    }

    /** Finish a primary drag. Rejected drops are restored automatically. */
    fun endPrimaryRoomDrag(x: Int, y: Int, editorState: EditorState) {
        endPointerRoomDrag(x, y, RoomPointerMoveMode.PRIMARY_DRAG, editorState)
    }

    /** Finish a middle drag. Rejected drops are restored automatically. */
    fun endMiddleRoomDrag(x: Int, y: Int, editorState: EditorState) {
        endPointerRoomDrag(x, y, RoomPointerMoveMode.MIDDLE_DRAG, editorState)
    }

    fun initIfNeeded(parser: RomParser, editorState: EditorState) {
        if (loadedParser !== parser) {
            loadArea(parser, 0, editorState, resetHistory = true)
            observedEditorEditVersion = editorState.editVersion
        } else {
            clearHistoryAfterExternalEdit(editorState)
        }
        // Always re-sync room list to pick up header changes (outlines reflect current positions)
        refreshAreaRooms(parser, selectedArea, editorState)
    }

    private fun clearHistoryAfterExternalEdit(editorState: EditorState) {
        if (observedEditorEditVersion >= 0 && observedEditorEditVersion != editorState.editVersion) {
            undoStack.clear()
            redoStack.clear()
            pendingStroke = null
            moveBuffer = null
            clearPointerMoveMode()
            loadedParser?.let { parser ->
                mapData = effectiveMinimapData(parser, editorState.project, selectedArea)
                stationData = effectiveMapStationData(parser, editorState.project, selectedArea)
            }
        }
        observedEditorEditVersion = editorState.editVersion
    }

    /** Context/select a room without implicitly committing a floating preview. */
    fun selectRoomAt(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        // Right-click/context selection and programmatic selection must not
        // commit a pending pointer drag.
        if (moveBuffer != null) cancelMove(editorState)
        val idx = areaRooms.indexOfFirst { r ->
            x in r.mapX until (r.mapX + r.width) && y in r.mapY until (r.mapY + r.height)
        }
        selectedRoomIndex = idx
    }

    // ─── Paint tools ───

    fun commitStroke(editorState: EditorState) {
        val pending = pendingStroke ?: return
        if (pending == mapData) {
            pendingStroke = null
            return
        }
        pushUndo(editorState)
        mapData = pending
        pendingStroke = null
        saveEditsToProject(editorState)
    }

    fun undo(editorState: EditorState) {
        if (moveBuffer != null) { cancelMove(editorState); return }
        if (undoStack.isEmpty()) return
        val parser = loadedParser ?: return
        redoStack.add(captureSnapshot(editorState))
        restoreSnapshot(undoStack.removeLast(), editorState, parser)
    }

    fun redo(editorState: EditorState) {
        if (redoStack.isEmpty()) return
        val parser = loadedParser ?: return
        undoStack.add(captureSnapshot(editorState))
        restoreSnapshot(redoStack.removeLast(), editorState, parser)
    }

    fun paintTile(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = selectedTileWord
        if (mapData.getTile(x, y) == word) return
        pushUndo(editorState)
        mapData = mapData.withTile(x, y, word)
        saveEditsToProject(editorState)
    }

    fun eraseTile(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        if (isEmptyMinimapTile(mapData.getTile(x, y))) return
        pushUndo(editorState)
        mapData = mapData.withTile(x, y, MinimapTiles.EMPTY)
        saveEditsToProject(editorState)
    }

    fun sampleTile(x: Int, y: Int) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = mapData.getTile(x, y)
        selectedTile = MinimapData.tileIndex(word)
        selectedPalette = MinimapData.tilePalette(word)
        selectedHFlip = MinimapData.tileHFlip(word)
        selectedVFlip = MinimapData.tileVFlip(word)
    }

    fun fillTile(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        val word = selectedTileWord
        val target = mapData.getTile(x, y)
        if (target != word) {
            pushUndo(editorState)
            mapData = floodFillMinimap(mapData, x, y, target, word)
            saveEditsToProject(editorState)
        }
    }

    fun toggleReveal(x: Int, y: Int, editorState: EditorState) {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return
        pushUndo(editorState)
        stationData = stationData.withToggle(x, y)
        saveEditsToProject(editorState)
    }

    val selectedTileWord: Int
        get() = MinimapData.makeTileWord(selectedTile, selectedPalette, selectedHFlip, selectedVFlip)

    fun flipSelectedHorizontal() {
        selectedHFlip = !selectedHFlip
    }

    fun flipSelectedVertical() {
        selectedVFlip = !selectedVFlip
    }

    fun rotateSelected(clockwise: Boolean, editorState: EditorState): Boolean {
        val graphics = tileGraphics
        if (graphics == null) {
            editorState.postStatus("Minimap tile graphics are not loaded, so rotation cannot be represented safely.")
            return false
        }
        val rotated = rotateMinimapTileWord(selectedTileWord, graphics, clockwise)
        if (rotated == null) {
            editorState.postStatus("This tile has no exact ${if (clockwise) "clockwise" else "counter-clockwise"} ROM representation.")
            return false
        }
        selectedTile = MinimapData.tileIndex(rotated)
        selectedPalette = MinimapData.tilePalette(rotated)
        selectedHFlip = MinimapData.tileHFlip(rotated)
        selectedVFlip = MinimapData.tileVFlip(rotated)
        return true
    }

    fun clearSelectedRoomMap(editorState: EditorState): Boolean =
        selectedRoom?.roomId?.let { roomId -> clearRoomMap(roomId, editorState) } ?: false

    fun clearRoomMap(roomId: Int, editorState: EditorState): Boolean {
        val room = areaRooms.firstOrNull { it.roomId == roomId } ?: return false
        val parser = loadedParser ?: return false
        val ownershipMask = roomMapOwnershipMask(room, areaRooms)
        val alreadyClear = (0 until room.height).all { ry ->
            (0 until room.width).all { rx ->
                !ownershipMask[ry][rx] ||
                    (isEmptyMinimapTile(mapData.getTile(room.mapX + rx, room.mapY + ry)) &&
                        !stationData.isRevealed(room.mapX + rx, room.mapY + ry))
            }
        }
        if (alreadyClear) {
            editorState.postStatus("${room.name}'s map rectangle is already clear.")
            return false
        }
        pushUndo(editorState)
        mapData = clearRoomTiles(mapData, room.mapX, room.mapY, room.width, room.height, ownershipMask)
        stationData = clearMapStationRect(
            stationData,
            room.mapX,
            room.mapY,
            room.width,
            room.height,
            ownershipMask,
        )
        saveEditsToProject(editorState)
        refreshAreaRooms(parser, selectedArea, editorState)
        selectedRoomIndex = areaRooms.indexOfFirst { it.roomId == room.roomId }
        editorState.postStatus("Cleared ${room.name}'s map tiles and station-reveal cells; the room remains assigned to ${MinimapData.AREA_NAMES[selectedArea]}.")
        return true
    }

    fun reassignSelectedRoomArea(targetArea: Int, editorState: EditorState): RoomAreaReassignmentResult? =
        selectedRoom?.roomId?.let { roomId -> reassignRoomArea(roomId, targetArea, editorState) }

    /**
     * Reassign the room captured by an area menu when that menu was opened.
     *
     * Popup input and canvas input have independent lifecycles, so the live
     * selection is not a safe command identity. In particular, closing a
     * context menu can clear or replace the selection before its item action
     * completes. Always execute the command against the captured room ID and
     * reload the source area's render state after a successful move.
     */
    fun reassignRoomArea(
        roomId: Int,
        targetArea: Int,
        editorState: EditorState,
    ): RoomAreaReassignmentResult? {
        val parser = loadedParser ?: return null
        if (moveBuffer != null) cancelMove(editorState)
        pendingStroke = null
        val before = captureSnapshot(editorState)
        val result = editorState.reassignRoomArea(roomId, targetArea, parser)
        areaReassignmentError = result.message.takeUnless { result.success }
        observedEditorEditVersion = editorState.editVersion
        if (result.success && result.sourceArea != result.targetArea) {
            undoStack.add(before)
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
            // Stay on the source area so the reassignment has immediate,
            // predictable feedback: the room disappears from this area's
            // room list and map. The user can select the destination area to
            // continue editing it there.
            loadArea(parser, result.sourceArea, editorState, resetHistory = false)
        }
        return result
    }

    val displayData: MinimapData get() = pendingStroke ?: mapData

    // ─── Project persistence ───

    private fun saveEditsToProject(editorState: EditorState) {
        val parser = loadedParser ?: return
        persistMinimapData(parser, editorState.project, mapData)
        persistMapStationData(parser, editorState.project, stationData)
        editorState.markDirty()
    }

    private fun pushUndo(editorState: EditorState) {
        undoStack.add(captureSnapshot(editorState))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun captureSnapshot(editorState: EditorState): MinimapProjectSnapshot = MinimapProjectSnapshot(
        selectedArea = selectedArea,
        selectedRoomId = selectedRoom?.roomId,
        minimapEdits = editorState.project.minimapEdits.mapValues { (_, edits) -> edits.map { it.copy() } },
        mapStationEdits = editorState.project.mapStationEdits.mapValues { (_, edits) -> edits.map { it.copy() } },
        roomHeaders = editorState.project.rooms.mapValues { (_, edits) -> edits.roomHeaderChange?.copy() },
        doorChanges = editorState.project.rooms.mapValues { (_, edits) -> edits.doorChanges.map { it.copy() } },
        plmChanges = editorState.project.rooms.mapValues { (_, edits) -> edits.plmChanges.map { it.copy() } },
        saveStationSpawns = editorState.project.rooms.mapValues { (_, edits) ->
            edits.saveStationSpawns.map { it.copy() }
        },
    )

    private fun restoreSnapshot(snapshot: MinimapProjectSnapshot, editorState: EditorState, parser: RomParser) {
        editorState.project.minimapEdits.clear()
        snapshot.minimapEdits.forEach { (area, edits) ->
            editorState.project.minimapEdits[area] = edits.map { it.copy() }.toMutableList()
        }
        editorState.project.mapStationEdits.clear()
        snapshot.mapStationEdits.forEach { (area, edits) ->
            editorState.project.mapStationEdits[area] = edits.map { it.copy() }.toMutableList()
        }
        val roomKeys = editorState.project.rooms.keys.toSet() + snapshot.roomHeaders.keys +
            snapshot.doorChanges.keys + snapshot.plmChanges.keys + snapshot.saveStationSpawns.keys
        for (key in roomKeys) {
            val roomId = key.toIntOrNull(16) ?: continue
            val edits = editorState.project.getOrCreateRoom(roomId)
            edits.roomHeaderChange = snapshot.roomHeaders[key]?.copy()
            edits.doorChanges.clear()
            edits.doorChanges.addAll(snapshot.doorChanges[key].orEmpty().map { it.copy() })
            edits.plmChanges.clear()
            edits.plmChanges.addAll(snapshot.plmChanges[key].orEmpty().map { it.copy() })
            edits.saveStationSpawns.clear()
            edits.saveStationSpawns.addAll(snapshot.saveStationSpawns[key].orEmpty().map { it.copy() })
        }
        pendingStroke = null
        moveBuffer = null
        editorState.notifyProjectMutation(parser)
        observedEditorEditVersion = editorState.editVersion
        loadArea(
            parser,
            snapshot.selectedArea,
            editorState,
            resetHistory = false,
            selectRoomId = snapshot.selectedRoomId,
        )
    }
}

internal data class MinimapProjectSnapshot(
    val selectedArea: Int,
    val selectedRoomId: Int?,
    val minimapEdits: Map<String, List<MinimapTileEdit>>,
    val mapStationEdits: Map<String, List<MapStationTileEdit>>,
    val roomHeaders: Map<String, RoomHeaderChange?>,
    val doorChanges: Map<String, List<DoorChange>>,
    val plmChanges: Map<String, List<PlmChange>>,
    val saveStationSpawns: Map<String, List<SaveStationSpawnChange>>,
)

/** Floating move buffer: holds lifted tiles while the user repositions a room on the minimap. */
data class RoomMoveBuffer(
    val roomId: Int,
    val origX: Int, val origY: Int,
    val width: Int, val height: Int,
    val tiles: Array<IntArray>,
    val revealed: Array<BooleanArray>,
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
internal fun extractRoomTiles(
    data: MinimapData,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    ownershipMask: Array<BooleanArray>? = null,
): Array<IntArray> =
    Array(h) { ry -> IntArray(w) { rx ->
        val mx = x + rx; val my = y + ry
        if ((ownershipMask == null || ownershipMask.getOrNull(ry)?.getOrNull(rx) == true) &&
            mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT
        ) data.getTile(mx, my) else MinimapTiles.EMPTY
    }}

/** Clear tiles within a room's bounds. */
internal fun clearRoomTiles(
    data: MinimapData,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    ownershipMask: Array<BooleanArray>? = null,
): MinimapData {
    val tiles = data.tiles.copyOf()
    for (ry in 0 until h) for (rx in 0 until w) {
        if (ownershipMask != null && ownershipMask.getOrNull(ry)?.getOrNull(rx) != true) continue
        val mx = x + rx; val my = y + ry
        if (mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT)
            tiles[my * MinimapData.MAP_WIDTH + mx] = MinimapTiles.EMPTY
    }
    return data.copy(tiles = tiles)
}

/** Place saved tiles at a new position. Only writes non-empty tiles to preserve underlying data. */
internal fun placeRoomTilesNonEmpty(data: MinimapData, x: Int, y: Int, w: Int, h: Int, saved: Array<IntArray>): MinimapData {
    val tiles = data.tiles.copyOf()
    for (ry in 0 until h) for (rx in 0 until w) {
        val mx = x + rx; val my = y + ry
        val tile = saved[ry][rx]
        if (!isEmptyMinimapTile(tile) && mx in 0 until MinimapData.MAP_WIDTH && my in 0 until MinimapData.MAP_HEIGHT)
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

/**
 * Rotate the visible pixels represented by a minimap tile word and find an
 * exactly equivalent ROM tile + flip combination. A null result means the
 * requested 90-degree rotation cannot be encoded by the current tile sheet.
 */
internal fun rotateMinimapTileWord(
    word: Int,
    graphics: Array<IntArray>,
    clockwise: Boolean,
): Int? {
    val sourceIndex = MinimapData.tileIndex(word)
    val source = graphics.getOrNull(sourceIndex) ?: return null
    if (source.size != 64) return null
    val visible = IntArray(64)
    val sourceHFlip = MinimapData.tileHFlip(word)
    val sourceVFlip = MinimapData.tileVFlip(word)
    for (y in 0 until 8) for (x in 0 until 8) {
        val sx = if (sourceHFlip) 7 - x else x
        val sy = if (sourceVFlip) 7 - y else y
        visible[y * 8 + x] = source[sy * 8 + sx]
    }
    val target = IntArray(64)
    for (y in 0 until 8) for (x in 0 until 8) {
        val sourceX = if (clockwise) y else 7 - y
        val sourceY = if (clockwise) 7 - x else x
        target[y * 8 + x] = visible[sourceY * 8 + sourceX]
    }

    val preferred = MinimapTiles.PALETTE_TILES.toList() + graphics.indices.filter { it !in MinimapTiles.PALETTE_TILES }
    for (candidateIndex in preferred) {
        val candidate = graphics.getOrNull(candidateIndex) ?: continue
        if (candidate.size != 64) continue
        for ((hFlip, vFlip) in listOf(false to false, true to false, false to true, true to true)) {
            var matches = true
            loop@ for (y in 0 until 8) for (x in 0 until 8) {
                val sx = if (hFlip) 7 - x else x
                val sy = if (vFlip) 7 - y else y
                if (candidate[sy * 8 + sx] != target[y * 8 + x]) {
                    matches = false
                    break@loop
                }
            }
            if (matches) {
                val reserved = word and 0x3000
                return MinimapData.makeTileWord(
                    candidateIndex,
                    MinimapData.tilePalette(word),
                    hFlip,
                    vFlip,
                ) or reserved
            }
        }
    }
    return null
}
