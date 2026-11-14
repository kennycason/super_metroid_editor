package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmCompiledOutputInspectorTest {
    @TempDir lateinit var directory: File

    @Test
    fun `selected statement resolves exact bytes comparisons ownership symbols headroom and references`() {
        val sourceText = """
            Target:
                NOP
            Caller:
                JSR Target
            Next:
                RTS
        """.trimIndent()
        val index = index(sourceText)
        val symbols = AsmCompiledSymbols(
            labels = mapOf("Target" to 0x808000, "Caller" to 0x808001, "Next" to 0x808004),
            lineAddressesByFile = mapOf("bank_80.asm" to intArrayOf(
                0x808000, 1,
                0x808001, 3,
                0x808004, 5,
            )),
            emittedLocations = listOf(
                AsmCompiledLocation("bank_80.asm", 1, 0x808000),
                AsmCompiledLocation("bank_80.asm", 3, 0x808001),
                AsmCompiledLocation("bank_80.asm", 5, 0x808004),
            ),
        )
        val baseline = ByteArray(0x8000) { 0xFF.toByte() }.also {
            it[1] = 0x20
            it[2] = 0x00
            it[3] = 0x80.toByte()
        }
        val loaded = baseline.copyOf().also { it[2] = 0x01 }
        val plan = RomWritePlan(loaded).also {
            it.add("asm-source:project", "Compiled bank_80.asm", 1, listOf(0x20, 0x00, 0x80), RomWriteKind.ASM_SOURCE)
        }
        val preview = AsmRomPreview.create(
            loadedRom = loaded,
            baselineRom = baseline,
            resultRom = plan.finalRom(),
            headerSize = 0,
            writeReport = plan.report(),
        )
        val space = AsmRomSpacePlan(
            romBodySize = 0x8000,
            banks = listOf(
                AsmRomSpaceBank(0x80, 0, 0x8000, 0x100, 0x80, 0xFF, emptyList(), emptyList(), largestAfter = 0x60),
            ),
        )

        val inspection = AsmCompiledOutputResolver.resolve(
            fileId = "bank_80.asm",
            lineIndex = 3,
            sourceText = sourceText,
            index = index,
            symbols = symbols,
            preview = preview,
            spacePlan = space,
            stale = false,
            diagnostics = listOf(AsmBuildDiagnostic(AsmDiagnosticSeverity.WARNING, "Example", "bank_80.asm", 3)),
        )

        assertEquals(AsmCompiledOutputStatus.CURRENT, inspection.status)
        assertEquals(3, inspection.emittedByteCount)
        assertEquals(0x808001, inspection.ranges.single().snesStart)
        assertContentEquals(listOf(0x20, 0x00, 0x80), inspection.ranges.single().baselineBytes)
        assertContentEquals(listOf(0x20, 0x01, 0x80), inspection.ranges.single().loadedBytes)
        assertContentEquals(listOf(0x20, 0x00, 0x80), inspection.ranges.single().resultBytes)
        assertTrue(inspection.changedFromLoaded)
        assertFalse(inspection.changedFromBaseline)
        assertEquals(listOf("Caller"), inspection.symbols.map { it.first })
        assertEquals("asm-source:project", inspection.owners.single().owner)
        assertEquals(0x60, inspection.bank?.largestAfter)
        assertEquals("Target", inspection.outgoingReferences.single().name)
        assertEquals("Example", inspection.diagnostics.single().message)
    }

    @Test
    fun `label-only statements remain useful and expose incoming references`() {
        val sourceText = """
            Target:
                NOP
            Caller:
                JSR Target
        """.trimIndent()
        val index = index(sourceText)
        val symbols = AsmCompiledSymbols(
            labels = mapOf("Target" to 0x808000),
            lineAddressesByFile = mapOf("bank_80.asm" to intArrayOf(0x808000, 1)),
        )

        val inspection = AsmCompiledOutputResolver.resolve(
            "bank_80.asm", 0, sourceText, index, symbols, preview = null, spacePlan = null,
            stale = false, diagnostics = emptyList(),
        )

        assertEquals(AsmCompiledOutputStatus.BUILD_REQUIRED, inspection.status)
        assertTrue(inspection.ranges.isEmpty())
        assertEquals("Target", inspection.incomingReferences.single().name)
        assertEquals(3, inspection.incomingReferences.single().lineIndex)
        assertNull(inspection.allocation)
    }

    @Test
    fun `stale output is explicit instead of being presented as current`() {
        val sourceText = "    NOP\n    RTS"
        val index = index(sourceText)
        val symbols = AsmCompiledSymbols(emptyMap(), mapOf("bank_80.asm" to intArrayOf(0x808000, 0, 0x808001, 1)))
        val bytes = byteArrayOf(0xEA.toByte(), 0x60)
        val preview = AsmRomPreview.create(bytes, bytes, 0, RomWritePlan(bytes).report())

        val inspection = AsmCompiledOutputResolver.resolve(
            "bank_80.asm", 0, sourceText, index, symbols, preview, null,
            stale = true, diagnostics = emptyList(),
        )

        assertEquals(AsmCompiledOutputStatus.STALE, inspection.status)
        assertEquals(listOf(0xEA), inspection.ranges.single().resultBytes)
    }

    private fun index(sourceText: String): AsmReferenceIndex {
        val source = File(directory, "src").apply { mkdirs() }
        val data = File(directory, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_80.asm\n")
        File(source, "bank_80.asm").writeText(sourceText)
        return AsmSourceParser().parse(source, data, emptyList())
    }
}

