package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SpcDataTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun writeU16(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU24(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun writeTransferBlock(rom: ByteArray, offset: Int, dest: Int, data: ByteArray): Int {
        writeU16(rom, offset, data.size)
        writeU16(rom, offset + 2, dest)
        data.copyInto(rom, offset + 4)
        return offset + 4 + data.size
    }

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
    fun `findSongSetPointerEntryPc returns vanilla pointer table entry`() {
        val rom = ByteArray(0x400000)
        val parser = RomParser(rom)
        val songSet = 0x03
        val tablePc = parser.snesToPc(0x8FE7E1)
        val chainSnes = 0xCF9000
        val chainPc = parser.snesToPc(chainSnes)

        writeU24(rom, tablePc + songSet, chainSnes)
        val end = writeTransferBlock(rom, chainPc, 0x5800, byteArrayOf(0x12, 0x34))
        writeU16(rom, end, 0)

        assertEquals(tablePc + songSet, SpcData.findSongSetPointerEntryPc(parser, songSet))
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
    fun `BRR encode then decode round-trips with acceptable error`() {
        // Generate a simple sine wave test signal
        val sampleCount = 256
        val pcm = ShortArray(sampleCount) { i ->
            (kotlin.math.sin(2.0 * Math.PI * i / 32) * 16000).toInt().toShort()
        }

        val brr = SpcData.encodeBrr(pcm)
        assertEquals(sampleCount / 16 * 9, brr.size, "BRR should be 9 bytes per 16-sample block")

        // Decode the BRR back
        val decoded = SpcData.decodeBrr(brr, 0)
        assertEquals(pcm.size, decoded.size, "Round-trip should preserve sample count")

        // Check error is within acceptable range (BRR is lossy)
        var maxError = 0
        var totalError = 0L
        for (i in pcm.indices) {
            val err = kotlin.math.abs(pcm[i].toInt() - decoded[i].toInt())
            if (err > maxError) maxError = err
            totalError += err
        }
        val avgError = totalError / pcm.size
        assertTrue(avgError < 500, "Average error should be small, got $avgError")
        assertTrue(maxError < 4000, "Max error should be bounded, got $maxError")
    }

    @Test
    fun `BRR encode empty input returns empty`() {
        val brr = SpcData.encodeBrr(ShortArray(0))
        assertEquals(0, brr.size)
    }

    @Test
    fun `BRR encode sets end flag on last block`() {
        val pcm = ShortArray(32) // 2 blocks
        val brr = SpcData.encodeBrr(pcm)
        assertEquals(18, brr.size) // 2 blocks * 9 bytes

        // First block header should NOT have end flag
        assertEquals(0, brr[0].toInt() and 0x01, "First block should not have end flag")
        // Last block header should have end flag
        assertEquals(1, brr[9].toInt() and 0x01, "Last block should have end flag")
    }

    @Test
    fun `BRR encode with ROM samples round-trips reasonably`() {
        val parser = loadTestRom() ?: return
        val spcRam = SpcData.buildInitialSpcRam(parser)
        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty())

        val entry = dir.first()
        val (originalPcm, _) = SpcData.decodeBrrWithLoop(spcRam, entry)
        assertTrue(originalPcm.size >= 16)

        // Re-encode and decode
        val brr = SpcData.encodeBrr(originalPcm)
        val reDecoded = SpcData.decodeBrr(brr, 0)
        assertEquals(originalPcm.size, reDecoded.size)

        // Since we're re-encoding already-decoded BRR, the error should be small
        var maxError = 0
        for (i in originalPcm.indices) {
            val err = kotlin.math.abs(originalPcm[i].toInt() - reDecoded[i].toInt())
            if (err > maxError) maxError = err
        }
        assertTrue(maxError < 4000, "Re-encoded max error should be bounded, got $maxError")
    }

    @Test
    fun `findSampleRomLocation locates BRR data in ROM`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val spcRam = baseRam.copyOf()
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)
        SpcData.applyTransferBlocks(spcRam, blocks)

        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty())

        val entry = dir.first()
        val location = SpcData.findSampleRomLocation(parser, 0x03, spcRam, entry, dir)
        assertNotNull(location, "Should find ROM location for sample #${entry.index}")
        assertTrue(location!!.brrSize > 0, "BRR size should be > 0")
        assertTrue(location.romPcOffset > 0, "ROM offset should be > 0")
        assertTrue(location.romPcOffset + location.brrSize <= parser.romData.size,
            "BRR data should fit within ROM")
        assertTrue(location.maxBrrSize >= location.brrSize,
            "Max BRR size should be >= actual BRR size")

        // Verify the ROM bytes match the SPC RAM bytes (proving correct offset)
        for (i in 0 until location.brrSize) {
            assertEquals(
                spcRam[entry.startAddr + i],
                parser.romData[location.romPcOffset + i],
                "ROM byte at offset $i should match SPC RAM"
            )
        }
    }

    @Test
    fun `buildSampleReplacementWrites overwrites BRR in-place`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val spcRam = baseRam.copyOf()
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)
        SpcData.applyTransferBlocks(spcRam, blocks)

        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty())
        val entry = dir.first()

        // Small BRR (1 block = 9 bytes) — should fit in any slot
        val testBrr = SpcData.encodeBrr(ShortArray(16) { 0 })
        assertEquals(9, testBrr.size)

        val result = SpcData.buildSampleReplacementWrites(parser, 0x03, entry.index, testBrr)
        assertNotNull(result, "Should produce replacement writes")

        val (writes, _) = result!!
        assertEquals(1, writes.size, "Should be exactly one write (in-place overwrite)")

        val (offset, data) = writes.first()
        assertTrue(offset > 0, "Write offset should be > 0")
        assertTrue(offset + data.size <= parser.romData.size, "Write should fit in ROM")
        assertTrue(data.size <= SpcData.measureBrrSize(spcRam, entry.startAddr),
            "Written data should be <= original BRR size")
    }

    @Test
    fun `buildSampleReplacementWrites trims oversized BRR`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val spcRam = baseRam.copyOf()
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)
        SpcData.applyTransferBlocks(spcRam, blocks)

        val dir = SpcData.findSampleDirectory(spcRam)
        assertTrue(dir.isNotEmpty())
        val entry = dir.first()

        val oldBrrSize = SpcData.measureBrrSize(spcRam, entry.startAddr)

        // Create a huge BRR that's definitely larger than any slot
        val hugePcm = ShortArray(oldBrrSize * 16) { (it % 1000).toShort() }
        val hugeBrr = SpcData.encodeBrr(hugePcm)
        assertTrue(hugeBrr.size > oldBrrSize, "Test BRR should be larger than slot")

        val result = SpcData.buildSampleReplacementWrites(parser, 0x03, entry.index, hugeBrr)
        assertNotNull(result, "Should succeed by trimming")

        val (writes, wasTrimmed) = result!!
        assertTrue(wasTrimmed, "Should report that BRR was trimmed")
        val writtenSize = writes.first().second.size
        assertTrue(writtenSize <= oldBrrSize,
            "Written size ($writtenSize) should be <= original slot ($oldBrrSize)")

        // Verify end flag is set on last BRR block
        val writtenData = writes.first().second
        val lastBlockHeader = writtenData[writtenData.size - 9].toInt() and 0xFF
        assertTrue(lastBlockHeader and 1 != 0, "Last BRR block should have end flag set")
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

    @Test
    fun `buildRomWritesForSpcRamWrites maps SPC writes into song transfer blocks`() {
        val rom = ByteArray(0x400000)
        val parser = RomParser(rom)
        val songSet = 0x03
        val chainSnes = 0xCF9000
        val chainPc = parser.snesToPc(chainSnes)
        writeU24(rom, parser.snesToPc(0x8FE7E1) + songSet, chainSnes)

        var pos = chainPc
        pos = writeTransferBlock(rom, pos, 0x5800, byteArrayOf(0x10, 0x11, 0x12, 0x13))
        val secondBlockPc = pos
        pos = writeTransferBlock(rom, pos, 0x5804, byteArrayOf(0x20, 0x21, 0x22, 0x23))
        writeU16(rom, pos, 0)

        val writes = SpcData.buildRomWritesForSpcRamWrites(
            parser,
            songSet,
            mapOf(0x5802 to byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        )

        assertEquals(2, writes.size)
        assertEquals(chainPc + 4 + 2, writes[0].first)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), writes[0].second)
        assertEquals(secondBlockPc + 4, writes[1].first)
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0xDD.toByte()), writes[1].second)
    }

    @Test
    fun `buildRomWritesForSpcRamWrites uses latest overlapping transfer block`() {
        val rom = ByteArray(0x400000)
        val parser = RomParser(rom)
        val songSet = 0x03
        val chainSnes = 0xCF9000
        val chainPc = parser.snesToPc(chainSnes)
        writeU24(rom, parser.snesToPc(0x8FE7E1) + songSet, chainSnes)

        var pos = chainPc
        pos = writeTransferBlock(rom, pos, 0x5800, byteArrayOf(0x10, 0x11, 0x12, 0x13))
        val secondBlockPc = pos
        pos = writeTransferBlock(rom, pos, 0x5802, byteArrayOf(0x20, 0x21))
        writeU16(rom, pos, 0)

        val writes = SpcData.buildRomWritesForSpcRamWrites(
            parser,
            songSet,
            mapOf(0x5802 to byteArrayOf(0xEE.toByte()))
        )

        assertEquals(1, writes.size)
        assertEquals(secondBlockPc + 4, writes.single().first)
        assertArrayEquals(byteArrayOf(0xEE.toByte()), writes.single().second)
    }

    @Test
    fun `buildRomWritesForSpcRamWrites can fall back to base transfer blocks`() {
        val rom = ByteArray(0x400000)
        val parser = RomParser(rom)
        val baseChainSnes = 0xCF8000
        val songChainSnes = 0xCF9000
        val baseChainPc = parser.snesToPc(baseChainSnes)
        val songChainPc = parser.snesToPc(songChainSnes)
        writeU24(rom, parser.snesToPc(0x8FE7E1), baseChainSnes)
        writeU24(rom, parser.snesToPc(0x8FE7E1) + 0x03, songChainSnes)

        var pos = baseChainPc
        pos = writeTransferBlock(rom, pos, 0x6C00, byteArrayOf(0x01, 0x02, 0x03, 0x04))
        writeU16(rom, pos, 0)
        pos = songChainPc
        pos = writeTransferBlock(rom, pos, 0x5800, byteArrayOf(0x10, 0x11, 0x12, 0x13))
        writeU16(rom, pos, 0)

        val writes = SpcData.buildRomWritesForSpcRamWrites(
            parser,
            0x03,
            mapOf(0x6C01 to byteArrayOf(0x7F)),
            fallbackToBaseSongSet = true
        )

        assertEquals(1, writes.size)
        assertEquals(baseChainPc + 4 + 1, writes.single().first)
        assertArrayEquals(byteArrayOf(0x7F), writes.single().second)
    }
}
