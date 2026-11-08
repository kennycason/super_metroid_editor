package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmSourceBankBudget
import kotlin.test.Test
import kotlin.test.assertEquals

class AsmSourceBankChipTest {
    @Test
    fun `bank chip distinguishes current contiguous space from a required build`() {
        assertEquals(
            "Bank \$8F · 32 KiB · 3.8 KiB alloc.",
            asmSourceBankChipLabel(
                AsmSourceBankBudget(
                    bank = 0x8F,
                    allocatableContiguousBytes = 3935,
                    spaceIsCurrent = true,
                ),
            ),
        )
        assertEquals(
            "Bank \$8F · 32 KiB · build for space",
            asmSourceBankChipLabel(AsmSourceBankBudget(bank = 0x8F)),
        )
    }
}
