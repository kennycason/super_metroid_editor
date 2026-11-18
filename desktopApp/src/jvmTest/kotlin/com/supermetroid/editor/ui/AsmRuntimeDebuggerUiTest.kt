package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AsmRuntimeDebuggerUiTest {
    @Test
    fun `debug addresses accept common ASM forms and reject out of range input`() {
        assertEquals(0x81A0AE, parseDebugAddress("\$81:A0AE"))
        assertEquals(0xE08010, parseDebugAddress("E08010"))
        assertEquals(0x7E1234, parseDebugAddress("7E_1234"))
        assertNull(parseDebugAddress("1000000"))
        assertNull(parseDebugAddress("not an address"))
    }

    @Test
    fun `watch values always expose decimal hexadecimal and binary forms`() {
        fun watch(width: AsmDebugWatchWidth, vararg bytes: Int) = AsmDebugWatch(
            id = 1,
            name = "value",
            expression = "value",
            address = 0x7E0000,
            width = width,
            bytes = bytes.map(Int::toByte).toByteArray(),
        )

        val word = watch(AsmDebugWatchWidth.WORD, 0x34, 0x12)
        assertEquals("4660", word.decimalValue())
        assertEquals("\$1234", word.hexadecimalValue())
        assertEquals("0001 0010 0011 0100", word.binaryValue())
        assertEquals("254", watch(AsmDebugWatchWidth.BYTE, 0xFE).decimalValue())
    }

    @Test
    fun `watch address resolution preserves supplied symbols and WRAM maps to Hex`() {
        assertEquals(
            0x7E09C2,
            resolveDebugWatchAddress("Energy", "Energy", 0x7E09C2) { null },
        )
        assertEquals(
            0xE08010,
            resolveDebugWatchAddress("Helper", "", null) { if (it == "Helper") 0xE08010 else null },
        )
        assertNull(resolveDebugWatchAddress("Missing", "", null) { null })

        assertEquals(0x09C2, debugWatchWramOffset(0x7E09C2))
        assertEquals(0x11234, debugWatchWramOffset(0x7F1234))
        assertEquals(0x7E09C2, wramDebugAddress(0x09C2))
        assertEquals(0x7F1234, wramDebugAddress(0x11234))
        assertNull(debugWatchWramOffset(0x808000))
        assertNull(wramDebugAddress(0x20000))
        assertEquals(true, isDebugWatchRangeValid(0xFFFFFC, AsmDebugWatchWidth.DWORD))
        assertEquals(false, isDebugWatchRangeValid(0xFFFFFD, AsmDebugWatchWidth.DWORD))
    }
}
