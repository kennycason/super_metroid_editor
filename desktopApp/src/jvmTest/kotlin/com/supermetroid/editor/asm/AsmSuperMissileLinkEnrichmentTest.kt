package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmSuperMissileLinkEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "super-missile-link."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "super-missile-link-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v33 adds linked super collision notes without changing frozen v32`() {
        val before = frozen(32); val revision = frozen(33)
        assertEquals(before, revision.copy(revision = before.revision,
            documentation = revision.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = revision.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(revision.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(revision.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, revision.documentation.count { it.id.startsWith(prefix) })
        assertEquals(2, groups.size); assertEquals(153, groups.sumOf { it.lines.size })
        assertEquals(62, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `linked collision scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("CMP.W #${'$'}0B00", "CMP.W #${'$'}0B00 ; Fast threshold")
                .replace("SBC.W #${'$'}000A", "SBC.W #${'$'}000A" + if (addresses) " ;90B39A;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Fast threshold | Fast-link probing begins at 11 pixels per frame.")
            assertContains(output, if (addresses) ";90B39A; Convert speed" else "; Convert speed")
        }
    }

    @Test fun `guards reject changed categories initialization threshold offset and cleanup`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}0200" to "CMP.W #${'$'}0100",
            "CMP.W #${'$'}0800" to "CMP.W #${'$'}0700",
            "BIT.W #${'$'}FF00" to "BIT.W #${'$'}00FF",
            "CMP.W #${'$'}0B00" to "CMP.W #${'$'}0A00",
            "SBC.W #${'$'}000A" to "SBC.W #${'$'}0009",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented link behavior covers speed boundary and explosion cleanup`() {
        fun fast(speed: Int) = kotlin.math.abs(speed) and 0xFF00 >= 0x0B00
        assertFalse(fast(0x0AFF)); assertTrue(fast(0x0B00)); assertTrue(fast(-0x0B00))
        fun offset(speed: Int) = (kotlin.math.abs(speed) ushr 8) - 10
        assertEquals(1, offset(0x0B00)); assertEquals(6, offset(0x1000))
        fun linkAction(category: Int, isFast: Boolean) = if (category == 0x0800) "clear" else if (isFast) "probe" else "sync"
        assertEquals("clear", linkAction(0x0800, false)); assertEquals("probe", linkAction(0x0200, true)); assertEquals("sync", linkAction(0x0200, false))
    }
}
