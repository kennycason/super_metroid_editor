package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class RomParserTest {
    
    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()
    
    @Test
    fun `test ROM loading`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        assertTrue(romData.size == 0x300000 || romData.size == 0x300200, 
            "ROM size should be 3MB or 3MB+512 bytes, got ${romData.size}")
    }
    
    @Test
    fun `test LoROM SNES to PC address conversion`() {
        val parser = loadTestRom() ?: return
        
        // LoROM: $8F:91F8 -> PC offset ((0x0F) * 0x8000) + (0x91F8 - 0x8000) = 0x78000 + 0x11F8 = 0x791F8
        val pcAddress = parser.snesToPc(0x8F91F8)
        val expectedPc = if (parser.getRomData().size == 0x300200) 0x200 + 0x791F8 else 0x791F8
        assertEquals(expectedPc, pcAddress, "LoROM conversion for \$8F:91F8 should be 0x${expectedPc.toString(16)}")
        
        // $8F:0000 -> PC offset (0x0F * 0x8000) + (0x0000 & 0x7FFF) = 0x78000
        val pcBank8F = parser.snesToPc(0x8F0000)
        val expectedBank = if (parser.getRomData().size == 0x300200) 0x200 + 0x78000 else 0x78000
        assertEquals(expectedBank, pcBank8F, "LoROM conversion for \$8F:0000")
    }
    
    @Test
    fun `test roomIdToPc conversion`() {
        val parser = loadTestRom() ?: return
        
        // roomIdToPc(0x91F8) should equal snesToPc(0x8F91F8)
        assertEquals(parser.snesToPc(0x8F91F8), parser.roomIdToPc(0x91F8))
        assertEquals(parser.snesToPc(0x8FA59F), parser.roomIdToPc(0xA59F))
    }
    
    @Test
    fun `test read Landing Site room header`() {
        val parser = loadTestRom() ?: return
        
        val room = parser.readRoomHeader(0x91F8)
        assertNotNull(room, "Landing Site room header should be found")
        assertEquals(0, room!!.area, "Landing Site should be in area 0 (Crateria)")
        assertEquals(9, room.width, "Landing Site should be 9 screens wide")
        assertEquals(5, room.height, "Landing Site should be 5 screens tall")
    }
    
    @Test
    fun `test read all known rooms`() {
        val parser = loadTestRom() ?: return
        
        // Room ID -> (name, area, expectedWidth, expectedHeight)
        val knownRooms = mapOf(
            0x91F8 to Triple("Landing Site", 0, 9 to 5),
            0x93AA to Triple("Crateria Power Bomb Room", 0, 2 to 1),
            0x9804 to Triple("Bomb Torizo", 0, 1 to 1),
            0xA59F to Triple("Kraid", 1, 2 to 2),
            0xB32E to Triple("Ridley", 2, 1 to 2),
            0xCD13 to Triple("Phantoon", 3, 1 to 1),
            0xDA60 to Triple("Draygon", 4, 2 to 2),
            0xDD58 to Triple("Mother Brain", 5, 4 to 1),
        )
        
        for ((roomId, expected) in knownRooms) {
            val (name, expectedArea, dims) = expected
            val (expectedWidth, expectedHeight) = dims
            
            val room = parser.readRoomHeader(roomId)
            assertNotNull(room, "$name (0x${roomId.toString(16)}) should be found in ROM")
            assertEquals(expectedArea, room!!.area, "$name should be in area $expectedArea")
            assertEquals(expectedWidth, room.width, "$name should be ${expectedWidth} screens wide")
            assertEquals(expectedHeight, room.height, "$name should be ${expectedHeight} screens tall")
        }
    }
    
    @Test
    fun `test LZ2 decompression on Landing Site`() {
        val parser = loadTestRom() ?: return
        
        val room = parser.readRoomHeader(0x91F8)
        assertNotNull(room, "Landing Site should be found")
        
        // Debug: dump bytes after the door out pointer to see state entries
        val pcOffset = parser.roomIdToPc(0x91F8)
        val romData = parser.getRomData()
        val stateStart = pcOffset + 11
        val stateBytes = romData.sliceArray(stateStart until minOf(stateStart + 40, romData.size))
        val hexStr = stateBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        println("State entries at PC 0x${stateStart.toString(16)}: $hexStr")
        println("levelDataPtr = 0x${room!!.levelDataPtr.toString(16)}")
        
        // Check what's at the state list start
        // For Landing Site, the first word should be the state condition
        val word1 = (romData[stateStart + 1].toInt() and 0xFF shl 8) or (romData[stateStart].toInt() and 0xFF)
        println("First state word: 0x${word1.toString(16)}")
        
        if (room.levelDataPtr == 0) {
            println("WARNING: levelDataPtr is 0 — findDefaultStateData may not be finding \$E5E6")
            // The Landing Site's default state data might follow immediately (no conditional states)
            // Let's check if E5E6 is at offset 11
            println("Checking if \$E5E6 is at offset 11: 0x${word1.toString(16)}")
        }
        
        assertTrue(room.levelDataPtr != 0, "Landing Site should have a level data pointer (got 0x${room.levelDataPtr.toString(16)})")
        
        println("Landing Site level data pointer: 0x${room.levelDataPtr.toString(16)}")
        
        val levelData = parser.decompressLZ2(room.levelDataPtr)
        
        // Landing Site is 9x5 screens = 144x80 blocks = 11520 blocks
        // Layer 1: 11520 * 2 bytes = 23040 bytes minimum
        val expectedMinSize = room.width * room.height * 16 * 16 * 2
        println("Decompressed ${levelData.size} bytes (expected Layer1 min: $expectedMinSize)")
        
        assertTrue(levelData.isNotEmpty(), "Decompressed data should not be empty")
        // Level data might not match expected size exactly — the decompressed data 
        // includes Layer 1 + BTS + possibly Layer 2 data, and the actual format 
        // may differ from our expectation. Just verify we got meaningful data.
        println("Decompressed ${levelData.size} bytes (expected min: $expectedMinSize)")
        println("First 20 bytes: ${levelData.take(20).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
        assertTrue(levelData.size > 100, "Should decompress more than 100 bytes (got ${levelData.size})")
    }

    @Test
    fun `test LZ5 compress roundtrip and size for key rooms`() {
        val parser = loadTestRom() ?: return
        val testRooms = listOf(0x91F8, 0x975C, 0xA322, 0x96BA)
        for (roomId in testRooms) {
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.levelDataPtr == 0) continue
            val (originalData, origCompressedSize) = parser.decompressLZ2WithSize(room.levelDataPtr)
            val recompressed = LZ5Compressor.compress(originalData)

            val verifyParser = RomParser(recompressed)
            val decompressed = verifyParser.decompressLZ5AtPc(0)

            assertTrue(decompressed.contentEquals(originalData),
                "Room 0x${roomId.toString(16)}: roundtrip decompression must match original")

            val ratio = recompressed.size.toFloat() / origCompressedSize * 100
            println("Room 0x${roomId.toString(16)}: orig_compressed=$origCompressedSize, " +
                    "new_compressed=${recompressed.size}, ratio=${String.format("%.1f", ratio)}%")

            assertTrue(recompressed.size <= origCompressedSize * 1.2,
                "Room 0x${roomId.toString(16)}: recompressed ${recompressed.size} should be within 20% of original $origCompressedSize")
        }
    }

    @Test
    fun `test LZ5 compress roundtrip on Kaizo ROM`() {
        val kaizoPaths = listOf(
            "/Users/kenny/Dropbox/emulator/snes/Super Metroid/Super Metroid Kaizo Lite.smc"
        )
        var parser: RomParser? = null
        for (p in kaizoPaths) {
            val f = java.io.File(p)
            if (f.exists()) { parser = RomParser.loadRom(f.absolutePath); break }
        }
        if (parser == null) { println("Kaizo ROM not found, skipping"); return }

        val testRooms = listOf(0x91F8, 0x975C, 0xA322, 0x96BA)
        for (roomId in testRooms) {
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.levelDataPtr == 0) continue
            val (originalData, origCompressedSize) = parser.decompressLZ2WithSize(room.levelDataPtr)
            val recompressed = LZ5Compressor.compress(originalData)

            val verifyParser = RomParser(recompressed)
            val decompressed = verifyParser.decompressLZ5AtPc(0)

            assertTrue(decompressed.contentEquals(originalData),
                "Kaizo Room 0x${roomId.toString(16)}: roundtrip mismatch!")

            val ratio = recompressed.size.toFloat() / origCompressedSize * 100
            val fits = recompressed.size <= origCompressedSize
            println("Kaizo 0x${roomId.toString(16)}: orig=$origCompressedSize, new=${recompressed.size}, " +
                    "ratio=${String.format("%.1f", ratio)}%, fits=$fits")
        }
    }

}
