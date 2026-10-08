package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AsmNavigationLinkTest {
    @Test
    fun `semantic pointers resolve in their runtime bank`() {
        assertEquals(0x83A123, semanticAsmAddress(RomConstants.BANK_FX, 0xA123))
        assertEquals(0x84B703, semanticAsmAddress(RomConstants.BANK_PLM, 0xB703))
        assertEquals(0xA0DCFF, semanticAsmAddress(RomConstants.BANK_ENEMY_AI, 0xDCFF))
    }

    @Test
    fun `null and non LoROM pointers do not become navigation links`() {
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0))
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0x7FFF))
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0xFFFF))
    }
}
