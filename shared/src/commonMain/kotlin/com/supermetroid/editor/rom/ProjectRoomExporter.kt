package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.TILE_EDIT_LAYER_2
import kotlin.math.min

class ProjectRoomExportException(message: String) : IllegalStateException(message)

data class ProjectRoomExportResult(
    val roomsPatched: Set<String>,
    /** Complete reserved ranges, including payload bytes whose value remains $FF. */
    val allocations: List<RomAllocation> = emptyList(),
)

/**
 * Applies project room edits directly to a mutable ROM image.
 *
 * This is the shared implementation used by desktop export and headless ROM builds.
 * Callers should pass a copy of the input ROM unless they explicitly want in-place mutation.
 */
class ProjectRoomExporter(
    private val project: SmEditProject,
    private val romParser: RomParser,
    private val romData: ByteArray,
    private val extraItemPlmIds: Set<Int> = emptySet(),
    private val roomAreaOverrides: Map<Int, Int> = project.rooms.mapNotNull { (roomKey, edits) ->
        val roomId = roomKey.toIntOrNull(16) ?: return@mapNotNull null
        edits.roomHeaderChange?.area?.let { roomId to it }
    }.toMap(),
    freeSpaceAllocator: RomFreeSpaceAllocator? = null,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        fun hasRoomEdits(project: SmEditProject): Boolean =
            project.rooms.values.any { it.hasEdits }
    }

    private val allocations = mutableListOf<RomAllocation>()
    private var stateGraphRedirectRoutinePtr: Int? = null
    private var statePredicateRoutinePtr: Int? = null
    private var stateExpressionInterpreterPtr: Int? = null
    private val stateExpressionPointers = mutableMapOf<Pair<Int, com.supermetroid.editor.data.ProjectRoomStateCondition>, Int>()

    private val allocationSession = (
        freeSpaceAllocator ?: RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = romParser::snesToPc,
            pcToSnes = romParser::pcToSnes,
            guardBytes = 2,
        )
        ).observing(allocations::add)

    // These names document each pointer's bank constraints. They deliberately
    // share one allocation session so no exporter owns an independent cursor.
    private val roomDataAllocator = allocationSession
    private val levelDataAllocator = allocationSession
    private val enemyAllocator = allocationSession
    private val enemyGfxAllocator = allocationSession

    private val vanillaEnemyGfxDestinationsBySpecies by lazy {
        collectVanillaEnemyGfxDestinations(romParser)
    }

    fun exportRooms(): ProjectRoomExportResult {
        val roomsPatched = linkedSetOf<String>()

        for ((roomKey, roomEdits) in project.rooms) {
            if (!roomEdits.hasEdits) continue
            val roomId = roomKey.toIntOrNull(16)
                ?: failExport("Project room key '$roomKey' is not a hexadecimal room ID")
            val room = romParser.readRoomHeader(roomId)
                ?: failExport("Room 0x$roomKey has edits but its room header could not be read")

            val headerChange = roomEdits.roomHeaderChange
            val effectiveWidth = headerChange?.width ?: room.width
            val effectiveHeight = headerChange?.height ?: room.height
            val isResized = effectiveWidth != room.width || effectiveHeight != room.height

            if (roomEdits.hasTileEdits || isResized) {
                if (room.levelDataPtr == 0) {
                    failExport("Room 0x$roomKey has level edits but no level-data pointer")
                }
                if (applyLevelDataEdits(roomKey, roomId, room, roomEdits, effectiveWidth, effectiveHeight, isResized)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (roomEdits.plmChanges.isNotEmpty()) {
                if (applyPlmChanges(roomKey, roomId, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (roomEdits.customScrollCommands.isNotEmpty()) {
                if (applyCustomScrollCommands(roomKey, roomId, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (roomEdits.doorChanges.isNotEmpty()) {
                if (room.doorOut == 0 || room.doorOut == 0xFFFF) {
                    failExport("Room 0x$roomKey has door edits but no writable door list")
                }
                roomsPatched.addAll(applyDoorChanges(roomKey, roomId, room, roomEdits))
            }

            if (roomEdits.enemyChanges.isNotEmpty()) {
                if (room.enemySetPtr == 0 || room.enemySetPtr == 0xFFFF) {
                    failExport("Room 0x$roomKey has enemy edits but no writable enemy-population pointer")
                }
                if (applyEnemyPopulationChanges(roomKey, roomId, room, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
                if (room.enemyGfxPtr == 0 || room.enemyGfxPtr == 0xFFFF) {
                    failExport("Room 0x$roomKey has enemy edits but no writable enemy-GFX pointer")
                }
                applyEnemyGfxChanges(roomKey, roomId, room, roomEdits)
            }

            if (roomEdits.scrollChanges.isNotEmpty() || isResized) {
                if (applyScrollChanges(roomKey, roomId, room, roomEdits, effectiveWidth, effectiveHeight, isResized)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (isResized && effectiveWidth != room.width) {
                applyResizedScrollCommandRemap(roomKey, roomId, room, effectiveWidth, effectiveHeight)
                applyResizedDoorAsmRemap(roomKey, roomId, room, effectiveWidth, effectiveHeight)
            }

            if (roomEdits.fxChange != null) {
                if (applyFxChange(roomKey, roomId, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (headerChange != null) {
                applyRoomHeaderChange(roomKey, roomId, headerChange)
                roomsPatched.add(roomKey)
            }

            if (roomEdits.stateDataChange != null) {
                applyStateDataChange(roomKey, roomId, roomEdits)
                roomsPatched.add(roomKey)
            }

            val rewrittenStateOffsets = if (roomEdits.stateGraphChanged) {
                rewriteStateGraph(roomKey, roomId, roomEdits)
            } else {
                emptyMap()
            }

            if (roomEdits.states.any { it.hasEdits }) {
                applyExistingStateEdits(roomKey, roomId, roomEdits, rewrittenStateOffsets)
                roomsPatched.add(roomKey)
            }
            if (roomEdits.stateGraphChanged) roomsPatched.add(roomKey)

            if (roomEdits.saveStationSpawns.isNotEmpty()) {
                if (applySaveStationSpawns(roomKey, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
            }
        }

        return ProjectRoomExportResult(
            roomsPatched = roomsPatched,
            allocations = allocations.toList(),
        )
    }

    private fun applyLevelDataEdits(
        roomKey: String,
        roomId: Int,
        room: Room,
        roomEdits: RoomEdits,
        effectiveWidth: Int,
        effectiveHeight: Int,
        isResized: Boolean,
        targetStateOffsets: List<Int>? = null,
    ): Boolean {
        val roomStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val allStateOffsets = targetStateOffsets ?: roomStateOffsets
        if (allStateOffsets.isEmpty()) {
            failExport("Room 0x$roomKey has level edits but no writable room-state data")
        }
        val blocksWide = effectiveWidth * 16

        val ptrToStates = linkedMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val levelPtr = readU24(romData, stateOffset)
            if (levelPtr != 0) ptrToStates.getOrPut(levelPtr) { mutableListOf() }.add(stateOffset)
        }

        if (ptrToStates.size > 1) {
            onLog(
                "Room 0x$roomKey: ${ptrToStates.size} distinct level data pointers across " +
                    "${allStateOffsets.size} states; applying edits to all"
            )
        }

        var wrote = false
        for ((levelPtr, statesForPtr) in ptrToStates) {
            val decompressed = runCatching { romParser.decompressLZ2WithSize(levelPtr) }.getOrNull()
            if (decompressed == null) {
                failExport("Room 0x$roomKey level data \$${levelPtr.toString(16)} could not be decompressed")
            }
            val (originalData, originalSize) = decompressed
            val editedData = if (isResized) {
                resizeLevelData(originalData, room.width, room.height, effectiveWidth, effectiveHeight)
            } else {
                originalData.copyOf()
            }
            if (editedData.size < 2) {
                failExport("Room 0x$roomKey level data is truncated (${editedData.size} bytes)")
            }

            val layer1Size = readU16(editedData, 0)
            val totalBlocks = blocksWide * effectiveHeight * 16
            val layer2Start = 2 + layer1Size + totalBlocks
            val stateBgScrolling = statesForPtr.firstOrNull()?.let { readU16(romData, it + 12) }
                ?: roomEdits.stateDataChange?.bgScrolling
                ?: room.bgScrolling
            val hasEmbeddedLayer2 = layer2Start + totalBlocks * 2 <= editedData.size && stateBgScrolling == 0

            for (op in roomEdits.operations) {
                for (edit in op.edits) {
                    val index = edit.blockY * blocksWide + edit.blockX
                    if (edit.blockX !in 0 until blocksWide || edit.blockY !in 0 until effectiveHeight * 16 ||
                        index !in 0 until totalBlocks
                    ) {
                        failExport(
                            "Room 0x$roomKey has a tile edit at (${edit.blockX},${edit.blockY}) outside " +
                                "its ${blocksWide}x${effectiveHeight * 16}-block bounds"
                        )
                    }
                    if (edit.layer == TILE_EDIT_LAYER_2) {
                        if (!hasEmbeddedLayer2) {
                            failExport(
                                "Room 0x$roomKey has a layer-2 tile edit but its active state does not " +
                                    "contain an embedded editable layer 2"
                            )
                        }
                        val offset = layer2Start + index * 2
                        val word = edit.newBlockWord and 0x0FFF
                        writeU16(editedData, offset, word)
                        continue
                    }

                    val wordOffset = 2 + index * 2
                    if (wordOffset + 1 >= editedData.size) {
                        failExport("Room 0x$roomKey layer-1 tile edit points outside decompressed level data")
                    }
                    writeU16(editedData, wordOffset, edit.newBlockWord)
                    val btsOffset = 2 + layer1Size + index
                    if (btsOffset >= editedData.size) {
                        failExport("Room 0x$roomKey BTS edit points outside decompressed level data")
                    }
                    editedData[btsOffset] = edit.newBts.toByte()
                }
            }

            val compressed = LZ5Compressor.compress(editedData)
            val roundTripped = runCatching { LZ5Compressor.decompress(compressed) }.getOrNull()
            if (roundTripped == null || !roundTripped.contentEquals(editedData)) {
                failExport("Room 0x$roomKey level data failed LZ5 round-trip validation")
            }

            val levelPc = romParser.snesToPc(levelPtr)
            val sharedOutsideRoom = hasExternalRoomStateReference(
                roomId,
                stateFieldOffset = 0,
                value = levelPtr,
                u24 = true,
            )
            val sharedWithUntargetedState = roomStateOffsets.any { stateOffset ->
                stateOffset !in allStateOffsets && readU24(romData, stateOffset) == levelPtr
            }
            if (compressed.size <= originalSize && !sharedOutsideRoom && !sharedWithUntargetedState) {
                compressed.copyInto(romData, levelPc)
                for (i in compressed.size until originalSize) romData[levelPc + i] = 0xFF.toByte()
                wrote = true
            } else {
                val allocation = levelDataAllocator.allocate(
                    bytes = compressed,
                    banks = levelDataRelocationBanks(levelPtr),
                    label = "room 0x$roomKey level data",
                )
                if (allocation == null) failExport(
                    "Room 0x$roomKey level data needs a private ${compressed.size}-byte allocation " +
                        (if (sharedOutsideRoom || sharedWithUntargetedState) "because another state shares its pointer" else "because it exceeds $originalSize bytes") +
                        ", and no contiguous free space was found in banks " +
                        levelDataRelocationBanks(levelPtr).joinToString { "\$${it.toString(16).uppercase()}" }
                )
                val newSnes = allocation.snesAddress
                for (stateOffset in statesForPtr) writeU24(romData, stateOffset, newSnes)
                onLog(
                    "Room 0x$roomKey: relocated level data \$${levelPtr.toString(16)} to " +
                        "\$${allocation.bank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} " +
                        "(${compressed.size} bytes, updated ${statesForPtr.size} state(s)" +
                        if (sharedOutsideRoom) ", copy-on-write for shared pointer)" else ")"
                )
                wrote = true
            }
        }
        return wrote
    }

    private fun applyPlmChanges(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
        targetStateOffsets: List<Int>? = null,
    ): Boolean {
        val roomStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val allStateOffsets = targetStateOffsets ?: roomStateOffsets
        val distinctPlmPtrs = linkedSetOf<Int>()
        for (stateOffset in allStateOffsets) {
            val plmPtr = readU16(romData, stateOffset + 20)
            if (plmPtr != 0 && plmPtr != 0xFFFF) distinctPlmPtrs.add(plmPtr)
        }
        if (distinctPlmPtrs.isEmpty()) {
            failExport("Room 0x$roomKey has PLM edits but no writable PLM-set pointer")
        }

        data class PlmSetData(
            val plmSetPtr: Int,
            val originalSize: Int,
            val plms: List<RomParser.PlmEntry>,
        )

        val plmSets = mutableListOf<PlmSetData>()
        for (plmSetPtr in distinctPlmPtrs) {
            val originalPlms = romParser.parsePlmSet(plmSetPtr)
            val modifiedPlms = originalPlms.toMutableList()
            for (change in roomEdits.plmChanges) {
                when (change.action) {
                    "add" -> modifiedPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> modifiedPlms.removeAll {
                        it.id == change.plmId && it.x == change.x && it.y == change.y
                    }
                }
            }
            plmSets.add(
                PlmSetData(
                    plmSetPtr = plmSetPtr,
                    originalSize = originalPlms.size * 6 + 2,
                    plms = dedupeItemPlmsByPosition(modifiedPlms),
                )
            )
        }

        var wrote = false
        for (plmSet in plmSets) {
            for (plm in plmSet.plms) {
                if (plm.id !in 0..0xFFFF || plm.param !in 0..0xFFFF ||
                    plm.x !in 0..0xFF || plm.y !in 0..0xFF
                ) {
                    failExport(
                        "Room 0x$roomKey PLM 0x${plm.id.toString(16)} has values outside the " +
                            "ROM format (x/y must be bytes; id/parameter must be words)"
                    )
                }
            }
            val serialized = RomParser.serializePlmSet(plmSet.plms)
            val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or plmSet.plmSetPtr)
            val writePc: Int

            val sharedOutsideRoom = hasExternalRoomStateReference(
                roomId,
                stateFieldOffset = 20,
                value = plmSet.plmSetPtr,
            )
            val sharedWithUntargetedState = roomStateOffsets.any { stateOffset ->
                stateOffset !in allStateOffsets && readU16(romData, stateOffset + 20) == plmSet.plmSetPtr
            }
            if (serialized.size <= plmSet.originalSize && !sharedOutsideRoom && !sharedWithUntargetedState) {
                writePc = plmPc
            } else {
                val allocation = roomDataAllocator.reserve(
                    size = serialized.size,
                    banks = listOf(0x8F),
                    label = "room 0x$roomKey PLM set",
                )
                if (allocation == null) failExport(
                    "Room 0x$roomKey PLM set 0x${plmSet.plmSetPtr.toString(16)} needs a private " +
                        "${serialized.size}-byte allocation" +
                        (if (sharedOutsideRoom || sharedWithUntargetedState) " because another state shares its pointer" else "") +
                        ", but bank \$8F has no contiguous free space"
                )
                writePc = allocation.pcOffset
                val newPtr = allocation.snesAddress and 0xFFFF
                var updatedStates = 0
                for (stateOffset in allStateOffsets) {
                    val existingPtr = readU16(romData, stateOffset + 20)
                    if (existingPtr == plmSet.plmSetPtr) {
                        writeU16(romData, stateOffset + 20, newPtr)
                        updatedStates++
                    }
                }
                onLog(
                    "Room 0x$roomKey: relocated PLM set 0x${plmSet.plmSetPtr.toString(16)} " +
                        "to 0x${allocation.snesAddress.toString(16)} (updated $updatedStates states" +
                        if (sharedOutsideRoom) ", copy-on-write for shared pointer)" else ")"
                )
            }

            for (plm in plmSet.plms) {
                val name = RomParser.plmDisplayName(plm.id, plm.param)
                onLog("  PLM: $name (0x${plm.id.toString(16)}) at (${plm.x},${plm.y}) param=0x${plm.param.toString(16)}")
            }
            for ((index, byte) in serialized.withIndex()) romData[writePc + index] = byte.toByte()
            if (writePc == plmPc) {
                for (i in writePc + serialized.size until plmPc + plmSet.originalSize) romData[i] = 0
            }
            wrote = true
        }
        return wrote
    }

    private fun applyCustomScrollCommands(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
        targetStateOffsets: List<Int>? = null,
    ): Boolean {
        val commandIdToPtr = mutableMapOf<String, Int>()
        for ((commandId, commands) in roomEdits.customScrollCommands) {
            if (commands.isEmpty()) continue
            val invalidCommand = commands.firstOrNull {
                it.screenIndex !in 0..0xFF || it.scrollValue !in 0..2
            }
            if (invalidCommand != null) {
                failExport(
                    "Room 0x$roomKey custom scroll command '$commandId' has invalid entry " +
                        "screen=${invalidCommand.screenIndex}, value=${invalidCommand.scrollValue}; " +
                        "the encoded ranges are screen 0-255 and value 0-2"
                )
            }
            val bytes = ByteArray(commands.size * 2 + 1)
            var offset = 0
            for (command in commands) {
                bytes[offset++] = command.screenIndex.toByte()
                bytes[offset++] = command.scrollValue.toByte()
            }
            bytes[offset] = 0x80.toByte()

            val allocation = roomDataAllocator.allocate(
                bytes = bytes,
                banks = listOf(0x8F),
                label = "room 0x$roomKey scroll command $commandId",
            )
            if (allocation == null) failExport(
                "Room 0x$roomKey custom scroll command '$commandId' needs ${bytes.size} bytes but " +
                    "bank \$8F has no contiguous free space"
            )
            val ptr = allocation.snesAddress and 0xFFFF
            commandIdToPtr[commandId] = ptr
            onLog(
                "Room 0x$roomKey: wrote custom scroll command '$commandId' (${commands.size} entries) " +
                    "at \$8F:${ptr.toString(16).uppercase()}"
            )
        }

        if (commandIdToPtr.isEmpty()) return false

        val allStateOffsets = targetStateOffsets ?: romParser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            failExport("Room 0x$roomKey has custom scroll commands but no writable room-state data")
        }
        val linkedCommandIds = mutableSetOf<String>()
        for (stateOffset in allStateOffsets) {
            val plmPtr = readU16(romData, stateOffset + 20)
            if (plmPtr == 0 || plmPtr == 0xFFFF) continue
            var off = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or plmPtr)
            while (off + 5 < romData.size) {
                val plmId = readU16(romData, off)
                if (plmId == 0) break
                val paramOffset = off + 4
                val param = readU16(romData, paramOffset)
                if (plmId == 0xB703 && (param and 0xFF00) == 0xCC00) {
                    val commandIndex = param and 0xFF
                    val commandId = "cmd_$commandIndex"
                    val ptr = commandIdToPtr[commandId]
                    if (ptr != null) {
                        writeU16(romData, paramOffset, ptr)
                        linkedCommandIds.add(commandId)
                    }
                }
                off += 6
            }
        }
        val unlinked = commandIdToPtr.keys - linkedCommandIds
        if (unlinked.isNotEmpty()) {
            failExport(
                "Room 0x$roomKey custom scroll command(s) ${unlinked.sorted().joinToString()} are not " +
                    "referenced by a scroll PLM; refusing to export unreachable command data"
            )
        }
        return true
    }

    private fun applyDoorChanges(
        roomKey: String,
        roomId: Int,
        room: Room,
        roomEdits: RoomEdits,
    ): Set<String> {
        val patchedRooms = linkedSetOf<String>()
        val byIndex = roomEdits.doorChanges.groupBy { it.doorIndex }
        for ((doorIndex, changes) in byIndex) {
            val change = changes.last()
            if (change.destRoomPtr !in 0..0xFFFF || change.bitflag !in 0..0xFFFF ||
                change.doorCapCode !in 0..0xFFFF || change.screenX !in 0..0xFF ||
                change.screenY !in 0..0xFF || change.distFromDoor !in 0..0xFFFF ||
                change.entryCode !in 0..0xFFFF
            ) {
                failExport("Room 0x$roomKey door $doorIndex contains a value outside its encoded byte/word range")
            }
            val entryPc = romParser.doorEntryPcOffset(room.doorOut, doorIndex)
                ?: failExport("Room 0x$roomKey door $doorIndex has no writable door-data entry")
            if (entryPc + 11 >= romData.size) {
                failExport("Room 0x$roomKey door $doorIndex data extends outside ROM bounds")
            }

            val orientation = (change.bitflag shr 8) and 0xFF
            val dirName = arrayOf("Right", "Left", "Down", "Up")[orientation and 3]
            val capStr = if (orientation and 0x04 != 0) " +cap" else ""
            val capX = change.doorCapCode and 0xFF
            val capY = (change.doorCapCode shr 8) and 0xFF
            val vanillaDestPtr = romParser.readUInt16At(entryPc)
            val vanillaOrient = romParser.readByteAt(entryPc + 3)
            val vanillaCapX = romParser.readByteAt(entryPc + 4)
            val vanillaCapY = romParser.readByteAt(entryPc + 5)
            val crossArea = if (change.bitflag and 0x40 != 0) " CROSS-AREA" else ""

            onLog(
                "Room 0x$roomKey door $doorIndex: orient=$orientation($dirName$capStr) cap=($capX,$capY) " +
                    "dest=0x${change.destRoomPtr.toString(16)} entry=0x${change.entryCode.toString(16)} " +
                    "bitflag=0x${change.bitflag.toString(16)}$crossArea"
            )
            onLog(
                "  vanilla: dest=0x${vanillaDestPtr.toString(16)} orient=$vanillaOrient cap=($vanillaCapX,$vanillaCapY) " +
                    "bitflag=0x${romParser.readUInt16At(entryPc + 2).toString(16)}"
            )

            var finalCapCode = change.doorCapCode
            var finalOrientation = orientation
            val destRoom = romParser.readRoomHeader(change.destRoomPtr)
            if (destRoom != null) {
                val maxX = destRoom.width * 16
                val maxY = destRoom.height * 16
                if (capX >= maxX || capY >= maxY) {
                    onLog(
                        "WARN: Room 0x$roomKey door $doorIndex cap position ($capX,$capY) is out of bounds " +
                            "for dest room 0x${change.destRoomPtr.toString(16)} (${destRoom.width}x${destRoom.height} screens)"
                    )
                    val derived = romParser.deriveDoorCapPosition(
                        change.destRoomPtr,
                        orientation and 3,
                        change.screenX,
                        change.screenY,
                    )
                    if (derived != null) {
                        finalCapCode = derived
                        onLog("  FIX: auto-derived valid cap -> (${derived and 0xFF},${(derived shr 8) and 0xFF})")
                    } else {
                        finalOrientation = orientation and 0xFB
                        onLog("  FIX: could not derive cap, cleared cap flag (orient $orientation -> $finalOrientation)")
                    }
                }
            } else {
                failExport(
                    "Room 0x$roomKey door $doorIndex destination 0x${change.destRoomPtr.toString(16)} " +
                        "does not have a readable room header"
                )
            }

            var finalBitflag = change.bitflag
            val sourceArea = roomAreaOverrides[roomId] ?: room.area
            val destinationArea = roomAreaOverrides[change.destRoomPtr] ?: destRoom.area
            val expectedCrossArea = sourceArea != destinationArea
            val correctedBitflag = if (expectedCrossArea) finalBitflag or 0x40 else finalBitflag and 0x40.inv()
            if (correctedBitflag != finalBitflag) {
                val action = if (expectedCrossArea) "set" else "cleared"
                onLog("  FIX: $action cross-area flag (area $sourceArea -> $destinationArea)")
                finalBitflag = correctedBitflag
            }

            var finalEntryCode = change.entryCode
            if (shouldClearEnemyBg2TransferOnDoor(roomId, change.destRoomPtr)) {
                val scrollWrites = parseDoorScrollWrites(romParser, change.entryCode)
                if (change.entryCode == 0 || scrollWrites.isNotEmpty()) {
                    val asm = buildDoorAsmClearingEnemyBg2Transfer(scrollWrites)
                    val allocation = roomDataAllocator.allocate(
                        bytes = asm,
                        banks = listOf(0x8F),
                        label = "room 0x$roomKey stale enemy BG2 cleanup door ASM",
                    )
                    if (allocation == null) failExport(
                        "Room 0x$roomKey door $doorIndex needs ${asm.size} bytes of arrival ASM to clear " +
                            "stale enemy BG2 state, but bank \$8F has no contiguous free space"
                    )
                    finalEntryCode = allocation.snesAddress and 0xFFFF
                    val preserved = if (scrollWrites.isNotEmpty()) {
                        ", preserved ${scrollWrites.size} scroll write(s)"
                    } else {
                        ""
                    }
                    onLog(
                        "  FIX: generated arrival ASM to clear stale enemy BG2 transfer flag$preserved " +
                            "(was \$8F:${change.entryCode.toString(16).uppercase()}, " +
                            "now \$8F:${finalEntryCode.toString(16).uppercase()})"
                    )
                } else {
                    failExport(
                        "Room 0x$roomKey door $doorIndex uses custom entry ASM " +
                            "\$8F:${change.entryCode.toString(16).uppercase()}, so the required enemy BG2 " +
                            "cleanup cannot be composed safely"
                    )
                }
            }

            patchedRooms.addAll(
                cloneDoorDependentBgTransferIfNeeded(
                    roomKey = roomKey,
                    roomId = roomId,
                    doorIndex = doorIndex,
                    change = change,
                    destRoom = destRoom,
                    finalBitflag = finalBitflag,
                    finalCapCode = finalCapCode,
                    finalEntryCode = finalEntryCode,
                )
            )

            writeU16(romData, entryPc, change.destRoomPtr)
            writeU16(romData, entryPc + 2, finalBitflag)
            romData[entryPc + 3] = finalOrientation.toByte()
            writeU16(romData, entryPc + 4, finalCapCode)
            romData[entryPc + 6] = (change.screenX and 0xFF).toByte()
            romData[entryPc + 7] = (change.screenY and 0xFF).toByte()
            writeU16(romData, entryPc + 8, change.distFromDoor)
            writeU16(romData, entryPc + 10, finalEntryCode)
            patchedRooms.add(roomKey)
        }
        return patchedRooms
    }

    private fun cloneDoorDependentBgTransferIfNeeded(
        roomKey: String,
        roomId: Int,
        doorIndex: Int,
        change: DoorChange,
        destRoom: Room?,
        finalBitflag: Int,
        finalCapCode: Int,
        finalEntryCode: Int,
    ): Set<String> {
        val sourceDoorOut = romParser.readRoomHeader(roomId)?.doorOut ?: return emptySet()
        val doorListPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or sourceDoorOut)
        val doorDefPtr = readU16(romData, doorListPc + doorIndex * 2)
        if (doorDefPtr < 0x8000 || destRoom == null) return emptySet()

        val patchedRooms = linkedSetOf<String>()
        val bgDoor = RomParser.DoorEntry(
            destRoomPtr = change.destRoomPtr,
            bitflag = finalBitflag,
            doorCapCode = finalCapCode,
            screenX = change.screenX,
            screenY = change.screenY,
            distFromDoor = change.distFromDoor,
            entryCode = finalEntryCode,
            doorDefPtr = doorDefPtr,
        )
        val bgParser = RomParser(romData)
        val destStateOffsets = bgParser.findAllStateDataOffsets(change.destRoomPtr)
        val distinctBgPtrs = destStateOffsets
            .map { stateOffset -> readU16(romData, stateOffset + 22) }
            .filter { it != 0 && it != 0xFFFF }
            .distinct()

        for (oldBgPtr in distinctBgPtrs) {
            val currentParser = RomParser(romData)
            val template = findMatchingDoorDependentBgTransfer(currentParser, oldBgPtr, bgDoor) ?: continue
            val newBgData = buildBgDataWithClonedDoorDependentTransfer(
                currentParser,
                oldBgPtr,
                doorDefPtr,
                template,
            ) ?: continue
            val allocation = roomDataAllocator.allocate(
                bytes = newBgData,
                banks = listOf(0x8F),
                label = "room 0x$roomKey door-dependent BG data",
            )
            if (allocation == null) failExport(
                "Room 0x$roomKey door $doorIndex needs ${newBgData.size} bytes to clone door-dependent " +
                    "BG data for destination 0x${change.destRoomPtr.toString(16)}, but bank \$8F has no " +
                    "contiguous free space"
            )
            val newBgPtr = allocation.snesAddress and 0xFFFF
            for (stateOffset in destStateOffsets) {
                val stateBgPtr = readU16(romData, stateOffset + 22)
                if (stateBgPtr == oldBgPtr) writeU16(romData, stateOffset + 22, newBgPtr)
            }
            patchedRooms.add(change.destRoomPtr.toString(16).uppercase())
            onLog(
                "  FIX: cloned door-dependent BG transfer for dest room 0x${change.destRoomPtr.toString(16)} " +
                    "door \$83:${doorDefPtr.toString(16).uppercase()} from template door " +
                    "\$83:${template.doorDefPtr.toString(16).uppercase()} " +
                    "(bg \$8F:${oldBgPtr.toString(16).uppercase()} -> \$8F:${newBgPtr.toString(16).uppercase()})"
            )
        }
        return patchedRooms
    }

    private fun applyEnemyPopulationChanges(
        roomKey: String,
        roomId: Int,
        room: Room,
        roomEdits: RoomEdits,
        targetStateOffsets: List<Int>? = null,
        targetEnemySetPtr: Int? = null,
    ): Boolean {
        val enemySetPtr = targetEnemySetPtr ?: room.enemySetPtr
        val originalEnemies = romParser.parseEnemyPopulation(enemySetPtr)
        val originalSet = originalEnemies.toSet()
        val modified = originalEnemies.toMutableList()
        for (change in roomEdits.enemyChanges) {
            when (change.action) {
                "add" -> modified.add(
                    RomParser.EnemyEntry(
                        change.enemyId,
                        change.x,
                        change.y,
                        change.initParam,
                        change.properties,
                        change.extra1,
                        change.extra2,
                        change.extra3,
                    )
                )
                "remove" -> modified.removeAll {
                    it.id == change.enemyId && it.x == change.origX && it.y == change.origY
                }
                "update" -> {
                    val index = modified.indexOfFirst {
                        it.id == change.enemyId && it.x == change.origX && it.y == change.origY
                    }
                    if (index >= 0) {
                        modified[index] = RomParser.EnemyEntry(
                            change.enemyId,
                            change.x,
                            change.y,
                            change.initParam,
                            change.properties,
                            change.extra1,
                            change.extra2,
                            change.extra3,
                        )
                    }
                }
            }
        }

        val enemyPc = romParser.snesToPc(RomConstants.BANK_ENEMY_SET or enemySetPtr)
        val killCountPc = enemyPc + originalEnemies.size * 16 + 2
        val killCount = if (killCountPc < romData.size) romData[killCountPc] else 0
        val originalSize = originalEnemies.size * 16 + 3
        val newSize = modified.size * 16 + 3
        val invalidEnemy = modified.firstOrNull { enemy ->
            listOf(
                enemy.id,
                enemy.x,
                enemy.y,
                enemy.initParam,
                enemy.properties,
                enemy.extra1,
                enemy.extra2,
                enemy.extra3,
            ).any { it !in 0..0xFFFF }
        }
        if (invalidEnemy != null) {
            failExport(
                "Room 0x$roomKey enemy 0x${invalidEnemy.id.toString(16)} contains a value outside " +
                    "the 16-bit enemy-population format"
            )
        }
        val sharedOutsideRoom = hasExternalRoomStateReference(
            roomId,
            stateFieldOffset = 8,
            value = enemySetPtr,
        )
        val roomStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val selectedStateOffsets = targetStateOffsets ?: roomStateOffsets
        val sharedWithUntargetedState = roomStateOffsets.any { stateOffset ->
            stateOffset !in selectedStateOffsets && readU16(romData, stateOffset + 8) == enemySetPtr
        }

        val writePc: Int
        if (newSize <= originalSize && !sharedOutsideRoom && !sharedWithUntargetedState) {
            writePc = enemyPc
        } else {
            val allocation = enemyAllocator.reserve(
                size = newSize,
                banks = listOf(0xA1),
                label = "room 0x$roomKey enemy population",
            )
            if (allocation == null) failExport(
                "Room 0x$roomKey enemy population needs a private $newSize-byte allocation" +
                    (if (sharedOutsideRoom || sharedWithUntargetedState) " because another state shares its pointer" else "") +
                    ", but bank \$A1 has no contiguous free space"
            )
            writePc = allocation.pcOffset
            val newPtr = allocation.snesAddress and 0xFFFF
            for (stateOffset in selectedStateOffsets) {
                val existingPtr = readU16(romData, stateOffset + 8)
                if (existingPtr == enemySetPtr) writeU16(romData, stateOffset + 8, newPtr)
            }
            onLog(
                "Room 0x$roomKey: relocated enemy set to 0x${allocation.snesAddress.toString(16)}" +
                    if (sharedOutsideRoom) " (copy-on-write for shared pointer)" else ""
            )
        }

        val originalSpeciesIds = originalEnemies.map { it.id }.toSet()
        var offset = writePc
        for (enemy in modified) {
            writeU16(romData, offset, enemy.id)
            writeU16(romData, offset + 2, enemy.x)
            writeU16(romData, offset + 4, enemy.y)
            writeU16(romData, offset + 6, enemy.initParam)
            val props = if (enemy in originalSet || enemy.id in originalSpeciesIds) {
                enemy.properties
            } else {
                enemy.properties or 0x2000
            }
            writeU16(romData, offset + 8, props)
            writeU16(romData, offset + 10, enemy.extra1)
            writeU16(romData, offset + 12, enemy.extra2)
            writeU16(romData, offset + 14, enemy.extra3)
            offset += 16
        }
        writeU16(romData, offset, 0xFFFF)
        offset += 2
        romData[offset] = killCount
        offset++
        if (writePc == enemyPc) {
            while (offset < enemyPc + originalSize) {
                romData[offset] = 0
                offset++
            }
        }
        return true
    }

    private fun applyEnemyGfxChanges(
        roomKey: String,
        roomId: Int,
        room: Room,
        roomEdits: RoomEdits,
        targetStateOffsets: List<Int>? = null,
        targetEnemySetPtr: Int? = null,
        targetEnemyGfxPtr: Int? = null,
    ): Boolean {
        val enemySetPtr = targetEnemySetPtr ?: room.enemySetPtr
        val enemyGfxPtr = targetEnemyGfxPtr ?: room.enemyGfxPtr
        val gfxEntries = romParser.parseEnemyGfxSet(enemyGfxPtr)
        val existingSpecies = gfxEntries.map { it.speciesId }.toSet()
        val vanillaPopulation = romParser.parseEnemyPopulation(enemySetPtr)
        val vanillaSpecies = vanillaPopulation.map { it.id }.toSet()

        val finalPopulation = vanillaPopulation.toMutableList()
        for (change in roomEdits.enemyChanges) {
            when (change.action) {
                "add" -> finalPopulation.add(RomParser.EnemyEntry(change.enemyId, change.x, change.y, change.initParam, change.properties))
                "remove" -> finalPopulation.removeAll {
                    it.id == change.enemyId && it.x == change.origX && it.y == change.origY
                }
            }
        }

        val finalSpecies = finalPopulation.map { it.id }.toSet()
        val neededSpecies = (finalSpecies - vanillaSpecies).filter { it !in existingSpecies }
        val skippedVanilla = (finalSpecies intersect vanillaSpecies) - existingSpecies
        if (skippedVanilla.isNotEmpty()) {
            onLog(
                "Room 0x$roomKey: skipped ${skippedVanilla.size} vanilla species from GFX set " +
                    "(${skippedVanilla.joinToString { "0x${it.toString(16)}" }})"
            )
        }
        if (neededSpecies.isEmpty()) return false

        val newEntries = gfxEntries.toMutableList()
        for (speciesId in neededSpecies) {
            if (newEntries.size >= 4) {
                failExport(
                    "Room 0x$roomKey needs enemy species 0x${speciesId.toString(16)} in its GFX set, " +
                        "but the SNES hardware limit is four species entries"
                )
            }
            val speciesPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            val speciesHp = if (speciesPc + 6 < romData.size) readU16(romData, speciesPc + 4) else 0
            if (speciesHp == 0) {
                failExport(
                    "Room 0x$roomKey enemy species 0x${speciesId.toString(16)} has HP=0 or an invalid " +
                        "species header and cannot be added safely"
                )
            }
            val vramDestination = selectEnemyGfxVramDestination(
                existingEntries = newEntries,
                vanillaDestinations = vanillaEnemyGfxDestinationsBySpecies[speciesId].orEmpty(),
            )
            if (vramDestination == null) {
                failExport(
                    "Room 0x$roomKey enemy species 0x${speciesId.toString(16)} has no safe enemy-GFX " +
                        "VRAM destination"
                )
            }
            newEntries.add(RomParser.EnemyGfxEntry(speciesId, vramDestination))
            onLog("Room 0x$roomKey: added species 0x${speciesId.toString(16)} to GFX set (vramDst=0x${vramDestination.toString(16)})")
        }
        if (newEntries.size == gfxEntries.size) return false

        val gfxPc = romParser.snesToPc(RomConstants.BANK_ENEMY_GFX or enemyGfxPtr)
        val originalGfxSize = gfxEntries.size * 4 + 2
        val newGfxSize = newEntries.size * 4 + 2
        val sharedOutsideRoom = hasExternalRoomStateReference(
            roomId,
            stateFieldOffset = 10,
            value = enemyGfxPtr,
        )
        val roomStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val selectedStateOffsets = targetStateOffsets ?: roomStateOffsets
        val sharedWithUntargetedState = roomStateOffsets.any { stateOffset ->
            stateOffset !in selectedStateOffsets && readU16(romData, stateOffset + 10) == enemyGfxPtr
        }
        val writeGfxPc: Int

        if (newGfxSize <= originalGfxSize && !sharedOutsideRoom && !sharedWithUntargetedState) {
            writeGfxPc = gfxPc
        } else {
            val allocation = enemyGfxAllocator.reserve(
                size = newGfxSize,
                banks = listOf(0xB4),
                label = "room 0x$roomKey enemy GFX set",
            )
            if (allocation == null) failExport(
                "Room 0x$roomKey enemy GFX set needs a private $newGfxSize-byte allocation" +
                    (if (sharedOutsideRoom || sharedWithUntargetedState) " because another state shares its pointer" else "") +
                    ", but bank \$B4 has no contiguous free space"
            )
            writeGfxPc = allocation.pcOffset
            val newGfxOffset = allocation.snesAddress and 0xFFFF
            for (stateOffset in selectedStateOffsets) {
                val existingPtr = readU16(romData, stateOffset + 10)
                if (existingPtr == enemyGfxPtr) writeU16(romData, stateOffset + 10, newGfxOffset)
            }
            onLog(
                "Room 0x$roomKey: relocated GFX set to 0x${allocation.snesAddress.toString(16)}" +
                    if (sharedOutsideRoom) " (copy-on-write for shared pointer)" else ""
            )
        }

        var offset = writeGfxPc
        for (entry in newEntries) {
            writeU16(romData, offset, entry.speciesId)
            writeU16(romData, offset + 2, entry.paletteIndex)
            offset += 4
        }
        writeU16(romData, offset, 0xFFFF)
        return true
    }

    private fun applyScrollChanges(
        roomKey: String,
        roomId: Int,
        room: Room,
        roomEdits: RoomEdits,
        effectiveWidth: Int,
        effectiveHeight: Int,
        isResized: Boolean,
        targetStateOffsets: List<Int>? = null,
        targetScrollPtr: Int? = null,
    ): Boolean {
        val scrollPtr = targetScrollPtr ?: room.roomScrollsPtr
        val originalScrolls = romParser.parseScrollData(scrollPtr, room.width, room.height)
        val modifiedScrolls = if (isResized) {
            val resized = IntArray(effectiveWidth * effectiveHeight) { 1 }
            for (sourceY in 0 until min(room.height, effectiveHeight)) {
                for (sourceX in 0 until min(room.width, effectiveWidth)) {
                    val oldIndex = sourceY * room.width + sourceX
                    val newIndex = sourceY * effectiveWidth + sourceX
                    if (oldIndex in originalScrolls.indices) resized[newIndex] = originalScrolls[oldIndex]
                }
            }
            resized
        } else {
            originalScrolls.copyOf()
        }

        for (change in roomEdits.scrollChanges) {
            if (change.newValue !in 0..2) {
                failExport("Room 0x$roomKey scroll value ${change.newValue} is invalid; expected 0, 1, or 2")
            }
            val index = change.screenY * effectiveWidth + change.screenX
            if (change.screenX !in 0 until effectiveWidth || change.screenY !in 0 until effectiveHeight ||
                index !in modifiedScrolls.indices
            ) {
                failExport(
                    "Room 0x$roomKey has a scroll edit at (${change.screenX},${change.screenY}) outside " +
                        "its ${effectiveWidth}x$effectiveHeight-screen bounds"
                )
            }
            modifiedScrolls[index] = change.newValue
        }

        // $0000/$0001 are engine sentinels for uniform blue/green screens, not ROM
        // addresses. Materialize a real table when either sentinel is edited.
        val usesSpecialScrollValue = scrollPtr <= 1
        val sharedOutsideRoom = !usesSpecialScrollValue && hasExternalRoomStateReference(
            roomId,
            stateFieldOffset = 14,
            value = scrollPtr,
        )
        val roomStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val selectedStateOffsets = targetStateOffsets ?: roomStateOffsets
        val sharedWithUntargetedState = !usesSpecialScrollValue && roomStateOffsets.any { stateOffset ->
            stateOffset !in selectedStateOffsets && readU16(romData, stateOffset + 14) == scrollPtr
        }
        if (!usesSpecialScrollValue && modifiedScrolls.size <= originalScrolls.size &&
            !sharedOutsideRoom && !sharedWithUntargetedState
        ) {
            val scrollPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or scrollPtr)
            for (i in modifiedScrolls.indices) {
                writeU8(romData, scrollPc + i, modifiedScrolls[i])
            }
            for (i in modifiedScrolls.size until originalScrolls.size) {
                writeU8(romData, scrollPc + i, 0)
            }
            return true
        }

        val scrollBytes = ByteArray(modifiedScrolls.size) { index -> modifiedScrolls[index].toByte() }
        val allocation = roomDataAllocator.allocate(
            bytes = scrollBytes,
            banks = listOf(0x8F),
            label = "room 0x$roomKey scroll data",
        ) ?: failExport(
            "Room 0x$roomKey needs a private ${modifiedScrolls.size}-byte scroll allocation" +
                (if (sharedOutsideRoom) " because another room shares its pointer" else "") +
                ", but bank \$8F has no free space; export aborted to avoid corrupting adjacent data"
        )

        val newPtr = allocation.snesAddress and 0xFFFF
        if (selectedStateOffsets.isEmpty()) {
            failExport("Room 0x$roomKey has scroll edits but no writable room-state data")
        }
        for (stateOffset in selectedStateOffsets) {
            writeU16(romData, stateOffset + 14, newPtr)
        }
        onLog(
            "Room 0x$roomKey: ${if (usesSpecialScrollValue) "materialized special" else "relocated"} " +
                "scroll data \$${scrollPtr.toString(16)} " +
                "to \$8F:${newPtr.toString(16).uppercase()} (${modifiedScrolls.size} bytes, " +
                "updated ${selectedStateOffsets.size} state(s)" +
                if (sharedOutsideRoom) ", copy-on-write for shared pointer)" else ")"
        )
        return true
    }

    private fun applyResizedScrollCommandRemap(
        roomKey: String,
        roomId: Int,
        room: Room,
        effectiveWidth: Int,
        effectiveHeight: Int,
    ) {
        val allPlms = RomParser(romData).getAllPlmEntriesForRoom(roomId)
        val scrollTriggerPlms = allPlms.filter { it.id == 0xB703 }
        val remappedPtrs = mutableSetOf<Int>()
        for (plm in scrollTriggerPlms) {
            val commandPtr = plm.param and 0xFFFF
            if (commandPtr == 0 || commandPtr in remappedPtrs) continue
            remappedPtrs.add(commandPtr)
            val pc = romParser.snesToPc(0x8F0000 or commandPtr)
            var offset = 0
            var remapped = 0
            while (offset < 256 && pc + offset < romData.size) {
                val screenIndex = romData[pc + offset].toInt() and 0xFF
                if (screenIndex >= 0x80) break
                val col = screenIndex % room.width
                val row = screenIndex / room.width
                if (row < effectiveHeight && col < effectiveWidth) {
                    val newIndex = row * effectiveWidth + col
                    romData[pc + offset] = newIndex.toByte()
                    if (newIndex != screenIndex) remapped++
                }
                offset += 2
            }
            if (remapped > 0) {
                onLog(
                    "Room 0x$roomKey: remapped $remapped screen indices in scroll command at " +
                        "\$8F:${commandPtr.toString(16).uppercase()} (width ${room.width}->$effectiveWidth)"
                )
            }
        }
    }

    private fun applyResizedDoorAsmRemap(
        roomKey: String,
        roomId: Int,
        room: Room,
        effectiveWidth: Int,
        effectiveHeight: Int,
    ) {
        data class ScrollWrite(val scrollValue: Int, val screenIndex: Int)

        val incomingDoors = romParser.findDoorsLeadingTo(roomId)
        val generatedAsmPtrs = mutableMapOf<Int, Int>()
        for (door in incomingDoors) {
            if (door.entryCode == 0 || door.entryCode == 0xFFFF) continue
            if (door.entryCode in generatedAsmPtrs) continue

            val originalPc = romParser.snesToPc(0x8F0000 or door.entryCode)
            val writes = mutableListOf<ScrollWrite>()
            var i = 0
            while (i < 60) {
                val byte = romParser.readByteAt(originalPc + i)
                if (byte == 0x6B) break
                if (byte == 0xA9 && i + 5 < 60) {
                    val immediate = romParser.readByteAt(originalPc + i + 1)
                    val next = romParser.readByteAt(originalPc + i + 2)
                    if (next == 0x8F) {
                        val lo = romParser.readByteAt(originalPc + i + 3)
                        val hi = romParser.readByteAt(originalPc + i + 4)
                        val bank = romParser.readByteAt(originalPc + i + 5)
                        if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                            writes.add(ScrollWrite(immediate, lo - 0x20))
                        }
                        i += 6
                        continue
                    }
                }
                if ((byte == 0xE2 || byte == 0xC2) && i + 1 < 60) {
                    i += 2
                    continue
                }
                i++
            }
            if (writes.isEmpty()) continue

            val asm = mutableListOf<Int>()
            asm.add(0xE2)
            asm.add(0x20)
            for (write in writes) {
                val col = write.screenIndex % room.width
                val row = write.screenIndex / room.width
                val newIndex = if (row < effectiveHeight && col < effectiveWidth) {
                    row * effectiveWidth + col
                } else {
                    write.screenIndex
                }
                asm.add(0xA9)
                asm.add(write.scrollValue)
                asm.add(0x8F)
                asm.add(0x20 + newIndex)
                asm.add(0xCD)
                asm.add(0x7E)
            }
            asm.add(0x6B)
            val asmBytes = asm.map { it.toByte() }.toByteArray()
            val allocation = roomDataAllocator.allocate(
                bytes = asmBytes,
                banks = listOf(0x8F),
                label = "room 0x$roomKey resized door scroll ASM",
            )
            if (allocation == null) failExport(
                "Room 0x$roomKey resized door ASM needs ${asmBytes.size} bytes but bank \$8F has no " +
                    "contiguous free space"
            )
            val newPtr = allocation.snesAddress and 0xFFFF
            generatedAsmPtrs[door.entryCode] = newPtr
            onLog(
                "Room 0x$roomKey: generated new door ASM at \$8F:${newPtr.toString(16).uppercase()} " +
                    "(${writes.size} scroll writes, was \$8F:${door.entryCode.toString(16).uppercase()})"
            )
        }

        if (generatedAsmPtrs.isEmpty()) return

        for (info in RoomRepository().getAllRooms()) {
            val sourceId = info.getRoomIdAsInt()
            val sourceRoom = romParser.readRoomHeader(sourceId) ?: continue
            if (sourceRoom.doorOut == 0) continue
            val doors = romParser.parseDoorList(sourceRoom.doorOut)
            for ((doorIndex, door) in doors.withIndex()) {
                if (door.destRoomPtr == roomId && door.entryCode in generatedAsmPtrs) {
                    val entryPc = romParser.doorEntryPcOffset(sourceRoom.doorOut, doorIndex) ?: continue
                    writeU16(romData, entryPc + 10, generatedAsmPtrs.getValue(door.entryCode))
                }
            }
        }
    }

    private fun applyFxChange(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
    ): Boolean {
        val fx = roomEdits.fxChange ?: return false
        val invalidWord = listOf(
            "liquidSurfaceStart" to fx.liquidSurfaceStart,
            "liquidSurfaceNew" to fx.liquidSurfaceNew,
            "liquidSpeed" to fx.liquidSpeed,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFFFF }
        val invalidByte = listOf(
            "liquidDelay" to fx.liquidDelay,
            "fxType" to fx.fxType,
            "fxBitA" to fx.fxBitA,
            "fxBitB" to fx.fxBitB,
            "fxBitC" to fx.fxBitC,
            "paletteFxBitflags" to fx.paletteFxBitflags,
            "tileAnimBitflags" to fx.tileAnimBitflags,
            "paletteBlend" to fx.paletteBlend,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFF }
        if (invalidWord != null || invalidByte != null) {
            val invalid = invalidWord ?: invalidByte!!
            failExport("Room 0x$roomKey FX field ${invalid.first}=${invalid.second} is outside its encoded range")
        }
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            failExport("Room 0x$roomKey has an FX edit but no writable room-state data")
        }
        val statesByFxPtr = linkedMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val stateFxPtr = readU16(romData, stateOffset + 6)
            if (stateFxPtr != 0 && stateFxPtr != 0xFFFF) {
                statesByFxPtr.getOrPut(stateFxPtr) { mutableListOf() }.add(stateOffset)
            }
        }
        val patchedFxPtrs = mutableSetOf<Int>()
        for ((stateFxPtr, stateOffsets) in statesByFxPtr) {
            val fxEntries = romParser.parseFxEntries(stateFxPtr)
            if (fxEntries.isEmpty()) continue
            val sharedOutsideRoom = hasExternalRoomStateReference(
                roomId,
                stateFieldOffset = 6,
                value = stateFxPtr,
            )
            var fxPc = romParser.snesToPc(RomConstants.BANK_FX or stateFxPtr)
            if (sharedOutsideRoom) {
                val originalBytes = romData.copyOfRange(fxPc, fxPc + fxEntries.size * 16)
                val allocation = roomDataAllocator.allocate(
                    bytes = originalBytes,
                    banks = listOf(0x83),
                    label = "room 0x$roomKey FX table",
                ) ?: failExport(
                    "Room 0x$roomKey FX table needs a private ${originalBytes.size}-byte allocation because " +
                        "another room shares its pointer, but bank \$83 has no contiguous free space"
                )
                val newPtr = allocation.snesAddress and 0xFFFF
                for (stateOffset in stateOffsets) writeU16(romData, stateOffset + 6, newPtr)
                fxPc = allocation.pcOffset
                onLog(
                    "Room 0x$roomKey: relocated FX table to 0x${allocation.snesAddress.toString(16)} " +
                        "(copy-on-write for shared pointer)"
                )
            }
            var patchedDefaultEntry = false
            for (entry in fxEntries) {
                if (entry.doorSelect == 0) {
                    fx.liquidSurfaceStart?.let { writeU16(romData, fxPc + 2, it) }
                    fx.liquidSurfaceNew?.let { writeU16(romData, fxPc + 4, it) }
                    fx.liquidSpeed?.let { writeU16(romData, fxPc + 6, it) }
                    fx.liquidDelay?.let { romData[fxPc + 8] = it.toByte() }
                    fx.fxType?.let { romData[fxPc + 9] = it.toByte() }
                    fx.fxBitA?.let { romData[fxPc + 10] = it.toByte() }
                    fx.fxBitB?.let { romData[fxPc + 11] = it.toByte() }
                    fx.fxBitC?.let { romData[fxPc + 12] = it.toByte() }
                    fx.paletteFxBitflags?.let { romData[fxPc + 13] = it.toByte() }
                    fx.tileAnimBitflags?.let { romData[fxPc + 14] = it.toByte() }
                    fx.paletteBlend?.let { romData[fxPc + 15] = it.toByte() }
                    patchedDefaultEntry = true
                    patchedFxPtrs.add(stateFxPtr)
                    break
                }
                fxPc += 16
            }
            if (!patchedDefaultEntry && sharedOutsideRoom) {
                failExport("Room 0x$roomKey shared FX table has no writable default entry")
            }
        }
        if (patchedFxPtrs.isEmpty()) {
            failExport("Room 0x$roomKey has an FX edit but no writable default FX entry")
        }
        onLog("Room 0x$roomKey: patched FX for ${patchedFxPtrs.size} distinct FX table(s)")
        return true
    }

    private fun applyRoomHeaderChange(
        roomKey: String,
        roomId: Int,
        headerChange: com.supermetroid.editor.data.RoomHeaderChange,
    ) {
        val invalidByte = listOf(
            "index" to headerChange.index,
            "mapX" to headerChange.mapX,
            "mapY" to headerChange.mapY,
            "upScroller" to headerChange.upScroller,
            "downScroller" to headerChange.downScroller,
            "creBitflag" to headerChange.creBitflag,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFF }
        if (invalidByte != null) {
            failExport(
                "Room 0x$roomKey header field ${invalidByte.first}=${invalidByte.second} is outside 0-255"
            )
        }
        if (headerChange.area != null && headerChange.area !in 0 until MinimapData.NUM_AREAS) {
            failExport("Room 0x$roomKey area ${headerChange.area} is invalid; expected 0-${MinimapData.NUM_AREAS - 1}")
        }
        if (headerChange.width != null && headerChange.width !in 1..0xFF) {
            failExport("Room 0x$roomKey width ${headerChange.width} is invalid; expected 1-255")
        }
        if (headerChange.height != null && headerChange.height !in 1..0xFF) {
            failExport("Room 0x$roomKey height ${headerChange.height} is invalid; expected 1-255")
        }
        if (headerChange.doorOut != null && headerChange.doorOut !in 0..0xFFFF) {
            failExport("Room 0x$roomKey door-list pointer ${headerChange.doorOut} is outside 0-65535")
        }
        val headerPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
        headerChange.index?.let { romData[headerPc] = it.toByte() }
        headerChange.area?.let { romData[headerPc + 1] = it.toByte() }
        headerChange.mapX?.let { romData[headerPc + 2] = it.toByte() }
        headerChange.mapY?.let { romData[headerPc + 3] = it.toByte() }
        headerChange.width?.let { romData[headerPc + 4] = it.toByte() }
        headerChange.height?.let { romData[headerPc + 5] = it.toByte() }
        headerChange.upScroller?.let { romData[headerPc + 6] = it.toByte() }
        headerChange.downScroller?.let { romData[headerPc + 7] = it.toByte() }
        headerChange.creBitflag?.let { romData[headerPc + 8] = it.toByte() }
        headerChange.doorOut?.let { writeU16(romData, headerPc + 9, it) }
        onLog("Room 0x$roomKey: patched room header")
    }

    private fun applyStateDataChange(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
    ) {
        val stateChange = roomEdits.stateDataChange ?: return
        if (stateChange.tileset != null && stateChange.tileset !in 0 until TileGraphics.NUM_TILESETS) {
            failExport(
                "Room 0x$roomKey tileset ${stateChange.tileset} is invalid; expected 0-${TileGraphics.NUM_TILESETS - 1}"
            )
        }
        val invalidByte = listOf(
            "musicData" to stateChange.musicData,
            "musicTrack" to stateChange.musicTrack,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFF }
        if (invalidByte != null) {
            failExport("Room 0x$roomKey state field ${invalidByte.first}=${invalidByte.second} is outside 0-255")
        }
        if (stateChange.bgScrolling != null && stateChange.bgScrolling !in 0..0xFFFF) {
            failExport("Room 0x$roomKey BG scrolling ${stateChange.bgScrolling} is outside 0-65535")
        }
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        if (allStateOffsets.isEmpty()) {
            failExport("Room 0x$roomKey has a state-data edit but no writable room-state data")
        }
        for (stateOffset in allStateOffsets) {
            stateChange.tileset?.let { romData[stateOffset + 3] = it.toByte() }
            stateChange.musicData?.let { romData[stateOffset + 4] = it.toByte() }
            stateChange.musicTrack?.let { romData[stateOffset + 5] = it.toByte() }
            stateChange.bgScrolling?.let { writeU16(romData, stateOffset + 12, it) }
        }
        onLog("Room 0x$roomKey: patched state data for ${allStateOffsets.size} state(s)")
    }

    /**
     * Rebuild an authored selector graph out-of-line while preserving the room
     * header's stable address. The four-byte inline bridge is interpreted by a
     * tiny shared bank-$8F routine (`LDA $0000,X; TAX; RTS`) which returns to
     * the vanilla state-selection loop with X pointing at the relocated graph.
     */
    private fun rewriteStateGraph(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
    ): Map<String, Int> {
        val sourceInspection = romParser.inspectRoomStates(roomId)
        if (!sourceInspection.isComplete) {
            val details = sourceInspection.issues.joinToString { it.message }.ifBlank { "missing default state" }
            failExport("Room 0x$roomKey state graph cannot be rebuilt safely: $details")
        }
        if (roomEdits.states.isEmpty()) {
            failExport("Room 0x$roomKey state graph cannot be empty")
        }
        if (roomEdits.states.size > 64) {
            failExport("Room 0x$roomKey has ${roomEdits.states.size} states; the supported maximum is 64")
        }
        val defaults = roomEdits.states.withIndex().filter {
            it.value.condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT
        }
        if (defaults.size != 1 || defaults.single().index != roomEdits.states.lastIndex) {
            failExport("Room 0x$roomKey must contain exactly one default state, last")
        }
        for (state in roomEdits.states) {
            validateStateSelector(state.condition, roomKey, state.id)
        }
        val roomArea = sourceInspection.area
            ?: failExport("Room 0x$roomKey has no readable area for state-condition export")
        val generatedPredicatePtr = if (roomEdits.states.any { it.condition.kind.isSmEditGeneratedPredicate() }) {
            ensureStatePredicateRoutine(roomKey)
        } else {
            null
        }
        val compiledPointers = roomEdits.states.mapNotNull { state ->
            state.condition.takeIf { it.requiresCompiledExpression() }?.let { condition ->
                state.id to ensureCompiledStateExpression(condition, roomArea, roomKey)
            }
        }.toMap()

        val recordBytesById = linkedMapOf<String, ByteArray>()
        for (state in roomEdits.states) {
            val sourceIndex = state.baseSourceStateIndex() ?: failExport(
                "Room 0x$roomKey state '${state.id}' has no source/template state"
            )
            val source = sourceInspection.states.getOrNull(sourceIndex) ?: failExport(
                "Room 0x$roomKey state '${state.id}' refers to missing source/template state $sourceIndex"
            )
            if (state.sourceStateIndex != null) {
                val expected = state.sourceCondition
                    ?: state.condition.takeUnless { state.conditionChanged }
                    ?: failExport("Room 0x$roomKey state '${state.id}' has no source selector")
                if (!sourceConditionMatches(source.condition, expected)) {
                    failExport(
                        "Room 0x$roomKey state '${state.id}' no longer matches source state $sourceIndex"
                    )
                }
            }
            val sourcePc = source.stateDataPcOffset ?: failExport(
                "Room 0x$roomKey source/template state $sourceIndex has no readable 26-byte record"
            )
            recordBytesById[state.id] = romData.copyOfRange(
                sourcePc,
                sourcePc + RomConstants.STATE_DATA_SIZE,
            )
        }

        val selectorBytes = roomEdits.states.sumOf { it.condition.encodedSizeBytes() }
        val conditionalCount = roomEdits.states.size - 1
        val graphSize = selectorBytes + RomConstants.STATE_DATA_SIZE +
            conditionalCount * RomConstants.STATE_DATA_SIZE
        val allocation = roomDataAllocator.reserve(
            size = graphSize,
            banks = listOf(0x8F),
            label = "room 0x$roomKey state graph",
        ) ?: failExport(
            "Room 0x$roomKey needs $graphSize contiguous bytes for its state graph, but bank \$8F has no space"
        )
        val redirectPtr = ensureStateGraphRedirectRoutine(roomKey)
        val graph = ByteArray(graphSize)
        val stateOffsets = linkedMapOf<String, Int>()
        var selectorCursor = 0
        var conditionalRecordCursor = selectorBytes + RomConstants.STATE_DATA_SIZE
        for (state in roomEdits.states) {
            val record = recordBytesById.getValue(state.id)
            if (state.condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) {
                encodeStateSelector(
                    graph, selectorCursor, state.condition, statePointer = null,
                    roomKey, state.id, generatedPredicatePtr, compiledPointers[state.id], roomArea,
                )
                selectorCursor += state.condition.encodedSizeBytes()
                record.copyInto(graph, selectorCursor)
                stateOffsets[state.id] = allocation.pcOffset + selectorCursor
                selectorCursor += RomConstants.STATE_DATA_SIZE
            } else {
                val statePointer = (allocation.snesAddress + conditionalRecordCursor) and 0xFFFF
                encodeStateSelector(
                    graph, selectorCursor, state.condition, statePointer,
                    roomKey, state.id, generatedPredicatePtr, compiledPointers[state.id], roomArea,
                )
                selectorCursor += state.condition.encodedSizeBytes()
                record.copyInto(graph, conditionalRecordCursor)
                stateOffsets[state.id] = allocation.pcOffset + conditionalRecordCursor
                conditionalRecordCursor += RomConstants.STATE_DATA_SIZE
            }
        }
        if (selectorCursor != selectorBytes + RomConstants.STATE_DATA_SIZE || conditionalRecordCursor != graphSize) {
            failExport("Room 0x$roomKey internal state-graph sizing mismatch")
        }
        roomDataAllocator.write(allocation, graph)

        val roomHeaderPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
        writeU16(romData, roomHeaderPc + 11, redirectPtr)
        writeU16(romData, roomHeaderPc + 13, allocation.snesAddress and 0xFFFF)
        onLog(
            "Room 0x$roomKey: rebuilt ${roomEdits.states.size}-state graph at " +
                "\$8F:${(allocation.snesAddress and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"
        )
        return stateOffsets
    }

    private fun ensureStateGraphRedirectRoutine(roomKey: String): Int {
        stateGraphRedirectRoutinePtr?.let { return it }
        val bytes = SmEditRoomStateGraphFormat.redirectRoutineBytes
        val bankStart = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x8000)
        val bankEndExclusive = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1
        for (pc in bankStart..(bankEndExclusive - bytes.size)) {
            if (bytes.indices.all { index -> romData[pc + index] == bytes[index] }) {
                return (romParser.pcToSnes(pc) and 0xFFFF).also { stateGraphRedirectRoutinePtr = it }
            }
        }
        val allocation = roomDataAllocator.allocate(
            bytes = bytes,
            banks = listOf(0x8F),
            label = "SMEDIT room state-graph redirect routine",
        ) ?: failExport(
            "Room 0x$roomKey needs the ${bytes.size}-byte state-graph redirect routine, " +
                "but bank \$8F has no space"
        )
        return (allocation.snesAddress and 0xFFFF).also { stateGraphRedirectRoutinePtr = it }
    }

    private fun ensureStatePredicateRoutine(roomKey: String): Int {
        statePredicateRoutinePtr?.let { return it }
        val bytes = SmEditRoomStatePredicateFormat.routineBytes
        val bankStart = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x8000)
        val bankEndExclusive = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1
        for (pc in bankStart..(bankEndExclusive - bytes.size)) {
            if (bytes.indices.all { index -> romData[pc + index] == bytes[index] }) {
                return (romParser.pcToSnes(pc) and 0xFFFF).also { statePredicateRoutinePtr = it }
            }
        }
        val allocation = roomDataAllocator.allocate(
            bytes = bytes,
            banks = listOf(0x8F),
            label = "SMEDIT typed room-state predicate routine",
        ) ?: failExport(
            "Room 0x$roomKey needs the ${bytes.size}-byte typed state-predicate routine, " +
                "but bank \$8F has no space"
        )
        return (allocation.snesAddress and 0xFFFF).also { statePredicateRoutinePtr = it }
    }

    private fun ensureStateExpressionInterpreter(roomKey: String): Int {
        stateExpressionInterpreterPtr?.let { return it }
        val bytes = SmEditCompiledRoomStateConditionFormat.interpreterBytes
        val bankStart = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x8000)
        val bankEndExclusive = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1
        for (pc in bankStart..(bankEndExclusive - bytes.size)) {
            if (bytes.indices.all { index -> romData[pc + index] == bytes[index] }) {
                return (romParser.pcToSnes(pc) and 0xFFFF).also { stateExpressionInterpreterPtr = it }
            }
        }
        val allocation = roomDataAllocator.allocate(
            bytes = bytes,
            banks = listOf(0x8F),
            label = "SMEDIT compound room-state interpreter",
        ) ?: failExport(
            "Room 0x$roomKey needs the ${bytes.size}-byte compound state interpreter, but bank \$8F has no space"
        )
        return (allocation.snesAddress and 0xFFFF).also { stateExpressionInterpreterPtr = it }
    }

    private fun ensureCompiledStateExpression(
        condition: com.supermetroid.editor.data.ProjectRoomStateCondition,
        area: Int,
        roomKey: String,
    ): Pair<Int, Int> {
        val interpreter = ensureStateExpressionInterpreter(roomKey)
        val key = area to condition
        stateExpressionPointers[key]?.let { return interpreter to it }
        val bytes = runCatching {
            SmEditCompiledRoomStateConditionFormat.expressionBytes(condition, area)
        }.getOrElse { failExport("Room 0x$roomKey has an invalid compound condition: ${it.message}") }
        val bankStart = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x8000)
        val bankEndExclusive = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1
        for (pc in bankStart..(bankEndExclusive - bytes.size)) {
            if (bytes.indices.all { index -> romData[pc + index] == bytes[index] }) {
                val pointer = romParser.pcToSnes(pc) and 0xFFFF
                stateExpressionPointers[key] = pointer
                return interpreter to pointer
            }
        }
        val allocation = roomDataAllocator.allocate(
            bytes = bytes,
            banks = listOf(0x8F),
            label = "room 0x$roomKey compound state expression",
        ) ?: failExport(
            "Room 0x$roomKey needs ${bytes.size} bytes for a compound state expression, but bank \$8F has no space"
        )
        val pointer = allocation.snesAddress and 0xFFFF
        stateExpressionPointers[key] = pointer
        return interpreter to pointer
    }

    private fun validateStateSelector(
        condition: com.supermetroid.editor.data.ProjectRoomStateCondition,
        roomKey: String,
        stateId: String,
    ) {
        val expected = projectRoomStateCondition(
            condition.kind, condition.argument, condition.negated, condition.children,
        )
        if (condition.routineCode != expected.routineCode ||
            condition.argumentKind != expected.argumentKind
        ) {
            failExport("Room 0x$roomKey state '$stateId' has an inconsistent typed selector")
        }
        when (condition.argumentKind) {
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.NONE -> Unit
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EVENT_ID,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> {
                if (condition.argument == null || condition.argument !in 0..0xFF) {
                    failExport("Room 0x$roomKey state '$stateId' selector argument must fit in one byte")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> {
                if (condition.argument == null || condition.argument !in 0x8000..0xFFFF) {
                    failExport(
                        "Room 0x$roomKey state '$stateId' incoming-door argument must be a bank \$83 pointer"
                    )
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BEAM_MASK -> {
                val argument = condition.argument
                if (argument == null || argument !in 1..0xFFFF || argument.countOneBits() != 1) {
                    failExport("Room 0x$roomKey state '$stateId' equipment/beam selector must contain one bit")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CAPACITY -> {
                if (condition.argument == null || condition.argument !in 0..0xFFFF) {
                    failExport("Room 0x$roomKey state '$stateId' capacity must fit in one word")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX -> {
                if (condition.argument == null || condition.argument !in 0..0x1FF) {
                    failExport("Room 0x$roomKey state '$stateId' item pickup ID must be 0-511")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK -> {
                val argument = condition.argument ?: -1
                val area = (argument ushr 8) and 0xFF
                val mask = argument and 0xFF
                if (argument !in 0..0xFFFF || area !in 0..7 ||
                    mask !in RoomStateCondition.BOSS_FLAG_MASKS
                ) {
                    failExport("Room 0x$roomKey state '$stateId' boss selector is invalid")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX -> {
                if (condition.argument == null || condition.argument !in 0..0x1FF) {
                    failExport("Room 0x$roomKey state '$stateId' persistent bit index must be 0-511")
                }
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHILDREN -> {
                if (!SmEditCompiledRoomStateConditionFormat.conditionTreeIsValid(condition)) {
                    failExport("Room 0x$roomKey state '$stateId' has an invalid compound condition tree")
                }
            }
        }
        condition.children.forEach { child -> validateStateSelector(child, roomKey, "$stateId child") }
    }

    private fun encodeStateSelector(
        destination: ByteArray,
        offset: Int,
        condition: com.supermetroid.editor.data.ProjectRoomStateCondition,
        statePointer: Int?,
        roomKey: String,
        stateId: String,
        generatedPredicatePtr: Int? = null,
        compiledPointers: Pair<Int, Int>? = null,
        area: Int = 0,
    ) {
        validateStateSelector(condition, roomKey, stateId)
        val compiled = condition.requiresCompiledExpression()
        val generated = condition.kind.isSmEditGeneratedPredicate()
        val effectiveCompiledPointers = if (compiled) {
            compiledPointers ?: ensureCompiledStateExpression(condition, area, roomKey)
        } else {
            null
        }
        val routineCode = if (compiled) {
            checkNotNull(effectiveCompiledPointers).first
        } else if (generated) {
            generatedPredicatePtr ?: ensureStatePredicateRoutine(roomKey)
        } else {
            condition.routineCode
        }
        writeU16(destination, offset, routineCode)
        if (compiled) {
            writeU16(destination, offset + 2, checkNotNull(effectiveCompiledPointers).second)
        } else if (generated) {
            destination[offset + 2] = generatedPredicateType(condition.kind).toByte()
            destination[offset + 3] = if (condition.negated) {
                SmEditRoomStatePredicateFormat.FLAG_INVERTED.toByte()
            } else {
                0
            }
            writeU16(destination, offset + 4, checkNotNull(condition.argument))
        }
        when (condition.argumentKind) {
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.NONE -> Unit
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EVENT_ID,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> {
                val argument = condition.argument
                if (argument == null || argument !in 0..0xFF) {
                    failExport("Room 0x$roomKey state '$stateId' selector argument must fit in one byte")
                }
                destination[offset + 2] = argument.toByte()
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> {
                val argument = condition.argument
                if (argument == null || argument !in 0x8000..0xFFFF) {
                    failExport("Room 0x$roomKey state '$stateId' incoming-door argument must be a bank \$83 pointer")
                }
                writeU16(destination, offset + 2, argument)
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BEAM_MASK,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CAPACITY,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK -> Unit
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHILDREN -> Unit
        }
        if (condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) {
            if (statePointer != null) failExport("Room 0x$roomKey default state '$stateId' cannot use a pointer")
            return
        }
        val pointer = statePointer ?: failExport("Room 0x$roomKey state '$stateId' has no record pointer")
        val pointerOffset = offset + condition.encodedSizeBytes() - 2
        writeU16(destination, pointerOffset, pointer)
    }

    private fun generatedPredicateType(
        kind: com.supermetroid.editor.data.ProjectRoomStateConditionKind,
    ): Int = when (kind) {
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED ->
            SmEditRoomStatePredicateFormat.TYPE_EQUIPMENT
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.BEAM_COLLECTED ->
            SmEditRoomStatePredicateFormat.TYPE_BEAM
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST ->
            SmEditRoomStatePredicateFormat.TYPE_MAX_MISSILES
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST ->
            SmEditRoomStatePredicateFormat.TYPE_MAX_SUPER_MISSILES
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST ->
            SmEditRoomStatePredicateFormat.TYPE_MAX_POWER_BOMBS
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST ->
            SmEditRoomStatePredicateFormat.TYPE_MAX_ENERGY
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST ->
            SmEditRoomStatePredicateFormat.TYPE_MAX_RESERVE_ENERGY
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED ->
            SmEditRoomStatePredicateFormat.TYPE_ITEM_PICKUP
        com.supermetroid.editor.data.ProjectRoomStateConditionKind.BOSS_DEFEATED ->
            SmEditRoomStatePredicateFormat.TYPE_BOSS
        else -> failExport("$kind is not a generated state predicate")
    }

    private fun sourceConditionMatches(
        inspected: RoomStateCondition,
        expected: com.supermetroid.editor.data.ProjectRoomStateCondition,
    ): Boolean {
        fun semanticMatch(
            runtime: RoomStateCondition,
            project: com.supermetroid.editor.data.ProjectRoomStateCondition,
        ): Boolean = runtime.kind.name == project.kind.name &&
            runtime.argument == project.argument && runtime.negated == project.negated &&
            runtime.children.size == project.children.size &&
            runtime.children.zip(project.children).all { (runtimeChild, projectChild) ->
                semanticMatch(runtimeChild, projectChild)
            }
        if (expected.usesSmEditRuntime()) {
            return semanticMatch(inspected, expected)
        }
        return inspected.code == expected.routineCode && inspected.argument == expected.argument
    }

    /**
     * Apply state-scoped overrides after legacy/common room edits so a selected
     * state's explicit values win. State graph creation/reordering is handled
     * by the later graph writer; this path only accepts verified existing
     * source states and fails closed for unsupported edit kinds.
     */
    private fun applyExistingStateEdits(
        roomKey: String,
        roomId: Int,
        roomEdits: RoomEdits,
        rewrittenStateOffsets: Map<String, Int> = emptyMap(),
    ) {
        val graphWasRewritten = rewrittenStateOffsets.isNotEmpty()
        val inspectedStates = if (graphWasRewritten) emptyList() else romParser.inspectRoomStates(roomId).states
        val room = romParser.readRoomHeader(roomId)
            ?: failExport("Room 0x$roomKey state edits have no readable room header")
        val effectiveWidth = roomEdits.roomHeaderChange?.width ?: room.width
        val effectiveHeight = roomEdits.roomHeaderChange?.height ?: room.height
        for (stateEdits in roomEdits.states.filter { it.hasEdits }) {
            val inspected = if (graphWasRewritten) {
                null
            } else {
                val sourceIndex = stateEdits.sourceStateIndex ?: failExport(
                    "Room 0x$roomKey state '${stateEdits.id}' is new, but its state graph was not rebuilt"
                )
                inspectedStates.getOrNull(sourceIndex) ?: failExport(
                    "Room 0x$roomKey state '${stateEdits.id}' refers to missing source state $sourceIndex"
                )
            }
            if (inspected != null) {
                val sourceCondition = stateEdits.sourceCondition
                    ?: stateEdits.condition.takeUnless { stateEdits.conditionChanged }
                    ?: failExport(
                        "Room 0x$roomKey state '${stateEdits.id}' changes its selector but has no source selector"
                    )
                if (!sourceConditionMatches(inspected.condition, sourceCondition)) {
                    failExport(
                        "Room 0x$roomKey state '${stateEdits.id}' no longer matches its source selector; " +
                            "reload or migrate the project before exporting"
                    )
                }
            }
            val stateOffset = rewrittenStateOffsets[stateEdits.id]
                ?: inspected?.stateDataPcOffset
                ?: failExport("Room 0x$roomKey state '${stateEdits.id}' has no writable state record")
            if (stateEdits.resourcesChanged) {
                failExport(
                    "Room 0x$roomKey state '${stateEdits.id}' changes explicit resource links, " +
                        "but link/unlink export is not active yet"
                )
            }
            if (stateEdits.conditionChanged && !graphWasRewritten) {
                applyStateConditionChange(
                    roomKey,
                    stateEdits.id,
                    checkNotNull(inspected),
                    stateEdits.condition,
                )
            }
            stateEdits.stateDataChange?.let {
                applyStateDataChangeAtOffset(roomKey, stateEdits.id, stateOffset, it)
            }
            val scopedRoomEdits = RoomEdits(
                roomId = roomId,
                operations = stateEdits.operations,
                plmChanges = stateEdits.plmChanges,
                enemyChanges = stateEdits.enemyChanges,
                scrollChanges = stateEdits.scrollChanges,
                customScrollCommands = stateEdits.customScrollCommands,
            )
            val targetOffsets = listOf(stateOffset)
            val effectiveRoom = if (effectiveWidth != room.width || effectiveHeight != room.height) {
                room.copy(width = effectiveWidth, height = effectiveHeight)
            } else {
                room
            }
            if (stateEdits.operations.any { it.edits.isNotEmpty() }) {
                applyLevelDataEdits(
                    roomKey,
                    roomId,
                    effectiveRoom,
                    scopedRoomEdits,
                    effectiveWidth,
                    effectiveHeight,
                    isResized = false,
                    targetStateOffsets = targetOffsets,
                )
            }
            if (stateEdits.plmChanges.isNotEmpty()) {
                applyPlmChanges(roomKey, roomId, scopedRoomEdits, targetOffsets)
            }
            if (stateEdits.customScrollCommands.isNotEmpty()) {
                applyCustomScrollCommands(roomKey, roomId, scopedRoomEdits, targetOffsets)
            }
            if (stateEdits.enemyChanges.isNotEmpty()) {
                val enemySetPtr = readU16(romData, stateOffset + 8)
                val enemyGfxPtr = readU16(romData, stateOffset + 10)
                applyEnemyPopulationChanges(
                    roomKey,
                    roomId,
                    room,
                    scopedRoomEdits,
                    targetOffsets,
                    enemySetPtr,
                )
                applyEnemyGfxChanges(
                    roomKey,
                    roomId,
                    room,
                    scopedRoomEdits,
                    targetOffsets,
                    enemySetPtr,
                    enemyGfxPtr,
                )
            }
            if (stateEdits.scrollChanges.isNotEmpty()) {
                val scrollPtr = readU16(romData, stateOffset + 14)
                applyScrollChanges(
                    roomKey,
                    roomId,
                    effectiveRoom,
                    scopedRoomEdits,
                    effectiveWidth,
                    effectiveHeight,
                    isResized = false,
                    targetStateOffsets = targetOffsets,
                    targetScrollPtr = scrollPtr,
                )
            }
            stateEdits.fxChange?.let {
                applyFxChangeAtOffset(roomKey, roomId, stateEdits.id, stateOffset, it)
            }
            for ((doorKey, change) in stateEdits.doorFxChanges) {
                val doorSelect = doorKey.toIntOrNull(16) ?: failExport(
                    "Room 0x$roomKey state '${stateEdits.id}' has invalid door FX key '$doorKey'"
                )
                if (doorSelect !in 1..0xFFFF) {
                    failExport(
                        "Room 0x$roomKey state '${stateEdits.id}' door FX key '$doorKey' must be a non-zero 16-bit pointer"
                    )
                }
                applyFxChangeAtOffset(
                    roomKey = roomKey,
                    roomId = roomId,
                    stateId = stateEdits.id,
                    stateOffset = stateOffset,
                    change = change,
                    doorSelect = doorSelect,
                )
            }
        }
    }

    private fun applyStateDataChangeAtOffset(
        roomKey: String,
        stateId: String,
        stateOffset: Int,
        change: com.supermetroid.editor.data.StateDataChange,
    ) {
        if (change.tileset != null && change.tileset !in 0 until TileGraphics.NUM_TILESETS) {
            failExport("Room 0x$roomKey state '$stateId' tileset ${change.tileset} is invalid")
        }
        val invalidByte = listOf(
            "musicData" to change.musicData,
            "musicTrack" to change.musicTrack,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFF }
        if (invalidByte != null) {
            failExport(
                "Room 0x$roomKey state '$stateId' field ${invalidByte.first}=${invalidByte.second} " +
                    "is outside 0-255"
            )
        }
        if (change.bgScrolling != null && change.bgScrolling !in 0..0xFFFF) {
            failExport("Room 0x$roomKey state '$stateId' Layer 2 motion is outside 0-65535")
        }
        change.tileset?.let { romData[stateOffset + 3] = it.toByte() }
        change.musicData?.let { romData[stateOffset + 4] = it.toByte() }
        change.musicTrack?.let { romData[stateOffset + 5] = it.toByte() }
        change.bgScrolling?.let { writeU16(romData, stateOffset + 12, it) }
        onLog("Room 0x$roomKey: patched state '$stateId' properties")
    }

    private fun applyStateConditionChange(
        roomKey: String,
        stateId: String,
        inspected: InspectedRoomState,
        condition: com.supermetroid.editor.data.ProjectRoomStateCondition,
    ) {
        if (inspected.condition.isDefault || condition.kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.DEFAULT) {
            failExport("Room 0x$roomKey state '$stateId' cannot replace or create the mandatory default branch in place")
        }
        val oldSize = inspected.condition.entrySizeBytes
        val newSize = condition.encodedSizeBytes()
        if (newSize != oldSize) {
            failExport(
                "Room 0x$roomKey state '$stateId' selector needs $newSize bytes but its existing slot is " +
                    "$oldSize bytes; use state-graph relocation"
            )
        }
        if (condition.requiresCompiledExpression()) {
            val statePointer = inspected.stateDataPointer ?: failExport(
                "Room 0x$roomKey state '$stateId' compound selector has no bank \$8F state pointer"
            )
            val area = romParser.readRoomHeader(roomKey.toInt(16))?.area
                ?: failExport("Room 0x$roomKey has no readable header for compound selector export")
            encodeStateSelector(
                destination = romData,
                offset = inspected.selectorPcOffset,
                condition = condition,
                statePointer = statePointer,
                roomKey = roomKey,
                stateId = stateId,
                compiledPointers = ensureCompiledStateExpression(condition, area, roomKey),
                area = area,
            )
            onLog("Room 0x$roomKey: changed compound selector for state '$stateId'")
            return
        }
        if (condition.kind.isSmEditGeneratedPredicate()) {
            val statePointer = inspected.stateDataPointer ?: failExport(
                "Room 0x$roomKey state '$stateId' generated selector has no bank \$8F state pointer"
            )
            encodeStateSelector(
                destination = romData,
                offset = inspected.selectorPcOffset,
                condition = condition,
                statePointer = statePointer,
                roomKey = roomKey,
                stateId = stateId,
                generatedPredicatePtr = ensureStatePredicateRoutine(roomKey),
            )
            onLog("Room 0x$roomKey: changed typed selector for state '$stateId'")
            return
        }
        val expectedArgumentKind = when (condition.kind) {
            com.supermetroid.editor.data.ProjectRoomStateConditionKind.INCOMING_DOOR ->
                com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER
            com.supermetroid.editor.data.ProjectRoomStateConditionKind.EVENT_SET ->
                com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EVENT_ID
            com.supermetroid.editor.data.ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET ->
                com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK
            else -> com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.NONE
        }
        if (condition.argumentKind != expectedArgumentKind) {
            failExport("Room 0x$roomKey state '$stateId' selector argument type is inconsistent")
        }
        val expectedRoutine = projectRoomStateCondition(condition.kind, condition.argument).routineCode
        if (condition.routineCode != expectedRoutine) {
            failExport(
                "Room 0x$roomKey state '$stateId' selector routine \$${condition.routineCode.toString(16)} " +
                    "does not match ${condition.kind}"
            )
        }
        val pos = inspected.selectorPcOffset
        writeU16(romData, pos, condition.routineCode)
        when (condition.argumentKind) {
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.NONE -> Unit
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EVENT_ID,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK -> {
                val argument = condition.argument
                if (argument == null || argument !in 0..0xFF) {
                    failExport("Room 0x$roomKey state '$stateId' selector argument must fit in one byte")
                }
                romData[pos + 2] = argument.toByte()
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_POINTER -> {
                val argument = condition.argument
                if (argument == null || argument !in 0..0xFFFF) {
                    failExport("Room 0x$roomKey state '$stateId' door argument must fit in two bytes")
                }
                writeU16(romData, pos + 2, argument)
            }
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.BEAM_MASK,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CAPACITY,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK ->
                failExport("Room 0x$roomKey state '$stateId' generated selector was not encoded through its typed ABI")
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX,
            com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind.CHILDREN ->
                failExport("Room 0x$roomKey state '$stateId' compiled selector was not encoded through its expression ABI")
        }
        onLog("Room 0x$roomKey: changed selector for state '$stateId'")
    }

    private fun applyFxChangeAtOffset(
        roomKey: String,
        roomId: Int,
        stateId: String,
        stateOffset: Int,
        change: com.supermetroid.editor.data.FxChange,
        doorSelect: Int = 0,
    ) {
        val invalidWord = listOf(
            "liquidSurfaceStart" to change.liquidSurfaceStart,
            "liquidSurfaceNew" to change.liquidSurfaceNew,
            "liquidSpeed" to change.liquidSpeed,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFFFF }
        val invalidByte = listOf(
            "liquidDelay" to change.liquidDelay,
            "fxType" to change.fxType,
            "fxBitA" to change.fxBitA,
            "fxBitB" to change.fxBitB,
            "fxBitC" to change.fxBitC,
            "paletteFxBitflags" to change.paletteFxBitflags,
            "tileAnimBitflags" to change.tileAnimBitflags,
            "paletteBlend" to change.paletteBlend,
        ).firstOrNull { (_, value) -> value != null && value !in 0..0xFF }
        if (invalidWord != null || invalidByte != null) {
            val invalid = invalidWord ?: invalidByte!!
            failExport("Room 0x$roomKey state '$stateId' FX field ${invalid.first}=${invalid.second} is invalid")
        }

        val fxPtr = readU16(romData, stateOffset + 6)
        val entries: List<RomParser.FxEntry>
        val entryIndex: Int
        var fxPc: Int
        if (fxPtr == 0 || fxPtr == 0xFFFF) {
            if (doorSelect != 0) {
                failExport(
                    "Room 0x$roomKey state '$stateId' has no FX table containing door " +
                        "\$${doorSelect.toString(16).uppercase().padStart(4, '0')}"
                )
            }
            val emptyDefault = ByteArray(16).also { bytes ->
                writeU16(bytes, 2, 0xFFFF)
                writeU16(bytes, 4, 0xFFFF)
                bytes[10] = 0x02
                bytes[11] = 0x02
            }
            val allocation = roomDataAllocator.allocate(
                bytes = emptyDefault,
                banks = listOf(0x83),
                label = "room 0x$roomKey state '$stateId' FX table",
            ) ?: failExport(
                "Room 0x$roomKey state '$stateId' needs a 16-byte FX allocation, but bank \$83 " +
                    "has no contiguous free space"
            )
            writeU16(romData, stateOffset + 6, allocation.snesAddress and 0xFFFF)
            fxPc = allocation.pcOffset
            entries = listOf(
                RomParser.FxEntry(0, 0xFFFF, 0xFFFF, 0, 0, 0, 2, 2, 0, 0, 0, 0)
            )
            entryIndex = 0
            onLog("Room 0x$roomKey: created FX table for state '$stateId'")
        } else {
            entries = romParser.parseFxEntries(fxPtr)
            entryIndex = entries.indexOfFirst { it.doorSelect == doorSelect }
            if (entryIndex < 0) {
                val target = if (doorSelect == 0) {
                    "default entry"
                } else {
                    "entry for door \$${doorSelect.toString(16).uppercase().padStart(4, '0')}"
                }
                failExport("Room 0x$roomKey state '$stateId' FX table has no $target")
            }
            fxPc = romParser.snesToPc(RomConstants.BANK_FX or fxPtr)
        }

        val sharedInRoom = fxPtr !in setOf(0, 0xFFFF) && romParser.findAllStateDataOffsets(roomId).any { otherOffset ->
            otherOffset != stateOffset && readU16(romData, otherOffset + 6) == fxPtr
        }
        val sharedOutsideRoom = fxPtr !in setOf(0, 0xFFFF) && hasExternalRoomStateReference(
            roomId,
            stateFieldOffset = 6,
            value = fxPtr,
        )
        if (fxPtr !in setOf(0, 0xFFFF) && (sharedInRoom || sharedOutsideRoom)) {
            val bytes = romData.copyOfRange(fxPc, fxPc + entries.size * 16)
            val allocation = roomDataAllocator.allocate(
                bytes = bytes,
                banks = listOf(0x83),
                label = "room 0x$roomKey state '$stateId' FX table",
            ) ?: failExport(
                "Room 0x$roomKey state '$stateId' needs a private ${bytes.size}-byte FX allocation, " +
                    "but bank \$83 has no contiguous free space"
            )
            writeU16(romData, stateOffset + 6, allocation.snesAddress and 0xFFFF)
            fxPc = allocation.pcOffset
            onLog("Room 0x$roomKey: forked FX for state '$stateId' (copy-on-write)")
        }

        val entryPc = fxPc + entryIndex * 16
        change.liquidSurfaceStart?.let { writeU16(romData, entryPc + 2, it) }
        change.liquidSurfaceNew?.let { writeU16(romData, entryPc + 4, it) }
        change.liquidSpeed?.let { writeU16(romData, entryPc + 6, it) }
        change.liquidDelay?.let { romData[entryPc + 8] = it.toByte() }
        change.fxType?.let { romData[entryPc + 9] = it.toByte() }
        change.fxBitA?.let { romData[entryPc + 10] = it.toByte() }
        change.fxBitB?.let { romData[entryPc + 11] = it.toByte() }
        change.fxBitC?.let { romData[entryPc + 12] = it.toByte() }
        change.paletteFxBitflags?.let { romData[entryPc + 13] = it.toByte() }
        change.tileAnimBitflags?.let { romData[entryPc + 14] = it.toByte() }
        change.paletteBlend?.let { romData[entryPc + 15] = it.toByte() }
        val target = if (doorSelect == 0) "default" else "door \$${doorSelect.toString(16).uppercase().padStart(4, '0')}"
        onLog("Room 0x$roomKey: patched $target FX for state '$stateId'")
    }

    private fun applySaveStationSpawns(
        roomKey: String,
        roomEdits: RoomEdits,
    ): Boolean {
        var wrote = false
        for (spawn in roomEdits.saveStationSpawns) {
            if (spawn.area !in 0..7) {
                failExport("Room 0x$roomKey save station area ${spawn.area} is invalid; expected 0-7")
            }
            if (spawn.saveIndex !in 0 until RomParser.SAVE_STATION_SLOT_COUNT) {
                failExport(
                    "Room 0x$roomKey save station index ${spawn.saveIndex} is unreachable: the game " +
                        "masks save PLM indices to 0-${RomParser.SAVE_STATION_SLOT_COUNT - 1}"
                )
            }
            val invalidWord = listOf(
                "roomId" to spawn.roomId,
                "doorPtr" to spawn.doorPtr,
                "scrollX" to spawn.scrollX,
                "scrollY" to spawn.scrollY,
                "samusY" to spawn.samusY,
                "samusX" to spawn.samusX,
            ).firstOrNull { (_, value) -> value !in 0..0xFFFF }
            if (invalidWord != null) {
                failExport(
                    "Room 0x$roomKey save station field ${invalidWord.first}=${invalidWord.second} is outside 0-65535"
                )
            }
            val romEntry = romParser.readSaveEntry(spawn.area, spawn.saveIndex)
            if (romEntry == null) {
                failExport(
                    "Room 0x$roomKey save station ${spawn.area}:${spawn.saveIndex} has no writable " +
                        "load-station entry"
                )
            }
            val offset = romEntry.pcOffset
            writeU16(romData, offset, spawn.roomId)
            writeU16(romData, offset + 2, spawn.doorPtr)
            writeU16(romData, offset + 6, spawn.scrollX)
            writeU16(romData, offset + 8, spawn.scrollY)
            writeU16(romData, offset + 10, spawn.samusY)
            writeU16(romData, offset + 12, spawn.samusX)
            onLog(
                "Room 0x$roomKey: patched AreaSave area=${spawn.area} index=${spawn.saveIndex} " +
                    "room=0x${spawn.roomId.toString(16)} door=0x${spawn.doorPtr.toString(16)} " +
                    "scroll=(${spawn.scrollX},${spawn.scrollY}) " +
                    "samus=(${spawn.samusX.toSigned16()},${spawn.samusY.toSigned16()})"
            )
            wrote = true
        }
        return wrote
    }

    private val RoomEdits.hasTileEdits: Boolean
        get() = operations.any { it.edits.isNotEmpty() }

    private fun isEditorItemPlm(plmId: Int): Boolean {
        if (RomParser.isItemPlm(plmId)) return true
        if (plmId in extraItemPlmIds) return true
        return project.patches
            .filter { it.enabled }
            .flatMap { it.customItems }
            .any { it.visiblePlmId == plmId || it.chozoPlmId == plmId || it.hiddenPlmId == plmId }
    }

    private fun dedupeItemPlmsByPosition(plms: List<RomParser.PlmEntry>): List<RomParser.PlmEntry> {
        val seenItemPositions = mutableSetOf<Long>()
        val deduped = mutableListOf<RomParser.PlmEntry>()
        for (plm in plms.asReversed()) {
            val key = (plm.x.toLong() shl 16) or plm.y.toLong()
            if (isEditorItemPlm(plm.id)) {
                if (key in seenItemPositions) continue
                seenItemPositions.add(key)
            }
            deduped.add(plm)
        }
        deduped.reverse()
        return deduped
    }

    private fun levelDataRelocationBanks(originalSnesAddress: Int): List<Int> {
        val originalBank = (originalSnesAddress shr 16) and 0xFF
        return (listOf(originalBank) + (0xCE downTo 0xC0)).distinct()
    }

    /**
     * Detects pointer aliases owned by a different room. In-place mutation is
     * safe across states of the room being edited because they are all part of
     * the operation; a different room must retain the original bytes.
     */
    private fun hasExternalRoomStateReference(
        roomId: Int,
        stateFieldOffset: Int,
        value: Int,
        u24: Boolean = false,
    ): Boolean {
        for (info in RoomRepository().getAllRooms()) {
            val otherRoomId = info.getRoomIdAsInt()
            if (otherRoomId == roomId) continue
            for (stateOffset in romParser.findAllStateDataOffsets(otherRoomId)) {
                val candidate = if (u24) {
                    if (stateOffset + stateFieldOffset + 2 >= romData.size) continue
                    readU24(romData, stateOffset + stateFieldOffset)
                } else {
                    if (stateOffset + stateFieldOffset + 1 >= romData.size) continue
                    readU16(romData, stateOffset + stateFieldOffset)
                }
                if (candidate == value) return true
            }
        }
        return false
    }

    private fun failExport(message: String): Nothing {
        onLog("ERROR: $message")
        throw ProjectRoomExportException(message)
    }

    private fun resizeLevelData(
        data: ByteArray,
        oldW: Int,
        oldH: Int,
        newW: Int,
        newH: Int,
    ): ByteArray {
        if (data.size < 2) return data
        val oldBw = oldW * 16
        val oldBh = oldH * 16
        val newBw = newW * 16
        val newBh = newH * 16
        val oldBlocks = oldBw * oldBh
        val newBlocks = newBw * newBh
        val oldLayer1Size = readU16(data, 0)
        val oldBtsStart = 2 + oldLayer1Size
        val oldLayer2Start = oldBtsStart + oldBlocks
        val hasLayer2 = oldLayer2Start + oldBlocks * 2 <= data.size
        val newLayer1Size = newBlocks * 2
        val newBtsStart = 2 + newLayer1Size
        val newLayer2Start = newBtsStart + newBlocks
        val newSize = if (hasLayer2) newLayer2Start + newBlocks * 2 else newBtsStart + newBlocks
        val out = ByteArray(newSize) { 0 }
        writeU16(out, 0, newLayer1Size)
        for (y in 0 until min(oldBh, newBh)) {
            for (x in 0 until min(oldBw, newBw)) {
                val oldIndex = y * oldBw + x
                val newIndex = y * newBw + x
                val oldL1 = 2 + oldIndex * 2
                val newL1 = 2 + newIndex * 2
                if (oldL1 + 1 < data.size && newL1 + 1 < out.size) {
                    out[newL1] = data[oldL1]
                    out[newL1 + 1] = data[oldL1 + 1]
                }
                val oldBts = oldBtsStart + oldIndex
                val newBts = newBtsStart + newIndex
                if (oldBts < data.size && newBts < out.size) out[newBts] = data[oldBts]
                if (hasLayer2) {
                    val oldL2 = oldLayer2Start + oldIndex * 2
                    val newL2 = newLayer2Start + newIndex * 2
                    if (oldL2 + 1 < data.size && newL2 + 1 < out.size) {
                        out[newL2] = data[oldL2]
                        out[newL2 + 1] = data[oldL2 + 1]
                    }
                }
            }
        }
        return out
    }

    private fun writeU8(data: ByteArray, offset: Int, value: Int) {
        if (offset !in data.indices || value !in 0..0xFF) {
            failExport("Invalid 8-bit ROM write at PC 0x${offset.toString(16)} with value $value")
        }
        data[offset] = value.toByte()
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        if (offset < 0 || offset + 1 >= data.size || value !in 0..0xFFFF) {
            failExport("Invalid 16-bit ROM write at PC 0x${offset.toString(16)} with value $value")
        }
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        if (offset < 0 || offset + 2 >= data.size || value !in 0..0xFFFFFF) {
            failExport("Invalid 24-bit ROM write at PC 0x${offset.toString(16)} with value $value")
        }
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }
}
