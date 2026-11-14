package com.supermetroid.editor.asm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmCompletionTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `instruction completion uses the embedded 65C816 catalog`() {
        val index = index()
        val text = "    LD"

        val result = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 0, text, text.length, explicit = false),
        )

        assertTrue(result.items.any { it.text == "LDA" && it.kind == AsmCompletionKind.INSTRUCTION })
        assertTrue(result.items.any { it.text == "LDX" })
        assertEquals(text.indexOf("LD"), result.replaceStart)
    }

    @Test
    fun `instruction completion preserves explicit operand size suffixes`() {
        val index = index()
        val partial = "    LDA."
        val choices = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 0, partial, partial.length, explicit = false),
        )

        assertEquals(listOf("LDA.B", "LDA.L", "LDA.W"), choices.items.map(AsmCompletionItem::text))

        val complete = "    LDA.L"
        assertNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 0, complete, complete.length, explicit = false),
            "a complete size-qualified mnemonic must never be replaced by the bare instruction",
        )
    }

    @Test
    fun `operand completion prefers scoped local and global labels`() {
        val index = index()
        val localText = "    BNE .Lo"
        val local = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 4, localText, localText.length, explicit = false),
        )
        assertEquals(".Loop", local.items.first().text)
        assertEquals(AsmCompletionKind.LOCAL_LABEL, local.items.first().kind)

        val globalText = "    JSR Gl"
        val global = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 4, globalText, globalText.length, explicit = false),
        )
        assertTrue(global.items.any { it.text == "GlobalRoutine" })
    }

    @Test
    fun `operand completion follows nested Asar label scope and flattened aliases`() {
        val index = index()
        val nestedText = "    LDA ..gr"
        val nested = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 10, nestedText, nestedText.length, explicit = false),
        )
        val flattenedText = "    LDA .Table_gr"
        val flattened = assertNotNull(
            AsmCompletionEngine.complete(
                index,
                "bank_80.asm",
                10,
                flattenedText,
                flattenedText.length,
                explicit = false,
            ),
        )

        assertTrue(nested.items.any { it.text == "..gravity" })
        assertTrue(flattened.items.any { it.text == ".Table_gravity" })
    }

    @Test
    fun `explicit completion after an opcode offers symbols with an empty prefix`() {
        val index = index()
        val text = "    JSR "

        val result = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 4, text, text.length, explicit = true),
        )

        assertTrue(result.items.any { it.text == "GlobalRoutine" })
    }

    @Test
    fun `operand completion offers expression functions with an insertion caret`() {
        val index = index()
        val text = "    LDA ba"

        val result = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 4, text, text.length, explicit = false),
        )
        val bank = result.items.single { it.text == "bank" }

        assertEquals(AsmCompletionKind.EXPRESSION_FUNCTION, bank.kind)
        assertEquals("bank()", bank.replacement)
        assertEquals(1, bank.caretBacktrack)
        assertTrue(bank.detail.contains("address bank byte"))
    }

    @Test
    fun `define completion explains an exactly known constant`() {
        val index = index()
        val text = "    LDA !Sp"

        val result = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 4, text, text.length, explicit = false),
        )
        val define = result.items.single { it.text == "!Speed" }

        assertEquals(AsmCompletionKind.DEFINE, define.kind)
        assertTrue(define.detail.contains("\$10 (16)"))
    }

    @Test
    fun `macro completion includes the indexed signature and source`() {
        val index = index()
        val text = "    %spr"

        val result = assertNotNull(
            AsmCompletionEngine.complete(index, "bank_80.asm", 0, text, text.length, explicit = false),
        )
        val macro = result.items.single { it.text == "%spritemapEntry" }

        assertEquals(AsmCompletionKind.MACRO, macro.kind)
        assertTrue(macro.detail.contains("%spritemapEntry(Size, Tile)"))
        assertTrue(macro.detail.contains("macros.asm:1"))
        assertEquals("%spritemapEntry()", macro.replacement)
        assertEquals(1, macro.caretBacktrack)
    }

    @Test
    fun `completion excludes symbols and macros owned by inactive modules`() {
        val index = index()

        val macro = AsmCompletionEngine.complete(
            index,
            "bank_80.asm",
            0,
            "    %spr",
            8,
            explicit = false,
            excludedFileIds = setOf("macros.asm"),
        )
        val symbol = AsmCompletionEngine.complete(
            index,
            "bank_80.asm",
            0,
            "    JSR Gl",
            10,
            explicit = false,
            excludedFileIds = setOf("bank_80.asm"),
        )

        assertNull(macro)
        assertNull(symbol)
    }

    @Test
    fun `macro call hint follows nested arguments and quoted commas`() {
        val index = index()
        val first = "    %spritemapEntry(function(${ '$' }01, ${ '$' }02), "
        val second = "    %spritemapEntry(\"left,right\", "

        val nestedHint = assertNotNull(AsmMacroCallAnalyzer.hint(index, first, first.length))
        val quotedHint = assertNotNull(AsmMacroCallAnalyzer.hint(index, second, second.length))

        assertEquals("spritemapEntry", nestedHint.macro.name)
        assertEquals(1, nestedHint.argumentIndex)
        assertEquals(1, nestedHint.parameterIndex)
        assertEquals(1, quotedHint.argumentIndex)
    }

    @Test
    fun `macro call hint ignores completed calls comments and inactive definitions`() {
        val index = index()
        val complete = "    %spritemapEntry(${ '$' }01, ${ '$' }02)"
        val comment = "    RTS ; %spritemapEntry("
        val string = "    db \"%spritemapEntry(\""
        val active = "    %spritemapEntry("

        assertNull(AsmMacroCallAnalyzer.hint(index, complete, complete.length))
        assertNull(AsmMacroCallAnalyzer.hint(index, comment, comment.length))
        assertNull(AsmMacroCallAnalyzer.hint(index, string, string.length - 1))
        assertNull(
            AsmMacroCallAnalyzer.hint(
                index,
                active,
                active.length,
                excludedFileIds = setOf("macros.asm"),
            ),
        )
    }

    @Test
    fun `completion stays out of comments and strings`() {
        val index = index()
        val comment = "    RTS ; LD"
        val string = "    incbin \"Lo"

        assertNull(AsmCompletionEngine.complete(index, "bank_80.asm", 0, comment, comment.length, explicit = true))
        assertNull(AsmCompletionEngine.complete(index, "bank_80.asm", 0, string, string.length, explicit = true))
    }

    @Test
    fun `completion safely handles a caret before a leading newline`() {
        val index = index()
        val text = "\n    RTS"

        assertNull(AsmCompletionEngine.complete(index, "bank_80.asm", 0, text, 0, explicit = false))
        assertNotNull(AsmCompletionEngine.complete(index, "bank_80.asm", 0, text, 0, explicit = true))
    }

    private fun index(): AsmReferenceIndex {
        val source = File(tempDirectory, "src").apply { mkdirs() }
        val data = File(tempDirectory, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc macros.asm\nincsrc bank_80.asm\n")
        File(source, "macros.asm").writeText(
            "macro spritemapEntry(Size, Tile)\n    dw <Size>, <Tile>\nendmacro\n",
        )
        File(source, "bank_80.asm").writeText(
            """
                !Speed = ${'$'}0010
                GlobalRoutine:
                    RTS

                Scope:
                  .Loop:
                    BNE .Loop
                    JSR GlobalRoutine
                  .Table:
                  ..gravity:
                    dw ${'$'}0001
            """.trimIndent(),
        )
        return AsmSourceParser().parse(source, data, emptyList())
    }
}
