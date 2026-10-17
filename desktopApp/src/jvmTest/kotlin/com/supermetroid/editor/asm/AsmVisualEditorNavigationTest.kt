package com.supermetroid.editor.asm

import com.supermetroid.editor.ui.asmVisualEditorContextMenuItems
import java.io.File
import kotlin.test.*

class AsmVisualEditorNavigationTest {
    private fun source(text: String) = AsmSourceFile("bank.asm", File("unused.asm"), "Test", "", true,
        text.lines(), emptyList(), List(text.lines().size) { emptyList() })
    private val wave = AsmEditorLink("Edit Wave Beam damage", "Beam damage", AsmEditorTarget.BeamDamage("wave"))
    private val ice = AsmEditorLink("Edit Ice Beam damage", "Beam damage", AsmEditorTarget.BeamDamage("ice"))

    @Test fun `sections respect aliases local labels duplicate names and placement boundaries`() {
        val text = """
            Table:
            ; alias note
            Alias:
                dw 1
              .local:
                dw 2
            org ${'$'}938000
                dw 3
            Next:
                dw 4
            Next:
                dw 5
        """.trimIndent()
        val sections = AsmSourceSections(text)
        assertEquals(sections.unique("Table"), sections.unique("Alias"))
        assertEquals(sections.unique("Alias"), sections.at(5))
        assertEquals(listOf(0, 2), sections.unique("Table")!!.labelLines)
        assertNull(sections.at(6)); assertNull(sections.at(7)); assertNull(sections.unique("Next"))
        assertEquals(3, sections.lineAt(text.indexOf("dw 1")))
    }
    @Test fun `one resolver maps labels and data without spilling into unrelated sections`() {
        val source = source("Table:\nAlias:\n dw 50\n dw InstList\nOther:\n dw 0\n")
        val resolver = AsmVisualEditorResolver(source, source.lines, AsmSemanticIndex(mapOf(0x938431 to listOf(wave))), mapOf(2 to 0x938431))
        for (line in 0..3) assertEquals(listOf(wave), resolver.linksAt(line))
        assertTrue(resolver.linksAt(4).isEmpty()); assertTrue(resolver.linksAt(5).isEmpty())
        assertTrue(resolver.linksAt(-1).isEmpty()); assertTrue(resolver.linksAt(100).isEmpty())
    }
    @Test fun `live identity wins and shifted unsaved text cannot reuse old address mappings`() {
        val source = source("Table:\n dw 50\nOther:\n dw 0\n")
        val semantics = AsmSemanticIndex(mapOf(0x938431 to listOf(ice)))
        val shifted = listOf("; inserted") + source.lines
        val resolver = AsmVisualEditorResolver(source, shifted, semantics, mapOf(1 to 0x938431)) { line ->
            if (line == 2) listOf(wave, wave) else emptyList()
        }
        assertEquals(listOf(wave), resolver.linksAt(2))
        assertTrue(resolver.linksAt(1).isEmpty(), "An old Ice address cannot be applied to a shifted label")
        assertTrue(resolver.linksAt(3).isEmpty())
    }
    @Test fun `terminal newline differences do not disable known reference navigation`() {
        val text = "Table:\n dw 50\n"
        val source = source(text).copy(lines = text.lines().dropLastWhile(String::isEmpty))
        val resolver = AsmVisualEditorResolver(source, text.lines(), AsmSemanticIndex(mapOf(0x938431 to listOf(wave))), mapOf(1 to 0x938431))
        assertEquals(listOf(wave), resolver.linksAt(0))
        assertEquals(listOf(wave), resolver.linksAt(1))
    }
    @Test fun `live room layout header selector and door declarations resolve without a ROM and stop at their ends`() {
        val declarations = listOf(
            "macro SmeditLayout_91F8_Data_6c6576656c2d31()" to AsmEditorTarget.Room(0x91F8, layoutId = "level-1"),
            "macro SmeditRoomInfo_91F8_Header()" to AsmEditorTarget.Room(0x91F8),
            "macro SmeditDoors_91F8_Door_0()" to AsmEditorTarget.Room(0x91F8),
            "macro SmeditSelectors_91F8_Condition_73746174652d31()" to AsmEditorTarget.Room(0x91F8, stateId = "state-1"),
            "macro SmeditArrival_91F8_Door_0()" to AsmEditorTarget.Room(0x91F8),
            "macro SmeditObjects_Project_${AsmProjectRoomLabels.encoded("new-room")}_State_73746174652d31()" to
                AsmEditorTarget.Room(0, stateId = "state-1", projectRoomId = "new-room"),
        )
        for ((declaration, target) in declarations) {
            val source = source("$declaration\n dw broken_draft\nendmacro\n; unrelated\n")
            val resolver = AsmVisualEditorResolver(source, source.lines, null, emptyMap())
            for (line in 0..2) assertEquals(listOf(target), resolver.linksAt(line).map { it.target })
            assertTrue(resolver.linksAt(3).isEmpty())
        }
    }
    @Test fun `context menu has one navigation action and preserves every shared destination`() {
        val opened = mutableListOf<List<AsmEditorLink>>()
        assertTrue(asmVisualEditorContextMenuItems(emptyList(), opened::add).isEmpty())
        val single = asmVisualEditorContextMenuItems(listOf(wave, wave), opened::add).single()
        assertEquals("Visual Editor", single.label); single.onClick()
        assertEquals(listOf(wave), opened.single())
        val shared = asmVisualEditorContextMenuItems(listOf(wave, ice), opened::add).single()
        assertEquals("Visual Editor…", shared.label); shared.onClick()
        assertEquals(listOf(wave, ice), opened.last())
    }
}
