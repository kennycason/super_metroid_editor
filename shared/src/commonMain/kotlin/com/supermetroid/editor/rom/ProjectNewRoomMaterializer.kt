package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectDoorDefinition
import com.supermetroid.editor.data.ProjectFxEntry
import com.supermetroid.editor.data.ProjectNewRoom
import com.supermetroid.editor.data.SmEditProject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ProjectNewRoomMaterialization(
    val roomIdsByProjectId: Map<String, Int>,
    val doorDefPtrsByProjectId: Map<String, List<Int>>,
    val allocations: List<RomAllocation>,
)

/**
 * Emits the minimal native Super Metroid graph for every project-owned room.
 * Allocation is two-phase: all room headers are reserved before door
 * destinations are resolved, which permits forward and cyclic room links.
 */
@OptIn(ExperimentalEncodingApi::class)
class ProjectNewRoomMaterializer(
    private val project: SmEditProject,
    private val parser: RomParser,
    private val romData: ByteArray,
    freeSpaceAllocator: RomFreeSpaceAllocator? = null,
    private val onLog: (String) -> Unit = {},
) {
    private val allocations = mutableListOf<RomAllocation>()
    private val allocator = (
        freeSpaceAllocator ?: RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = parser::snesToPc,
            pcToSnes = parser::pcToSnes,
            guardBytes = 2,
        )
        ).observing(allocations::add)

    private data class PendingRoom(
        val definition: ProjectNewRoom,
        val headerAllocation: RomAllocation,
        val levelPtr: Int,
        val enemyPtr: Int,
        val enemyGfxPtr: Int,
        val scrollPtr: Int,
        val plmPtr: Int,
    )

    fun materialize(): ProjectNewRoomMaterialization {
        if (project.newRooms.isEmpty()) {
            return ProjectNewRoomMaterialization(emptyMap(), emptyMap(), emptyList())
        }
        validateProjectIdentities()

        val pending = project.newRooms.map { definition ->
            val roomLabel = "project room '${definition.name}' (${definition.id})"
            validateDefinition(definition)
            val levelData = decodeLevelData(definition)
            val compressedLevel = LZ5Compressor.compress(levelData)
            val roundTrip = runCatching { LZ5Compressor.decompress(compressedLevel) }.getOrNull()
            if (roundTrip == null || !roundTrip.contentEquals(levelData)) {
                fail("$roomLabel level data failed LZ5 round-trip validation")
            }
            val level = allocate(
                compressedLevel,
                (0xCE downTo 0xC0).toList(),
                "$roomLabel level data",
            )
            val enemies = allocate(encodeEnemies(definition), listOf(0xA1), "$roomLabel enemy population")
            val enemyGfx = allocate(encodeEnemyGfx(definition), listOf(0xB4), "$roomLabel enemy GFX set")
            val scrollPtr = encodeScrollPointer(definition, roomLabel)
            val plms = allocate(encodePlms(definition), listOf(0x8F), "$roomLabel PLM set")
            val header = reserve(11 + 2 + RomConstants.STATE_DATA_SIZE, listOf(0x8F), "$roomLabel header/default state")

            PendingRoom(
                definition = definition,
                headerAllocation = header,
                levelPtr = level.snesAddress,
                enemyPtr = enemies.snesAddress and 0xFFFF,
                enemyGfxPtr = enemyGfx.snesAddress and 0xFFFF,
                scrollPtr = scrollPtr,
                plmPtr = plms.snesAddress and 0xFFFF,
            )
        }

        val roomIds = pending.associate { it.definition.id to (it.headerAllocation.snesAddress and 0xFFFF) }
        val doorDefsByRoom = linkedMapOf<String, Map<Int, Int>>()
        val orderedDoorDefsByRoom = linkedMapOf<String, List<Int>>()
        val doorListByRoom = linkedMapOf<String, Int>()
        for (room in pending) {
            val sourceToNewDoorDef = linkedMapOf<Int, Int>()
            val doorPointers = room.definition.doors.mapIndexed { index, door ->
                val destination = resolveDestination(door, roomIds)
                val bytes = encodeDoor(door, destination)
                val allocation = allocate(
                    bytes,
                    listOf(0x83),
                    "project room '${room.definition.name}' door $index",
                )
                val pointer = allocation.snesAddress and 0xFFFF
                if (door.sourceDoorDefPtr != 0) sourceToNewDoorDef[door.sourceDoorDefPtr] = pointer
                pointer
            }
            val listBytes = ByteArray((doorPointers.size + 1) * 2)
            doorPointers.forEachIndexed { index, pointer -> writeU16(listBytes, index * 2, pointer) }
            val list = allocate(
                listBytes,
                listOf(0x8F),
                "project room '${room.definition.name}' door list",
            )
            doorDefsByRoom[room.definition.id] = sourceToNewDoorDef
            orderedDoorDefsByRoom[room.definition.id] = doorPointers
            doorListByRoom[room.definition.id] = list.snesAddress and 0xFFFF
        }

        for (room in pending) {
            val definition = room.definition
            val state = definition.initialState
            val remappedFx = state.fxEntries.mapNotNull { entry ->
                val newDoor = doorDefsByRoom[definition.id]?.get(entry.doorSelect)
                if (newDoor != null) entry.copy(doorSelect = newDoor)
                else if (entry.doorSelect != 0) null
                else entry
            }.distinctBy { it.doorSelect }.let { entries ->
                val withoutDefault = entries.filter { it.doorSelect != 0 }
                withoutDefault + (entries.lastOrNull { it.doorSelect == 0 } ?: ProjectFxEntry())
            }
            val fx = allocate(
                encodeFx(remappedFx),
                listOf(0x83),
                "project room '${definition.name}' FX table",
            )
            val bytes = ByteArray(room.headerAllocation.size)
            val header = definition.header
            bytes[0] = header.index.toByte()
            bytes[1] = header.area.toByte()
            bytes[2] = header.mapX.toByte()
            bytes[3] = header.mapY.toByte()
            bytes[4] = header.width.toByte()
            bytes[5] = header.height.toByte()
            bytes[6] = header.upScroller.toByte()
            bytes[7] = header.downScroller.toByte()
            bytes[8] = header.creBitflag.toByte()
            writeU16(bytes, 9, doorListByRoom.getValue(definition.id))
            writeU16(bytes, 11, 0xE5E6)
            val stateOffset = 13
            writeU24(bytes, stateOffset, room.levelPtr)
            bytes[stateOffset + 3] = state.tileset.toByte()
            bytes[stateOffset + 4] = state.musicData.toByte()
            bytes[stateOffset + 5] = state.musicTrack.toByte()
            writeU16(bytes, stateOffset + 6, fx.snesAddress and 0xFFFF)
            writeU16(bytes, stateOffset + 8, room.enemyPtr)
            writeU16(bytes, stateOffset + 10, room.enemyGfxPtr)
            writeU16(bytes, stateOffset + 12, state.bgScrolling)
            writeU16(bytes, stateOffset + 14, room.scrollPtr)
            writeU16(bytes, stateOffset + 16, state.xraySpecialCasingPtr)
            writeU16(bytes, stateOffset + 18, state.mainAsmPtr)
            writeU16(bytes, stateOffset + 20, room.plmPtr)
            writeU16(bytes, stateOffset + 22, state.bgDataPtr)
            writeU16(bytes, stateOffset + 24, state.setupAsmPtr)
            allocator.write(room.headerAllocation, bytes)
            onLog(
                "Created project room '${definition.name}' at \$8F:${roomIds.getValue(definition.id).hex4()} " +
                    "(${header.width}x${header.height}, ${definition.doors.size} door(s))"
            )
        }

        return ProjectNewRoomMaterialization(roomIds, orderedDoorDefsByRoom, allocations.toList())
    }

    private fun validateProjectIdentities() {
        val duplicateIds = project.newRooms.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) fail("Duplicate project room IDs: ${duplicateIds.joinToString()}")
        val duplicateHandles = project.newRooms.groupingBy { it.handle }.eachCount().filterValues { it > 1 }.keys
        if (duplicateHandles.isNotEmpty()) fail("Duplicate project room handles: ${duplicateHandles.joinToString()}")
    }

    private fun validateDefinition(room: ProjectNewRoom) {
        val h = room.header
        if (room.id.isBlank()) fail("A project room has an empty stable ID")
        if (room.name.isBlank()) fail("Project room '${room.id}' has an empty name")
        if (h.index !in 0..0xFF) fail("Project room '${room.name}' index ${h.index} is outside 0-255")
        if (h.area !in 0..7) fail("Project room '${room.name}' area ${h.area} is outside 0-7")
        if (h.mapX !in 0 until MinimapData.MAP_WIDTH || h.mapY !in 0 until MinimapData.ROOM_MAP_HEIGHT) {
            fail("Project room '${room.name}' map position is outside the 64x31 room-coordinate area")
        }
        if (h.width !in 1..16 || h.height !in 1..16) fail("Project room '${room.name}' dimensions must be 1-16 screens")
        if (h.mapX + h.width > MinimapData.MAP_WIDTH || h.mapY + h.height > MinimapData.ROOM_MAP_HEIGHT) {
            fail("Project room '${room.name}' map rectangle exceeds the 64x31 room-coordinate area")
        }
        if (listOf(h.upScroller, h.downScroller, h.creBitflag).any { it !in 0..0xFF }) {
            fail("Project room '${room.name}' header contains a value outside its byte range")
        }
        val s = room.initialState
        if (s.tileset !in 0 until TileGraphics.NUM_TILESETS) fail("Project room '${room.name}' tileset ${s.tileset} is invalid")
        if (s.musicData !in 0..0xFF || s.musicTrack !in 0..0xFF) fail("Project room '${room.name}' music values are invalid")
        if (s.bgScrolling !in 0..0xFFFF) fail("Project room '${room.name}' Layer 2 motion is invalid")
        if (s.scrollData.size != h.width * h.height) {
            fail("Project room '${room.name}' has ${s.scrollData.size} scroll cells; expected ${h.width * h.height}")
        }
        if (s.scrollData.any { it !in 0..2 }) fail("Project room '${room.name}' has an invalid scroll value")
        if (s.enemyGfx.size > 4) fail("Project room '${room.name}' exceeds the four-entry enemy GFX limit")
        if (room.doors.size > 16) fail("Project room '${room.name}' exceeds SMEDIT's 16-door limit")
        if (s.fxEntries.count { it.doorSelect == 0 } != 1 || s.fxEntries.lastOrNull()?.doorSelect != 0) {
            fail("Project room '${room.name}' FX table must end with exactly one default entry")
        }
        for (fx in s.fxEntries) {
            val words = listOf(fx.doorSelect, fx.liquidSurfaceStart, fx.liquidSurfaceNew, fx.liquidSpeed)
            val bytes = listOf(
                fx.liquidDelay, fx.fxType, fx.fxBitA, fx.fxBitB, fx.fxBitC,
                fx.paletteFxBitflags, fx.tileAnimBitflags, fx.paletteBlend,
            )
            if (words.any { it !in 0..0xFFFF } || bytes.any { it !in 0..0xFF }) {
                fail("Project room '${room.name}' has an FX value outside the native format")
            }
        }
    }

    private fun decodeLevelData(room: ProjectNewRoom): ByteArray {
        val bytes = runCatching { Base64.decode(room.initialState.levelDataBase64) }.getOrElse {
            fail("Project room '${room.name}' has invalid base64 level data")
        }
        if (bytes.size < 2) fail("Project room '${room.name}' level data is truncated")
        val blocks = room.header.width * 16 * room.header.height * 16
        val layer1Size = readU16(bytes, 0)
        if (layer1Size != blocks * 2 || bytes.size < 2 + layer1Size + blocks) {
            fail(
                "Project room '${room.name}' level payload does not match its " +
                    "${room.header.width}x${room.header.height}-screen dimensions"
            )
        }
        return bytes
    }

    private fun encodeEnemies(room: ProjectNewRoom): ByteArray {
        val out = ByteArray(room.initialState.enemies.size * 16 + 3)
        var offset = 0
        for (enemy in room.initialState.enemies) {
            val fields = listOf(enemy.id, enemy.x, enemy.y, enemy.initParam, enemy.properties, enemy.extra1, enemy.extra2, enemy.extra3)
            if (fields.any { it !in 0..0xFFFF }) fail("Project room '${room.name}' has an invalid enemy field")
            fields.forEachIndexed { index, value -> writeU16(out, offset + index * 2, value) }
            offset += 16
        }
        writeU16(out, offset, 0xFFFF)
        out[offset + 2] = 0
        return out
    }

    private fun encodeEnemyGfx(room: ProjectNewRoom): ByteArray {
        val out = ByteArray(room.initialState.enemyGfx.size * 4 + 2)
        room.initialState.enemyGfx.forEachIndexed { index, entry ->
            if (entry.speciesId !in 0..0xFFFF || entry.paletteIndex !in 0..0xFFFF) {
                fail("Project room '${room.name}' has an invalid enemy GFX entry")
            }
            writeU16(out, index * 4, entry.speciesId)
            writeU16(out, index * 4 + 2, entry.paletteIndex)
        }
        writeU16(out, out.size - 2, 0xFFFF)
        return out
    }

    private fun encodePlms(room: ProjectNewRoom): ByteArray {
        val out = ByteArray(room.initialState.plms.size * 6 + 2)
        room.initialState.plms.forEachIndexed { index, plm ->
            if (plm.id !in 0..0xFFFF || plm.x !in 0..0xFF || plm.y !in 0..0xFF || plm.param !in 0..0xFFFF) {
                fail("Project room '${room.name}' has an invalid PLM entry")
            }
            val offset = index * 6
            writeU16(out, offset, plm.id)
            out[offset + 2] = plm.x.toByte()
            out[offset + 3] = plm.y.toByte()
            writeU16(out, offset + 4, plm.param)
        }
        return out
    }

    private fun encodeScrollPointer(room: ProjectNewRoom, label: String): Int {
        val values = room.initialState.scrollData
        if (values.all { it == 1 }) return 0
        if (values.all { it == 2 }) return 1
        val allocation = allocate(values.map(Int::toByte).toByteArray(), listOf(0x8F), "$label scroll data")
        return allocation.snesAddress and 0xFFFF
    }

    private fun encodeFx(entries: List<ProjectFxEntry>): ByteArray {
        val out = ByteArray(entries.size * 16)
        entries.forEachIndexed { index, fx ->
            val offset = index * 16
            writeU16(out, offset, fx.doorSelect)
            writeU16(out, offset + 2, fx.liquidSurfaceStart)
            writeU16(out, offset + 4, fx.liquidSurfaceNew)
            writeU16(out, offset + 6, fx.liquidSpeed)
            out[offset + 8] = fx.liquidDelay.toByte()
            out[offset + 9] = fx.fxType.toByte()
            out[offset + 10] = fx.fxBitA.toByte()
            out[offset + 11] = fx.fxBitB.toByte()
            out[offset + 12] = fx.fxBitC.toByte()
            out[offset + 13] = fx.paletteFxBitflags.toByte()
            out[offset + 14] = fx.tileAnimBitflags.toByte()
            out[offset + 15] = fx.paletteBlend.toByte()
        }
        return out
    }

    private fun encodeDoor(door: ProjectDoorDefinition, destinationRoomId: Int): ByteArray {
        val words = listOf(destinationRoomId, door.bitflag, door.doorCapCode, door.distFromDoor, door.entryCode)
        if (words.any { it !in 0..0xFFFF } || door.screenX !in 0..0xFF || door.screenY !in 0..0xFF) {
            fail("A project door contains a value outside the native DoorDef format")
        }
        val out = ByteArray(12)
        writeU16(out, 0, destinationRoomId)
        writeU16(out, 2, door.bitflag)
        writeU16(out, 4, door.doorCapCode)
        out[6] = door.screenX.toByte()
        out[7] = door.screenY.toByte()
        writeU16(out, 8, door.distFromDoor)
        writeU16(out, 10, door.entryCode)
        return out
    }

    private fun resolveDestination(door: ProjectDoorDefinition, newRoomIds: Map<String, Int>): Int {
        val value = door.destination
        return when {
            value.startsWith("rom:") -> value.removePrefix("rom:").toIntOrNull(16)
                ?: fail("Invalid ROM room destination '$value'")
            value.startsWith("project:") -> newRoomIds[value.removePrefix("project:")]
                ?: fail("Unknown project room destination '$value'")
            else -> fail("Unknown project room destination format '$value'")
        }
    }

    private fun reserve(size: Int, banks: List<Int>, label: String): RomAllocation =
        allocator.reserve(size, banks, label)
            ?: fail("$label needs $size bytes, but no contiguous free space exists in ${banks.joinToString { "\$${it.toString(16).uppercase()}" }}")

    private fun allocate(bytes: ByteArray, banks: List<Int>, label: String): RomAllocation =
        allocator.allocate(bytes, banks, label)
            ?: fail("$label needs ${bytes.size} bytes, but no contiguous free space exists in ${banks.joinToString { "\$${it.toString(16).uppercase()}" }}")

    private fun fail(message: String): Nothing {
        onLog("ERROR: $message")
        throw ProjectRoomExportException(message)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun Int.hex4(): String = toString(16).uppercase().padStart(4, '0')
}
