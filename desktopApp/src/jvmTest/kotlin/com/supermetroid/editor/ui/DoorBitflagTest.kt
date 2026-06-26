package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DoorBitflagTest {

    @Test
    fun `matched vanilla orientation keeps closing cap while adding cross area`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0000,
            matchedOrientation = 0x04,
            crossArea = true,
        )

        assertEquals(0x0440, bitflag)
    }

    @Test
    fun `matched vanilla orientation preserves unrelated low flags`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0080,
            matchedOrientation = 0x05,
            crossArea = false,
        )

        assertEquals(0x0580, bitflag)
    }

    @Test
    fun `missing match preserves current orientation`() {
        val bitflag = mergeDoorBitflagWithMatchedOrientation(
            currentBitflag = 0x0540,
            matchedOrientation = null,
        )

        assertEquals(0x0540, bitflag)
    }
}
