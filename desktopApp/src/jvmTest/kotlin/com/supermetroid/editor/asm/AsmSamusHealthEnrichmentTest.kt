package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmSamusHealthEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "samus-health."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(
        javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json")
    ).bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(
        revision = "samus-health-test",
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

    @Test fun `v17 adds health damage reserve and game-time notes without changing frozen v16`() {
        val previous = frozen(16)
        val v17 = frozen(17)
        assertEquals("super-metroid-ntsc-17", v17.revision)
        assertEquals(previous, v17.copy(
            revision = previous.revision,
            documentation = v17.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v17.lineDocumentation.filterNot { it.id.startsWith(prefix) },
        ))
        assertEquals(v17.documentation.filter { it.id.startsWith(prefix) },
            current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v17.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(9, v17.documentation.count { it.id.startsWith(prefix) })
        assertEquals(7, groups.size)
        assertEquals(285, groups.sumOf { it.lines.size })
        assertEquals(157, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(setOf("bank_82.asm", "bank_90.asm", "bank_91.asm"), groups.map { it.fileId }.toSet())
    }

    @Test fun `all health scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.B DP_Temp12", "ADC.B DP_Temp12 ; Restore request")
                .replace("LDA.W PeriodicSubDamage+1",
                    "LDA.W PeriodicSubDamage+1" + if (addresses) " ;90E9E6;" else "")
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
            assertContains(output, "; Restore request | Add the pickup or restoration amount.")
            assertContains(output, if (addresses) ";90E9E6; Read the packed" else "; Read the packed")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed damage suit warning clock and refill contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}012C" to "CMP.W #${'$'}012D",
            "BIT.W #${'$'}0020" to "BIT.W #${'$'}0040",
            "CMP.W #${'$'}001F" to "CMP.W #${'$'}0020",
            "CMP.W #${'$'}003C*!FPS" to "CMP.W #${'$'}003B*!FPS",
            "BIT.W #${'$'}0007" to "BIT.W #${'$'}0003",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented health arithmetic covers overflow suits borrow and clock caps`() {
        fun restore(energy: Int, reserve: Int, amount: Int, maxEnergy: Int, maxReserve: Int): Pair<Int, Int> {
            val total = energy + amount
            return if (total < maxEnergy) total to reserve
            else maxEnergy to minOf(maxReserve, reserve + total - maxEnergy)
        }
        assertEquals(99 to 0, restore(90, 0, 9, 99, 400))
        assertEquals(99 to 16, restore(95, 12, 8, 99, 400))
        assertEquals(99 to 400, restore(98, 399, 9, 99, 400))
        assertEquals(listOf(0x10000, 0x8000, 0x4000), listOf(0, 1, 2).map { 0x10000 shr it })
        assertEquals(9, 10 - 0 - 1, "fractional subtraction borrow also reaches whole energy")
        assertEquals(listOf(59, 59, 59, 99), listOf(0x3B, 0x3B, 0x3B, 0x63))
    }
}
