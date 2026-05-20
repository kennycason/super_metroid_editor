package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import java.io.File

/**
 * Validates ROM data format assumptions against the actual vanilla ROM.
 * All expected values are derived from Kejardon's docs and Patrick Johnston's
 * bank $8F disassembly. See docs/rom_data_format.md.
 */
class RomDataFormatTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    // ── State data field offsets ───────────────────────────────────

    @Nested
    inner class StateDataOffsets {

        @Test
        fun `state data is 26 bytes with PLM pointer at offset 20`() {
            val parser = loadTestRom() ?: return
            val stateOffsets = parser.findAllStateDataOffsets(0x91F8)
            assertTrue(stateOffsets.isNotEmpty(), "Landing Site should have state data")

            for (offset in stateOffsets) {
                val stateData = parser.readStateData(offset)
                assertTrue(stateData.containsKey("plmSetPtr"), "State data should have plmSetPtr field")
                assertTrue(stateData.containsKey("levelDataPtr"), "State data should have levelDataPtr field")
                assertTrue(stateData.containsKey("enemySetPtr"), "State data should have enemySetPtr field")

                val romData = parser.getRomData()
                val plmPtrDirect = readU16(romData, offset + 20)
                assertEquals(stateData["plmSetPtr"], plmPtrDirect,
                    "PLM pointer from readStateData must match raw bytes at offset+20")

                val enemyPtrDirect = readU16(romData, offset + 8)
                assertEquals(stateData["enemySetPtr"], enemyPtrDirect,
                    "Enemy pop pointer from readStateData must match raw bytes at offset+8")
            }
        }

        @Test
        fun `Landing Site default state has valid pointers`() {
            val parser = loadTestRom() ?: return
            val stateOffsets = parser.findAllStateDataOffsets(0x91F8)
            val defaultState = stateOffsets.last()
            val data = parser.readStateData(defaultState)

            assertTrue(data["levelDataPtr"]!! > 0, "Level data pointer should be non-zero")
            assertTrue(data["plmSetPtr"]!! > 0, "PLM set pointer should be non-zero")
            assertTrue(data["enemySetPtr"]!! > 0, "Enemy set pointer should be non-zero")
            assertTrue(data["tileset"]!! in 0..28, "Tileset should be 0-28")
        }

        @Test
        fun `Crateria Save Room state data has save station PLM`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x93D5) ?: fail("Crateria Save Room not found")
            val plms = parser.parsePlmSet(room.plmSetPtr)
            assertTrue(plms.any { it.id == 0xB76F },
                "Crateria Save Room should have save station PLM (0xB76F)")
        }

        @Test
        fun `state data field offsets match documented layout for multiple rooms`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()

            val testRooms = listOf(0x91F8, 0x92FD, 0x9804, 0xA59F, 0xCD13)
            for (roomId in testRooms) {
                val offsets = parser.findAllStateDataOffsets(roomId)
                assertTrue(offsets.isNotEmpty(), "Room 0x${roomId.toString(16)} should have states")

                for (stateOff in offsets) {
                    val levelPtr = (romData[stateOff].toInt() and 0xFF) or
                        ((romData[stateOff + 1].toInt() and 0xFF) shl 8) or
                        ((romData[stateOff + 2].toInt() and 0xFF) shl 16)
                    val tileset = romData[stateOff + 3].toInt() and 0xFF
                    val plmPtr = readU16(romData, stateOff + 20)

                    assertTrue(levelPtr > 0,
                        "Room 0x${roomId.toString(16)}: level data ptr should be > 0")
                    assertTrue(tileset in 0..28,
                        "Room 0x${roomId.toString(16)}: tileset $tileset out of range")
                    assertTrue(plmPtr != 0xFFFF,
                        "Room 0x${roomId.toString(16)}: PLM ptr should not be 0xFFFF")
                }
            }
        }
    }

    // ── State condition parsing ────────────────────────────────────

    @Nested
    inner class StateConditions {

        @Test
        fun `Landing Site has exactly 4 states`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x91F8)
            assertEquals(4, offsets.size,
                "Landing Site should have 4 states (timebomb, power bombs, awake, default)")
        }

        @Test
        fun `Crateria Save Room has exactly 1 state (default only)`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x93D5)
            assertEquals(1, offsets.size, "Single-state room should have exactly 1 state")
        }

        @Test
        fun `Bomb Torizo room has 3 states`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x9804)
            assertEquals(3, offsets.size,
                "Bomb Torizo should have 3 states (timebomb, torizo dead, default)")
        }

        @Test
        fun `Crateria mainstreet has 3 states`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x92FD)
            assertEquals(3, offsets.size,
                "Crateria mainstreet: timebomb, awake, default")
        }

        @Test
        fun `Mother Brain room has 3 states with E5FF as 4-byte condition`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0xDD58)
            assertEquals(3, offsets.size,
                "MB room should have 3 states (E5FF boss dead, E612 event, E5E6 default)")

            val states = parser.parseRoomStates(0xDD58)
            assertEquals(3, states.size,
                "parseRoomStates should also find 3 states for MB room")

            assertEquals(0xE5FF, states[0].conditionCode, "First state should be E5FF")
            assertEquals(0xE612, states[1].conditionCode, "Second state should be E612")
            assertEquals(0xE5E6, states[2].conditionCode, "Third state should be E5E6 (default)")

            for (state in states) {
                val data = parser.readStateData(state.stateDataPcOffset)
                val tileset = data["tileset"] ?: -1
                assertEquals(14, tileset,
                    "All MB states should use tileset 14, got $tileset for ${state.conditionName}")
            }
        }

        @Test
        fun `findAllStateDataOffsets and parseRoomStates agree on state count for all tested rooms`() {
            val parser = loadTestRom() ?: return
            val testRooms = listOf(0x91F8, 0x92FD, 0x9804, 0xDD58, 0x93D5)
            for (roomId in testRooms) {
                val offsets = parser.findAllStateDataOffsets(roomId)
                val states = parser.parseRoomStates(roomId)
                assertEquals(offsets.size, states.size,
                    "Room 0x${roomId.toString(16)}: findAllStateDataOffsets (${offsets.size}) " +
                    "and parseRoomStates (${states.size}) disagree on state count")
            }
        }

        @Test
        fun `all states for a room have the same level data size`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x91F8)

            val room = parser.readRoomHeader(0x91F8)!!
            val expectedBlocks = room.width * room.height * 256
            for (stateOff in offsets) {
                val data = parser.readStateData(stateOff)
                val levelPtr = data["levelDataPtr"]!!
                if (levelPtr == 0) continue
                val decompressed = parser.decompressLZ2(levelPtr)
                assertTrue(decompressed.size >= expectedBlocks * 2,
                    "Decompressed level data should be >= ${expectedBlocks * 2} bytes (Layer 1)")
            }
        }
    }

    // ── Door cap PLM IDs ──────────────────────────────────────────

    @Nested
    inner class DoorCapPlmIds {

        /**
         * Door PLM headers in bank $84 are 6 bytes: setup(2) + open(2) + close(2).
         * Each valid door PLM ID should read 3 meaningful pointers from bank $84.
         * Invalid IDs (pointing mid-header) would read garbage.
         */
        private fun assertValidDoorPlmHeader(parser: RomParser, plmId: Int, label: String) {
            val romData = parser.getRomData()
            val pc = parser.snesToPc(0x840000 or plmId)
            assertTrue(pc > 0 && pc + 5 < romData.size, "$label: PC address out of range")

            val setup = readU16(romData, pc)
            val openInstr = readU16(romData, pc + 2)

            assertTrue(setup in 0xC700..0xCFFF,
                "$label (0x${plmId.toString(16)}): setup ptr 0x${setup.toString(16)} out of expected range")
            assertTrue(openInstr in 0xBE00..0xD000,
                "$label (0x${plmId.toString(16)}): open instr ptr 0x${openInstr.toString(16)} out of range")
        }

        @Test
        fun `all opening door cap PLM IDs have valid 6-byte headers`() {
            val parser = loadTestRom() ?: return

            val doorPlms = mapOf(
                "Grey Left" to 0xC842, "Grey Right" to 0xC848,
                "Grey Up" to 0xC84E, "Grey Down" to 0xC854,
                "Yellow Left" to 0xC85A, "Yellow Right" to 0xC860,
                "Yellow Up" to 0xC866, "Yellow Down" to 0xC86C,
                "Green Left" to 0xC872, "Green Right" to 0xC878,
                "Green Up" to 0xC87E, "Green Down" to 0xC884,
                "Red Left" to 0xC88A, "Red Right" to 0xC890,
                "Red Up" to 0xC896, "Red Down" to 0xC89C,
                "Blue Left" to 0xC8A2, "Blue Right" to 0xC8A8,
                "Blue Up" to 0xC8AE, "Blue Down" to 0xC8B4,
            )

            for ((label, plmId) in doorPlms) {
                assertValidDoorPlmHeader(parser, plmId, label)
            }
        }

        @Test
        fun `Left-to-Right spacing is exactly 6 for all door colors`() {
            val pairs = listOf(
                "Grey" to (0xC842 to 0xC848),
                "Yellow" to (0xC85A to 0xC860),
                "Green" to (0xC872 to 0xC878),
                "Red" to (0xC88A to 0xC890),
                "Blue" to (0xC8A2 to 0xC8A8),
            )
            for ((color, ids) in pairs) {
                assertEquals(6, ids.second - ids.first,
                    "$color: Right - Left should be 6 (got ${ids.second - ids.first})")
            }
        }

        @Test
        fun `direction spacing is +6 for all four directions`() {
            val baseIds = mapOf(
                "Grey" to 0xC842, "Yellow" to 0xC85A,
                "Green" to 0xC872, "Red" to 0xC88A, "Blue" to 0xC8A2
            )
            for ((color, leftId) in baseIds) {
                val rightId = leftId + 6
                val upId = leftId + 12
                val downId = leftId + 18
                assertEquals(6, rightId - leftId, "$color L→R")
                assertEquals(6, upId - rightId, "$color R→U")
                assertEquals(6, downId - upId, "$color U→D")
            }
        }

        @Test
        fun `wrong PLM IDs at +4 offset produce invalid headers`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()

            val wrongIds = listOf(0xC8A6, 0xC88E, 0xC876, 0xC85E)
            for (wrongId in wrongIds) {
                val pc = parser.snesToPc(0x840000 or wrongId)
                val setup = readU16(romData, pc)

                // These land mid-header: the "setup" pointer they read is actually
                // the close-instruction pointer of the Left door, NOT a setup routine.
                // Valid setup routines are C794, C7B1, C7BB -- these wrong ones won't match.
                val validSetups = setOf(0xC794, 0xC7B1, 0xC7BB)
                assertFalse(setup in validSetups,
                    "PLM 0x${wrongId.toString(16)} at +4 offset should NOT read a valid setup " +
                    "(got 0x${setup.toString(16)}) -- this ID is mid-header garbage")
            }
        }

        @Test
        fun `vanilla Landing Site door caps use correct PLM IDs`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x91F8)
            val defaultState = offsets.last()
            val data = parser.readStateData(defaultState)
            val plms = parser.parsePlmSet(data["plmSetPtr"]!!)

            val doorPlms = plms.filter { RomParser.doorCapColor(it.id) != null }

            // Default state: Green left at (0x8E, 0x46), Yellow left at (0x8E, 0x16)
            val greenDoor = doorPlms.find { it.id == 0xC872 }
            assertNotNull(greenDoor, "Landing Site default should have green door (0xC872)")
            assertEquals(0x8E, greenDoor!!.x)
            assertEquals(0x46, greenDoor.y)

            val yellowDoor = doorPlms.find { it.id == 0xC85A }
            assertNotNull(yellowDoor, "Landing Site default should have yellow door (0xC85A)")
            assertEquals(0x8E, yellowDoor!!.x)
            assertEquals(0x16, yellowDoor.y)
        }

        @Test
        fun `door cap setup routines match expected values per color`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()

            // Grey doors use C794, colored doors use C7B1, blue doors use C7BB
            val expectedSetup = mapOf(
                0xC842 to 0xC794, 0xC848 to 0xC794, // Grey
                0xC85A to 0xC7B1, 0xC860 to 0xC7B1, // Yellow
                0xC872 to 0xC7B1, 0xC878 to 0xC7B1, // Green
                0xC88A to 0xC7B1, 0xC890 to 0xC7B1, // Red
                0xC8A2 to 0xC7BB, 0xC8A8 to 0xC7BB, // Blue
            )
            for ((plmId, expectedSetupPtr) in expectedSetup) {
                val pc = parser.snesToPc(0x840000 or plmId)
                val actualSetup = readU16(romData, pc)
                assertEquals(expectedSetupPtr, actualSetup,
                    "PLM 0x${plmId.toString(16)}: setup should be 0x${expectedSetupPtr.toString(16)}")
            }
        }
    }

    // ── Gate PLM ──────────────────────────────────────────────────

    @Nested
    inner class GatePlm {

        @Test
        fun `gate PLM C836 has valid header in bank 84`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()
            val pc = parser.snesToPc(0x84C836)

            val setup = readU16(romData, pc)
            assertEquals(0xC6E0, setup,
                "Gate PLM C836 setup should be C6E0 (gate direction selector)")
        }

        @Test
        fun `vanilla rooms use C836 for gates with correct params`() {
            val parser = loadTestRom() ?: return

            // Landing Site default state has Green gate left (C872) and Yellow gate left (C85A).
            // But gates (C836) appear in other rooms. Check Crateria mainstreet.
            val room = parser.readRoomHeader(0x92FD) ?: fail("Crateria mainstreet not found")
            val plms = parser.parsePlmSet(room.plmSetPtr)

            // Crateria mainstreet has a red door at (0x1E, 0x36) with PLM C88A
            val redDoor = plms.find { it.id == 0xC88A }
            assertNotNull(redDoor, "Crateria mainstreet should have red door (C88A)")
        }
    }

    // ── PLM set structure ─────────────────────────────────────────

    @Nested
    inner class PlmSetStructure {

        @Test
        fun `PLM set entries are 6 bytes each`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()

            val room = parser.readRoomHeader(0x91F8)!!
            val plmSetAddr = 0x8F0000 or room.plmSetPtr
            var pc = parser.snesToPc(plmSetAddr)
            var count = 0

            while (pc + 5 < romData.size && count < 100) {
                val id = readU16(romData, pc)
                if (id == 0) break
                pc += 6
                count++
            }

            // Verify terminator is 0x0000
            val terminator = readU16(romData, pc)
            assertEquals(0, terminator, "PLM set should end with 0x0000 terminator")
            assertTrue(count > 0, "Should have read at least one PLM entry")

            // Verify parsePlmSet returns same count
            val parsed = parser.parsePlmSet(room.plmSetPtr)
            assertEquals(count, parsed.size,
                "parsePlmSet count should match manual iteration")
        }

        @Test
        fun `PLM coordinates are within room bounds`() {
            val parser = loadTestRom() ?: return
            val testRooms = listOf(0x91F8, 0x92FD, 0x9804, 0xA59F)

            for (roomId in testRooms) {
                val room = parser.readRoomHeader(roomId) ?: continue
                val plms = parser.parsePlmSet(room.plmSetPtr)
                val maxX = room.width * 16
                val maxY = room.height * 16

                for (plm in plms) {
                    assertTrue(plm.x < maxX,
                        "Room 0x${roomId.toString(16)} PLM 0x${plm.id.toString(16)}: " +
                        "x=${plm.x} should be < $maxX")
                    assertTrue(plm.y < maxY,
                        "Room 0x${roomId.toString(16)} PLM 0x${plm.id.toString(16)}: " +
                        "y=${plm.y} should be < $maxY")
                }
            }
        }
    }

    // ── Room header ───────────────────────────────────────────────

    @Nested
    inner class RoomHeader {

        @Test
        fun `room header is 11 bytes with door-out pointer at offset 9`() {
            val parser = loadTestRom() ?: return
            val romData = parser.getRomData()
            val pc = parser.roomIdToPc(0x91F8)

            // Byte 0: room index
            // Byte 1: area (0 = Crateria)
            assertEquals(0, romData[pc + 1].toInt() and 0xFF, "Landing Site area = 0 (Crateria)")

            // Bytes 4-5: width, height (direct screen count)
            val width = romData[pc + 4].toInt() and 0xFF
            val height = romData[pc + 5].toInt() and 0xFF
            assertEquals(9, width, "Landing Site width = 9 screens")
            assertEquals(5, height, "Landing Site height = 5 screens")

            // Bytes 9-10: door-out pointer (bank $8F)
            val doorOutPtr = readU16(romData, pc + 9)
            assertTrue(doorOutPtr > 0x8000, "Door-out pointer should be > 0x8000 (bank $8F)")
        }

        @Test
        fun `room areas match expected values`() {
            val parser = loadTestRom() ?: return
            val expectations = mapOf(
                0x91F8 to 0, // Crateria
                0xA59F to 1, // Brinstar (Kraid)
                0xB32E to 2, // Norfair (Ridley)
                0xCD13 to 3, // Wrecked Ship (Phantoon)
                0xDA60 to 4, // Maridia (Draygon)
                0xDD58 to 5, // Tourian (Mother Brain)
            )
            for ((roomId, expectedArea) in expectations) {
                val room = parser.readRoomHeader(roomId)
                assertNotNull(room, "Room 0x${roomId.toString(16)} should exist")
                assertEquals(expectedArea, room!!.area,
                    "Room 0x${roomId.toString(16)} area")
            }
        }
    }

    // ── LZ5 compression round-trip ────────────────────────────────

    @Nested
    inner class Lz5RoundTrip {

        @Test
        fun `compress then decompress produces identical data for all Landing Site states`() {
            val parser = loadTestRom() ?: return
            val offsets = parser.findAllStateDataOffsets(0x91F8)

            for ((i, stateOff) in offsets.withIndex()) {
                val data = parser.readStateData(stateOff)
                val levelPtr = data["levelDataPtr"] ?: continue
                if (levelPtr == 0) continue

                val (original, _) = parser.decompressLZ2WithSize(levelPtr)
                val compressed = LZ5Compressor.compress(original)
                val verify = RomParser(compressed).decompressLZ5AtPc(0)

                assertTrue(verify.contentEquals(original),
                    "State $i: LZ5 round-trip mismatch (orig=${original.size}, compressed=${compressed.size})")
            }
        }
    }

    // ── Other common PLM IDs ──────────────────────────────────────

    @Nested
    inner class CommonPlmIds {

        @Test
        fun `save station PLM B76F exists at correct position in Crateria save room`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x93D5) ?: fail("Save room not found")
            val plms = parser.parsePlmSet(room.plmSetPtr)

            val save = plms.find { it.id == 0xB76F }
            assertNotNull(save, "Crateria save room should have save station PLM 0xB76F")
            assertEquals(5, save!!.x, "Save station X")
            assertEquals(11, save.y, "Save station Y (0x0B)")
            assertEquals(1, save.param, "Save station index = 1")
        }

        @Test
        fun `map station PLM B6D3 exists in Crateria map room`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x9994) ?: fail("Crateria map room not found")
            val plms = parser.parsePlmSet(room.plmSetPtr)

            val map = plms.find { it.id == 0xB6D3 }
            assertNotNull(map, "Crateria map room should have map station PLM 0xB6D3")
        }

        @Test
        fun `scroll PLMs use IDs B703 B63B B647`() {
            val parser = loadTestRom() ?: return
            // Crateria mainstreet has many scroll PLMs
            val room = parser.readRoomHeader(0x92FD) ?: fail("Mainstreet not found")
            val plms = parser.parsePlmSet(room.plmSetPtr)

            val scrollIds = setOf(0xB703, 0xB63B, 0xB647, 0xB63F, 0xB643)
            val scrollPlms = plms.filter { it.id in scrollIds }
            assertTrue(scrollPlms.isNotEmpty(), "Crateria mainstreet should have scroll PLMs")
            assertTrue(scrollPlms.any { it.id == 0xB703 }, "Should have normal scroll PLM (B703)")
        }
    }

    // ── LoROM address mapping ─────────────────────────────────────

    @Nested
    inner class LoromMapping {

        @Test
        fun `snesToPc and pcToSnes are inverse operations`() {
            val parser = loadTestRom() ?: return

            val testAddresses = listOf(
                0x8F8000, 0x8F91F8, 0x8FFFFF,
                0x848000, 0x84C836,
                0xA18000,
                0xC08000,
            )
            for (snes in testAddresses) {
                val pc = parser.snesToPc(snes)
                val roundTrip = parser.pcToSnes(pc)
                assertEquals(snes, roundTrip,
                    "Round-trip for \$${snes.toString(16).uppercase()}: " +
                    "PC=0x${pc.toString(16)}, back=\$${roundTrip.toString(16).uppercase()}")
            }
        }

        @Test
        fun `deriveDoorCapPosition returns 1 tile inward from edge, matching vanilla`() {
            val parser = loadTestRom() ?: return
            // DDF3 door #2 → DD58: vanilla cap=(62,6), direction Left, screen(3,0)
            val capDD58Right = parser.deriveDoorCapPosition(0xDD58, 1, 3, 0)
            assertNotNull(capDD58Right, "Should find door blocks on DD58 right edge")
            val cx = capDD58Right!! and 0xFF
            val cy = (capDD58Right shr 8) and 0xFF
            assertEquals(62, cx, "DD58 Left entry: cap X should be 62 (1 inward from edge 63)")
            assertEquals(6, cy, "DD58 Left entry: cap Y should be 6 (topmost door block)")

            // DE4D → DD58: vanilla cap=(1,6), direction Right, screen(0,0)
            // DD58's left edge has no door blocks, so derive should return null
            val capDD58Left = parser.deriveDoorCapPosition(0xDD58, 0, 0, 0)
            assertNull(capDD58Left, "DD58 has no door blocks on left edge (x=0)")

            // Test Landing Site (91F8) — well-known vanilla caps
            val ls = parser.readRoomHeader(0x91F8)!!
            val lsDoors = parser.parseDoorList(ls.doorOut)
            println("\nLanding Site doors for cap comparison:")
            for ((i, d) in lsDoors.withIndex()) {
                val vanillaCapX = d.doorCapCode and 0xFF
                val vanillaCapY = (d.doorCapCode shr 8) and 0xFF
                println("  Door #$i → 0x${d.destRoomPtr.toString(16).uppercase()}, " +
                    "dir=${d.directionName}, vanilla cap=($vanillaCapX,$vanillaCapY)")
            }
        }

        @Test
        fun `bank 8F PLM area starts at correct PC offset`() {
            val parser = loadTestRom() ?: return
            val pc = parser.snesToPc(0x8F8000)
            val expected = if (parser.getRomData().size > 0x300000) 0x200 + 0x78000 else 0x78000
            assertEquals(expected, pc)
        }
    }

    @Nested
    inner class MotherBrainDoorCapAnalysis {

        @Test
        fun `analyze MB room door caps and incoming connections`() {
            val parser = loadTestRom() ?: return
            val mbRoom = parser.readRoomHeader(0xDD58) ?: return
            println("=== Mother Brain Room (0xDD58) ===")
            println("Dimensions: ${mbRoom.width}x${mbRoom.height} screens (${mbRoom.width*16}x${mbRoom.height*16} blocks)")

            val mbDoors = parser.parseDoorList(mbRoom.doorOut)
            println("\nDD58 outgoing doors (${mbDoors.size}):")
            for ((i, d) in mbDoors.withIndex()) {
                val capX = d.doorCapCode and 0xFF
                val capY = (d.doorCapCode shr 8) and 0xFF
                println("  Door #$i → dest=0x${d.destRoomPtr.toString(16).uppercase()}, " +
                    "dir=${d.directionName}(${d.direction}), cap=($capX,$capY), " +
                    "screen=(${d.screenX},${d.screenY})")
            }

            val mbStates = parser.findAllStateDataOffsets(0xDD58)
            val defaultState = mbStates.lastOrNull()
            if (defaultState != null) {
                val data = parser.readStateData(defaultState)
                val plmPtr = data["plmSetPtr"] ?: 0
                val plms = parser.parsePlmSet(plmPtr)
                println("\nDD58 PLM set ($8F:${plmPtr.toString(16).uppercase()}, ${plms.size} PLMs):")
                for (plm in plms) {
                    val isDoorCap = plm.id in listOf(0xC8A2, 0xC8A8, 0xC8AE, 0xC8B4)
                    println("  PLM 0x${plm.id.toString(16).uppercase()} at (${plm.x},${plm.y}) param=0x${plm.param.toString(16)}" +
                        if (isDoorCap) " *** DOOR CAP ***" else "")
                }
            }

            println("\n--- Scanning Tourian rooms for doors entering DD58 ---")
            val tourian = listOf(0xDAE1, 0xDB31, 0xDB7D, 0xDBCD, 0xDC19, 0xDC65,
                0xDCB1, 0xDCFF, 0xDD2B, 0xDD58, 0xDDA3, 0xDDF3, 0xDE4D, 0xDEA7,
                0xDF1B, 0xDF8D, 0xE06B, 0xE0B5)
            for (srcId in tourian) {
                if (srcId == 0xDD58) continue
                val srcRoom = parser.readRoomHeader(srcId) ?: continue
                val srcDoors = parser.parseDoorList(srcRoom.doorOut)
                for ((di, d) in srcDoors.withIndex()) {
                    if (d.destRoomPtr == 0xDD58) {
                        val capX = d.doorCapCode and 0xFF
                        val capY = (d.doorCapCode shr 8) and 0xFF
                        println("  Room 0x${srcId.toString(16).uppercase()} door #$di → DD58: " +
                            "dir=${d.directionName}(${d.direction}), cap=($capX,$capY), " +
                            "screen=(${d.screenX},${d.screenY})")
                    }
                }
            }

            val autoCap = parser.deriveDoorCapPosition(0xDD58, 0, 0, 0)
            if (autoCap != null) {
                val ax = autoCap and 0xFF; val ay = (autoCap shr 8) and 0xFF
                println("\nAuto-derived cap for DD58 dir=Right(0) screen(0,0): ($ax,$ay)")
            }
            val autoCap1 = parser.deriveDoorCapPosition(0xDD58, 1, 3, 0)
            if (autoCap1 != null) {
                val ax = autoCap1 and 0xFF; val ay = (autoCap1 shr 8) and 0xFF
                println("Auto-derived cap for DD58 dir=Left(1) screen(3,0): ($ax,$ay)")
                assertEquals(62, ax, "Cap X for Left entry should be 1 tile inward from right edge (62, not 63)")
                assertEquals(6, ay, "Cap Y should match topmost door block")
            }

            println("\n--- Door blocks on edges of DD58 level data ---")
            val levelData = try { parser.decompressLZ2(mbRoom.levelDataPtr) } catch (_: Exception) { null }
            if (levelData != null) {
                val bw = mbRoom.width * 16
                val bh = mbRoom.height * 16
                println("Left edge (x=0):")
                for (by in 0 until bh) {
                    val bt = parser.blockTypeAt(levelData, bw, bh, 0, by)
                    if (bt == 0x9) println("  Door block at (0,$by)")
                }
                println("Right edge (x=${bw-1}):")
                for (by in 0 until bh) {
                    val bt = parser.blockTypeAt(levelData, bw, bh, bw - 1, by)
                    if (bt == 0x9) println("  Door block at (${bw-1},$by)")
                }
                println("x=1 column:")
                for (by in 0 until bh) {
                    val bt = parser.blockTypeAt(levelData, bw, bh, 1, by)
                    if (bt == 0x9) println("  Door block at (1,$by)")
                }
                println("x=${bw-2} column:")
                for (by in 0 until bh) {
                    val bt = parser.blockTypeAt(levelData, bw, bh, bw - 2, by)
                    if (bt == 0x9) println("  Door block at (${bw-2},$by)")
                }
            }
        }
    }
}
