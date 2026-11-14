package com.supermetroid.editor.asm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmEditorAnalysisTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `constant expressions are exact and unknown constructs remain unknown`() {
        assertEquals(0x80FFFF, AsmConstantExpression.evaluate("\$80:FF00 + (\$FF & \$FF)"))
        assertEquals(0x80, AsmConstantExpression.evaluate("bank(\$808000)"))
        assertEquals(0x34, AsmConstantExpression.evaluate("low(\$1234)"))
        assertEquals(0x1234, AsmConstantExpression.evaluate("!Base + 4", mapOf("!Base" to 0x1230)))
        assertEquals(1, AsmConstantExpression.evaluate("!0"))
        assertNull(AsmConstantExpression.evaluate("CustomFunction(\$10)"))
        assertNull(AsmConstantExpression.evaluate("Unknown + 1"))
    }

    @Test
    fun `expression overflow bank crossing and warnpc use only exact values`() {
        val source = source(
            """
                !Count = 2 + 2
                org ${'$'}80FFFE
                dw ${'$'}0001, !Count
                org ${'$'}808000
                fill ${'$'}20
                warnpc ${'$'}808010
                db ${'$'}F0 + ${'$'}20
                org DynamicAddress
                dw UnknownSize
            """.trimIndent(),
        )

        val diagnostics = lint(source)

        assertTrue(diagnostics.any { it.lineIndex == 2 && it.message.contains("crosses the ROM bank boundary") })
        assertTrue(diagnostics.any { it.lineIndex == 5 && it.message.contains("exceeds warnpc") })
        assertTrue(diagnostics.any { it.lineIndex == 6 && it.message.contains("evaluates to 272") })
        assertTrue(diagnostics.none { it.lineIndex == 7 && it.message.contains("bank") }, "unknown cursors must stop placement inference")
    }

    @Test
    fun `unresolved calls proven return mismatches and processor width are conservative`() {
        val source = source(
            """
                org ${'$'}808000
                NearRoutine:
                    REP #${'$'}20
                    LDA #${'$'}01
                    JSL LongRoutine
                    JSR MissingRoutine
                    RTS

                LongRoutine:
                    RTL
            """.trimIndent(),
        )

        val diagnostics = lint(source)

        assertTrue(diagnostics.any { it.lineIndex == 3 && it.message.contains("16-bit processor state") })
        assertTrue(diagnostics.any { it.lineIndex == 4 && it.message.contains("proven terminal return is RTL") }.not(),
            "JSL and RTL are a matching pair")
        assertTrue(diagnostics.any { it.lineIndex == 5 && it.message.contains("MissingRoutine") })
    }

    @Test
    fun `near and long call mismatch requires a uniquely resolved simple routine`() {
        val source = source(
            """
                Caller:
                    JSR LongRoutine
                    JSL NearRoutine
                    RTS
                LongRoutine:
                    RTL
                NearRoutine:
                    RTS
            """.trimIndent(),
        )

        val diagnostics = lint(source)

        assertEquals(2, diagnostics.count { it.message.contains("proven terminal return") })
        assertTrue(diagnostics.any { it.lineIndex == 1 && it.message.contains("RTL") })
        assertTrue(diagnostics.any { it.lineIndex == 2 && it.message.contains("RTS") })
    }

    @Test
    fun `warning-only validation does not masquerade as compiled output`() {
        val warning = AsmBuildDiagnostic(AsmDiagnosticSeverity.WARNING, "Review this", "bank_80.asm", 0)
        assertEquals(false, AsmSourceLinter.report(listOf(warning))?.succeeded)
        assertEquals(true, AsmSourceLinter.report(listOf(warning))?.validationOnly)
    }

    private fun source(bankText: String): Pair<AsmReferenceIndex, Map<String, String>> {
        val src = File(directory, "src").apply { mkdirs() }
        val data = File(directory, "data").apply { mkdirs() }
        val texts = mapOf(
            "main.asm" to "incsrc bank_80.asm\n",
            "bank_80.asm" to bankText,
        )
        texts.forEach { (id, text) -> File(src, id).writeText(text) }
        return AsmSourceParser().parse(src, data, emptyList(), sourceTexts = texts) to texts
    }

    private fun lint(source: Pair<AsmReferenceIndex, Map<String, String>>): List<AsmBuildDiagnostic> {
        val (index, texts) = source
        return AsmSourceLinter.lintText(
            "bank_80.asm",
            texts.getValue("bank_80.asm"),
            index.macros,
            AsmEditorAnalysisContext(index, texts),
        )
    }
}
