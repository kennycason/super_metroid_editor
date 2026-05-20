package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for room resize: level data transformation, scroll data handling,
 * and export correctness.
 */
class RoomResizeTest {

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

    // Helper to read a 16-bit LE word from a byte array
    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    // Helper to read L1 size header
    private fun l1Size(data: ByteArray): Int = readWord(data, 0)

    @Nested
    inner class ResizeLevelData {

        @Test
        fun `identity resize returns equivalent data`() {
            val parser = loadTestRom() ?: return
            // Parlor: 5x5 screens
            val room = parser.readRoomHeader(0x92FD)!!
            assertEquals(5, room.width)
            assertEquals(5, room.height)
            val original = parser.decompressLZ2(room.levelDataPtr)

            val resized = resizeLevelData(original, 5, 5, 5, 5)
            assertTrue(resized.contentEquals(original), "Identity resize should produce identical data")
        }

        @Test
        fun `grow width preserves existing tiles`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val original = parser.decompressLZ2(room.levelDataPtr)
            val origBlocksW = room.width * 16 // 80
            val newBlocksW = (room.width + 1) * 16 // 96

            val resized = resizeLevelData(original, room.width, room.height, room.width + 1, room.height)

            // L1 size header should reflect new dimensions
            val newL1Size = l1Size(resized)
            assertEquals(newBlocksW * room.height * 16 * 2, newL1Size)

            // Spot-check: tile at (0,0) should be preserved
            val origTile00 = readWord(original, 2)
            val resizedTile00 = readWord(resized, 2)
            assertEquals(origTile00, resizedTile00, "Tile (0,0) should be preserved after grow")

            // Spot-check: tile at (5,5) should be preserved
            val origIdx = 5 * origBlocksW + 5
            val newIdx = 5 * newBlocksW + 5
            val origTile55 = readWord(original, 2 + origIdx * 2)
            val resizedTile55 = readWord(resized, 2 + newIdx * 2)
            assertEquals(origTile55, resizedTile55, "Tile (5,5) should be preserved after grow")

            // New column tiles should be 0 (air)
            for (by in 0 until room.height * 16) {
                val idx = by * newBlocksW + origBlocksW // first new column tile
                val tile = readWord(resized, 2 + idx * 2)
                assertEquals(0, tile, "New tile at (${origBlocksW},$by) should be air")
            }
        }

        @Test
        fun `grow height preserves existing tiles`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val original = parser.decompressLZ2(room.levelDataPtr)
            val blocksW = room.width * 16
            val origBlocksH = room.height * 16
            val resized = resizeLevelData(original, room.width, room.height, room.width, room.height + 1)

            // Tile at (10, 10) should be preserved
            val origTile = readWord(original, 2 + (10 * blocksW + 10) * 2)
            val resizedTile = readWord(resized, 2 + (10 * blocksW + 10) * 2)
            assertEquals(origTile, resizedTile, "Tile (10,10) should be preserved")

