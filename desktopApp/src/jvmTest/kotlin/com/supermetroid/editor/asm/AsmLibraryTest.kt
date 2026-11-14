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
        assertEquals(24, AsmLibrary.guides.size)
        assertEquals(AsmLibrary.guides.size, AsmLibrary.guides.map { it.id }.toSet().size)
        AsmLibrary.guides.forEach { guide ->
            assertNotNull(AsmLibrary.guide(guide.id))
            assertTrue(guide.sections.size >= 3, guide.id)
        }
        val valuesGuide = AsmLibrary.guide("values-and-data")!!
        assertTrue(valuesGuide.sections.flatMap { it.example?.code?.lines().orEmpty() }.any { "%00010000" in it })
        val readingGuide = AsmLibrary.guide("reading-source")!!
        val readingText = readingGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + (section.example?.code.orEmpty())
        }
        assertTrue("SamusPhysicsConstants_InitialYSpeeds_Jumping" in readingText)
        assertTrue("dot is the scope marker" in readingText)
        val jumpGuide = AsmLibrary.guide("samus-jump-edit")!!
        val jumpText = jumpGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + (section.example?.code.orEmpty())
        }
        assertTrue("Make_Samus_Jump" in jumpText)
        assertTrue("\$90:9EB9" in jumpText)
        assertTrue("\$90:9EBF" in jumpText)
        assertTrue("cannot silently omit" in jumpText)
        val directiveGuide = AsmLibrary.guide("asar-directives")!!
        val documentedDirectives = directiveGuide.sections.flatMap { section ->
            section.table?.rows.orEmpty().map { row -> row.first() }
        }.toSet()
        assertEquals(AsmLibrary.directiveNames, documentedDirectives)
        assertTrue(setOf("org", "warnpc", "incsrc", "db", "assert").all(AsmLibrary.directiveNames::contains))
        AsmLibrary.directives.values.forEach { directive ->
            assertTrue(directive.form.isNotBlank(), directive.name)
            assertTrue(directive.summary.isNotBlank(), directive.name)
            assertTrue(directive.practicalNote.isNotBlank(), directive.name)
        }
        val hooksGuide = AsmLibrary.guide("hooks-and-overrides")!!
        val hooksText = hooksGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("displaced work" in hooksText)
        assertTrue("Objectives_MainPauseRoutine" in hooksText)
        assertTrue("warnpc \$918000" in hooksText)
        assertTrue("@smedit-checked-hook-v2" in hooksText)
        assertTrue("no persisted authoritative address" in hooksText)
        assertTrue("requested bytes, chosen range, owner and constraint" in hooksText)
        val spaceGuide = AsmLibrary.guide("rom-space-planner")!!
        val spaceText = spaceGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n")
        }
        assertTrue("same typed requests" in spaceText)
        assertTrue("Largest block" in spaceText)
        assertTrue("total compatible space" in spaceText)
        assertTrue("hand-editing a generated org" in spaceText)
        assertTrue("WRAM" in spaceText)
        val outputGuide = AsmLibrary.guide("compiled-output-inspector")!!
        val outputText = outputGuide.sections.joinToString("\n") { it.paragraphs.joinToString("\n") }
        assertTrue("Baseline is the immutable project ASM snapshot" in outputText)
        assertTrue("Stale explicitly means" in outputText)
        assertTrue("Generated build input" in outputText)
        val changesGuide = AsmLibrary.guide("semantic-project-changes")!!
        val changesText = changesGuide.sections.joinToString("\n") { it.paragraphs.joinToString("\n") }
        assertTrue("do not record whether typing in ASM" in changesText)
        assertTrue("never applies a partial diff hunk" in changesText)
        assertTrue("containing a managed marker cannot be restored independently" in changesText)
        val capabilityGuide = AsmLibrary.guide("asm-visual-capability-matrix")!!
        val capabilityText = capabilityGuide.sections.joinToString("\n") { it.paragraphs.joinToString("\n") }
        assertTrue("one durable source authority plus two views" in capabilityText)
        assertTrue("docs/asm/capability_matrix.md" in capabilityText)
        assertTrue("cache-free build of the emitted bytes and pointers" in capabilityText)
        assertTrue(":desktopApp:asmFastTest" in capabilityText)
        assertTrue(":desktopApp:asmReciprocityTest" in capabilityText)
        val assistanceGuide = AsmLibrary.guide("asm-editor-assistance")!!
        val assistanceText = assistanceGuide.sections.joinToString("\n") { it.paragraphs.joinToString("\n") }
        assertTrue("unknown label, custom function, macro expansion" in assistanceText)
        assertTrue("direct branch/call/jump target" in assistanceText)
        assertTrue("one guarded multi-file Undo/Redo transaction" in assistanceText)
        assertTrue("no whole-file formatter" in assistanceText)
        val debuggerGuide = AsmLibrary.guide("source-runtime-debugger")!!
        val debuggerText = debuggerGuide.sections.joinToString("\n") { it.paragraphs.joinToString("\n") }
        assertTrue("versioned debug capability" in debuggerText)
        assertTrue("side-effect-free paused bus peek" in debuggerText)
        assertTrue("Breakpoints exist only for the active emulator session" in debuggerText)
        val nativeSpriteGuide = AsmLibrary.guide("native-sprite-source-assets")!!
        val nativeSpriteText = nativeSpriteGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("Move to project space" in nativeSpriteText)
        assertTrue("literal dw \$hhhh" in nativeSpriteText)
        assertTrue("OAM placement" in nativeSpriteText)
        val cinematicGuide = AsmLibrary.guide("cinematic-map-source-assets")!!
        val cinematicText = cinematicGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" +
                section.table?.rows.orEmpty().joinToString("\n") { it.joinToString(" ") }
        }
        assertTrue("Title_Mode7_Tilemap" in cinematicText)
        assertTrue("Move to project space" in cinematicText)
        assertTrue("PAL options branches" in cinematicText)
        assertTrue("flattened pixels" in cinematicText)
        val textGuide = AsmLibrary.guide("fixed-text-source-fields")!!
        val textGuideText = textGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("stale fixed-text JSON overrides" in textGuideText)
        assertTrue("literal dw \$hhhh" in textGuideText)
        assertTrue("typewriter messages" in textGuideText)
        assertTrue(";82966F;" in textGuideText)
        val typewriterGuide = AsmLibrary.guide("typewriter-text-streams")!!
        val typewriterText = typewriterGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("LDA.W #label" in typewriterText)
        assertTrue("bank \$A6 only" in typewriterText)
        assertTrue("\$000D" in typewriterText)
        assertTrue("Japanese glyph data" in typewriterText)
        val introStoryGuide = AsmLibrary.guide("intro-story-text-pages")!!
        val introStoryText = introStoryGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("\$0005*!FPS" in introStoryText)
        assertTrue("Timing expressions" in introStoryText)
        assertTrue("Japanese font/text path" in introStoryText)
        val demoGuide = AsmLibrary.guide("checked-hook-demo")!!
        val demoText = demoGuide.sections.joinToString("\n") { section ->
            section.paragraphs.joinToString("\n") + "\n" + section.example?.code.orEmpty()
        }
        assertTrue("LowEnergyCheck+\$0008" in demoText)
        assertTrue("SMEDIT_CheckedHookDemo_Threshold_End" in demoText)
        assertTrue("last enabled Project Module" in demoText)
        val quickReference = AsmLibrary.guide("quick-reference-tables")!!
        val tables = quickReference.sections.mapNotNull { it.table }
        assertEquals(5, tables.size)
        tables.forEach { table ->
            assertTrue(table.headers.isNotEmpty())
            assertEquals(table.headers.size, table.columnWeights.size)
            table.rows.forEach { row -> assertEquals(table.headers.size, row.size) }
        }
        val numberRows = tables.first().rows
        assertTrue(listOf("16", "\$10", "%00010000", "bit 4") in numberRows)
        val vectorRows = tables.last().rows
        assertTrue(listOf("Native", "\$80:FFEA", "NMI", "NMI") in vectorRows)
        assertTrue(listOf("Emulation", "\$80:FFFC", "RESET", "Boot") in vectorRows)
        EXPECTED_MNEMONICS.forEach { mnemonic ->
            val pageId = AsmLibrary.instructionPageId(mnemonic)
            assertEquals(mnemonic, AsmLibrary.mnemonicFromPageId(pageId))
        }
    }

    @Test
    fun `clicking a sized opcode preserves search and suffix context when navigating library pages`() {
        val state = AsmWorkspaceState()
        state.query = "old source search"

        state.showInstruction("lda.w")

        assertEquals(AsmBrowserMode.LIBRARY, state.browserMode)
        assertEquals("old source search", state.query)
        assertEquals("LDA", state.selectedInstruction)
        assertEquals("LDA.W", state.selectedInstructionToken)
        assertEquals("instruction:LDA", state.selectedLibraryPageId)

        state.openLibraryGuide("widths-and-suffixes")
        assertEquals("widths-and-suffixes", state.selectedLibraryPageId)
        assertEquals(null, state.selectedInstruction)
        assertEquals(null, state.selectedInstructionToken)
        assertEquals("old source search", state.query)

        state.openLibraryInstruction("sta.l")
        assertEquals("instruction:STA", state.selectedLibraryPageId)
        assertEquals("STA.L", state.selectedInstructionToken)
        assertEquals("old source search", state.query)

        state.openLibraryDirective("warnpc")
        assertEquals("asar-directives", state.selectedLibraryPageId)
        assertEquals("warnpc", state.selectedLibraryDirective)
        assertEquals(null, state.selectedInstruction)
        assertEquals(null, state.selectedInstructionToken)
        assertEquals("old source search", state.query)
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
