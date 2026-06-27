package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertTrue

class PatchDisassemblerTest {
    @Test
    fun `disassembler tracks REP and SEP immediate widths`() {
        val text = disassemblePatchWrites(
            listOf(
                SmPatchWrite(
                    offset = 0x87C20,
                    bytes = listOf(
                        0xC2, 0x30,       // REP #$30
                        0xA9, 0x34, 0x12, // LDA #$1234
                        0xE2, 0x20,       // SEP #$20
                        0xA9, 0x56,       // LDA #$56
                        0xD0, 0xFA,       // BNE back to SEP
                    )
                )
            )
        )

        assertTrue(text.contains("\$90:FC20  C2 30        REP #\$30"))
        assertTrue(text.contains("\$90:FC22  A9 34 12     LDA #\$1234"))
        assertTrue(text.contains("\$90:FC25  E2 20        SEP #\$20"))
        assertTrue(text.contains("\$90:FC27  A9 56        LDA #\$56"))
        assertTrue(text.contains("\$90:FC29  D0 FA        BNE \$90:FC25"))
    }

    @Test
    fun `disassembler shows short pointer writes as incomplete instructions`() {
        val text = disassemblePatchWrites(
            listOf(SmPatchWrite(offset = 0x82353, bytes = listOf(0x20, 0xFC)))
        )

        assertTrue(text.contains("; record 1: PC 0x082353 -> \$90:A353, 2 bytes"))
        assertTrue(text.contains("\$90:A353  20 FC        .db \$20, \$FC ; incomplete JSR"))
    }
}
