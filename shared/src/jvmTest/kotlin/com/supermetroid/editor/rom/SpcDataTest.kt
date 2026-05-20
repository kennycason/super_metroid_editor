package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SpcDataTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `song set pointer table at 8F-E7E1 has all 24 song sets`() {
        val parser = loadTestRom() ?: return
        val table = SpcData.findSongSetPointerTable(parser)

        val expectedSets = listOf(
            0x00, 0x03, 0x06, 0x09, 0x0C, 0x0F, 0x12, 0x15,
            0x18, 0x1B, 0x1E, 0x21, 0x24, 0x27, 0x2A, 0x2D,
            0x30, 0x33, 0x36, 0x39, 0x3C, 0x3F, 0x42, 0x45
        )
        for (ss in expectedSets) {
            assertTrue(table.containsKey(ss),
                "Song set 0x${ss.toString(16).padStart(2, '0')} should be in table")
        }
        assertEquals(expectedSets.size, table.size,
            "Table should have exactly ${expectedSets.size} entries")
    }

    @Test
    fun `song set 0x00 points to CF-8000 (base SPC data)`() {
        val parser = loadTestRom() ?: return
        val ptr = SpcData.readSongSetPointer(parser, 0x00)
        assertEquals(0xCF8000, ptr, "Song set 0x00 should point to \$CF:8000")
    }

    @Test
    fun `each song set has valid transfer blocks`() {
        val parser = loadTestRom() ?: return
        val songSets = listOf(0x03, 0x06, 0x09, 0x0C, 0x0F, 0x12, 0x15, 0x18,
            0x1B, 0x1E, 0x21, 0x24, 0x27, 0x2A, 0x2D, 0x30,
            0x33, 0x36, 0x39, 0x3C, 0x3F, 0x42, 0x45)

        for (ss in songSets) {
            val blocks = SpcData.findSongSetTransferData(parser, ss)
            assertTrue(blocks.isNotEmpty(),
                "Song set 0x${ss.toString(16).padStart(2, '0')} should have transfer blocks, got 0")
            assertTrue(blocks.size >= 3,
                "Song set 0x${ss.toString(16).padStart(2, '0')} should have >=3 blocks, got ${blocks.size}")

            for (block in blocks) {
                assertTrue(block.destAddr in 0..0xFFFF,
                    "Block dest 0x${block.destAddr.toString(16)} should be in SPC RAM range")
                assertTrue(block.data.isNotEmpty(), "Block data should not be empty")
            }
        }
    }

    @Test
    fun `different song sets have different transfer data`() {
        val parser = loadTestRom() ?: return
        val songSets = listOf(0x03, 0x06, 0x0F, 0x15, 0x1E, 0x24)
        val dataHashes = mutableMapOf<Int, Int>()

        for (ss in songSets) {
            val blocks = SpcData.findSongSetTransferData(parser, ss)
            val totalSize = blocks.sumOf { it.data.size }
            dataHashes[ss] = totalSize
        }

        val uniqueHashes = dataHashes.values.toSet()
        assertTrue(uniqueHashes.size >= songSets.size - 1,
            "Most song sets should have different data sizes. Sizes: $dataHashes")
    }

    @Test
    fun `build initial SPC RAM succeeds`() {
        val parser = loadTestRom() ?: return
        val spcRam = SpcData.buildInitialSpcRam(parser)
        assertEquals(0x10000, spcRam.size, "SPC RAM should be 64KB")
        assertTrue(spcRam.any { it.toInt() != 0 }, "SPC RAM should contain data")
    }

    @Test
    fun `sample directory from base SPC RAM has entries`() {
        val parser = loadTestRom() ?: return
        val spcRam = SpcData.buildInitialSpcRam(parser)
        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty(), "Sample directory should have entries")
        assertTrue(dir.size >= 10, "Should have at least 10 samples, got ${dir.size}")

        for (entry in dir) {
            assertTrue(entry.startAddr in 0x0200..0xFF00,
                "Sample #${entry.index} start 0x${entry.startAddr.toString(16)} should be in valid SPC RAM range")
        }
    }

    @Test
    fun `BRR decoding produces valid PCM samples`() {
        val parser = loadTestRom() ?: return
        val spcRam = SpcData.buildInitialSpcRam(parser)
        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty())

        val entry = dir.first()
        val (pcm, _) = SpcData.decodeBrrWithLoop(spcRam, entry)
        assertTrue(pcm.isNotEmpty(), "Decoded PCM should not be empty")
        assertTrue(pcm.size >= 16, "PCM should have at least 16 samples, got ${pcm.size}")
    }

    @Test
    fun `song set transfer blocks change SPC RAM content`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)

        val songSets = listOf(0x03, 0x0F, 0x1E)
        for (ss in songSets) {
            val ram = baseRam.copyOf()
            val blocks = SpcData.findSongSetTransferData(parser, ss)
            assertTrue(blocks.isNotEmpty(), "Song set 0x${ss.toString(16)} should have blocks")
            SpcData.applyTransferBlocks(ram, blocks)

            var diffCount = 0
            for (i in ram.indices) {
                if (ram[i] != baseRam[i]) diffCount++
            }
            assertTrue(diffCount > 100,
                "Song set 0x${ss.toString(16)} should change significant SPC RAM, only $diffCount bytes differ")
        }
    }

    @Test
    fun `different song sets produce different sample directories`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)

        val ramTitle = baseRam.copyOf()
        SpcData.applyTransferBlocks(ramTitle, SpcData.findSongSetTransferData(parser, 0x03))
        val dirTitle = SpcData.findSampleDirectory(ramTitle)

        val ramBrinstar = baseRam.copyOf()
        SpcData.applyTransferBlocks(ramBrinstar, SpcData.findSongSetTransferData(parser, 0x0F))
        val dirBrinstar = SpcData.findSampleDirectory(ramBrinstar)

        assertTrue(dirTitle.isNotEmpty() && dirBrinstar.isNotEmpty())

        val titleStarts = dirTitle.map { it.startAddr }.toSet()
        val brinstarStarts = dirBrinstar.map { it.startAddr }.toSet()
        assertNotEquals(titleStarts, brinstarStarts,
            "Title and Green Brinstar should have different sample sets")
    }
}
