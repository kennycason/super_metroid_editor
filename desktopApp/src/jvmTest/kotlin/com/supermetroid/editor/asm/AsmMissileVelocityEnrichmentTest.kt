package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmMissileVelocityEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "missile-velocity."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "missile-velocity-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v31 adds missile launch and acceleration notes without changing frozen v30`() {
        val before = frozen(30); val revision = frozen(31)
        assertEquals(before, revision.copy(revision = before.revision,
            documentation = revision.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = revision.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(revision.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(revision.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, revision.documentation.count { it.id.startsWith(prefix) })
        assertEquals(3, groups.size); assertEquals(85, groups.sumOf { it.lines.size })
        assertEquals(51, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `missile velocity scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W MissileInitializedBitset", "ADC.W MissileInitializedBitset ; Launch step")
                .replace("JSR.W Spawn_SuperMissileLink", "JSR.W Spawn_SuperMissileLink" + if (addresses) " ;90B324;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Launch step | Advance the launch-delay/base-speed accumulator.")
            assertContains(output, if (addresses) ";90B324; Create the super missile's" else "; Create the super missile's")
        }
    }

    @Test fun `guards reject changed initialization direction category and acceleration pairs`() {
        val original = sources()
        for ((old, changed) in listOf(
            "STZ.B DP_Temp16" to "LDA.B DP_Temp16",
            "AND.W #${'$'}000F" to "AND.W #${'$'}00FF",
            "ADC.W MissileInitializedBitset" to "SBC.W MissileInitializedBitset",
            "BIT.W #${'$'}0200" to "BIT.W #${'$'}0100",
            "ADC.W ${'$'}0002,Y" to "ADC.W ${'$'}0004,Y",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented missile state covers delayed initialization and pair indexing`() {
        fun ticksUntilInitialized(initial: Int, increment: Int): Int {
            var value = initial; var ticks = 0
            while (value and 0xFF00 == 0) { value = (value + increment) and 0xFFFF; ticks++ }
            return ticks
        }
        assertEquals(4, ticksUntilInitialized(0, 0x40))
        assertEquals(listOf(0, 4, 8, 12, 16, 20, 24, 28, 32, 36), (0..9).map { it * 4 })
        fun accelerated(x: Int, y: Int, pair: Pair<Int, Int>) = x + pair.first to y + pair.second
        assertEquals(105 to -54, accelerated(100, -50, 5 to -4))
    }
}
