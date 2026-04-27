package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Verifies PLM parsing and door cap detection for Landing Site (0x91F8).
 *
 * Landing Site has 4 doors:
 *   - Bottom-left: Blue (beam)
 *   - Top-left: can vary by state, typically blue
 *   - Bottom-right: Green (super missile)
 *   - Top-right: Yellow (power bomb)
 */
class DoorCapTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    @Test
    fun `Landing Site room dimensions`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)
        assertNotNull(room, "Landing Site should be found")
        assertEquals(0, room!!.area, "Landing Site is Crateria (area 0)")
        assertEquals(9, room.width, "Landing Site is 9 screens wide")
        assertEquals(5, room.height, "Landing Site is 5 screens tall")
    }

    @Test
    fun `Landing Site PLM set is non-empty`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        assertTrue(room.plmSetPtr != 0, "PLM set pointer should be non-zero (got 0x${room.plmSetPtr.toString(16)})")

        val plms = parser.parsePlmSet(room.plmSetPtr)
        assertTrue(plms.isNotEmpty(), "Landing Site should have PLM entries")
        println("Landing Site: ${plms.size} PLMs, plmSetPtr=0x${room.plmSetPtr.toString(16)}")
    }

    @Test
    fun `Landing Site has correct door cap colors`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val plms = parser.parsePlmSet(room.plmSetPtr)

        val doorCapPlms = plms.filter { RomParser.doorCapColor(it.id) != null }
        assertTrue(doorCapPlms.isNotEmpty(), "Landing Site should have at least one door cap PLM")

        println("=== Landing Site Door Caps ===")
        for (plm in doorCapPlms) {
            val color = RomParser.doorCapColor(plm.id)!!
            val colorName = when (color) {
                RomParser.DOOR_CAP_BLUE -> "BLUE"
                RomParser.DOOR_CAP_RED -> "RED"
                RomParser.DOOR_CAP_GREEN -> "GREEN"
                RomParser.DOOR_CAP_YELLOW -> "YELLOW/PB"
                RomParser.DOOR_CAP_GREY -> "GREY"
                else -> "0x${color.toString(16)}"
            }
            println("  $colorName cap PLM 0x${plm.id.toString(16)} at block (${plm.x}, ${plm.y})")
        }

        // Verify specific doors:
        // Bottom-right (x=142, y=70) = Green (super missile), PLM $C872
        val bottomRight = doorCapPlms.find { it.x == 142 && it.y == 70 }
        assertNotNull(bottomRight, "Should find door cap PLM at (142, 70)")
        assertEquals(RomParser.DOOR_CAP_GREEN, RomParser.doorCapColor(bottomRight!!.id),
            "Bottom-right door should be GREEN (super missile)")

        // Top-right (x=142, y=22) = Yellow (power bomb), PLM $C85A
        val topRight = doorCapPlms.find { it.x == 142 && it.y == 22 }
        assertNotNull(topRight, "Should find door cap PLM at (142, 22)")
        assertEquals(RomParser.DOOR_CAP_YELLOW, RomParser.doorCapColor(topRight!!.id),
            "Top-right door should be YELLOW (power bomb)")

        // Left-side doors have no cap PLMs (they're blue/beam by default)
    }

    @Test
    fun `doorCapColor only matches exact PLM IDs not in-between values`() {
        // Exact door cap IDs (4 per color, stride 6: left/right/up/down)
        val expectedGrey   = listOf(0xC842, 0xC848, 0xC84E, 0xC854)
        val expectedYellow = listOf(0xC85A, 0xC860, 0xC866, 0xC86C)
        val expectedGreen  = listOf(0xC872, 0xC878, 0xC87E, 0xC884)
        val expectedRed    = listOf(0xC88A, 0xC890, 0xC896, 0xC89C)
        val expectedBlue   = listOf(0xC8A2, 0xC8A8, 0xC8AE, 0xC8B4)

        for (id in expectedGrey)   assertEquals(RomParser.DOOR_CAP_GREY,   RomParser.doorCapColor(id), "0x${id.toString(16)} should be GREY")
        for (id in expectedYellow) assertEquals(RomParser.DOOR_CAP_YELLOW, RomParser.doorCapColor(id), "0x${id.toString(16)} should be YELLOW")
        for (id in expectedGreen)  assertEquals(RomParser.DOOR_CAP_GREEN,  RomParser.doorCapColor(id), "0x${id.toString(16)} should be GREEN")
        for (id in expectedRed)    assertEquals(RomParser.DOOR_CAP_RED,    RomParser.doorCapColor(id), "0x${id.toString(16)} should be RED")
        for (id in expectedBlue)   assertEquals(RomParser.DOOR_CAP_BLUE,   RomParser.doorCapColor(id), "0x${id.toString(16)} should be BLUE")

        // In-between IDs should NOT match (these are scroll PLMs, event PLMs, etc.)
        val inBetween = listOf(0xC843, 0xC845, 0xC84A, 0xC856, 0xC85B, 0xC863, 0xC870, 0xC875, 0xC886, 0xC89E, 0xC8A5, 0xC8B0)
        for (id in inBetween) assertNull(RomParser.doorCapColor(id), "0x${id.toString(16)} should NOT be a door cap")

        // Far outside range
        assertNull(RomParser.doorCapColor(0xB600), "0xB600 should not be a door cap")
        assertNull(RomParser.doorCapColor(0xC900), "0xC900 should not be a door cap")
    }

    @Test
    fun `dump all Landing Site PLMs for analysis`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val plms = parser.parsePlmSet(room.plmSetPtr)

        println("=== ALL Landing Site PLMs (${plms.size} entries) ===")
        for ((i, plm) in plms.withIndex()) {
            val doorColor = RomParser.doorCapColor(plm.id)
            val tag = when {
                doorColor == RomParser.DOOR_CAP_BLUE -> " [BLUE DOOR]"
                doorColor == RomParser.DOOR_CAP_RED -> " [RED DOOR]"
                doorColor == RomParser.DOOR_CAP_GREEN -> " [GREEN DOOR]"
                doorColor == RomParser.DOOR_CAP_YELLOW -> " [YELLOW DOOR]"
                else -> ""
            }
            println("  #$i: PLM 0x${plm.id.toString(16).padStart(4, '0')} at (${plm.x}, ${plm.y}) param=0x${plm.param.toString(16)}$tag")
        }
    }

    @Test
    fun `Landing Site door blocks in level data`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val levelData = parser.decompressLZ2(room.levelDataPtr)

        val blocksWide = room.width * 16  // 144
        val blocksTall = room.height * 16 // 80
        val tileDataStart = 2
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val btsDataStart = tileDataStart + layer1Size

        println("=== Landing Site Door Blocks (type 9) ===")
        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                val idx = by * blocksWide + bx
                val offset = tileDataStart + idx * 2
                if (offset + 1 >= levelData.size) continue
                val lo = levelData[offset].toInt() and 0xFF
                val hi = levelData[offset + 1].toInt() and 0xFF
                val blockType = ((hi shl 8) or lo shr 12) and 0x0F
                if (blockType == 0x9) {
                    val btsOffset = btsDataStart + idx
                    val bts = if (btsOffset < levelData.size) levelData[btsOffset].toInt() and 0xFF else 0
                    println("  Door block at ($bx, $by) BTS=0x${bts.toString(16)}")
                }
            }
        }
    }
}
