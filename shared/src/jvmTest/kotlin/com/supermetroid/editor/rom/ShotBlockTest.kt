package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Investigates shot block (type 0xC) BTS values to determine what weapon breaks them.
 * Tests against West Ocean (0x93FE) and other rooms with known shot block types.
 */
class ShotBlockTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun dumpShotBlocks(parser: RomParser, roomId: Int, roomName: String) {
        val room = parser.readRoomHeader(roomId) ?: run {
            println("Could not read room $roomName (0x${roomId.toString(16)})")
            return
        }
        val levelData = parser.decompressLZ2(room.levelDataPtr)
        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val tileDataStart = 2
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val btsDataStart = tileDataStart + layer1Size

        // Also parse PLMs to see if any affect shot blocks
        val plms = parser.parsePlmSet(room.plmSetPtr)

        println("=== $roomName (0x${roomId.toString(16)}) — ${room.width}×${room.height} screens ===")
        println("PLMs: ${plms.size} entries")

        // Count BTS distribution for shot blocks
        val btsCounts = mutableMapOf<Int, Int>()
        val shotBlocks = mutableListOf<Triple<Int, Int, Int>>() // (x, y, bts)

        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                val idx = by * blocksWide + bx
                val offset = tileDataStart + idx * 2
                if (offset + 1 >= levelData.size) continue
                val lo = levelData[offset].toInt() and 0xFF
                val hi = levelData[offset + 1].toInt() and 0xFF
                val blockType = ((hi shl 8) or lo shr 12) and 0x0F
                if (blockType == 0xC) {
                    val btsOffset = btsDataStart + idx
                    val bts = if (btsOffset < levelData.size) levelData[btsOffset].toInt() and 0xFF else 0
                    btsCounts[bts] = (btsCounts[bts] ?: 0) + 1
                    shotBlocks.add(Triple(bx, by, bts))
                }
            }
        }

        println("Shot block BTS distribution:")
        for ((bts, count) in btsCounts.toSortedMap()) {
            println("  BTS 0x${bts.toString(16).padStart(2, '0')}: $count blocks")
        }

        println("Shot block positions (${shotBlocks.size} total):")
        for ((x, y, bts) in shotBlocks) {
            // Check if any PLM is at or near this position
            val nearbyPlm = plms.find { it.x == x && it.y == y }
            val plmTag = if (nearbyPlm != null) " [PLM 0x${nearbyPlm.id.toString(16)}]" else ""
            println("  ($x, $y) BTS=0x${bts.toString(16).padStart(2, '0')}$plmTag")
        }

        // Also dump PLMs that might be shot-block related (not door caps)
        val nonDoorPlms = plms.filter { RomParser.doorCapColor(it.id) == null }
        if (nonDoorPlms.isNotEmpty()) {
            println("Non-door PLMs:")
            for (plm in nonDoorPlms) {
                println("  PLM 0x${plm.id.toString(16).padStart(4, '0')} at (${plm.x}, ${plm.y}) param=0x${plm.param.toString(16)}")
            }
        }
        println()
    }

    @Test
    fun `dump West Ocean shot blocks`() {
        val parser = loadTestRom() ?: return
        dumpShotBlocks(parser, 0x93FE, "West Ocean")
    }

    @Test
    fun `dump Landing Site shot blocks`() {
        val parser = loadTestRom() ?: return
        dumpShotBlocks(parser, 0x91F8, "Landing Site")
    }

    @Test
    fun `dump shot block PLM table from ROM`() {
        val parser = loadTestRom() ?: return
        // Shot block PLM table at SNES $94:9EA6
        // Each entry is a 2-byte PLM ID, indexed by BTS*2
        val tableAddr = parser.snesToPc(0x949EA6)
        val romData = parser.getRomData()
        
        println("=== Shot Block PLM Table ($94:9EA6) ===")
        println("BTS -> PLM ID (what happens when shot/bombed)")
        for (bts in 0 until 0x50) {
            val offset = tableAddr + bts * 2
            if (offset + 1 >= romData.size) break
            val lo = romData[offset].toInt() and 0xFF
            val hi = romData[offset + 1].toInt() and 0xFF
            val plmId = (hi shl 8) or lo
            if (plmId != 0) {
                println("  BTS 0x${bts.toString(16).padStart(2, '0')}: PLM 0x${plmId.toString(16).padStart(4, '0')}")
            }
        }
    }

    @Test
    fun `analyze D0xx shot block PLM headers`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        
        println("=== Shot Block Reaction PLM Headers (D0xx) ===")
        // Each PLM header has 2 pointers: setup routine, first instruction pointer
        // For door-type PLMs there are 3 pointers
        for (bts in 0..0x0B) {
            val tableOffset = parser.snesToPc(0x949EA6) + bts * 2
            val lo = romData[tableOffset].toInt() and 0xFF
            val hi = romData[tableOffset + 1].toInt() and 0xFF
            val plmId = (hi shl 8) or lo
            
            // Read the PLM header at $84:plmId
            val headerAddr = parser.snesToPc(0x840000 or plmId)
            if (headerAddr + 5 < romData.size) {
                val setup = (romData[headerAddr + 1].toInt() and 0xFF shl 8) or (romData[headerAddr].toInt() and 0xFF)
                val instr = (romData[headerAddr + 3].toInt() and 0xFF shl 8) or (romData[headerAddr + 2].toInt() and 0xFF)
                // Read first few bytes of header for analysis
                val headerBytes = (0 until 8).map { romData[headerAddr + it].toInt() and 0xFF }
                    .joinToString(" ") { "%02X".format(it) }
                println("  BTS 0x${bts.toString(16).padStart(2, '0')}: PLM 0x${plmId.toString(16)} -> setup=0x${setup.toString(16)}, instr=0x${instr.toString(16)}, raw=[$headerBytes]")
            }
        }
    }

    @Test
    fun `dump multiple rooms with shot blocks`() {
        val parser = loadTestRom() ?: return
        // Rooms known to have various shot block types
        val rooms = listOf(
            0x93FE to "West Ocean",
            0x91F8 to "Landing Site",
            0x9804 to "Bomb Torizo",
            0x92FD to "Parlor and Alcatraz",
            0xA59F to "Kraid",
        )
        for ((id, name) in rooms) {
            dumpShotBlocks(parser, id, name)
        }
    }

    @Test
    fun `BTS 0x0C-0x0F map to no-op PLM B62F in vanilla ROM`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        val tableAddr = parser.snesToPc(0x949EA6)

        for (bts in 0x0C..0x0F) {
            val offset = tableAddr + bts * 2
            val plmId = (romData[offset].toInt() and 0xFF) or
                    ((romData[offset + 1].toInt() and 0xFF) shl 8)
            assertEquals(0xB62F, plmId,
                "BTS 0x${bts.toString(16)} should map to no-op PLM \$B62F, got \$${plmId.toString(16)}")
        }
    }

    @Test
    fun `BTS 0x00-0x0B map to valid D0xx shot block PLMs`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        val tableAddr = parser.snesToPc(0x949EA6)

        for (bts in 0x00..0x0B) {
            val offset = tableAddr + bts * 2
            val plmId = (romData[offset].toInt() and 0xFF) or
                    ((romData[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue(plmId in 0xD064..0xD090,
                "BTS 0x${bts.toString(16)} should map to a D0xx PLM, got \$${plmId.toString(16)}")
        }
    }

    @Test
    fun `each valid BTS has a unique PLM ID`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        val tableAddr = parser.snesToPc(0x949EA6)

        val plmIds = mutableSetOf<Int>()
        for (bts in 0x00..0x0B) {
            val offset = tableAddr + bts * 2
            val plmId = (romData[offset].toInt() and 0xFF) or
                    ((romData[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue(plmIds.add(plmId),
                "BTS 0x${bts.toString(16)} PLM \$${plmId.toString(16)} is a duplicate")
        }
        assertEquals(12, plmIds.size, "Should have 12 unique shot block PLMs")
    }

    @Test
    fun `vanilla ROM has no shot blocks with BTS above 0x0B`() {
        val parser = loadTestRom() ?: return
        val repo = com.supermetroid.editor.data.RoomRepository()
        val roomIds = repo.getAllRooms().map { it.getRoomIdAsInt() }

        var found = 0
        for (rid in roomIds) {
            val room = parser.readRoomHeader(rid) ?: continue
            if (room.levelDataPtr == 0) continue
            val data = parser.decompressLZ2(room.levelDataPtr)
            val bw = room.width * 16
            val bh = room.height * 16
            val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            for (i in 0 until bw * bh) {
                val off = 2 + i * 2
                if (off + 1 >= data.size) break
                val word = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
                val blockType = (word shr 12) and 0xF
                if (blockType == 0xC) {
                    val btsOff = 2 + layer1Size + i
                    if (btsOff < data.size) {
                        val bts = data[btsOff].toInt() and 0xFF
                        if (bts in 0x0C..0x3F) {
                            found++
                            println("WARN: Room 0x${rid.toString(16)} has shot block with BTS 0x${bts.toString(16)}")
                        }
                    }
                }
            }
        }
        assertEquals(0, found, "Vanilla ROM should have no shot blocks with BTS 0x0C-0x3F")
    }

    @Test
    fun `item PLM IDs match SMILE reference`() {
        val smileDefs = mapOf(
            "Energy Tank"   to Triple(0xEF2B, 0xEED7, 0xEF7F),
            "Missile"       to Triple(0xEF2F, 0xEEDB, 0xEF83),
            "Super Missile" to Triple(0xEF33, 0xEEDF, 0xEF87),
            "Power Bomb"    to Triple(0xEF37, 0xEEE3, 0xEF8B),
            "Bomb"          to Triple(0xEF3B, 0xEEE7, 0xEF8F),
            "Charge Beam"   to Triple(0xEF3F, 0xEEEB, 0xEF93),
            "Ice Beam"      to Triple(0xEF43, 0xEEEF, 0xEF97),
            "Hi-Jump Boots" to Triple(0xEF47, 0xEEF3, 0xEF9B),
            "Speed Booster" to Triple(0xEF4B, 0xEEF7, 0xEF9F),
            "Wave Beam"     to Triple(0xEF4F, 0xEEFB, 0xEFA3),
            "Spazer"        to Triple(0xEF53, 0xEEFF, 0xEFA7),
            "Spring Ball"   to Triple(0xEF57, 0xEF03, 0xEFAB),
            "Varia Suit"    to Triple(0xEF5B, 0xEF07, 0xEFAF),
            "Gravity Suit"  to Triple(0xEF5F, 0xEF0B, 0xEFB3),
            "X-Ray Scope"   to Triple(0xEF63, 0xEF0F, 0xEFB7),
            "Plasma Beam"   to Triple(0xEF67, 0xEF13, 0xEFBB),
            "Grapple Beam"  to Triple(0xEF6B, 0xEF17, 0xEFBF),
            "Space Jump"    to Triple(0xEF6F, 0xEF1B, 0xEFC3),
            "Screw Attack"  to Triple(0xEF73, 0xEF1F, 0xEFC7),
            "Morph Ball"    to Triple(0xEF77, 0xEF23, 0xEFCB),
            "Reserve Tank"  to Triple(0xEF7B, 0xEF27, 0xEFCF),
        )

        for (def in RomParser.ITEM_DEFS) {
            val expected = smileDefs[def.name]
                ?: fail("Item '${def.name}' not found in SMILE reference")
            assertEquals(expected.first, def.chozoId,
                "${def.name} chozo ID mismatch")
            assertEquals(expected.second, def.visibleId,
                "${def.name} visible ID mismatch")
            assertEquals(expected.third, def.hiddenId,
                "${def.name} hidden ID mismatch")
        }
        assertEquals(smileDefs.size, RomParser.ITEM_DEFS.size,
            "ITEM_DEFS should have ${smileDefs.size} entries")
    }

    @Test
    fun `gate PLM params match SMILE reference`() {
        val expectedGates = mapOf(
            0x00 to "Blue (left)",
            0x02 to "Blue (right)",
            0x04 to "Pink (left)",
            0x06 to "Pink (right)",
            0x08 to "Green (left)",
            0x0A to "Green (right)",
            0x0C to "Yellow (left)",
            0x0E to "Yellow (right)",
        )
        for (gate in RomParser.GATE_PLMS) {
            if (gate.plmId == 0xC836) {
                assertTrue(gate.param in expectedGates,
                    "Gate param 0x${gate.param.toString(16)} not in SMILE reference")
            }
        }
    }

    @Test
    fun `station PLM params use correct format`() {
        val saveStation = RomParser.STATION_PLMS.find { it.plmId == 0xB76F }
        assertNotNull(saveStation, "Save station PLM should be in catalog")
        assertEquals(0x8000, saveStation!!.defaultParam,
            "Save station high byte should be 0x80")

        val energyRefill = RomParser.STATION_PLMS.find { it.plmId == 0xB6DF }
        assertNotNull(energyRefill, "Energy refill PLM should be in catalog")
        assertEquals(0x0000, energyRefill!!.defaultParam,
            "Energy/missile refill default param should be 0x0000 (not save-station format)")
    }
}