            // New row tiles should be 0
            for (bx in 0 until blocksW) {
                val idx = origBlocksH * blocksW + bx
                val tile = readWord(resized, 2 + idx * 2)
                assertEquals(0, tile, "New tile at ($bx,$origBlocksH) should be air")
            }
        }

        @Test
        fun `shrink width truncates right columns`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val original = parser.decompressLZ2(room.levelDataPtr)
            val origBlocksW = room.width * 16
            val newBlocksW = (room.width - 1) * 16

            val resized = resizeLevelData(original, room.width, room.height, room.width - 1, room.height)

            // Tiles within new bounds should be preserved
            val origTile = readWord(original, 2 + (5 * origBlocksW + 5) * 2)
            val resizedTile = readWord(resized, 2 + (5 * newBlocksW + 5) * 2)
            assertEquals(origTile, resizedTile, "Tile (5,5) should be preserved after shrink")

            // L1 size should match new dimensions
            assertEquals(newBlocksW * room.height * 16 * 2, l1Size(resized))
        }

        @Test
        fun `BTS data is preserved across resize`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val original = parser.decompressLZ2(room.levelDataPtr)
            val origBlocksW = room.width * 16
            val origBlocksH = room.height * 16
            val origL1Size = l1Size(original)
            val btsStart = 2 + origL1Size

            // Find a non-zero BTS value in the original data
            var sampleX = -1; var sampleY = -1; var sampleBts = -1
            for (by in 0 until origBlocksH) {
                for (bx in 0 until origBlocksW) {
                    val bts = original[btsStart + by * origBlocksW + bx].toInt() and 0xFF
                    if (bts != 0) {
                        sampleX = bx; sampleY = by; sampleBts = bts
                        break
                    }
                }
                if (sampleX >= 0) break
            }
            assertTrue(sampleX >= 0, "Should find at least one non-zero BTS in Parlor")

            val resized = resizeLevelData(original, room.width, room.height, room.width + 1, room.height)
            val newBlocksW = (room.width + 1) * 16
            val newL1Size = l1Size(resized)
            val newBtsStart = 2 + newL1Size

            val resizedBts = resized[newBtsStart + sampleY * newBlocksW + sampleX].toInt() and 0xFF
            assertEquals(sampleBts, resizedBts, "BTS at ($sampleX,$sampleY) should be preserved")
        }

        @Test
        fun `total data size is correct for resize with L2`() {
            // Landing Site (0x91F8) has L2 data
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x91F8)!!
            val original = parser.decompressLZ2(room.levelDataPtr)
            val origBlocksW = room.width * 16
            val origBlocksH = room.height * 16
            val origTotal = origBlocksW * origBlocksH
            val origL1Size = l1Size(original)
            val hasL2 = original.size >= 2 + origL1Size + origTotal + origTotal * 2  // needs origTotal

            val resized = resizeLevelData(original, room.width, room.height, room.width + 1, room.height)
            val newBlocksW = (room.width + 1) * 16
            val newTotal = newBlocksW * origBlocksH
            val newL1Size = newTotal * 2
            val expectedSize = 2 + newL1Size + newTotal + (if (hasL2) newTotal * 2 else 0)
            assertEquals(expectedSize, resized.size, "Resized data size should match expected layout")
        }
    }

    @Nested
    inner class ScrollDataResize {

        @Test
        fun `scroll data grows when room expands`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val scrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
            assertEquals(room.width * room.height, scrolls.size, "Original scroll count = screens")

            // Simulate resize scroll: 5x5 → 6x5
            val newWidth = 6; val newHeight = 5
            val resized = IntArray(newWidth * newHeight) { 1 }
            for (sy in 0 until minOf(room.height, newHeight)) {
                for (sx in 0 until minOf(room.width, newWidth)) {
                    resized[sy * newWidth + sx] = scrolls[sy * room.width + sx]
                }
            }
            assertEquals(30, resized.size)
            // Original scrolls should be preserved in overlap region
            for (sy in 0 until room.height) {
                for (sx in 0 until room.width) {
                    assertEquals(scrolls[sy * room.width + sx], resized[sy * newWidth + sx],
                        "Scroll ($sx,$sy) should be preserved")
                }
            }
            // New column should default to Blue (1)
            for (sy in 0 until newHeight) {
                assertEquals(1, resized[sy * newWidth + 5], "New scroll column should default to Blue")
            }
        }
    }

    @Nested
    inner class ScrollDataAdjacency {

        @Test
        fun `scroll data does not overlap scroll PLM commands in ROM`() {
            // Verify that Parlor's scroll data is immediately followed by scroll PLM command data
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            val scrollSize = room.width * room.height // 25 bytes
            val scrollEndAddr = room.roomScrollsPtr + scrollSize // $9370 + 25 = $9389

            // The scroll PLM params for Parlor reference $9389, $938C, etc.
            // This confirms that writing >25 bytes at $9370 would corrupt PLM commands
            val plms = parser.parsePlmSet(room.plmSetPtr)
            val scrollTriggerPlms = plms.filter { it.id == 0xB703 }
            assertTrue(scrollTriggerPlms.isNotEmpty(), "Parlor should have scroll trigger PLMs")

            // At least one scroll trigger should reference data at or near $9389
            val hasAdjacentRef = scrollTriggerPlms.any { plm ->
                val paramAddr = plm.param and 0xFFFF
                paramAddr >= scrollEndAddr && paramAddr < scrollEndAddr + 0x40
            }
            assertTrue(hasAdjacentRef,
                "Scroll trigger PLM params should reference data adjacent to scroll data " +
                "(confirming overflow would corrupt them)")
        }
    }

    @Nested
    inner class SyntheticResizeLevelData {

        @Test
        fun `resize 2x2 to 3x3 produces correct layout`() {
            val oldW = 2; val oldH = 2
            val oldBlocksW = oldW * 16; val oldBlocksH = oldH * 16
            val oldTotal = oldBlocksW * oldBlocksH
            val oldL1Size = oldTotal * 2

            // Build synthetic level data: [2-byte header][L1][BTS]
            val dataSize = 2 + oldL1Size + oldTotal
            val data = ByteArray(dataSize)
            data[0] = (oldL1Size and 0xFF).toByte()
            data[1] = ((oldL1Size shr 8) and 0xFF).toByte()

            // Place a recognizable tile at (15, 15) — last tile of first screen
            val tileIdx = 15 * oldBlocksW + 15
            val tileWord = 0x1234
            data[2 + tileIdx * 2] = (tileWord and 0xFF).toByte()
            data[2 + tileIdx * 2 + 1] = ((tileWord shr 8) and 0xFF).toByte()
            // Place a BTS value there too
            data[2 + oldL1Size + tileIdx] = 0x42

            val resized = resizeLevelData(data, oldW, oldH, 3, 3)
            val newBlocksW = 3 * 16
            val newL1Size = l1Size(resized)
            val newBtsStart = 2 + newL1Size

            // Check the tile survived at the same (x,y)
            val newTileIdx = 15 * newBlocksW + 15
            val resTile = readWord(resized, 2 + newTileIdx * 2)
            assertEquals(tileWord, resTile, "Tile at (15,15) should survive resize")

            // Check BTS survived
            val resBts = resized[newBtsStart + newTileIdx].toInt() and 0xFF
            assertEquals(0x42, resBts, "BTS at (15,15) should survive resize")

            // Check new area is air
            val newAreaIdx = 0 * newBlocksW + (oldBlocksW) // first tile of new column
            assertEquals(0, readWord(resized, 2 + newAreaIdx * 2), "New column should be air")
        }

        @Test
        fun `shrink 3x3 to 2x2 discards right and bottom`() {
            val oldW = 3; val oldH = 3
            val oldBlocksW = oldW * 16
            val oldTotal = oldBlocksW * oldH * 16
            val oldL1Size = oldTotal * 2
            val dataSize = 2 + oldL1Size + oldTotal
            val data = ByteArray(dataSize)
            data[0] = (oldL1Size and 0xFF).toByte()
            data[1] = ((oldL1Size shr 8) and 0xFF).toByte()

            // Tile at (10,10) — survives shrink
            val idx1 = 10 * oldBlocksW + 10
            data[2 + idx1 * 2] = 0xAB.toByte()
            data[2 + idx1 * 2 + 1] = 0xCD.toByte()

            // Tile at (33,10) — in 3rd screen column, should be discarded
            val idx2 = 10 * oldBlocksW + 33
            data[2 + idx2 * 2] = 0xFF.toByte()
            data[2 + idx2 * 2 + 1] = 0xFF.toByte()

            val resized = resizeLevelData(data, 3, 3, 2, 2)
            val newBlocksW = 2 * 16

            // Surviving tile
            val newIdx1 = 10 * newBlocksW + 10
            assertEquals(0xCDAB, readWord(resized, 2 + newIdx1 * 2), "Tile (10,10) should survive")

            // Total size should be for 2x2
            val newTotal = newBlocksW * 2 * 16
            assertEquals(2 + newTotal * 2 + newTotal, resized.size)
        }
    }
}
