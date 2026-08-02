package com.supermetroid.editor.cli

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser

class RoomExporter(
    private val parser: RomParser,
    private val allRoomInfos: List<RoomInfo> = parser.roomCatalog.rooms,
) {
    constructor(parser: RomParser, repo: RoomRepository) : this(parser, repo.getAllRooms())

    private val roomInfoById: Map<Int, RoomInfo> = allRoomInfos.associateBy { it.getRoomIdAsInt() }

    fun exportRoomSummaries(): List<RoomSummary> {
        return allRoomInfos.mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            RoomSummary(
                roomId = roomId,
                roomIdHex = hexId(roomId),
                handle = info.handle,
                name = info.name,
                area = room.area,
                areaName = room.areaName,
                mapX = room.mapX,
                mapY = room.mapY,
                widthScreens = room.width,
                heightScreens = room.height,
            )
        }
    }

    fun exportAllRooms(): List<RoomExport> {
        return allRoomInfos.mapNotNull { info -> exportRoom(info.getRoomIdAsInt()) }
    }

    fun exportRoom(roomId: Int): RoomExport? {
        val room = parser.readRoomHeader(roomId) ?: return null
        val info = roomInfoById[roomId]
        val handle = info?.handle ?: room.handle
        val name = info?.name ?: room.name

        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val totalBlocks = blocksWide * blocksTall

        // Decompress level data for collision/BTS grids
        val collisionGrid: List<List<Int>>
        val btsGrid: List<List<Int>>
        var hasLevelData = false

        if (room.levelDataPtr != 0) {
            val levelData = try {
                parser.decompressLZ2(room.levelDataPtr)
            } catch (e: Exception) {
                System.err.println("Warning: decompression failed for ${hexId(roomId)}: ${e.message}")
                null
            }
            if (levelData != null && levelData.size >= 2) {
                hasLevelData = true
                val layer1Size = (levelData[0].toInt() and 0xFF) or
                    ((levelData[1].toInt() and 0xFF) shl 8)
                val tileDataStart = 2
                val btsDataStart = tileDataStart + layer1Size

                val blockTypes = IntArray(totalBlocks)
                val btsBytes = IntArray(totalBlocks)
                for (i in 0 until totalBlocks) {
                    val offset = tileDataStart + i * 2
                    if (offset + 1 < levelData.size) {
                        val lo = levelData[offset].toInt() and 0xFF
                        val hi = levelData[offset + 1].toInt() and 0xFF
                        blockTypes[i] = (((hi shl 8) or lo) ushr 12) and 0x0F
                    }
                    val btsOffset = btsDataStart + i
                    if (btsOffset < levelData.size) {
                        btsBytes[i] = levelData[btsOffset].toInt() and 0xFF
                    }
                }

                collisionGrid = (0 until blocksTall).map { row ->
                    (0 until blocksWide).map { col -> blockTypes[row * blocksWide + col] }
                }
                btsGrid = (0 until blocksTall).map { row ->
                    (0 until blocksWide).map { col -> btsBytes[row * blocksWide + col] }
                }
            } else {
                collisionGrid = emptyGrid(blocksTall, blocksWide)
                btsGrid = emptyGrid(blocksTall, blocksWide)
            }
        } else {
            collisionGrid = emptyGrid(blocksTall, blocksWide)
            btsGrid = emptyGrid(blocksTall, blocksWide)
        }

        // Parse PLMs
        val plms = parser.parsePlmSet(room.plmSetPtr)
        val plmExports = plms.map { plm ->
            PlmExport(
                id = plm.id,
                idHex = "0x${plm.id.toString(16).uppercase()}",
                blockX = plm.x,
                blockY = plm.y,
                param = plm.param,
                category = categorizePlm(plm.id),
            )
        }

        // Items
        val items = plms.filter { RomParser.isItemPlm(it.id) }.map { plm ->
            ItemExport(
                name = RomParser.itemNameForPlm(plm.id) ?: "Unknown",
                blockX = plm.x,
                blockY = plm.y,
                plmId = plm.id,
                plmIdHex = "0x${plm.id.toString(16).uppercase()}",
            )
        }

        // Enemies
        val enemies = parser.parseEnemyPopulation(room.enemySetPtr).map { e ->
            EnemyExport(
                id = e.id,
                idHex = "0x${e.id.toString(16).uppercase()}",
                name = RomParser.enemyName(e.id),
                pixelX = e.x,
                pixelY = e.y,
                blockX = e.x / 16,
                blockY = e.y / 16,
            )
        }

        // Doors with cap matching (validated against collision grid when available)
        val doors = buildDoorExports(room, plms, collisionGrid, hasLevelData)

        return RoomExport(
            roomId = roomId,
            roomIdHex = hexId(roomId),
            handle = handle,
            name = name,
            area = room.area,
            areaName = room.areaName,
            mapX = room.mapX,
            mapY = room.mapY,
            widthScreens = room.width,
            heightScreens = room.height,
            widthBlocks = blocksWide,
            heightBlocks = blocksTall,
            tileset = room.tileset,
            collision = collisionGrid,
            bts = btsGrid,
            doors = doors,
            items = items,
            enemies = enemies,
            plms = plmExports,
        )
    }

    fun resolveRoomId(idOrHandle: String): Int? {
        // Try hex ID first
        val asHex = idOrHandle.removePrefix("0x").removePrefix("0X")
        asHex.toIntOrNull(16)?.let { return it }

        // Try handle/name lookup from this ROM's catalog.
        val lookup = idOrHandle.trim().lowercase()
        val info = allRoomInfos.firstOrNull {
            it.handle.lowercase() == lookup || it.name.lowercase() == lookup
        } ?: return null
        return info.getRoomIdAsInt()
    }

    private fun buildDoorExports(
        room: Room,
        plms: List<RomParser.PlmEntry>,
        collisionGrid: List<List<Int>>,
        hasLevelData: Boolean,
    ): List<DoorExport> {
        val doorEntries = parser.parseDoorList(room.doorOut)

        // Collect door cap PLMs with their positions
        val caps = plms.mapNotNull { plm ->
            val color = RomParser.doorCapColor(plm.id) ?: return@mapNotNull null
            CapInfo(plm.x, plm.y, color)
        }

        // Without level data we can't validate doors against collision grid,
        // so emit all parsed doors unfiltered
        if (!hasLevelData) {
            return doorEntries.map { door -> makeDoorExport(door, room, caps) }
        }

        // Count type-9 block clusters on each room edge to detect over-reads
        val edgeDoorCounts = countEdgeDoors(collisionGrid)

        // Track how many doors we've emitted per direction
        val directionCounts = mutableMapOf<Int, Int>()

        return doorEntries.mapNotNull { door ->
            val dir = door.direction and 0x03

            // Elevators bypass collision validation — the elevator platform may
            // not use type-9 blocks even though the door transition is real
            if (!door.isElevator) {
                val emittedForDir = directionCounts.getOrDefault(dir, 0)
                val maxForDir = when (dir) {
                    0 -> edgeDoorCounts.right
                    1 -> edgeDoorCounts.left
                    2 -> edgeDoorCounts.bottom
                    3 -> edgeDoorCounts.top
                    else -> 0
                }
                if (emittedForDir >= maxForDir) return@mapNotNull null
            }

            directionCounts[dir] = directionCounts.getOrDefault(dir, 0) + 1
            makeDoorExport(door, room, caps)
        }
    }

    private fun makeDoorExport(
        door: RomParser.DoorEntry,
        room: Room,
        caps: List<CapInfo>,
    ): DoorExport {
        val destId = door.destRoomPtr
        val destInfo = roomInfoById[destId]
        val matchedCap = matchDoorCap(door, room, caps)
        return DoorExport(
            destRoomId = destId,
            destRoomIdHex = hexId(destId),
            destRoomHandle = destInfo?.handle,
            direction = door.directionName,
            isElevator = door.isElevator,
            doorCapColor = matchedCap?.let { colorName(it.color) },
            requiredAbility = matchedCap?.let { abilityForColor(it.color) },
        )
    }

    private data class EdgeDoorCounts(val left: Int, val right: Int, val top: Int, val bottom: Int)

    /**
     * Count distinct door clusters on each room edge by scanning for type-9 blocks.
     * A "cluster" is a contiguous vertical run (left/right edges) or horizontal run
     * (top/bottom edges) of door blocks. Each cluster = one physical door.
     */
    private fun countEdgeDoors(collisionGrid: List<List<Int>>): EdgeDoorCounts {
        val rows = collisionGrid.size
        val cols = if (rows > 0) collisionGrid[0].size else 0
        if (rows == 0 || cols == 0) return EdgeDoorCounts(0, 0, 0, 0)

        val left = countVerticalClusters(collisionGrid, rows, col = 0)
        val right = countVerticalClusters(collisionGrid, rows, col = cols - 1)
        val top = countHorizontalClusters(collisionGrid, cols, row = 0)
        val bottom = countHorizontalClusters(collisionGrid, cols, row = rows - 1)

        return EdgeDoorCounts(left, right, top, bottom)
    }

    private fun countVerticalClusters(grid: List<List<Int>>, rows: Int, col: Int): Int {
        var clusters = 0
        var inCluster = false
        for (r in 0 until rows) {
            val isDoor = grid[r][col] == 9
            if (isDoor && !inCluster) { clusters++; inCluster = true }
            if (!isDoor) inCluster = false
        }
        return clusters
    }

    private fun countHorizontalClusters(grid: List<List<Int>>, cols: Int, row: Int): Int {
        var clusters = 0
        var inCluster = false
        for (c in 0 until cols) {
            val isDoor = grid[row][c] == 9
            if (isDoor && !inCluster) { clusters++; inCluster = true }
            if (!isDoor) inCluster = false
        }
        return clusters
    }

    /**
     * Match a door cap PLM to a door entry by edge proximity.
     *
     * Door direction tells us which edge of the source room the door is on:
     *   Right(0) → cap on right edge, Left(1) → left edge,
     *   Down(2) → bottom edge, Up(3) → top edge.
     *
     * When multiple caps are on the same edge, we use the cap's block coordinate
     * (converted to screen index) to disambiguate.
     */
    private fun matchDoorCap(
        door: RomParser.DoorEntry,
        room: Room,
        caps: List<CapInfo>,
    ): CapInfo? {
        if (caps.isEmpty()) return null

        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val dir = door.direction and 0x03

        // Filter caps on the correct edge
        val edgeCaps = when (dir) {
            0 -> caps.filter { it.x >= blocksWide - 2 }  // Right
            1 -> caps.filter { it.x <= 1 }                // Left
            2 -> caps.filter { it.y >= blocksTall - 2 }   // Down
            3 -> caps.filter { it.y <= 1 }                // Up
            else -> return null
        }

        if (edgeCaps.isEmpty()) return null
        if (edgeCaps.size == 1) return edgeCaps[0]

        // Multiple caps on same edge: match by the door's index in the door list.
        // Caps and doors are both ordered along the edge, so sort caps by position
        // and take them in order (callers process doors sequentially).
        val sorted = when (dir) {
            0, 1 -> edgeCaps.sortedBy { it.y }   // vertical edge: sort by Y
            else -> edgeCaps.sortedBy { it.x }    // horizontal edge: sort by X
        }

        // Use door's screen coordinate to find the closest cap
        val doorBlockCoord = when (dir) {
            0, 1 -> door.screenY * 16   // horizontal doors: Y position in blocks
            else -> door.screenX * 16   // vertical doors: X position in blocks
        }

        return sorted.minByOrNull { cap ->
            val capCoord = when (dir) {
                0, 1 -> cap.y
                else -> cap.x
            }
            kotlin.math.abs(capCoord - doorBlockCoord)
        }
    }

    private data class CapInfo(val x: Int, val y: Int, val color: Int)

    companion object {
        fun hexId(roomId: Int): String = "0x${roomId.toString(16).uppercase()}"

        fun colorName(argb: Int): String = when (argb) {
            RomParser.DOOR_CAP_BLUE -> "blue"
            RomParser.DOOR_CAP_RED -> "red"
            RomParser.DOOR_CAP_GREEN -> "green"
            RomParser.DOOR_CAP_YELLOW -> "yellow"
            RomParser.DOOR_CAP_GREY -> "grey"
            else -> "unknown"
        }

        fun abilityForColor(argb: Int): String? = when (argb) {
            RomParser.DOOR_CAP_BLUE -> "beam"
            RomParser.DOOR_CAP_RED -> "missile"
            RomParser.DOOR_CAP_GREEN -> "super_missile"
            RomParser.DOOR_CAP_YELLOW -> "power_bomb"
            RomParser.DOOR_CAP_GREY -> "boss_event"
            else -> null
        }

        fun categorizePlm(plmId: Int): String = when {
            RomParser.isItemPlm(plmId) -> "item"
            RomParser.doorCapColor(plmId) != null -> "door_cap"
            else -> "other"
        }

        private fun emptyGrid(rows: Int, cols: Int): List<List<Int>> =
            List(rows) { List(cols) { 0 } }
    }
}
