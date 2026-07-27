package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomConstants.ROM_SIZE

enum class RomRoomCatalogSource {
    VANILLA,
    DISCOVERED_BANK_8F,
    UNSUPPORTED,
}

data class RomRoomCatalog(
    val source: RomRoomCatalogSource,
    val rooms: List<RoomInfo>,
    val editable: Boolean,
    val discoveredRoomCount: Int = rooms.size,
    val discoveredStateCount: Int = 0,
    val expandedLevelPointerCount: Int = 0,
    val parsedLevelDataStateCount: Int = 0,
) {
    val readable: Boolean get() = rooms.isNotEmpty()
    val readOnly: Boolean get() = readable && !editable

    fun loadNotice(fileName: String? = null): String? =
        if (!readOnly) null else buildString {
            appendLine("Read-only expanded ROM layout loaded.")
            if (!fileName.isNullOrBlank()) {
                appendLine("File: $fileName")
            }
            appendLine(
                "SMEDIT discovered $discoveredRoomCount room header(s) in bank \$8F " +
                    "and parsed $parsedLevelDataStateCount/$discoveredStateCount room state level-data block(s)."
            )
            if (expandedLevelPointerCount > 0) {
                appendLine(
                    "$expandedLevelPointerCount level-data pointer(s) target expanded ROM space beyond the vanilla 3 MiB area."
                )
            }
            appendLine(
                "Editing/export is disabled for this ROM because relocated room data is not write-safe yet. " +
                    "Room listing and map rendering are enabled for inspection."
            )
        }.trim()
}

data class DiscoveredRoom(
    val roomId: Int,
    val pcOffset: Int,
    val header: Room,
    val stateCount: Int,
    val parsedLevelDataStateCount: Int,
    val expandedLevelPointerCount: Int,
)

object RomRoomCatalogDetector {
    fun detect(
        parser: RomParser,
        compatibility: RomCompatibility.Report = parser.compatibilityReport,
    ): RomRoomCatalog {
        val vanillaRooms = RoomRepository().getAllRooms().sortedBy { it.getRoomIdAsInt() }
        if (compatibility.supportedForEditing) {
            return RomRoomCatalog(
                source = RomRoomCatalogSource.VANILLA,
                rooms = vanillaRooms,
                editable = true,
                discoveredStateCount = vanillaRooms.size,
                parsedLevelDataStateCount = vanillaRooms.size,
            )
        }

        val discovered = RomRoomScanner.discover(parser, validateLevelData = true)
        if (discovered.isEmpty()) {
            return RomRoomCatalog(
                source = RomRoomCatalogSource.UNSUPPORTED,
                rooms = emptyList(),
                editable = false,
            )
        }

        val vanillaById = vanillaRooms.associateBy { it.getRoomIdAsInt() }
        val rooms = discovered.map { room ->
            val known = vanillaById[room.roomId]
            val id = hexRoomId(room.roomId)
            RoomInfo(
                id = id,
                handle = known?.handle ?: "room_${id.removePrefix("0x").lowercase()}",
                name = known?.name ?: "Room $id",
                comment = known?.comment ?: "Discovered from ROM room table scan",
            )
        }.sortedBy { it.getRoomIdAsInt() }

        return RomRoomCatalog(
            source = RomRoomCatalogSource.DISCOVERED_BANK_8F,
            rooms = rooms,
            editable = false,
            discoveredRoomCount = discovered.size,
            discoveredStateCount = discovered.sumOf { it.stateCount },
            expandedLevelPointerCount = discovered.sumOf { it.expandedLevelPointerCount },
            parsedLevelDataStateCount = discovered.sumOf { it.parsedLevelDataStateCount },
        )
    }

    private fun hexRoomId(roomId: Int): String =
        "0x${(roomId and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"
}

object RomRoomScanner {
    private val stateConditionWords = setOf(
        0xE5E6,
        0xE5EB,
        0xE5FF,
        0xE612,
        0xE629,
        0xE640,
        0xE652,
        0xE669,
        0xE678,
        0xE683,
        0xE6A1,
        0xE6BA,
    )

    fun discover(parser: RomParser, validateLevelData: Boolean): List<DiscoveredRoom> {
        val romData = parser.getRomData()
        val bankStart = runCatching { parser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x8000) }.getOrNull()
            ?: return emptyList()
        val bankEndExclusive = runCatching { parser.snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1 }.getOrNull()
            ?: return emptyList()
        if (bankStart !in romData.indices || bankStart >= romData.size) return emptyList()

