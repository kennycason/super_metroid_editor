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
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        fun hasRoomEdits(project: SmEditProject): Boolean =
            project.rooms.values.any { it.hasEdits }
    }

    private val allocations = mutableListOf<RomAllocation>()

    private val roomDataAllocator = RomFreeSpaceAllocator(
        romData = romData,
        snesToPc = romParser::snesToPc,
        pcToSnes = romParser::pcToSnes,
        guardBytes = 1,
        onReserve = allocations::add,
    )

    private val levelDataAllocator = RomFreeSpaceAllocator(
        romData = romData,
        snesToPc = romParser::snesToPc,
        pcToSnes = romParser::pcToSnes,
        guardBytes = 1,
        onReserve = allocations::add,
    )

    private val enemyAllocator = RomFreeSpaceAllocator(
        romData = romData,
        snesToPc = romParser::snesToPc,
        pcToSnes = romParser::pcToSnes,
        guardBytes = 1,
        onReserve = allocations::add,
    )

    private val enemyGfxAllocator = RomFreeSpaceAllocator(
        romData = romData,
        snesToPc = romParser::snesToPc,
        pcToSnes = romParser::pcToSnes,
        guardBytes = 2,
        onReserve = allocations::add,
    )

    private val vanillaEnemyGfxDestinationsBySpecies by lazy {
        collectVanillaEnemyGfxDestinations(romParser)
    }

    fun exportRooms(): ProjectRoomExportResult {
        val roomsPatched = linkedSetOf<String>()

        for ((roomKey, roomEdits) in project.rooms) {
            if (!roomEdits.hasEdits) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue

            val headerChange = roomEdits.roomHeaderChange
            val effectiveWidth = headerChange?.width ?: room.width
            val effectiveHeight = headerChange?.height ?: room.height
            val isResized = effectiveWidth != room.width || effectiveHeight != room.height

            if ((roomEdits.hasTileEdits || isResized) && room.levelDataPtr != 0) {
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

            if (roomEdits.doorChanges.isNotEmpty() && room.doorOut != 0 && room.doorOut != 0xFFFF) {
                roomsPatched.addAll(applyDoorChanges(roomKey, roomId, room, roomEdits))
            }

            if (roomEdits.enemyChanges.isNotEmpty() && room.enemySetPtr != 0 && room.enemySetPtr != 0xFFFF) {
                if (applyEnemyPopulationChanges(roomKey, roomId, room, roomEdits)) {
                    roomsPatched.add(roomKey)
                }
            }

            if (roomEdits.enemyChanges.isNotEmpty() && room.enemyGfxPtr != 0 && room.enemyGfxPtr != 0xFFFF) {
                applyEnemyGfxChanges(roomKey, roomId, room, roomEdits)
            }

            if ((roomEdits.scrollChanges.isNotEmpty() || isResized) && room.roomScrollsPtr > 1) {
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
    ): Boolean {
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
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
                onLog("WARN: Room 0x$roomKey level data \$${levelPtr.toString(16)} could not be decompressed; skipped")
                continue
            }
            val (originalData, originalSize) = decompressed
            val editedData = if (isResized) {
                resizeLevelData(originalData, room.width, room.height, effectiveWidth, effectiveHeight)
            } else {
                originalData.copyOf()
            }
            if (editedData.size < 2) continue

            val layer1Size = readU16(editedData, 0)
            val totalBlocks = blocksWide * effectiveHeight * 16
            val layer2Start = 2 + layer1Size + totalBlocks
            val hasEmbeddedLayer2 = layer2Start + totalBlocks * 2 <= editedData.size &&
                (roomEdits.stateDataChange?.bgScrolling ?: room.bgScrolling) == 0

            for (op in roomEdits.operations) {
                for (edit in op.edits) {
                    val index = edit.blockY * blocksWide + edit.blockX
                    if (index !in 0 until totalBlocks) continue
                    if (edit.layer == TILE_EDIT_LAYER_2) {
                        if (hasEmbeddedLayer2) {
                            val offset = layer2Start + index * 2
                            val word = edit.newBlockWord and 0x0FFF
                            writeU16(editedData, offset, word)
                        }
                        continue
                    }

                    val wordOffset = 2 + index * 2
                    if (wordOffset + 1 < editedData.size) {
                        writeU16(editedData, wordOffset, edit.newBlockWord)
                    }
                    val btsOffset = 2 + layer1Size + index
                    if (btsOffset < editedData.size) editedData[btsOffset] = edit.newBts.toByte()
                }
            }

            val compressed = LZ5Compressor.compress(editedData)
            val roundTripped = runCatching { LZ5Compressor.decompress(compressed) }.getOrNull()
            if (roundTripped == null || !roundTripped.contentEquals(editedData)) {
                failExport("Room 0x$roomKey level data failed LZ5 round-trip validation")
            }

            val levelPc = romParser.snesToPc(levelPtr)
            if (compressed.size <= originalSize) {
                compressed.copyInto(romData, levelPc)
                for (i in compressed.size until originalSize) romData[levelPc + i] = 0xFF.toByte()
                wrote = true
            } else {
                val allocation = levelDataAllocator.allocate(
                    bytes = compressed,
                    banks = levelDataRelocationBanks(levelPtr),
                    label = "room 0x$roomKey level data",
                )
                if (allocation == null) {
                    onLog(
                        "WARN: Room 0x$roomKey lvlPtr=\$${levelPtr.toString(16)} compressed " +
                            "${compressed.size} > orig $originalSize and no free space; skipped"
                    )
                    continue
                }
                val newSnes = allocation.snesAddress
                for (stateOffset in statesForPtr) writeU24(romData, stateOffset, newSnes)
                for (i in levelPc until levelPc + originalSize) romData[i] = 0xFF.toByte()
                onLog(
                    "Room 0x$roomKey: relocated level data \$${levelPtr.toString(16)} to " +
                        "\$${allocation.bank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} " +
                        "(${compressed.size} bytes, updated ${statesForPtr.size} state(s))"
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
    ): Boolean {
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val distinctPlmPtrs = linkedSetOf<Int>()
        for (stateOffset in allStateOffsets) {
            val plmPtr = readU16(romData, stateOffset + 20)
            if (plmPtr != 0 && plmPtr != 0xFFFF) distinctPlmPtrs.add(plmPtr)
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
            val serialized = RomParser.serializePlmSet(plmSet.plms)
            val plmPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or plmSet.plmSetPtr)
            val writePc: Int

            if (serialized.size <= plmSet.originalSize) {
                writePc = plmPc
            } else {
                val allocation = roomDataAllocator.reserve(
                    size = serialized.size,
                    banks = listOf(0x8F),
                    label = "room 0x$roomKey PLM set",
                )
                if (allocation == null) {
                    onLog(
                        "WARN: Room 0x$roomKey no free space for expanded PLM set " +
                            "0x${plmSet.plmSetPtr.toString(16)}; skipped"
                    )
                    continue
                }
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
                        "to 0x${allocation.snesAddress.toString(16)} (updated $updatedStates states)"
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
    ): Boolean {
        val commandIdToPtr = mutableMapOf<String, Int>()
        for ((commandId, commands) in roomEdits.customScrollCommands) {
            if (commands.isEmpty()) continue
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
            if (allocation == null) {
                onLog("WARN: Room 0x$roomKey: no free space for custom scroll command '$commandId' (${bytes.size} bytes)")
                continue
            }
            val ptr = allocation.snesAddress and 0xFFFF
            commandIdToPtr[commandId] = ptr
            onLog(
                "Room 0x$roomKey: wrote custom scroll command '$commandId' (${commands.size} entries) " +
                    "at \$8F:${ptr.toString(16).uppercase()}"
            )
        }

        if (commandIdToPtr.isEmpty()) return false

        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
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
                    val ptr = commandIdToPtr["cmd_$commandIndex"]
                    if (ptr != null) writeU16(romData, paramOffset, ptr)
                }
                off += 6
            }
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
            val entryPc = romParser.doorEntryPcOffset(room.doorOut, doorIndex) ?: continue
            if (entryPc + 11 >= romData.size) continue

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
                onLog("WARN: Room 0x$roomKey door $doorIndex dest 0x${change.destRoomPtr.toString(16)} could not be read")
            }

            var finalBitflag = change.bitflag
            if (destRoom != null && room.area != destRoom.area && finalBitflag and 0x40 == 0) {
                finalBitflag = finalBitflag or 0x40
                onLog("  FIX: auto-set cross-area flag (area ${room.area} -> ${destRoom.area})")
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
                    if (allocation != null) {
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
                        onLog("WARN: Room 0x$roomKey door $doorIndex: no free space for enemy BG2 cleanup door ASM (${asm.size} bytes)")
                    }
                } else {
                    onLog(
                        "WARN: Room 0x$roomKey door $doorIndex: preserves custom entry ASM " +
                            "\$8F:${change.entryCode.toString(16).uppercase()}; could not safely add enemy BG2 cleanup"
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
            if (allocation == null) {
                onLog(
                    "WARN: Room 0x$roomKey door $doorIndex: no free space to clone door-dependent BG data " +
                        "for dest 0x${change.destRoomPtr.toString(16)} (${newBgData.size} bytes)"
                )
                continue
            }
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
    ): Boolean {
        val originalEnemies = romParser.parseEnemyPopulation(room.enemySetPtr)
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

        val enemyPc = romParser.snesToPc(RomConstants.BANK_ENEMY_SET or room.enemySetPtr)
        val killCountPc = enemyPc + originalEnemies.size * 16 + 2
        val killCount = if (killCountPc < romData.size) romData[killCountPc] else 0
        val originalSize = originalEnemies.size * 16 + 3
        val newSize = modified.size * 16 + 3

        val writePc: Int
        if (newSize <= originalSize) {
            writePc = enemyPc
        } else {
            val allocation = enemyAllocator.reserve(
                size = newSize,
                banks = listOf(0xA1),
                label = "room 0x$roomKey enemy population",
            )
            if (allocation == null) {
                onLog("WARN: Room 0x$roomKey no free space for expanded enemy set; skipped enemy patch")
                return false
            }
            writePc = allocation.pcOffset
            val newPtr = allocation.snesAddress and 0xFFFF
            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
            for (stateOffset in allStateOffsets) {
                val existingPtr = readU16(romData, stateOffset + 8)
                if (existingPtr == room.enemySetPtr) writeU16(romData, stateOffset + 8, newPtr)
            }
            onLog("Room 0x$roomKey: relocated enemy set to 0x${allocation.snesAddress.toString(16)}")
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
    ): Boolean {
        val gfxEntries = romParser.parseEnemyGfxSet(room.enemyGfxPtr)
        val existingSpecies = gfxEntries.map { it.speciesId }.toSet()
        val vanillaPopulation = romParser.parseEnemyPopulation(room.enemySetPtr)
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
                onLog("WARN: Room 0x$roomKey GFX set already has ${newEntries.size} entries; skipping species 0x${speciesId.toString(16)}")
                continue
            }
            val speciesPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            val speciesHp = if (speciesPc + 6 < romData.size) readU16(romData, speciesPc + 4) else 0
            if (speciesHp == 0) {
                onLog("WARN: Room 0x$roomKey: skipping species 0x${speciesId.toString(16)} from GFX set; HP=0")
                continue
            }
            val vramDestination = selectEnemyGfxVramDestination(
                existingEntries = newEntries,
                vanillaDestinations = vanillaEnemyGfxDestinationsBySpecies[speciesId].orEmpty(),
            )
            if (vramDestination == null) {
                onLog("WARN: Room 0x$roomKey: skipping species 0x${speciesId.toString(16)} from GFX set; no safe VRAM destination")
                continue
            }
            newEntries.add(RomParser.EnemyGfxEntry(speciesId, vramDestination))
            onLog("Room 0x$roomKey: added species 0x${speciesId.toString(16)} to GFX set (vramDst=0x${vramDestination.toString(16)})")
        }
        if (newEntries.size == gfxEntries.size) return false

        val gfxPc = romParser.snesToPc(RomConstants.BANK_ENEMY_GFX or room.enemyGfxPtr)
        val originalGfxSize = gfxEntries.size * 4 + 2
        val newGfxSize = newEntries.size * 4 + 2
        val writeGfxPc: Int

        if (newGfxSize <= originalGfxSize) {
            writeGfxPc = gfxPc
        } else {
            val allocation = enemyGfxAllocator.reserve(
                size = newGfxSize,
                banks = listOf(0xB4),
                label = "room 0x$roomKey enemy GFX set",
            )
            if (allocation == null) {
                onLog("WARN: Room 0x$roomKey no free space in bank \$B4 for expanded GFX set")
                return false
            }
            writeGfxPc = allocation.pcOffset
            val newGfxOffset = allocation.snesAddress and 0xFFFF
            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
            for (stateOffset in allStateOffsets) {
                val existingPtr = readU16(romData, stateOffset + 10)
                if (existingPtr == room.enemyGfxPtr) writeU16(romData, stateOffset + 10, newGfxOffset)
            }
            onLog("Room 0x$roomKey: relocated GFX set to 0x${allocation.snesAddress.toString(16)}")
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
    ): Boolean {
        val originalScrolls = romParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
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
            val index = change.screenY * effectiveWidth + change.screenX
            if (index in modifiedScrolls.indices) modifiedScrolls[index] = change.newValue
        }

        val scrollPc = romParser.snesToPc(RomConstants.BANK_ROOM_DATA or room.roomScrollsPtr)
        if (modifiedScrolls.size <= originalScrolls.size) {
            for (i in modifiedScrolls.indices) {
                if (scrollPc + i < romData.size) romData[scrollPc + i] = modifiedScrolls[i].toByte()
            }
            for (i in modifiedScrolls.size until originalScrolls.size) {
                if (scrollPc + i < romData.size) romData[scrollPc + i] = 0.toByte()
            }
            return true
        }

        val scrollBytes = ByteArray(modifiedScrolls.size) { index -> modifiedScrolls[index].toByte() }
        val allocation = roomDataAllocator.allocate(
            bytes = scrollBytes,
            banks = listOf(0x8F),
            label = "room 0x$roomKey scroll data",
        ) ?: failExport(
            "Room 0x$roomKey: no free space in bank \$8F for expanded scroll data " +
                "(${modifiedScrolls.size} bytes); export aborted to avoid corrupting adjacent data"
        )

        val newPtr = allocation.snesAddress and 0xFFFF
        for (i in originalScrolls.indices) {
            if (scrollPc + i < romData.size) romData[scrollPc + i] = 0xFF.toByte()
        }
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        for (stateOffset in allStateOffsets) {
            writeU16(romData, stateOffset + 14, newPtr)
        }
        onLog(
            "Room 0x$roomKey: relocated scroll data \$${room.roomScrollsPtr.toString(16)} " +
                "to \$8F:${newPtr.toString(16).uppercase()} (${modifiedScrolls.size} bytes, " +
                "updated ${allStateOffsets.size} state(s))"
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
            if (allocation == null) {
                onLog("WARN: Room 0x$roomKey: no free space for door ASM generation (${asmBytes.size} bytes)")
                continue
            }
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
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        val patchedFxPtrs = mutableSetOf<Int>()
        for (stateOffset in allStateOffsets) {
            val stateFxPtr = readU16(romData, stateOffset + 6)
            if (stateFxPtr == 0 || stateFxPtr == 0xFFFF || stateFxPtr in patchedFxPtrs) continue
            patchedFxPtrs.add(stateFxPtr)
            val fxEntries = romParser.parseFxEntries(stateFxPtr)
            if (fxEntries.isEmpty()) continue
            var fxPc = romParser.snesToPc(RomConstants.BANK_FX or stateFxPtr)
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
                    break
                }
                fxPc += 16
            }
        }
        if (patchedFxPtrs.isNotEmpty()) {
            onLog("Room 0x$roomKey: patched FX for ${patchedFxPtrs.size} state(s)")
        }
        return patchedFxPtrs.isNotEmpty()
    }

    private fun applyRoomHeaderChange(
        roomKey: String,
        roomId: Int,
        headerChange: com.supermetroid.editor.data.RoomHeaderChange,
    ) {
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
        val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
        for (stateOffset in allStateOffsets) {
            stateChange.tileset?.let { romData[stateOffset + 3] = it.toByte() }
            stateChange.musicData?.let { romData[stateOffset + 4] = it.toByte() }
            stateChange.musicTrack?.let { romData[stateOffset + 5] = it.toByte() }
            stateChange.bgScrolling?.let { writeU16(romData, stateOffset + 12, it) }
        }
        onLog("Room 0x$roomKey: patched state data for ${allStateOffsets.size} state(s)")
    }

    private fun applySaveStationSpawns(
        roomKey: String,
        roomEdits: RoomEdits,
    ): Boolean {
        var wrote = false
        for (spawn in roomEdits.saveStationSpawns) {
            val romEntry = romParser.readSaveEntry(spawn.area, spawn.saveIndex)
            if (romEntry == null) {
                onLog(
                    "WARN: Room 0x$roomKey save station ${spawn.area}:${spawn.saveIndex} " +
                        "has no writable AreaSave entry; skipped"
                )
                continue
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

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        if (offset + 1 < data.size) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        if (offset + 2 < data.size) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
            data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        }
    }
}
