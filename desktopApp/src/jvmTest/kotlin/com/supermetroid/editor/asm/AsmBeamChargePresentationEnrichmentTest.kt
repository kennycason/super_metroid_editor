package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmBeamChargePresentationEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "beam-charge-presentation."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun previous() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-29.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun revision() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-30.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "beam-charge-presentation-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v30 adds charge and flare presentation notes without changing frozen v29`() {
        val v29 = previous()
        val v30 = revision()
        assertEquals(v29, v30.copy(revision = v29.revision,
            documentation = v30.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v30.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v30.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v30.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(7, v30.documentation.count { it.id.startsWith(prefix) })
        assertEquals(3, groups.size); assertEquals(227, groups.sumOf { it.lines.size })
        assertEquals(83, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `charge presentation preserves statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("CMP.W #${'$'}00FF", "CMP.W #${'$'}00FF ; Restart sentinel")
                .replace("CMP.W #${'$'}00FE", "CMP.W #${'$'}00FE" + if (addresses) " ;90BB74;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Restart sentinel | ${'$'}FF restarts at frame zero.")
            assertContains(output, if (addresses) ";90BB74; ${'$'}FE moves backward" else "; ${'$'}FE moves backward")
        }
    }

    @Test fun `guards reject changed thresholds sentinels component stride and visibility page`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}000F" to "CMP.W #${'$'}000E",
            "CMP.W #${'$'}001E" to "CMP.W #${'$'}001D",
            "CMP.W #${'$'}00FF" to "CMP.W #${'$'}00FD",
            "CMP.W #${'$'}00FE" to "CMP.W #${'$'}00FC",
            "CPX.W #${'$'}0006" to "CPX.W #${'$'}0004",
            "AND.W #${'$'}FF00" to "AND.W #${'$'}FFFF",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented flare scripts cover thresholds components and visibility`() {
        fun components(charge: Int) = if (charge < 15) emptyList() else if (charge < 30) listOf(0) else listOf(0, 2, 4)
        assertEquals(emptyList(), components(14)); assertEquals(listOf(0), components(15)); assertEquals(listOf(0, 2, 4), components(30))
        fun advance(frame: Int, script: List<Int>): Int = when (script[frame + 1]) {
            0xFF -> 0
            0xFE -> frame + 1 - script[frame + 2]
            else -> frame + 1
        }
        assertEquals(0, advance(0, listOf(3, 0xFF)))
        assertEquals(0, advance(2, listOf(0, 0, 3, 0xFE, 3)))
        fun visible(screenY: Int) = screenY and 0xFF00 == 0
        assertTrue(visible(0xFF)); assertFalse(visible(0x100))
        assertEquals(listOf(4, 2, 0), (4 downTo 0 step 2).toList())
    }
}
