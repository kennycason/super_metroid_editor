package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicTransferBlockEdit
import com.supermetroid.editor.rom.SpcData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class MusicTransferChainBudgetTest {
    @Test
    fun `native payload assessment blocks transfer chains larger than one LoROM bank`() {
        val payload = nativePayload(
            sourcePlayIndex = 1,
            0x0100 to ByteArray(0x8100) { 0x55 },
            MusicSequenceBudget.SONG_TABLE_BASE + 2 to byteArrayOf(0x34, 0x12)
        )
        val assessment = MusicTransferChainBudget.assessNativePayloadRomExport(
            originalBlocks = listOf(SpcData.TransferBlock(0x5828, ByteArray(32))),
            payload = payload,
            targetPlayIndex = 5
        )

        assertFalse(assessment.canExportToRom)
        assertTrue(assessment.relocatedChainBytes > MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES)
        assertTrue(assessment.overByBytes > 0)
    }

    @Test
    fun `native payload compaction remaps source song table entry to target play index`() {
        val payload = nativePayload(
            sourcePlayIndex = 1,
            MusicSequenceBudget.SONG_TABLE_BASE + 2 to byteArrayOf(0x78, 0x56),
            0x6000 to byteArrayOf(0x01, 0x02)
        )

        val writes = MusicTransferChainBudget.compactedNativePayloadWrites(payload, targetPlayIndex = 5)

        assertArrayEquals(byteArrayOf(0x78, 0x56), writes.getValue(MusicSequenceBudget.SONG_TABLE_BASE + 10))
        assertArrayEquals(byteArrayOf(0x01, 0x02), writes.getValue(0x6000))
    }

    @Test
    fun `preflight counts the actual union with original bank data`() {
        val payload = nativePayload(1,
            0x8000 to ByteArray(0x4000) { 0x55 },
            MusicSequenceBudget.SONG_TABLE_BASE + 2 to byteArrayOf(0x34, 0x12))
        val original = listOf(SpcData.TransferBlock(0x1500, ByteArray(0x4000)))
        val assessment = MusicTransferChainBudget.assessNativePayloadRomExport(original, payload, 5)
        assertTrue(assessment.payloadChainBytes < MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES)
        assertFalse(assessment.canExportToRom, "Original sibling assets must not be dropped to make an import fit")

        val overlapping = listOf(SpcData.TransferBlock(0x8000, ByteArray(0x4000)))
        val union = MusicTransferChainBudget.assessNativePayloadRomExport(overlapping, payload, 5)
        assertTrue(union.canExportToRom)
        assertEquals(union.payloadChainBytes, union.relocatedChainBytes, "Overlapping sample data is counted once")
    }

    @Test
    fun `full song table remapping cannot be overwritten by stale target entry`() {
        val source = MusicSequenceBudget.SONG_TABLE_BASE + 2
        val payload = nativePayload(1, source to byteArrayOf(0x78, 0x56, 0, 0, 0, 0, 0, 0, 0x34, 0x12))
        val writes = MusicTransferChainBudget.compactedNativePayloadWrites(payload, 5)
        val ram = ByteArray(65536).also { for ((at, data) in writes) data.copyInto(it, at) }
        assertArrayEquals(byteArrayOf(0x78, 0x56), ram.copyOfRange(source + 8, source + 10))
        assertFalse(writes.any { (at, data) -> source in at until at + data.size })
    }

    @Test
    fun `malformed pointers and out of bounds blocks fail before exporting`() {
        val source = MusicSequenceBudget.SONG_TABLE_BASE + 2
        for (payload in listOf(
            nativePayload(1, source to byteArrayOf(0x78)),
            nativePayload(1, source to byteArrayOf(0x78, 0x56), 0xFFFF to byteArrayOf(1, 2)),
            nativePayload(1, source to byteArrayOf(0x78, 0x56), -1 to byteArrayOf(1))
        )) {
            assertThrows<IllegalArgumentException> { MusicTransferChainBudget.compactedNativePayloadWrites(payload, 5) }
        }
        assertThrows<IllegalArgumentException> {
            MusicTransferChainBudget.compactedNativePayloadWrites(nativePayload(1, source to byteArrayOf(0x78, 0x56)), Int.MAX_VALUE)
        }
    }

    private fun nativePayload(
        sourcePlayIndex: Int,
        vararg blocks: Pair<Int, ByteArray>
    ): MusicNativePayloadEdit {
        val encoder = Base64.getEncoder()
        return MusicNativePayloadEdit(
            formatLabel = "Test",
            sourceFileName = "test.nspc",
            sourcePlayIndex = sourcePlayIndex,
            baseline = com.supermetroid.editor.data.MusicNativeBaselineEdit(tempo = 36),
            blocks = blocks.map { (addr, data) ->
                MusicTransferBlockEdit(addr, encoder.encodeToString(data))
            }.toMutableList()
        )
    }
}
