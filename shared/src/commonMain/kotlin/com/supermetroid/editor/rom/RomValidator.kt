package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * ROM-wide validation scanner.
 * Checks for common issues that cause in-game bugs or crashes.
 */
object RomValidator {

    enum class Severity { ERROR, WARNING, INFO }

    data class Issue(
        val severity: Severity,
        val category: String,
        val roomId: Int?,
        val roomName: String,
        val message: String,
    )

    /**
     * Run all validations and return a list of issues found.
     */
    fun validate(parser: RomParser, roomIds: List<Int>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val rooms = mutableMapOf<Int, Room>()
        for (rid in roomIds) {
            val room = parser.readRoomHeader(rid) ?: continue
            rooms[rid] = room
        }

        issues.addAll(checkDoorConsistency(parser, rooms))
        issues.addAll(checkItemBitflagDuplicates(parser, rooms))
        issues.addAll(checkEnemyGfxLimits(parser, rooms))
        issues.addAll(checkRoomDimensions(rooms))

        return issues.sortedWith(compareBy({ it.severity }, { it.category }, { it.roomName }))
    }

    /**
     * Verify all doors point to valid destination rooms and have coordinates
     * within the destination room's dimensions.
     */
    fun checkDoorConsistency(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val doors = parser.parseDoorList(room.doorOut)
            for ((idx, door) in doors.withIndex()) {
                val destRoom = rooms[door.destRoomPtr]
                val label = "Door #$idx (${door.directionName})"

                if (destRoom == null) {
                    // Destination room not in our known room list — could be valid but unknown
                    val destHex = "0x${door.destRoomPtr.toString(16).uppercase()}"
                    issues.add(Issue(
                        Severity.WARNING, "Doors", roomId, room.name,
                        "$label → destination room $destHex not found in ROM"
                    ))
                    continue
                }

                // Check spawn coordinates against destination room dimensions
                val maxScreenX = destRoom.width - 1
                val maxScreenY = destRoom.height - 1
                if (door.screenX > maxScreenX) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenX=${door.screenX} exceeds ${destRoom.name} width (${destRoom.width} screens, max X=$maxScreenX)"
                    ))
                }
                if (door.screenY > maxScreenY) {
                    issues.add(Issue(
                        Severity.ERROR, "Doors", roomId, room.name,
                        "$label → screenY=${door.screenY} exceeds ${destRoom.name} height (${destRoom.height} screens, max Y=$maxScreenY)"
                    ))
                }
            }
        }
        return issues
    }

    /**
     * Scan all item PLMs across all rooms for duplicate collection bitflags.
     * Two items sharing the same param means collecting one silently collects the other.
     */
    fun checkItemBitflagDuplicates(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        // Map: param → list of (roomId, plmId, itemName)
        data class ItemLocation(val roomId: Int, val roomName: String, val plmId: Int, val itemName: String)
        val paramMap = mutableMapOf<Int, MutableList<ItemLocation>>()

        for ((roomId, room) in rooms) {
            val plms = parser.getAllPlmEntriesForRoom(roomId)
            for (plm in plms) {
                if (!RomParser.isItemPlm(plm.id)) continue
                val itemName = RomParser.itemNameForPlm(plm.id) ?: "Unknown item"
                paramMap.getOrPut(plm.param) { mutableListOf() }
                    .add(ItemLocation(roomId, room.name, plm.id, itemName))
            }
        }

        for ((param, locations) in paramMap) {
            if (locations.size <= 1) continue
            // Multiple items sharing the same collection bit
            val roomNames = locations.map { "${it.itemName} in ${it.roomName}" }.joinToString(", ")
            val paramHex = "0x${param.toString(16).uppercase()}"
            issues.add(Issue(
                Severity.WARNING, "Items", null,
                locations.first().roomName,
                "Collection bit $paramHex shared by ${locations.size} items: $roomNames"
            ))
        }
        return issues
    }

    /**
     * Check that each room's enemy GFX set doesn't exceed the 4-slot hardware limit.
     */
    fun checkEnemyGfxLimits(parser: RomParser, rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((roomId, room) in rooms) {
            val gfxEntries = parser.parseEnemyGfxSet(room.enemyGfxPtr)
            if (gfxEntries.size > 4) {
                issues.add(Issue(
                    Severity.ERROR, "Enemy GFX", roomId, room.name,
                    "${gfxEntries.size} enemy tileset slots used (SNES hardware max is 4). Excess sprites will be garbled."
                ))
            }
        }
        return issues
    }

    /**
     * Check rooms for suspicious dimensions or map positions.
     */
    fun checkRoomDimensions(rooms: Map<Int, Room>): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((_, room) in rooms) {
            if (room.mapX + room.width > MinimapData.MAP_WIDTH) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap right edge: mapX=${room.mapX} + width=${room.width} = ${room.mapX + room.width} > ${MinimapData.MAP_WIDTH}"
                ))
            }
            if (room.mapY + room.height > MinimapData.MAP_HEIGHT) {
                issues.add(Issue(
                    Severity.WARNING, "Room Header", room.roomId, room.name,
                    "Room extends past minimap bottom edge: mapY=${room.mapY} + height=${room.height} = ${room.mapY + room.height} > ${MinimapData.MAP_HEIGHT}"
                ))
            }
        }
        return issues
    }
}
