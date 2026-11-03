package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmPlmRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("plm-runtime.") }
    private fun recipe() = current.copy(revision = "plm-runtime-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("plm-runtime.") }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs()
        File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v9 adds PLM runtime and item lifecycle notes without changing the exact frozen v8 recipe`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-8.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-9", current.revision)
        assertEquals(previous, current.copy(revision = previous.revision,
            documentation = current.documentation.filterNot { it.id.startsWith("plm-runtime.") },
            lineDocumentation = current.lineDocumentation.filterNot { it.id.startsWith("plm-runtime.") }))
        assertEquals(10, current.documentation.count { it.id.startsWith("plm-runtime.") })
        assertEquals(9, groups.size)
        assertEquals(103, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(172, groups.sumOf { it.lines.size })
        val prose = current.documentation.filter { it.id.startsWith("plm-runtime.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all PLM scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("JMP.W (DP_Temp12)", "JMP.W (DP_Temp12) ; Execute PLM command")
                .replace("DEC\n", "DEC" + if (addresses) " ;8485E4;\n" else "\n")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.getValue("bank_84.asm")
            assertContains(output, "; Execute PLM command | Dispatch the bank-\$84 command;")
            assertContains(output, if (addresses) ";8485E4; Pre-decrement" else "; Pre-decrement")
            groups.forEach { group -> group.lines.mapNotNull { it.comment }.forEach { assertContains(output, it) } }
        }
    }

    @Test fun `guards reject changed slot bounds coordinate math dispatch frames item bits and pickup routing`() {
        val original = sources()
        for ((old, changed) in listOf(
            "LDY.W #\$004E" to "LDY.W #\$004C",
            "STA.W \$4204" to "STA.W \$4202",
            "BIT.W PLM_Flag" to "LDA.W PLM_Flag",
            "BPL .nonZeroTimer" to "BMI .nonZeroTimer",
            "PEA.W .loop-1" to "PEA.W .loop",
            "STZ.W PLM_IDs,X" to "STZ.W PLM_BlockIndices,X",
            "LDA.L SRAMMirror_Items,X" to "LDA.L SRAMMirror_Doors,X",
            "dw InstList_PLM_MissileTank_3" to "dw InstList_PLM_MissileTank_2",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented slot coordinate timer and item bit arithmetic covers boundaries`() {
        val slots = generateSequence(0x4E) { (it - 2).takeIf { next -> next >= 0 } }.toList()
        assertEquals(40, slots.size)
        assertEquals((0..0x4E step 2).toList().reversed(), slots)
        for (width in listOf(1, 16, 63, 255)) for (y in listOf(0, 1, 31, 255)) for (x in listOf(0, width - 1)) {
            val byteOffset = (y * width + x) * 2
            val block = byteOffset / 2
            assertEquals(y, block / width)
            assertEquals(x, block % width)
        }
        for ((timer, next) in listOf(0 to 0xFFFF, 1 to 0, 2 to 1, 0xFFFF to 0xFFFE))
            assertEquals(next, (timer - 1) and 0xFFFF)
        for (bit in 0..511) {
            assertEquals(bit / 8, bit ushr 3)
            assertEquals(1 shl (bit and 7), 1 shl (bit % 8))
        }
    }
}
