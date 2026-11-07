package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmNmiPresentationEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("nmi-presentation.") }
    private fun recipe() = current.copy(
        revision = "nmi-presentation-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("nmi-presentation.") },
        lineDocumentation = groups,
    )
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File =
        File(temporary, "src-${temporary.list().orEmpty().size}").apply {
            mkdirs()
            File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
            texts.forEach { (file, text) -> File(this, file).writeText(text) }
        }

    @Test fun `v14 adds the NMI presentation pipeline without changing frozen v13`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-13.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v14 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-14.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-14", v14.revision)
        assertEquals(previous, v14.copy(
            revision = previous.revision,
            documentation = v14.documentation.filterNot { it.id.startsWith("nmi-presentation.") },
            lineDocumentation = v14.lineDocumentation.filterNot { it.id.startsWith("nmi-presentation.") },
        ))
        val frozenGroups = v14.lineDocumentation.filter { it.id.startsWith("nmi-presentation.") }
        assertEquals(v14.documentation.filter { it.id.startsWith("nmi-presentation.") },
            current.documentation.filter { it.id.startsWith("nmi-presentation.") })
        assertEquals(frozenGroups, groups)
        assertEquals(9, v14.documentation.count { it.id.startsWith("nmi-presentation.") })
        assertEquals(5, frozenGroups.size)
        assertEquals(275, frozenGroups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(296, frozenGroups.sumOf { it.lines.size })
        assertEquals(setOf("bank_80.asm"), frozenGroups.map { it.fileId }.toSet())
        assertEquals(setOf(
            "SetupHDMATransfer",
            "Update_IO_Registers",
            "UpdateOAM_CGRAM",
            "TransferSamusTilesToVRAM",
            "ProcessAnimatedTilesObjectVRAMTransfers",
        ), frozenGroups.map { it.symbol }.toSet())
        val prose = v14.documentation.filter { it.id.startsWith("nmi-presentation.") }.flatMap { it.paragraphs } +
            frozenGroups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all NMI presentation scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W ${'$'}4300,X", "STA.W ${'$'}4300,X ; Configure selected channel")
                .replace("LDX.B DP_IRQAutoJoy", "LDX.B DP_IRQAutoJoy" + if (addresses) " ;8091EE;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature)
                    .filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.getValue("bank_80.asm")
            assertContains(output, "; Configure selected channel | Set DMAPx and BBADx")
            assertContains(output, if (addresses) ";8091EE; Read the NMI" else "; Read the NMI")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("nmi-presentation.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed descriptor PPU OAM Samus and animated tile contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "ADC.W #${'$'}0008" to "ADC.W #${'$'}0009",
            "STX.W ${'$'}4200" to "STX.W ${'$'}4201",
            "LDA.W #${'$'}0220" to "LDA.W #${'$'}0200",
            "LDA.W #${'$'}6100" to "LDA.W #${'$'}6200",
            "LDX.B #${'$'}0A" to "LDX.B #${'$'}08",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented DMA geometry covers inline channel OAM Samus and object boundaries`() {
        assertEquals(8, 1 + 1 + 1 + 3 + 2, "The inline descriptor has eight bytes after JSL")
        assertEquals((0..7).map { it * 0x10 }, listOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70))
        assertEquals(0x220, 0x200 + 0x20, "OAM low and high tables form one upload")
        assertEquals(0x200, 256 * 2, "CGRAM contains 256 two-byte colours")
        assertEquals(listOf(0x6000, 0x6100, 0x6080, 0x6180),
            listOf(0x6000, 0x6100, 0x6080, 0x6180).distinct())
        assertEquals(listOf(0x0A, 0x08, 0x06, 0x04, 0x02, 0x00),
            generateSequence(0x0A) { (it - 2).takeIf { next -> next >= 0 } }.toList())
    }
}
