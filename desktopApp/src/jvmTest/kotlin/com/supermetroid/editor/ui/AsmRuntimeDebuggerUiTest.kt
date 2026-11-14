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
}
