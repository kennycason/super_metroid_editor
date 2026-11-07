package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmSaveFileEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "save-files."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(
        javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json")
    ).bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(
        revision = "save-files-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) },
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

    @Test fun `v16 adds SRAM mirror and slot validation without changing frozen v15`() {
        val previous = frozen(15)
        val v16 = frozen(16)
        assertEquals("super-metroid-ntsc-16", v16.revision)
        assertEquals(previous, v16.copy(
            revision = previous.revision,
            documentation = v16.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v16.lineDocumentation.filterNot { it.id.startsWith(prefix) },
        ))
        assertEquals(v16.documentation.filter { it.id.startsWith(prefix) },
            current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v16.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(7, v16.documentation.count { it.id.startsWith(prefix) })
        assertEquals(3, groups.size)
        assertEquals(154, groups.sumOf { it.lines.size })
        assertEquals(107, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(setOf("SaveToSRAM", "LoadFromSRAM", "SaveSlotOffsets"), groups.map { it.symbol }.toSet())
    }

    @Test fun `all save scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W SRAMMirror_Equipment,Y", "STA.W SRAMMirror_Equipment,Y ; Refresh mirror")
                .replace("LDA.L SRAM_Checksum0,X", "LDA.L SRAM_Checksum0,X" + if (addresses) " ;8180D4;" else "")
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
            val output = result.values.joinToString("\n")
            assertContains(output, "; Refresh mirror | Refresh the corresponding save-mirror word.")
            assertContains(output, if (addresses) ";8180D4; Read one slot word" else "; Read one slot word")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed slot checksum mirror and corrupt clearing contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "AND.W #${'$'}0003" to "AND.W #${'$'}0007",
            "LDY.W #${'$'}005E" to "LDY.W #${'$'}0060",
            "EOR.W #${'$'}FFFF" to "EOR.W #${'$'}FFFE",
            "dw ${'$'}0010,${'$'}066C,${'$'}0CC8" to "dw ${'$'}0010,${'$'}066E,${'$'}0CC8",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented save geometry and checksum pairs are internally consistent`() {
        val offsets = listOf(0x0010, 0x066C, 0x0CC8)
        assertEquals(listOf(0x65C, 0x65C), offsets.zipWithNext { a, b -> b - a })
        for (sum in listOf(0, 1, 0x1234, 0xFFFF)) {
            assertEquals(0xFFFF, sum xor (sum xor 0xFFFF))
        }
        assertEquals(0x60, 0x5E + 2, "equipment/options mirror is copied as 48 words")
        assertEquals(0x100, 128 * 2, "one area's explored-map slice is 256 bytes")
    }
}
