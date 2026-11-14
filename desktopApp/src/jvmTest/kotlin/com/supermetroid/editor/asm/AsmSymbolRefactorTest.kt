package com.supermetroid.editor.asm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import com.supermetroid.editor.ui.AsmEditableSymbolReference
import com.supermetroid.editor.ui.asmSymbolRenameContextMenuItems
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsmSymbolRefactorTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `rename context action appears only for project global identifiers`() {
        var invoked = false
        val global = AsmEditableSymbolReference(4, "Routine")
        val action = asmSymbolRenameContextMenuItems(AsmWorkspaceKind.PROJECT, global) { invoked = true }.single()
        assertEquals("Rename Symbol…", action.label)
        action.onClick()
        assertTrue(invoked)
        assertTrue(asmSymbolRenameContextMenuItems(AsmWorkspaceKind.REFERENCE, global) {}.isEmpty())
        assertTrue(asmSymbolRenameContextMenuItems(AsmWorkspaceKind.PROJECT, AsmEditableSymbolReference(4, ".local")) {}.isEmpty())
        assertTrue(asmSymbolRenameContextMenuItems(AsmWorkspaceKind.PROJECT, null) {}.isEmpty())
    }

    @Test
    fun `global rename previews and rewrites only proven declaration and references`() {
        val fixture = fixture()

        val plan = AsmSymbolRefactor.planRename(
            fixture.index,
            fixture.texts,
            "bank_80.asm",
            0,
            "Routine",
            "UpdatedRoutine",
        )

        assertEquals(4, plan.occurrences.size)
        assertEquals(setOf("bank_80.asm", "bank_81.asm"), plan.replacements.keys)
        val bank80 = plan.replacements.getValue("bank_80.asm").second
        val bank81 = plan.replacements.getValue("bank_81.asm").second
        assertContains(bank80, "UpdatedRoutine:")
        assertContains(bank80, "JSR UpdatedRoutine")
        assertContains(bank81, "dw UpdatedRoutine_Loop")
        assertContains(bank81, "; Routine and Routine_Loop stay in comments")
        assertContains(bank81, "db \"Routine\"")
        assertContains(bank80, ".Loop:", message = "local declarations keep their authored spelling")
        assertFalse(bank80.lineSequence().any { it.trim() == "Routine:" })
    }

    @Test
    fun `rename refuses locals macros collisions stale indexes and unclassified tokens`() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            AsmSymbolRefactor.planRename(fixture.index, fixture.texts, "bank_80.asm", 1, ".Loop", "Other")
        }
        assertFailsWith<IllegalArgumentException> {
            AsmSymbolRefactor.planRename(fixture.index, fixture.texts, "bank_80.asm", 0, "Routine", "OtherRoutine")
        }
        val stale = fixture.texts + ("bank_80.asm" to fixture.texts.getValue("bank_80.asm") + "; draft\n")
        assertFailsWith<IllegalArgumentException> {
            AsmSymbolRefactor.planRename(fixture.index, stale, "bank_80.asm", 0, "Routine", "Fresh")
        }
        val ambiguous = fixture.texts + ("bank_81.asm" to fixture.texts.getValue("bank_81.asm") + "db Routine[0]\n")
        assertFailsWith<IllegalArgumentException> {
            AsmSymbolRefactor.planRename(fixture.index, ambiguous, "bank_80.asm", 0, "Routine", "Fresh")
        }
    }

    @Test
    fun `plan is a complete expected-text transaction suitable for guarded undo`() {
        val fixture = fixture()
        val plan = AsmSymbolRefactor.planRename(fixture.index, fixture.texts, "bank_80.asm", 0, "Routine", "Fresh")

        plan.replacements.forEach { (fileId, pair) ->
            assertEquals(fixture.texts.getValue(fileId), pair.first)
            assertTrue(pair.second.contains("Fresh"))
        }
    }

    private data class Fixture(val index: AsmReferenceIndex, val texts: Map<String, String>)

    private fun fixture(): Fixture {
        val source = File(directory, "src").apply { mkdirs() }
        val data = File(directory, "data").apply { mkdirs() }
        val texts = mapOf(
            "main.asm" to "incsrc bank_80.asm\nincsrc bank_81.asm\n",
            "bank_80.asm" to """
                Routine:
                  .Loop:
                    BNE .Loop
                    JSR Routine
                    RTS
                OtherRoutine:
                    RTS
            """.trimIndent(),
            "bank_81.asm" to """
                Pointer:
                    dw Routine
                    dw Routine_Loop
                    ; Routine and Routine_Loop stay in comments
                    db "Routine"
            """.trimIndent(),
        )
        texts.forEach { (id, text) -> File(source, id).writeText(text) }
        return Fixture(AsmSourceParser().parse(source, data, emptyList(), sourceTexts = texts), texts)
    }
}
