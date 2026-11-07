package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileTrailEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "projectile-trails."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "projectile-trails-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { note -> note.id.startsWith(prefix) && groups.any { it.symbol == note.symbol } },
        lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v37 adds projectile trail allocation interpretation drawing and freeze notes without changing frozen v36`() {
        val before = frozen(36); val revision = frozen(37)
        assertEquals(before, revision.copy(revision = before.revision,
            documentation = revision.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = revision.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(revision.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(revision.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(7, revision.documentation.count { it.id.startsWith(prefix) })
        assertEquals(5, groups.size); assertEquals(220, groups.sumOf { it.lines.size })
        assertEquals(80, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `trail scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W #${'$'}001F", "ADC.W #${'$'}001F ; Type base")
                .replace("PEA.W .loopLeft-1", "PEA.W .loopLeft-1" + if (addresses) " ;90B6D0;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Type base | Map categories 1/2 to trail types ${'$'}20/${'$'}21.")
            assertContains(output, if (addresses) ";90B6D0; Create a synthetic RTS return" else "; Create a synthetic RTS return")
        }
    }

    @Test fun `guards reject changed type capacity interpreter and OAM contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}0003" to "CMP.W #${'$'}0004",
            "ADC.W #${'$'}001F" to "ADC.W #${'$'}001E",
            "LDY.W #${'$'}0022" to "LDY.W #${'$'}0020",
            "PEA.W .loopLeft-1" to "PEA.W .loop-1",
            "CPX.W #${'$'}0200" to "CPX.W #${'$'}01FC",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented trails cover type mapping slots frames visibility and frozen state`() {
        fun trailType(type: Int): Int? = if (type and 0x0F00 == 0) type and 0x3F else
            ((type ushr 8) and 0xF).takeIf { it < 3 }?.plus(0x1F)
        assertEquals(0x11, trailType(0x8011)); assertEquals(0x20, trailType(0x8100)); assertEquals(0x21, trailType(0x8200))
        assertNull(trailType(0x8300)); assertEquals(18, (0x22 downTo 0 step 2).count())
        fun frame(word: Int) = when { word < 0 -> "command"; word == 0 -> "end"; else -> "duration" }
        assertEquals(listOf("command", "end", "duration"), listOf(frame(-1), frame(0), frame(4)))
        fun visible(value: Int) = value and 0xFF00 == 0
        assertTrue(visible(255)); assertFalse(visible(256)); assertFalse(visible(-1))
        fun tick(timer: Int, frozen: Boolean) = if (frozen) timer else timer - 1
        assertEquals(4, tick(4, true)); assertEquals(3, tick(4, false))
    }
}
