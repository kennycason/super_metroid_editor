package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AsmCompiledSymbolsTest {
    @Test
    fun `address at or before follows the nearest mapped source line regardless of map order`() {
        val symbols = AsmCompiledSymbols(
            labels = emptyMap(),
            lineAddressesByFile = mapOf(
                "bank_8F.asm" to intArrayOf(
                    0x8F8040, 12,
                    0x8F8000, 2,
                    0x8F8020, 7,
                ),
            ),
        )

        assertNull(symbols.addressAtOrBefore("bank_8F.asm", 1))
        assertEquals(0x8F8000, symbols.addressAtOrBefore("bank_8F.asm", 2))
        assertEquals(0x8F8000, symbols.addressAtOrBefore("bank_8F.asm", 6))
        assertEquals(0x8F8020, symbols.addressAtOrBefore("bank_8F.asm", 11))
        assertEquals(0x8F8040, symbols.addressAtOrBefore("bank_8F.asm", 40))
        assertNull(symbols.addressAtOrBefore("missing.asm", 40))
    }
}
