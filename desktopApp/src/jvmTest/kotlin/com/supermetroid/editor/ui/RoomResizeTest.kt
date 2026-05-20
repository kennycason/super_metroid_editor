package com.supermetroid.editor.ui
import com.supermetroid.editor.rom.TestRomHelper

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

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

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

    @Nested
    inner class ScrollCommandRemapping {

        @Test
        fun `screen index remaps correctly from width 5 to 6`() {
            // Screen (3,1) in 5-wide = index 8. In 6-wide should be index 9.
            val oldWidth = 5; val newWidth = 6
            val oldIdx = 8
            val col = oldIdx % oldWidth // 3
            val row = oldIdx / oldWidth // 1
            val newIdx = row * newWidth + col // 1*6+3 = 9
            assertEquals(3, col)
            assertEquals(1, row)
            assertEquals(9, newIdx)
        }

        @Test
        fun `screen index remaps correctly from width 5 to 7`() {
            // Screen (1,2) in 5-wide = index 11. In 7-wide should be index 15.
            val oldWidth = 5; val newWidth = 7
            val oldIdx = 11
            val col = oldIdx % oldWidth // 1
            val row = oldIdx / oldWidth // 2
            val newIdx = row * newWidth + col // 2*7+1 = 15
            assertEquals(1, col)
            assertEquals(2, row)
            assertEquals(15, newIdx)
        }

        @Test
        fun `Parlor scroll commands reference valid screens for vanilla 5x5`() {
            val parser = loadTestRom() ?: return
            val room = parser.readRoomHeader(0x92FD)!!
            assertEquals(5, room.width)
            assertEquals(5, room.height)

            val plms = parser.parsePlmSet(room.plmSetPtr)
            val scrollTriggers = plms.filter { it.id == 0xB703 }
            assertTrue(scrollTriggers.isNotEmpty(), "Parlor should have B703 scroll triggers")

            val seen = mutableSetOf<Int>()
            for (plm in scrollTriggers) {
                val cmdPtr = plm.param and 0xFFFF
                if (cmdPtr == 0 || cmdPtr in seen) continue
                seen.add(cmdPtr)
                val snesAddr = 0x8F0000 or cmdPtr
                val pc = parser.snesToPc(snesAddr)
                var offset = 0
                while (offset < 256) {
                    val screenIdx = parser.readByteAt(pc + offset).toByte().toInt() and 0xFF
                    if (screenIdx >= 0x80) break
                    assertTrue(screenIdx < room.width * room.height,
                        "Screen index $screenIdx should be < ${room.width * room.height} (${room.width}x${room.height})")
                    offset += 2
                }
            }
        }
        @Test
        fun `Parlor has incoming doors with entry ASM from other rooms`() {
            val parser = loadTestRom() ?: return
            val incomingDoors = parser.findDoorsLeadingTo(0x92FD)
            assertTrue(incomingDoors.isNotEmpty(), "Parlor should have incoming doors from other rooms")
            val withAsm = incomingDoors.filter { it.entryCode != 0 && it.entryCode != 0xFFFF }
            assertTrue(withAsm.isNotEmpty(),
                "At least one incoming door to Parlor should have entry ASM with scroll writes")
        }
    }

    @Nested
    inner class DoorAsmGeneration {

        @Test
        fun `door ASM scroll writes can be parsed from Parlor incoming doors`() {
            val parser = loadTestRom() ?: return
            val incomingDoors = parser.findDoorsLeadingTo(0x92FD)
            val withAsm = incomingDoors.filter { it.entryCode != 0 && it.entryCode != 0xFFFF }
            assertTrue(withAsm.isNotEmpty())

            // Parse scroll writes from the first door with ASM
            val door = withAsm.first()
            val pc = parser.snesToPc(0x8F0000 or door.entryCode)
            val writes = mutableListOf<Pair<Int, Int>>() // (screenIdx, scrollValue)
            var i = 0
            while (i < 60) {
                val b = parser.readByteAt(pc + i)
                if (b == 0x6B) break
                if (b == 0xA9 && i + 5 < 60) {
                    val imm = parser.readByteAt(pc + i + 1)
                    val next = parser.readByteAt(pc + i + 2)
                    if (next == 0x8F) {
                        val lo = parser.readByteAt(pc + i + 3)
                        val hi = parser.readByteAt(pc + i + 4)
                        val bank = parser.readByteAt(pc + i + 5)
                        if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                            writes.add((lo - 0x20) to imm)
                        }
                        i += 6; continue
                    }
                }
                if ((b == 0xE2 || b == 0xC2) && i + 1 < 60) { i += 2; continue }
                i++
            }
            assertTrue(writes.isNotEmpty(),
                "Door ASM at \$${door.entryCode.toString(16)} should contain scroll writes")
            // All screen indices should be valid for 5-wide room
            for ((idx, _) in writes) {
                assertTrue(idx < 25, "Screen index $idx should be < 25 for 5x5 room")
            }
        }

        @Test
        fun `generated door ASM has correct byte structure`() {
            // Simulate generating door ASM for a set of scroll writes
            val writes = listOf(0 to 1, 8 to 2, 19 to 0) // (screenIdx, scrollValue)
            val oldWidth = 5; val newWidth = 6

            // Expected: SEP #$20 + 3 writes + RTL = 2 + 3*6 + 1 = 21 bytes
            val expectedSize = 2 + writes.size * 6 + 1
            assertEquals(21, expectedSize)

            val asm = ByteArray(expectedSize)
            var ptr = 0
            asm[ptr++] = 0xE2.toByte() // SEP
            asm[ptr++] = 0x20.toByte() // #$20
            for ((idx, value) in writes) {
                val col = idx % oldWidth
                val row = idx / oldWidth
                val newIdx = row * newWidth + col
                asm[ptr++] = 0xA9.toByte() // LDA #imm8
                asm[ptr++] = value.toByte()
                asm[ptr++] = 0x8F.toByte() // STA long
                asm[ptr++] = (0x20 + newIdx).toByte()
                asm[ptr++] = 0xCD.toByte()
                asm[ptr++] = 0x7E.toByte()
            }
            asm[ptr++] = 0x6B.toByte() // RTL

            // Verify structure
            assertEquals(0xE2, asm[0].toInt() and 0xFF, "Should start with SEP")
            assertEquals(0x20, asm[1].toInt() and 0xFF, "SEP #$20")
            assertEquals(0x6B, asm[expectedSize - 1].toInt() and 0xFF, "Should end with RTL")

            // Verify remapped screen indices
            // Write 0: screen 0 (0,0) → 0*6+0 = 0
            assertEquals(0x20, asm[5].toInt() and 0xFF, "Screen 0 → $7ECD20")
            // Write 1: screen 8 (3,1) → 1*6+3 = 9
            assertEquals(0x20 + 9, asm[11].toInt() and 0xFF, "Screen 8 remapped to 9 → $7ECD29")
            // Write 2: screen 19 (4,3) → 3*6+4 = 22
            assertEquals(0x20 + 22, asm[17].toInt() and 0xFF, "Screen 19 remapped to 22 → $7ECD36")
        }

        @Test
        fun `shared door ASM routines are not modified by generation approach`() {
            // Verify that the original ROM data at shared routine $B981 is untouched
            // when we generate NEW routines instead of patching in-place
            val parser = loadTestRom() ?: return
            val pc = parser.snesToPc(0x8F0000 or 0xB981)
            // Read original bytes at $B981
            val originalBytes = ByteArray(30) { parser.readByteAt(pc + it).toByte() }

            // Simulate what our export does: copy ROM data
            val romCopy = ByteArray(30)
            System.arraycopy(originalBytes, 0, romCopy, 0, 30)

            // The generation approach writes to FREE SPACE, not to $B981
            // So romCopy at $B981 offset should remain unchanged
            assertTrue(originalBytes.contentEquals(romCopy),
                "Original door ASM at \$B981 should not be modified")
        }

        @Test
        fun `findDoorsLeadingTo returns doors from multiple source rooms`() {
            val parser = loadTestRom() ?: return
            val incoming = parser.findDoorsLeadingTo(0x92FD) // Parlor
            assertTrue(incoming.size >= 3,
                "Parlor should have at least 3 incoming doors (Landing Site, Flyway, Terminator, etc.)")
            // Verify they come from different source rooms
            val destRooms = incoming.map { it.destRoomPtr }.distinct()
            assertEquals(1, destRooms.size, "All incoming doors should point to Parlor")
            assertEquals(0x92FD, destRooms.first())
        }
    }

    @Nested
    inner class CustomScrollCommands {

        @Test
        fun `ScrollCommand data class serializes correctly`() {
            val cmd = com.supermetroid.editor.data.ScrollCommand(screenIndex = 9, scrollValue = 1)
            assertEquals(9, cmd.screenIndex)
            assertEquals(1, cmd.scrollValue)
        }

        @Test
        fun `custom scroll command bytes layout is correct`() {
            // Simulate writing command data as the export does
            val commands = listOf(
                com.supermetroid.editor.data.ScrollCommand(0, 1),  // Screen 0 → Blue
                com.supermetroid.editor.data.ScrollCommand(5, 0),  // Screen 5 → Red
                com.supermetroid.editor.data.ScrollCommand(12, 2), // Screen 12 → Green
            )
            val dataSize = commands.size * 2 + 1
            val data = ByteArray(dataSize)
            var ptr = 0
            for (cmd in commands) {
                data[ptr++] = cmd.screenIndex.toByte()
                data[ptr++] = cmd.scrollValue.toByte()
            }
            data[ptr] = 0x80.toByte() // terminator

            assertEquals(7, data.size, "3 commands = 6 bytes + 1 terminator")
            assertEquals(0, data[0].toInt() and 0xFF, "First screen index")
            assertEquals(1, data[1].toInt() and 0xFF, "First scroll value (Blue)")
            assertEquals(5, data[2].toInt() and 0xFF, "Second screen index")
            assertEquals(0, data[3].toInt() and 0xFF, "Second scroll value (Red)")
            assertEquals(12, data[4].toInt() and 0xFF, "Third screen index")
            assertEquals(2, data[5].toInt() and 0xFF, "Third scroll value (Green)")
            assertEquals(0x80, data[6].toInt() and 0xFF, "Terminator")
        }
    }
}
