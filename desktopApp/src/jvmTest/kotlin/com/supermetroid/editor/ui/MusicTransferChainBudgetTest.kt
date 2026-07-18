package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicTransferBlockEdit
import com.supermetroid.editor.rom.SpcData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun nativePayload(
        sourcePlayIndex: Int,
        vararg blocks: Pair<Int, ByteArray>
    ): MusicNativePayloadEdit {
        val encoder = Base64.getEncoder()
        return MusicNativePayloadEdit(
            formatLabel = "Test",
            sourceFileName = "test.nspc",
            sourcePlayIndex = sourcePlayIndex,
            blocks = blocks.map { (addr, data) ->
                MusicTransferBlockEdit(addr, encoder.encodeToString(data))
            }.toMutableList()
        )
    }
}
