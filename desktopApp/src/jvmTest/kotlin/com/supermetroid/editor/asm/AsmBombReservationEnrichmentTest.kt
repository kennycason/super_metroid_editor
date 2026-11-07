package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmBombReservationEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "bomb-reservation."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "bomb-reservation-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v26 adds bomb reservations and spread gating without changing frozen v25`() {
        val previous = frozen(25); val v26 = frozen(26)
        assertEquals("super-metroid-ntsc-26", v26.revision)
        assertEquals(previous, v26.copy(revision = previous.revision,
            documentation = v26.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v26.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v26.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v26.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, v26.documentation.count { it.id.startsWith(prefix) })
        assertEquals(2, groups.size); assertEquals(54, groups.sumOf { it.lines.size })
        assertEquals(35, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `reservation scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("BIT.W ShotBinding", "BIT.W ShotBinding ; Shot edge")
                .replace("BIT.W #${'$'}1000", "BIT.W #${'$'}1000" + if (addresses) " ;90C0AE;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Shot edge | Require a new configured-shot edge.")
            assertContains(output, if (addresses) ";90C0AE; Test Morph Ball" else "; Test Morph Ball")
        }
    }

    @Test fun `guards reject changed equipment charge timeout capacity and cooldown contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "BIT.W #${'$'}1000" to "BIT.W #${'$'}0800",
            "CMP.W #${'$'}003C" to "CMP.W #${'$'}003B",
            "AND.W #${'$'}00C0" to "AND.W #${'$'}0080",
            "CMP.W #${'$'}0005" to "CMP.W #${'$'}0004",
            "AND.W #${'$'}00FF" to "AND.W #${'$'}FFFF",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented spread and reservation gates cover their boundaries`() {
        fun spread(charge: Int, bombs: Int, heldGate: Boolean, timeout: Int) =
            charge >= 60 && bombs == 0 && (!heldGate || timeout.and(0xC0) == 0xC0)
        assertFalse(spread(59, 0, false, 0)); assertFalse(spread(60, 1, false, 0))
        assertFalse(spread(60, 0, true, 0xBF)); assertTrue(spread(60, 0, true, 0xC0))
        fun canReserve(count: Int, cooldown: Int) = count == 0 || (count < 5 && cooldown.and(0xFF) == 0)
        assertTrue(canReserve(0, 9)); assertTrue(canReserve(4, 0)); assertFalse(canReserve(5, 0))
    }
}