        val end = minOf(bankEndExclusive, romData.size)
        val discovered = mutableListOf<DiscoveredRoom>()
        val seenRoomIds = mutableSetOf<Int>()
        for (pc in bankStart until maxOf(bankStart, end - 0x30)) {
            if (!looksLikeRoomHeader(romData, pc)) continue
            val roomId = parser.pcToSnes(pc) and 0xFFFF
            if (!seenRoomIds.add(roomId)) continue
            val stateScan = scanStateData(parser, pc) ?: continue
            val header = runCatching { parser.readRoomHeader(roomId) }.getOrNull() ?: continue
            if (validateLevelData && stateScan.parsedLevelDataStates <= 0) continue
            discovered.add(
                DiscoveredRoom(
                    roomId = roomId,
                    pcOffset = pc,
                    header = header,
                    stateCount = stateScan.stateCount,
                    parsedLevelDataStateCount = stateScan.parsedLevelDataStates,
                    expandedLevelPointerCount = stateScan.expandedLevelPointers,
                )
            )
        }
        return discovered.sortedBy { it.roomId }
    }

    private fun looksLikeRoomHeader(romData: ByteArray, pc: Int): Boolean {
        if (pc < 0 || pc + 13 >= romData.size) return false
        val area = romData[pc + 1].toInt() and 0xFF
        val width = romData[pc + 4].toInt() and 0xFF
        val height = romData[pc + 5].toInt() and 0xFF
        val doorOut = readU16OrNull(romData, pc + 9) ?: return false
        val condition = readU16OrNull(romData, pc + 11) ?: return false
        return area <= 6 &&
            width in 1..16 &&
            height in 1..16 &&
            doorOut in 0x8000..0xFFFF &&
            condition in stateConditionWords
    }

    private fun scanStateData(parser: RomParser, roomHeaderPc: Int): StateScan? {
        val romData = parser.getRomData()
        val states = mutableListOf<Int>()
        var cursor = roomHeaderPc + 11
        repeat(64) {
            val condition = readU16OrNull(romData, cursor) ?: return null
            when (condition) {
                0xE5E6 -> {
                    val defaultStatePc = cursor + 2
                    if (defaultStatePc + RomConstants.STATE_DATA_SIZE > romData.size) return null
                    states.add(defaultStatePc)
                    return summarizeStates(parser, states)
                }
                0xE5EB -> {
                    val ptr = readU16OrNull(romData, cursor + 4) ?: return null
                    statePcForPtr(parser, ptr)?.let(states::add)
                    cursor += 6
                }
                0xE612, 0xE629, 0xE683, 0xE6A1, 0xE6BA -> {
                    val ptr = readU16OrNull(romData, cursor + 3) ?: return null
                    statePcForPtr(parser, ptr)?.let(states::add)
                    cursor += 5
                }
                0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678 -> {
                    val ptr = readU16OrNull(romData, cursor + 2) ?: return null
                    statePcForPtr(parser, ptr)?.let(states::add)
                    cursor += 4
                }
                else -> return null
            }
            if (cursor + 2 >= romData.size) return null
        }
        return null
    }

    private fun statePcForPtr(parser: RomParser, ptr: Int): Int? {
        if (ptr !in 0x8000..0xFFFF) return null
        val pc = runCatching { parser.snesToPc(RomConstants.BANK_ROOM_DATA or ptr) }.getOrNull() ?: return null
        return pc.takeIf { it + RomConstants.STATE_DATA_SIZE <= parser.getRomData().size }
    }

    private fun summarizeStates(parser: RomParser, statePcs: List<Int>): StateScan {
        val romData = parser.getRomData()
        var parsedLevelData = 0
        var expandedLevelPointers = 0
        for (statePc in statePcs.distinct()) {
            val levelPtr = readU24OrNull(romData, statePc) ?: continue
            if (levelPtr == 0) continue
            val levelPc = runCatching { parser.snesToPc(levelPtr) }.getOrNull() ?: continue
            if (levelPc >= parser.romStartOffsetForLayout() + ROM_SIZE && levelPc < romData.size) {
                expandedLevelPointers++
            }
            val levelData = runCatching { parser.decompressLZ2(levelPtr) }.getOrNull() ?: continue
            if (levelData.size >= 2) parsedLevelData++
        }
        return StateScan(
            stateCount = statePcs.distinct().size,
            parsedLevelDataStates = parsedLevelData,
            expandedLevelPointers = expandedLevelPointers,
        )
    }

    private fun readU16OrNull(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 1 >= data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU24OrNull(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 >= data.size) return null
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    }

    private data class StateScan(
        val stateCount: Int,
        val parsedLevelDataStates: Int,
        val expandedLevelPointers: Int,
    )
}
