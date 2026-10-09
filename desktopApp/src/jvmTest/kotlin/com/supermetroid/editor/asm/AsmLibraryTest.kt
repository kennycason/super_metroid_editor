package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmLibraryTest {
    @Test
    fun `instruction library contains the complete 65C816 mnemonic set`() {
        assertEquals(EXPECTED_MNEMONICS, AsmInstructionReference.instructions.keys)
        AsmInstructionReference.instructions.values.forEach { instruction ->
            assertTrue(instruction.name.isNotBlank(), instruction.mnemonic)
            assertTrue(instruction.summary.isNotBlank(), instruction.mnemonic)
            assertTrue(instruction.commonForms.isNotEmpty(), instruction.mnemonic)
            assertTrue(instruction.example.code.isNotBlank(), instruction.mnemonic)
            assertTrue(instruction.example.explanation.isNotBlank(), instruction.mnemonic)
            assertTrue(instruction.practicalNote.isNotBlank(), instruction.mnemonic)
        }
    }

    @Test
    fun `guide and instruction page ids round trip`() {
        assertEquals(9, AsmLibrary.guides.size)
        assertEquals(AsmLibrary.guides.size, AsmLibrary.guides.map { it.id }.toSet().size)
        AsmLibrary.guides.forEach { guide ->
            assertNotNull(AsmLibrary.guide(guide.id))
            assertTrue(guide.sections.size >= 3, guide.id)
        }
        val valuesGuide = AsmLibrary.guide("values-and-data")!!
        assertTrue(valuesGuide.sections.flatMap { it.example?.code?.lines().orEmpty() }.any { "%00010000" in it })
        val jumpGuide = AsmLibrary.guide("samus-jump-edit")!!
        val jumpText = jumpGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + (section.example?.code.orEmpty())
        }
        assertTrue("Make_Samus_Jump" in jumpText)
        assertTrue("\$90:9EB9" in jumpText)
        assertTrue("\$90:9EBF" in jumpText)
        assertTrue("cannot silently omit" in jumpText)
        EXPECTED_MNEMONICS.forEach { mnemonic ->
            val pageId = AsmLibrary.instructionPageId(mnemonic)
            assertEquals(mnemonic, AsmLibrary.mnemonicFromPageId(pageId))
        }
    }

    @Test
    fun `clicking a sized opcode selects its instruction page and preserves suffix context`() {
        val state = AsmWorkspaceState()
        state.query = "old source search"

        state.showInstruction("lda.w")

        assertEquals(AsmBrowserMode.LIBRARY, state.browserMode)
        assertEquals("", state.query)
        assertEquals("LDA", state.selectedInstruction)
        assertEquals("LDA.W", state.selectedInstructionToken)
        assertEquals("instruction:LDA", state.selectedLibraryPageId)

        state.openLibraryGuide("widths-and-suffixes")
        assertEquals("widths-and-suffixes", state.selectedLibraryPageId)
        assertEquals(null, state.selectedInstruction)
        assertEquals(null, state.selectedInstructionToken)
    }

    private companion object {
        val EXPECTED_MNEMONICS = setOf(
            "ADC", "AND", "ASL", "BCC", "BCS", "BEQ", "BIT", "BMI", "BNE", "BPL", "BRA", "BRK", "BRL", "BVC", "BVS",
            "CLC", "CLD", "CLI", "CLV", "CMP", "COP", "CPX", "CPY", "DEC", "DEX", "DEY", "EOR", "INC", "INX", "INY",
            "JML", "JMP", "JSL", "JSR", "LDA", "LDX", "LDY", "LSR", "MVN", "MVP", "NOP", "ORA", "PEA", "PEI", "PER",
            "PHA", "PHB", "PHD", "PHK", "PHP", "PHX", "PHY", "PLA", "PLB", "PLD", "PLP", "PLX", "PLY", "REP", "ROL", "ROR",
            "RTI", "RTL", "RTS", "SBC", "SEC", "SED", "SEI", "SEP", "STA", "STP", "STX", "STY", "STZ", "TAX", "TAY", "TCD",
            "TCS", "TDC", "TRB", "TSB", "TSC", "TSX", "TXA", "TXS", "TXY", "TYA", "TYX", "WAI", "WDM", "XBA", "XCE",
        )
    }
}
