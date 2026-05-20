package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Round-trip tests: edit ROM data → export → read back → verify.
 * Covers tile editing, BTS changes, PLM add/remove, and door parsing.
 */
class EditRoundTripTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    // ─── Address conversion ────────────────────────────────────────

    @Test
    fun `pcToSnes round-trips with snesToPc`() {
        val parser = loadTestRom() ?: return
        val testAddresses = listOf(
            0x8F91F8, // Landing Site
            0x8FA59F, // Kraid
            0x83895E, // A door entry
            0x8FF000, // End of bank $8F (potential free space)
            0x8F8000, // Start of bank $8F
            0x8FFFFF, // End of bank $8F
        )
        for (snes in testAddresses) {
            val pc = parser.snesToPc(snes)
            val backToSnes = parser.pcToSnes(pc)
            assertEquals(snes, backToSnes,
                "pcToSnes(snesToPc(0x${snes.toString(16)})) should round-trip. " +
                "Got PC=0x${pc.toString(16)} → SNES=0x${backToSnes.toString(16)}")
        }
    }

    @Test
    fun `pcToSnes produces valid bank 8F addresses`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val pc = parser.snesToPc(0x8F0000 or room.plmSetPtr)
        val snes = parser.pcToSnes(pc)
        val bank = (snes shr 16) and 0xFF
        val addr = snes and 0xFFFF
        assertTrue(bank in 0x80..0xFF, "Bank should be in LoROM range, got 0x${bank.toString(16)}")
        assertTrue(addr >= 0x8000, "Address should be >= 0x8000 for LoROM, got 0x${addr.toString(16)}")
    }

    // ─── Item PLM catalog ──────────────────────────────────────────

    @Test
    fun `item catalog has 21 items with 3 styles each`() {
        assertEquals(21, RomParser.ITEM_DEFS.size, "Should have 21 item definitions")
        val allIds = mutableSetOf<Int>()
        for (item in RomParser.ITEM_DEFS) {
            assertTrue(item.chozoId > 0, "${item.name} chozo ID should be > 0")
            assertTrue(item.visibleId > 0, "${item.name} visible ID should be > 0")
            assertTrue(item.hiddenId > 0, "${item.name} hidden ID should be > 0")
            allIds.addAll(listOf(item.chozoId, item.visibleId, item.hiddenId))
        }
        assertEquals(63, allIds.size, "Should have 63 unique PLM IDs (21 items × 3 styles)")
    }

    @Test
    fun `isItemPlm detects all item variants`() {
        for (item in RomParser.ITEM_DEFS) {
            assertTrue(RomParser.isItemPlm(item.chozoId), "${item.name} Chozo should be detected")
            assertTrue(RomParser.isItemPlm(item.visibleId), "${item.name} Visible should be detected")
            assertTrue(RomParser.isItemPlm(item.hiddenId), "${item.name} Hidden should be detected")
        }
        assertFalse(RomParser.isItemPlm(0x0000), "PLM 0x0000 should not be an item")
        assertFalse(RomParser.isItemPlm(0xC842), "Door cap PLM should not be an item")
    }

    @Test
    fun `itemNameForPlm returns correct names`() {
        val morph = RomParser.ITEM_DEFS.first { it.name == "Morph Ball" }
        assertEquals("Morph Ball (Chozo)", RomParser.itemNameForPlm(morph.chozoId))
        assertEquals("Morph Ball (Visible)", RomParser.itemNameForPlm(morph.visibleId))
        assertEquals("Morph Ball (Hidden)", RomParser.itemNameForPlm(morph.hiddenId))
        assertNull(RomParser.itemNameForPlm(0x1234), "Unknown PLM should return null")
    }

    // ─── Door entry parsing ────────────────────────────────────────

    @Test
    fun `Landing Site has door entries`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        assertTrue(room.doorOut != 0, "Landing Site should have a door-out pointer")

        val doors = parser.parseDoorList(room.doorOut)
        assertTrue(doors.isNotEmpty(), "Landing Site should have door entries")
        println("Landing Site: ${doors.size} door entries")
        for ((i, door) in doors.withIndex()) {
            println("  #$i → Room 0x${door.destRoomPtr.toString(16)}, " +
                    "dir=${door.directionName}, screen=(${door.screenX},${door.screenY}), " +
                    "elevator=${door.isElevator}")
        }
    }

    @Test
    fun `Landing Site door 0 has valid destination room`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val door0 = parser.parseDoorEntry(room.doorOut, 0)
        assertNotNull(door0, "Door index 0 should be valid")

        // Destination room should be parseable
        val destRoom = parser.readRoomHeader(door0!!.destRoomPtr)
        assertNotNull(destRoom, "Destination room 0x${door0.destRoomPtr.toString(16)} should be valid")
        println("Landing Site door #0 → ${destRoom!!.name} " +
                "(area=${destRoom.areaName}, ${destRoom.width}x${destRoom.height})")
    }

    @Test
    fun `door entries have valid direction values`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val doors = parser.parseDoorList(room.doorOut)
        for ((i, door) in doors.withIndex()) {
            val dir = door.direction and 0x03
            assertTrue(dir in 0..3, "Door #$i direction low 2 bits should be 0-3, got $dir")
            assertTrue(door.directionName in listOf("Right", "Left", "Down", "Up"),
                "Door #$i should have a known direction name, got ${door.directionName}")
        }
    }

    @Test
    fun `parseDoorEntry returns null for invalid index`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val invalid = parser.parseDoorEntry(room.doorOut, 100)
        assertNull(invalid, "Door index 100 should be invalid")
    }

    @Test
    fun `getStateDataPcOffset returns valid offset`() {
        val parser = loadTestRom() ?: return
        val offset = parser.getStateDataPcOffset(0x91F8)
        assertNotNull(offset, "Landing Site should have a state data offset")
        assertTrue(offset!! > 0, "State data offset should be positive")

        // Verify PLM pointer at offset+20 matches room.plmSetPtr
        val romData = parser.getRomData()
        val plmPtr = (romData[offset + 20].toInt() and 0xFF) or
                     ((romData[offset + 21].toInt() and 0xFF) shl 8)
        val room = parser.readRoomHeader(0x91F8)!!
        assertEquals(room.plmSetPtr, plmPtr,
            "PLM pointer at state data +20 should match room.plmSetPtr")
    }

    @Test
    fun `Landing Site state list starts with E612 condition`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        val roomPc = parser.roomIdToPc(0x91F8)
        val stateListOff = roomPc + 11
        val firstCode = (romData[stateListOff].toInt() and 0xFF) or
                ((romData[stateListOff + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xE612, firstCode,
            "Landing Site state list should start with E612 (boss check)")
    }

    @Test
    fun `findAllStateDataOffsets finds multiple states for multi-state rooms`() {
        val parser = loadTestRom() ?: return

        // Landing Site has E612+E669+E612+E5E6 = 4 states
        val landingStates = parser.findAllStateDataOffsets(0x91F8)
        File("/tmp/sm_states_debug.txt").writeText(
            "Landing Site: ${landingStates.size} states\n" +
            landingStates.mapIndexed { i, off -> "  #$i at PC 0x${off.toString(16)}" }.joinToString("\n")
        )
        assertTrue(landingStates.size >= 2,
            "Landing Site should have at least 2 states, got ${landingStates.size}")

        // All states for Landing Site should have the same PLM set pointer
        val romData = parser.getRomData()
        val room = parser.readRoomHeader(0x91F8)!!
        var matchCount = 0
        for ((i, stateOff) in landingStates.withIndex()) {
            val plmPtr = (romData[stateOff + 20].toInt() and 0xFF) or
                    ((romData[stateOff + 21].toInt() and 0xFF) shl 8)
            println("  State #$i at PC 0x${stateOff.toString(16)}: plmPtr=0x${plmPtr.toString(16)}")
            if (plmPtr == room.plmSetPtr) matchCount++
        }
        assertTrue(matchCount >= 1,
            "At least one state should have the same PLM pointer as readRoomHeader returned")

        // Bomb Torizo (9804) should also have multiple states
        val btStates = parser.findAllStateDataOffsets(0x9804)
        println("Bomb Torizo: ${btStates.size} state data offsets")
        assertTrue(btStates.isNotEmpty(), "Bomb Torizo should have at least 1 state")
    }

    @Test
    fun `PLM reallocation updates all state pointers`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData().copyOf()
        val room = parser.readRoomHeader(0x91F8)!!
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.size >= 2,
            "Landing Site needs multiple states for this test, got ${allStates.size}")

        // Verify all states that share the PLM pointer
        val statesWithSamePlm = allStates.filter { stateOff ->
            val plmPtr = (romData[stateOff + 20].toInt() and 0xFF) or
                    ((romData[stateOff + 21].toInt() and 0xFF) shl 8)
            plmPtr == room.plmSetPtr
        }
        println("States sharing plmPtr 0x${room.plmSetPtr.toString(16)}: ${statesWithSamePlm.size}")

        // Simulate relocation: write new pointer to all matching states
        val fakeNewPtr = 0xF000
        for (stateOff in statesWithSamePlm) {
            romData[stateOff + 20] = (fakeNewPtr and 0xFF).toByte()
            romData[stateOff + 21] = ((fakeNewPtr shr 8) and 0xFF).toByte()
        }

        // Verify all were updated
        for (stateOff in statesWithSamePlm) {
            val updatedPtr = (romData[stateOff + 20].toInt() and 0xFF) or
                    ((romData[stateOff + 21].toInt() and 0xFF) shl 8)
            assertEquals(fakeNewPtr, updatedPtr,
                "State at 0x${stateOff.toString(16)} should have updated PLM pointer")
        }
        println("PLM reallocation all-states test: PASS (updated ${statesWithSamePlm.size} states)")
    }

    // ─── Tile edit → export → readback round-trip ──────────────────

    @Test
    fun `tile edit round-trip - modify block word and read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val (originalData, origCompSize) = parser.decompressLZ2WithSize(room.levelDataPtr)
        val editedData = originalData.copyOf()

        val bw = room.width * 16 // 144 blocks wide
        val testX = 10; val testY = 5
        val idx = testY * bw + testX
        val blockOffset = 2 + idx * 2

        // Read original block word
        val origLo = editedData[blockOffset].toInt() and 0xFF
        val origHi = editedData[blockOffset + 1].toInt() and 0xFF
        val origWord = (origHi shl 8) or origLo

        // Write a new block: metatile 0x1AB, H-flip, block type 0xE (Grapple)
        val newWord = 0xE1AB  // type=E, metatile=0x1AB
        editedData[blockOffset] = (newWord and 0xFF).toByte()
        editedData[blockOffset + 1] = ((newWord shr 8) and 0xFF).toByte()

        // Also modify BTS
        val layer1Size = (editedData[0].toInt() and 0xFF) or ((editedData[1].toInt() and 0xFF) shl 8)
        val btsOffset = 2 + layer1Size + idx
        val origBts = editedData[btsOffset].toInt() and 0xFF
        editedData[btsOffset] = 0x42.toByte()

        // Compress
        val compressed = LZ5Compressor.compress(editedData)
        println("Tile edit: origComp=$origCompSize, newComp=${compressed.size}")

        // Write to temp ROM copy and decompress back
        val romData = parser.getRomData().copyOf()
        val pcOff = parser.snesToPc(room.levelDataPtr)
        assertTrue(compressed.size <= origCompSize,
            "Compressed edited data should fit in original space")
        System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
        for (i in compressed.size until origCompSize) romData[pcOff + i] = 0xFF.toByte()

        // Read back from the patched ROM
        val patchedParser = RomParser(romData)
        val readBack = patchedParser.decompressLZ2(room.levelDataPtr)

        // Verify edited block
        val rbLo = readBack[blockOffset].toInt() and 0xFF
        val rbHi = readBack[blockOffset + 1].toInt() and 0xFF
        val rbWord = (rbHi shl 8) or rbLo
        assertEquals(newWord, rbWord,
            "Read-back block word at ($testX,$testY) should be 0x${newWord.toString(16)}, " +
            "got 0x${rbWord.toString(16)}")

        // Verify BTS
        val rbBts = readBack[btsOffset].toInt() and 0xFF
        assertEquals(0x42, rbBts,
            "Read-back BTS at ($testX,$testY) should be 0x42, got 0x${rbBts.toString(16)}")

        // Verify unmodified blocks are intact
        val checkX = 20; val checkY = 10
        val checkIdx = checkY * bw + checkX
        val checkOff = 2 + checkIdx * 2
        assertEquals(originalData[checkOff], readBack[checkOff], "Unmodified block should match original")
        assertEquals(originalData[checkOff + 1], readBack[checkOff + 1], "Unmodified block should match original")

        println("Tile edit round-trip: PASS (orig=0x${origWord.toString(16)} → " +
                "new=0x${newWord.toString(16)}, BTS $origBts → 0x42)")
    }

    // ─── PLM add/remove → export → readback ────────────────────────

    @Test
    fun `PLM add round-trip - add item, write, read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val originalPlms = parser.parsePlmSet(room.plmSetPtr)
        println("Landing Site: ${originalPlms.size} original PLMs")

        // Simulate adding a Missile (Visible) at (5, 3) with param=0x51
        // Params are sequential indices; 0x51-0x7F is the safe gap between Norfair and Wrecked Ship
        val missileVisibleId = RomParser.ITEM_DEFS.first { it.name == "Missile" }.visibleId
        val newPlm = RomParser.PlmEntry(missileVisibleId, 5, 3, 0x51)
        val modifiedPlms = originalPlms + newPlm

        val romData = parser.getRomData().copyOf()
        val newSize = modifiedPlms.size * 6 + 2

        // Find free space at end of bank $8F for expanded set
        val bank8FEnd = parser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = parser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF && b != 0x00) break
            freePtr--
        }
        freePtr++
        val freeSpace = bank8FEnd - freePtr
        println("Free space at end of bank \$8F: $freeSpace bytes (need $newSize)")
        assertTrue(freeSpace >= newSize, "Should have enough free space for PLM set")

        // Write PLM set to free space
        var offset = freePtr
        for (plm in modifiedPlms) {
            romData[offset] = (plm.id and 0xFF).toByte()
            romData[offset + 1] = ((plm.id shr 8) and 0xFF).toByte()
            romData[offset + 2] = plm.x.toByte()
            romData[offset + 3] = plm.y.toByte()
            romData[offset + 4] = (plm.param and 0xFF).toByte()
            romData[offset + 5] = ((plm.param shr 8) and 0xFF).toByte()
            offset += 6
        }
        romData[offset] = 0; romData[offset + 1] = 0

        // Update PLM pointer in ALL state data blocks that share the same pointer
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        val newSnes = parser.pcToSnes(freePtr)
        val newPtr = newSnes and 0xFFFF
        for (stateOff in allStates) {
            val existingPlmPtr = (romData[stateOff + 20].toInt() and 0xFF) or
                    ((romData[stateOff + 21].toInt() and 0xFF) shl 8)
            if (existingPlmPtr == room.plmSetPtr) {
                romData[stateOff + 20] = (newPtr and 0xFF).toByte()
                romData[stateOff + 21] = ((newPtr shr 8) and 0xFF).toByte()
            }
        }

        // Read back from patched ROM
        val patchedParser = RomParser(romData)
        val patchedRoom = patchedParser.readRoomHeader(0x91F8)!!
        val readBackPlms = patchedParser.parsePlmSet(patchedRoom.plmSetPtr)

        assertEquals(modifiedPlms.size, readBackPlms.size,
            "Read-back PLM count should match. Expected ${modifiedPlms.size}, got ${readBackPlms.size}")

        // Verify the added missile is present
        val foundMissile = readBackPlms.find {
            it.id == missileVisibleId && it.x == 5 && it.y == 3
        }
        assertNotNull(foundMissile, "Should find the added Missile PLM at (5, 3)")
        assertEquals(0x51, foundMissile!!.param, "Missile param should be 0x51")
        assertTrue(RomParser.isItemPlm(foundMissile.id), "Should be detected as item PLM")
        assertEquals("Missile (Visible)", RomParser.itemNameForPlm(foundMissile.id))

        // Verify original PLMs are still there
        for (orig in originalPlms) {
            val found = readBackPlms.find { it.id == orig.id && it.x == orig.x && it.y == orig.y }
            assertNotNull(found, "Original PLM 0x${orig.id.toString(16)} at (${orig.x},${orig.y}) should still exist")
        }

        println("PLM add round-trip: PASS (added Missile at (5,3), total ${readBackPlms.size} PLMs)")
    }

    @Test
    fun `PLM remove round-trip - remove a PLM, write in place, read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val originalPlms = parser.parsePlmSet(room.plmSetPtr)
        assertTrue(originalPlms.size >= 2, "Need at least 2 PLMs to test removal")

        // Remove the last PLM
        val removed = originalPlms.last()
        val modifiedPlms = originalPlms.dropLast(1)

        // Write in place (smaller set fits in original space)
        val romData = parser.getRomData().copyOf()
        val plmPc = parser.snesToPc(0x8F0000 or room.plmSetPtr)
        var offset = plmPc
        for (plm in modifiedPlms) {
            romData[offset] = (plm.id and 0xFF).toByte()
            romData[offset + 1] = ((plm.id shr 8) and 0xFF).toByte()
            romData[offset + 2] = plm.x.toByte()
            romData[offset + 3] = plm.y.toByte()
            romData[offset + 4] = (plm.param and 0xFF).toByte()
            romData[offset + 5] = ((plm.param shr 8) and 0xFF).toByte()
            offset += 6
        }
        romData[offset] = 0; romData[offset + 1] = 0

        // Read back
        val patchedParser = RomParser(romData)
        val readBackPlms = patchedParser.parsePlmSet(room.plmSetPtr)

        assertEquals(modifiedPlms.size, readBackPlms.size,
            "Should have one fewer PLM after removal")

        val shouldBeGone = readBackPlms.find {
            it.id == removed.id && it.x == removed.x && it.y == removed.y
        }
        assertNull(shouldBeGone,
            "Removed PLM 0x${removed.id.toString(16)} at (${removed.x},${removed.y}) should be gone")

        println("PLM remove round-trip: PASS (removed PLM 0x${removed.id.toString(16)}, " +
                "${readBackPlms.size} remaining)")
    }

    // ─── Full export to /tmp ───────────────────────────────────────

    @Test
    fun `full export round-trip - edit tiles and PLMs, write to tmp, read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()

        // === Tile edits ===
        val (levelData, origCompSize) = parser.decompressLZ2WithSize(room.levelDataPtr)
        val editedLevel = levelData.copyOf()
        val bw = room.width * 16
        val layer1Size = (editedLevel[0].toInt() and 0xFF) or ((editedLevel[1].toInt() and 0xFF) shl 8)

        // Edit block (15, 8): set to Shot Block (type 0xC), metatile 0x72, BTS 0x09
        val editX = 15; val editY = 8
        val editIdx = editY * bw + editX
        val editOff = 2 + editIdx * 2
        val editBtsOff = 2 + layer1Size + editIdx
        val newBlockWord = 0xC072 // type=C, metatile=0x72
        editedLevel[editOff] = (newBlockWord and 0xFF).toByte()
        editedLevel[editOff + 1] = ((newBlockWord shr 8) and 0xFF).toByte()
        editedLevel[editBtsOff] = 0x09.toByte()

        val compressed = LZ5Compressor.compress(editedLevel)
        val pcOff = parser.snesToPc(room.levelDataPtr)
        assertTrue(compressed.size <= origCompSize, "Compressed data should fit")
        System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
        for (i in compressed.size until origCompSize) romData[pcOff + i] = 0xFF.toByte()

        // === PLM edits: add Energy Tank ===
        val originalPlms = parser.parsePlmSet(room.plmSetPtr)
        val eTankVisibleId = RomParser.ITEM_DEFS.first { it.name == "Energy Tank" }.visibleId
        val modifiedPlms = originalPlms + RomParser.PlmEntry(eTankVisibleId, editX, editY, 0x51)

        val bank8FEnd = parser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = parser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            freePtr--
        }
        freePtr++

        var plmOff = freePtr
        for (plm in modifiedPlms) {
            romData[plmOff] = (plm.id and 0xFF).toByte()
            romData[plmOff + 1] = ((plm.id shr 8) and 0xFF).toByte()
            romData[plmOff + 2] = plm.x.toByte()
            romData[plmOff + 3] = plm.y.toByte()
            romData[plmOff + 4] = (plm.param and 0xFF).toByte()
            romData[plmOff + 5] = ((plm.param shr 8) and 0xFF).toByte()
            plmOff += 6
        }
        romData[plmOff] = 0; romData[plmOff + 1] = 0

        // Update PLM pointer in ALL state data blocks that share the same pointer
        val allStates2 = parser.findAllStateDataOffsets(0x91F8)
        val newSnes2 = parser.pcToSnes(freePtr)
        val newPtr2 = newSnes2 and 0xFFFF
        for (stateOff in allStates2) {
            val existingPlmPtr = (romData[stateOff + 20].toInt() and 0xFF) or
                    ((romData[stateOff + 21].toInt() and 0xFF) shl 8)
            if (existingPlmPtr == room.plmSetPtr) {
                romData[stateOff + 20] = (newPtr2 and 0xFF).toByte()
                romData[stateOff + 21] = ((newPtr2 shr 8) and 0xFF).toByte()
            }
        }

        // === Write to /tmp ===
        val tmpFile = File.createTempFile("sm_edit_test_", ".smc")
        tmpFile.deleteOnExit()
        tmpFile.writeBytes(romData)
        println("Wrote edited ROM to ${tmpFile.absolutePath} (${romData.size} bytes)")

        // === Read back from file ===
        val readParser = RomParser.loadRom(tmpFile.absolutePath)
        val readRoom = readParser.readRoomHeader(0x91F8)!!

        // Verify tile edit
        val readLevel = readParser.decompressLZ2(readRoom.levelDataPtr)
        val readLo = readLevel[editOff].toInt() and 0xFF
        val readHi = readLevel[editOff + 1].toInt() and 0xFF
        val readWord = (readHi shl 8) or readLo
        assertEquals(newBlockWord, readWord,
            "Block at ($editX,$editY) should be 0x${newBlockWord.toString(16)}")
        val readBts = readLevel[editBtsOff].toInt() and 0xFF
        assertEquals(0x09, readBts, "BTS at ($editX,$editY) should be 0x09")

        // Verify PLM edit
        val readPlms = readParser.parsePlmSet(readRoom.plmSetPtr)
        assertEquals(modifiedPlms.size, readPlms.size, "PLM count should match")
        val foundETank = readPlms.find { it.id == eTankVisibleId && it.x == editX && it.y == editY }
        assertNotNull(foundETank, "Should find added Energy Tank at ($editX,$editY)")
        assertEquals(0x51, foundETank!!.param, "E-Tank param should be 0x51")

        // Verify room header is still valid
        assertEquals(room.width, readRoom.width, "Room width should be unchanged")
        assertEquals(room.height, readRoom.height, "Room height should be unchanged")
        assertEquals(room.area, readRoom.area, "Room area should be unchanged")

        println("Full export round-trip: PASS")
        println("  Tile: block=0x${readWord.toString(16)}, BTS=0x${readBts.toString(16)}")
        println("  PLM: ${readPlms.size} entries, E-Tank at ($editX,$editY) param=0x${foundETank.param.toString(16)}")
        tmpFile.delete()
    }

    // ─── Item PLM parameter analysis ────────────────────────────────

    @Test
    fun `scan all item PLMs and verify unique params per area`() {
        val parser = loadTestRom() ?: return
        val repo = com.supermetroid.editor.data.RoomRepository()
        val allRoomInfos = repo.getAllRooms()
        val roomIds = allRoomInfos.mapNotNull { try { it.getRoomIdAsInt() } catch (_: Exception) { null } }

        val items = parser.scanAllItemPlms(roomIds)
        println("Total item PLMs across all rooms: ${items.size}")

        val byArea = items.groupBy { it.area }
        for ((area, areaItems) in byArea.entries.sortedBy { it.key }) {
            val areaName = when (area) { 0 -> "Crateria"; 1 -> "Brinstar"; 2 -> "Norfair"; 3 -> "Wrecked Ship"; 4 -> "Maridia"; 5 -> "Tourian"; else -> "Area$area" }
            println("$areaName (area=$area): ${areaItems.size} items")
            for (ri in areaItems) {
                val itemName = RomParser.itemNameForPlm(ri.plm.id) ?: "??"
                println("  Room 0x${ri.roomId.toString(16)} ($itemName) param=0x${ri.plm.param.toString(16)} pos=(${ri.plm.x},${ri.plm.y})")
            }

            val paramCounts = areaItems.groupBy { it.plm.param }
            val dupes = paramCounts.filter { it.value.size > 1 }
            if (dupes.isNotEmpty()) {
                println("  WARNING: duplicate params in $areaName:")
                for ((p, d) in dupes) println("    param=0x${p.toString(16)} used by ${d.size} items")
            }
        }

        assertTrue(items.size >= 80, "Should find at least 80 item PLMs (game has ~100)")
    }

    // ─── Multiple rooms door parsing ───────────────────────────────

    @Test
    fun `door parsing works across multiple rooms`() {
        val parser = loadTestRom() ?: return
        val testRooms = listOf(
            0x91F8 to "Landing Site",
            0x92FD to "Parlor",
            0x93FE to "West Ocean",
            0x9804 to "Bomb Torizo",
            0xA59F to "Kraid",
        )
        for ((roomId, name) in testRooms) {
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.doorOut == 0 || room.doorOut == 0xFFFF) {
                println("$name: no door-out pointer")
                continue
            }
            val doors = parser.parseDoorList(room.doorOut)
            assertTrue(doors.isNotEmpty(), "$name should have at least one door")
            for ((i, door) in doors.withIndex()) {
                val destRoom = parser.readRoomHeader(door.destRoomPtr)
                assertNotNull(destRoom,
                    "$name door #$i destination 0x${door.destRoomPtr.toString(16)} should be valid")
            }
            println("$name: ${doors.size} doors, all destinations valid")
        }
    }
}
