package com.supermetroid.editor.cli

import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.procgen.LevelGrid
import com.supermetroid.editor.rom.RomParser
import java.security.MessageDigest

/**
 * Lossless, versioned room-layout export for training generative models.
 *
 * This intentionally exports only the tile grid and structural door tiles. Runtime
 * objects such as door PLMs/covers, destinations, save stations, and room-state
 * transitions stay on the deterministic SMEDIT side of the generation boundary.
 */
class RoomTrainingExporter(
    private val parser: RomParser,
    private val allRoomInfos: List<RoomInfo> = parser.roomCatalog.rooms,
) {
    private val roomInfoById = allRoomInfos.associateBy { it.getRoomIdAsInt() }

    fun exportAll(): Pair<List<RoomTrainingExport>, Int> {
        val exports = allRoomInfos.mapNotNull { exportRoom(it.getRoomIdAsInt()) }
        return exports to (allRoomInfos.size - exports.size)
    }

    fun exportRoom(roomId: Int): RoomTrainingExport? {
        val room = parser.readRoomHeader(roomId) ?: return null
        if (room.levelDataPtr == 0 || room.width <= 0 || room.height <= 0) return null
        val width = room.width * 16
        val height = room.height * 16
        val levelData = runCatching { parser.decompressLZ2(room.levelDataPtr) }.getOrNull() ?: return null
        val grid = LevelGrid.parse(levelData, width, height) ?: return null
        val cellCount = width * height
        val layer1Words = IntArray(cellCount)
        val blockTypes = IntArray(cellCount)
        val resolvedBlockTypes = IntArray(cellCount)
        val bts = IntArray(cellCount)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val word = grid.word(x, y)
                layer1Words[index] = word
                blockTypes[index] = (word ushr 12) and 0xF
                resolvedBlockTypes[index] = grid.resolvedType(x, y)
                bts[index] = grid.bts(x, y)
            }
        }
        val info = roomInfoById[roomId]
        val hash = contentHash(width, height, room.tileset, layer1Words, bts)
        return RoomTrainingExport(
            schemaVersion = SCHEMA_VERSION,
            roomId = roomId,
            roomIdHex = hexId(roomId),
            handle = info?.handle ?: room.handle,
            name = info?.name ?: room.name,
            area = room.area,
            areaName = room.areaName,
            tileset = room.tileset,
            widthScreens = room.width,
            heightScreens = room.height,
            widthBlocks = width,
            heightBlocks = height,
            contentHash = hash,
            layer1Words = layer1Words.toList(),
            blockTypes = blockTypes.toList(),
            resolvedBlockTypes = resolvedBlockTypes.toList(),
            bts = bts.toList(),
            doorGroups = findDoorGroups(blockTypes, width, height),
        )
    }

    private fun findDoorGroups(blockTypes: IntArray, width: Int, height: Int): List<RoomTrainingDoorGroup> {
        val remaining = blockTypes.indices.filterTo(mutableSetOf()) { blockTypes[it] == DOOR_BLOCK_TYPE }
        val groups = ArrayList<RoomTrainingDoorGroup>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            remaining.remove(start)
            val cells = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cells.add(current)
                val x = current % width
                val y = current / width
                for ((dx, dy) in CARDINALS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbor = ny * width + nx
                    if (remaining.remove(neighbor)) queue.add(neighbor)
                }
            }
            cells.sort()
            val xs = cells.map { it % width }
            val ys = cells.map { it / width }
            val minX = xs.minOrNull() ?: 0
            val maxX = xs.maxOrNull() ?: 0
            val minY = ys.minOrNull() ?: 0
            val maxY = ys.maxOrNull() ?: 0
            val edge = when {
                minX <= 1 -> "left"
                maxX >= width - 2 -> "right"
                minY <= 1 -> "top"
                maxY >= height - 2 -> "bottom"
                else -> "interior"
            }
            val orientation = if (maxY - minY >= maxX - minX) "vertical" else "horizontal"
            groups.add(RoomTrainingDoorGroup(edge, orientation, cells))
        }
        return groups.sortedBy { it.cellIndices.firstOrNull() ?: Int.MAX_VALUE }
    }

    private fun contentHash(
        width: Int,
        height: Int,
        tileset: Int,
        layer1Words: IntArray,
        bts: IntArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateInt(value: Int) {
            digest.update((value and 0xFF).toByte())
            digest.update(((value ushr 8) and 0xFF).toByte())
            digest.update(((value ushr 16) and 0xFF).toByte())
            digest.update(((value ushr 24) and 0xFF).toByte())
        }
        updateInt(width)
        updateInt(height)
        updateInt(tileset)
        for (word in layer1Words) updateInt(word)
        for (value in bts) digest.update(value.toByte())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOOR_BLOCK_TYPE = 0x9
        private val CARDINALS = arrayOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)

        fun hexId(roomId: Int): String = "0x${roomId.toString(16).uppercase()}"
    }
}
