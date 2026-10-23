package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmEnemyRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("enemy-runtime.") }
    private fun recipe() = current.copy(revision = "enemy-runtime-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("enemy-runtime.") }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File = File(temporary, "src").apply {
        mkdirs()
        File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v8 adds enemy scheduling and script notes without changing the exact frozen v7 recipe`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-7.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-8", current.revision)
        assertEquals(previous, current.copy(revision = previous.revision,
            documentation = current.documentation.filterNot { it.id.startsWith("enemy-runtime.") },
            lineDocumentation = current.lineDocumentation.filterNot { it.id.startsWith("enemy-runtime.") }))
        assertEquals(23, current.documentation.count { it.id.startsWith("enemy-runtime.") })
        assertEquals(17, groups.size)
        assertEquals(302, groups.flatMap { it.lines }.count { it.comment != null })
        val prose = current.documentation.filter { it.id.startsWith("enemy-runtime.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all runtime and both bank-local command scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("JML.W [EnemyAIPointer]", "JML.W [EnemyAIPointer] ; Execute enemy AI pointer")
                .replace("DEC.W Enemy.instTimer,X", "DEC.W Enemy.instTimer,X" + if (addresses) " ;A0C276;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(result.getValue("bank_A0.asm"), "; Execute enemy AI pointer | Tail-jump to AI;")
            assertContains(result.getValue("bank_A0.asm"), if (addresses) ";A0C276; Pre-decrement" else "; Pre-decrement")
            groups.forEach { group -> group.lines.mapNotNull { it.comment }.forEach { assertContains(result.getValue(group.fileId), it) } }
            assertContains(result.getValue("bank_A3.asm"), "Instruction_CommonA3_Sleep:")
        }
    }

    @Test fun `guards reject changed eligibility flags selector priority script dispatch and bank-local return frames`() {
        val original = sources()
        for ((old, changed) in listOf(
            "BIT.W #\$0800" to "BIT.W #\$0400",
            "BIT.W #\$0400" to "BIT.W #\$0200",
            "ADC.W #\$00F8" to "ADC.W #\$00E0",
            "LDA.W \$0020,Y" to "LDA.W \$001E,Y",
            "INX\n    LSR\n    BCS +" to "LSR\n    INX\n    BCS +",
            "LDA.W \$0018,X" to "LDA.W \$001A,X",
            "AND.W #\$0004" to "AND.W #\$0002",
            "BPL .timer" to "BMI .timer",
            "PEA.W .loop-1" to "PEA.W .loop",
            "PEA.W ProcessEnemyInstructions_return-1" to "PEA.W ProcessEnemyInstructions_return",
            "AND.W #\$7FFF" to "ORA.W #\$8000",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
        val root = source(original.mapValues { (file, text) -> if (file == "bank_A3.asm") text.replace("PEA.W ProcessEnemyInstructions_return-1", "PEA.W ProcessEnemyInstructions_return") else text })
        assertFailsWith<IllegalArgumentException>("The \$A3 copies need their own complete guards") { recipe().apply(root) }
    }

    @Test fun `documented selector priority and predecrement arithmetic cover zero all bits and word wraparound`() {
        // Arithmetic contracts, not an execution of the CPU. The native gate pins the actual shifts/branches and header offsets.
        fun shiftedIndex(flags: Int): Int {
            if (flags == 0) return 0
            var bits = flags
            var index = 0
            do { index++; val carry = bits and 1; bits = bits ushr 1 } while (carry == 0)
            return index
        }
        assertEquals(0x18, 0x18 + 2 * shiftedIndex(0))
        for (flags in 1..0xFFFF) {
            val expected = 1 + Integer.numberOfTrailingZeros(flags)
            assertEquals(expected, shiftedIndex(flags), "Flags $flags")
        }
        for ((timer, next) in listOf(0 to 0xFFFF, 1 to 0, 2 to 1, 0xFFFF to 0xFFFE))
            assertEquals(next, (timer - 1) and 0xFFFF)
        assertEquals(listOf(0x1A, 0x1C, 0x1E, 0x20), listOf(1, 2, 4, 8).map { 0x18 + 2 * shiftedIndex(it) })
    }
}
