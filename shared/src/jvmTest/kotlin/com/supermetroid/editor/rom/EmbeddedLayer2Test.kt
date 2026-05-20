package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

class EmbeddedLayer2Test {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `embedded L2 data layout is L1-BTS-L2`() {
        val parser = loadTestRom() ?: return

        // Bat Cave (0xb07a, 1x2): L2 should fill entire room (100% non-zero words)
        val room = parser.readRoomHeader(0xb07a) ?: return
        assertEquals(0, room.bgScrolling, "Bat Cave should have embedded L2 (bgScrolling=0)")

        val levelData = parser.decompressLZ2(room.levelDataPtr)
        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val totalBlocks = blocksWide * blocksTall

        val l2Words = parser.readEmbeddedLayer2(levelData, blocksWide, blocksTall)
        assertNotNull(l2Words, "Bat Cave should have embedded L2 data")

        // With correct layout, L2 should have content at row 0
        val firstRowHasContent = (0 until blocksWide).any { l2Words!![it] != 0 }
        assertTrue(firstRowHasContent, "L2 row 0 should have non-zero tiles (layout is L1-BTS-L2)")

        // All L2 words should be non-zero for Bat Cave (full coverage background)
        val nonZeroCount = l2Words!!.count { it != 0 }
        assertEquals(totalBlocks, nonZeroCount, "Bat Cave L2 should have 100% non-zero words")
    }

    @Test
    fun `L2 covers full room for various room sizes`() {
        val parser = loadTestRom() ?: return

        val testRooms = mapOf(
            0xa15b to "Hopper Energy Tank Room (1x1)",
            0xaa82 to "Post Crocomire Farming Room (2x2)",
            0xb6ee to "Lower Norfair Fireflea Room (3x6)",
        )

        for ((addr, name) in testRooms) {
            val room = parser.readRoomHeader(addr) ?: continue
            if (room.bgScrolling != 0) continue

            val levelData = parser.decompressLZ2(room.levelDataPtr)
            val blocksWide = room.width * 16
            val blocksTall = room.height * 16
            val l2Words = parser.readEmbeddedLayer2(levelData, blocksWide, blocksTall)
            assertNotNull(l2Words, "$name: should have embedded L2")

            // L2 row 0 should have content (not BTS data misread as L2)
            val row0HasContent = (0 until blocksWide).any { l2Words!![it] != 0 }
            assertTrue(row0HasContent, "$name: L2 row 0 should have content")
        }
    }

    @Test
    fun `door cap color uses exact PLM IDs not ranges`() {
        // Verify only the 4 directional IDs per color match, not intermediate PLM IDs
        val greyIds = listOf(0xC842, 0xC848, 0xC84E, 0xC854)
        val yellowIds = listOf(0xC85A, 0xC860, 0xC866, 0xC86C)
        val greenIds = listOf(0xC872, 0xC878, 0xC87E, 0xC884)
        val redIds = listOf(0xC88A, 0xC890, 0xC896, 0xC89C)
        val blueIds = listOf(0xC8A2, 0xC8A8, 0xC8AE, 0xC8B4)

        for (id in greyIds) assertNotNull(RomParser.doorCapColor(id), "Grey cap $id should match")
        for (id in yellowIds) assertNotNull(RomParser.doorCapColor(id), "Yellow cap $id should match")
        for (id in greenIds) assertNotNull(RomParser.doorCapColor(id), "Green cap $id should match")
        for (id in redIds) assertNotNull(RomParser.doorCapColor(id), "Red cap $id should match")
        for (id in blueIds) assertNotNull(RomParser.doorCapColor(id), "Blue cap $id should match")

        // IDs between door caps should NOT match (these are scroll PLMs, events, etc.)
        val nonDoorIds = listOf(0xC844, 0xC846, 0xC85C, 0xC85E, 0xC874, 0xC876)
        for (id in nonDoorIds) assertNull(RomParser.doorCapColor(id), "Non-door PLM $id should not match")
    }
}
