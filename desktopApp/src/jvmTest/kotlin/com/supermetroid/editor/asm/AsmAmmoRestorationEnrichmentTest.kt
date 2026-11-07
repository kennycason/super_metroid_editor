package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmAmmoRestorationEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "ammo-restoration."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(
        javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json")
    ).bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "ammo-restoration-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v19 adds ammunition restoration without changing frozen v18`() {
        val previous = frozen(18)
        val v19 = frozen(19)
        assertEquals("super-metroid-ntsc-19", v19.revision)
        assertEquals(previous, v19.copy(revision = previous.revision,
            documentation = v19.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v19.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v19.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v19.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, v19.documentation.count { it.id.startsWith(prefix) })
        assertEquals(3, groups.size)
        assertEquals(75, groups.sumOf { it.lines.size })
        assertEquals(36, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `ammo scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W Missiles", "ADC.W Missiles ; Pickup amount")
                .replace("CMP.W MaxMissiles", "CMP.W MaxMissiles" + if (addresses) " ;91DF8D;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.values.joinToString("\n")
            assertContains(output, "; Pickup amount | Add the requested amount to live missiles.")
            assertContains(output, if (addresses) ";91DF8D; Compare it with normal" else "; Compare it with normal")
        }
    }

    @Test fun `guards reject changed reserve and capacity contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}0063" to "CMP.W #${'$'}0064",
            "ADC.W ReserveMissiles" to "ADC.W Missiles",
            "CMP.W MaxSuperMissiles" to "CMP.W MaxMissiles",
            "CMP.W MaxPowerBombs" to "CMP.W MaxMissiles",
        )) {
            assertTrue(original.values.any { old in it })
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented ammunition clamping covers missile overflow and exact maxima`() {
        fun missiles(current: Int, reserve: Int, amount: Int, maximum: Int): Pair<Int, Int> {
            val total = current + amount
            if (total < maximum) return total to reserve
            return maximum to minOf(minOf(99, maximum), reserve + total - maximum)
        }
        assertEquals(45 to 7, missiles(40, 7, 5, 50))
        assertEquals(50 to 12, missiles(48, 10, 4, 50))
        assertEquals(120 to 99, missiles(119, 98, 5, 120))
        fun simple(current: Int, amount: Int, maximum: Int) = minOf(current + amount, maximum)
        assertEquals(10, simple(8, 2, 10))
        assertEquals(10, simple(9, 5, 10))
    }
}
