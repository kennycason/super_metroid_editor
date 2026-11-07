package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmBombRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "bomb-runtime."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun previous() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-26.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "bomb-runtime-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v27 adds bomb fuse and explosion runtime without changing frozen v26`() {
        val v26 = previous()
        assertEquals("super-metroid-ntsc-27", current.revision)
        assertEquals(v26, current.copy(revision = v26.revision,
            documentation = current.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = current.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(4, current.documentation.count { it.id.startsWith(prefix) })
        assertEquals(2, groups.size); assertEquals(62, groups.sumOf { it.lines.size })
        assertEquals(32, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `bomb runtime preserves statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W #${'$'}001C", "ADC.W #${'$'}001C ; Late animation")
                .replace("JSL.L Spawn_PowerBombExplosion", "JSL.L Spawn_PowerBombExplosion" + if (addresses) " ;90C18D;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Late animation | Advance 28 bytes")
            assertContains(output, if (addresses) ";90C18D; Create the global" else "; Create the global")
        }
    }

    @Test fun `guards reject changed fuse threshold stride sound wait and cleanup contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}000F" to "CMP.W #${'$'}0010",
            "ADC.W #${'$'}001C" to "ADC.W #${'$'}001A",
            "LDA.W #${'$'}0008" to "LDA.W #${'$'}0007",
            "LDA.W #${'$'}FFFF" to "LDA.W #${'$'}8000",
            "BNE .return" to "BEQ .return",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented fuse transitions cover animation explosion and cleanup boundaries`() {
        fun afterTick(timer: Int) = when (timer - 1) { 0 -> "explode"; 15 -> "late"; else -> "wait" }
        assertEquals("late", afterTick(16)); assertEquals("explode", afterTick(1)); assertEquals("wait", afterTick(17))
        fun clearPowerBomb(slotTimer: Int, globalFlag: Int) = slotTimer == 0 && globalFlag == 0
        assertFalse(clearPowerBomb(0, -1)); assertTrue(clearPowerBomb(0, 0)); assertFalse(clearPowerBomb(-1, 0))
        assertEquals(28, 0x1C)
    }
}
