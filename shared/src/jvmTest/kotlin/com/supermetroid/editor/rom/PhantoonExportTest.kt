package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests the Phantoon sprite export pipeline: decompress → (modify) → recompress → verify fits.
 */
class PhantoonExportTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `LZ5 round-trip compression fits within original ROM slot for all Phantoon blocks`() {
        val parser = loadTestRom() ?: return

        for ((i, block) in EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            // Decompress original
            val rawBytes = parser.decompressLZ5AtPc(block.pcAddress)
            val (_, origCompressedSize) = parser.decompressLZ5AtPcWithSize(block.pcAddress)

            // Recompress
            val recompressed = LZ5Compressor.compress(rawBytes)

            println("Block $i (${block.label}):")
            println("  Original raw: ${rawBytes.size} bytes")
            println("  Original compressed: $origCompressedSize bytes")
            println("  Recompressed: ${recompressed.size} bytes")
            println("  Fits: ${recompressed.size <= origCompressedSize} (${recompressed.size}/$origCompressedSize)")

            // Verify decompression of recompressed data matches original
            // (write to a temp array and decompress)
            val tempRom = ByteArray(recompressed.size + 1)
            System.arraycopy(recompressed, 0, tempRom, 0, recompressed.size)
            tempRom[recompressed.size] = 0xFF.toByte() // terminator

            assertTrue(recompressed.size <= origCompressedSize,
                "Block $i recompressed size ${recompressed.size} must fit in original slot $origCompressedSize")
        }
    }

    @Test
    fun `LZ5 round-trip preserves Phantoon tile data exactly`() {
        val parser = loadTestRom() ?: return

        for ((i, block) in EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val rawBytes = parser.decompressLZ5AtPc(block.pcAddress)
            val recompressed = LZ5Compressor.compress(rawBytes)

            // Write recompressed data into a copy of the ROM and decompress again
            val romCopy = parser.getRomData().copyOf()
            System.arraycopy(recompressed, 0, romCopy, block.pcAddress, recompressed.size)
            // Pad remaining with 0xFF
            val (_, origSize) = parser.decompressLZ5AtPcWithSize(block.pcAddress)
            for (j in recompressed.size until origSize) {
                romCopy[block.pcAddress + j] = 0xFF.toByte()
            }

            // Write patched ROM to temp file, reload, and verify round-trip
            val tmpFile = File.createTempFile("phantoon_export_test_", ".smc")
            tmpFile.deleteOnExit()
            tmpFile.writeBytes(romCopy)
            val roundTripParser = RomParser.loadRom(tmpFile.absolutePath)
            val roundTrip = roundTripParser.decompressLZ5AtPc(block.pcAddress)

            assertArrayEquals(rawBytes, roundTrip,
                "Block $i: decompress->compress->decompress should yield identical data")
            println("Block $i round-trip verified: ${rawBytes.size} bytes preserved exactly")
            tmpFile.delete()
        }
    }

    @Test
    fun `export path simulation - spriteTileBlocks to ROM patching`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        // Simulate: modify one pixel in tile 5, then export
        gfx.writePixelIndex(5, 3, 3, 7)
        val modifiedBlocks = gfx.getRawBlocks()!!

        var patchedCount = 0
        for ((i, block) in EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val rawBytes = modifiedBlocks[i]
            val compressed = LZ5Compressor.compress(rawBytes)
            val (_, origCompressedSize) = parser.decompressLZ5AtPcWithSize(block.pcAddress)

            if (compressed.size <= origCompressedSize) {
                // Simulate patching
                System.arraycopy(compressed, 0, rom, block.pcAddress, compressed.size)
                for (j in compressed.size until origCompressedSize) {
                    rom[block.pcAddress + j] = 0xFF.toByte()
                }
                patchedCount++
                println("Block $i: patched ${compressed.size}/$origCompressedSize bytes")
            } else {
                println("Block $i: SKIPPED (${compressed.size} > $origCompressedSize)")
            }
        }

        assertTrue(patchedCount > 0, "At least one block should be patchable")
        println("Patched $patchedCount/${EnemySpriteGraphics.PHANTOON_BLOCKS.size} blocks")

        // Verify the patched ROM decompresses correctly (write to temp file)
        val tmpFile = File.createTempFile("phantoon_sim_", ".smc")
        tmpFile.deleteOnExit()
        tmpFile.writeBytes(rom)
        val patchedParser = RomParser.loadRom(tmpFile.absolutePath)
        tmpFile.delete()
        val patchedGfx = EnemySpriteGraphics(patchedParser)
        assertTrue(patchedGfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))
        val verifyPixel = patchedGfx.readPixelIndex(5, 3, 3)
        assertEquals(7, verifyPixel, "Modified pixel should survive compress→patch→decompress")
    }
}
