package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmSbaRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf("wave-sba-runtime.", "plasma-sba-runtime.", "spazer-sba-runtime.")
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "sba-runtime-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { selected(it.id) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    private fun verifyRevision(number: Int, prefix: String, documents: Int, scopes: Int, lines: Int, comments: Int) {
        val before = frozen(number - 1); val revision = frozen(number)
        assertEquals(before, revision.copy(revision = before.revision,
            documentation = revision.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = revision.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(revision.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(revision.lineDocumentation.filter { it.id.startsWith(prefix) }, current.lineDocumentation.filter { it.id.startsWith(prefix) })
        assertEquals(documents, revision.documentation.count { it.id.startsWith(prefix) })
        val added = revision.lineDocumentation.filter { it.id.startsWith(prefix) }
        assertEquals(scopes, added.size); assertEquals(lines, added.sumOf { it.lines.size })
        assertEquals(comments, added.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `v41 adds wave runtime without changing frozen v40`() = verifyRevision(41, prefixes[0], 5, 1, 112, 44)
    @Test fun `v42 adds plasma runtime without changing frozen v41`() = verifyRevision(42, prefixes[1], 5, 4, 81, 42)
    @Test fun `v43 adds spazer runtime without changing frozen v42`() = verifyRevision(43, prefixes[2], 8, 10, 193, 99)

    @Test fun `SBA runtime scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("CMP.W #${'$'}0800", "CMP.W #${'$'}0800" + if (addresses) " ;90DA4A;" else "")
                .replace("SBC.W #${'$'}0005", "SBC.W #${'$'}0005 ; Radius contraction")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Radius contraction | Contract inward by five pixels.")
            assertContains(output, if (addresses) ";90DA4A; Stop increasing" else "; Stop increasing")
        }
    }

    @Test fun `guards reject changed homing phase radius pair and viewport boundaries`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}F801" to "CMP.W #${'$'}F800",
            "JMP.W (.pointers,X)" to "JSR.W (.pointers,X)",
            "CMP.W #${'$'}00C0" to "CMP.W #${'$'}00BF",
            "JSR.W (.clearing,X)" to "JMP.W (.clearing,X)",
            "SBC.W #${'$'}0005" to "SBC.W #${'$'}0006",
            "CMP.W #${'$'}00F8" to "CMP.W #${'$'}00F7",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented SBA boundaries cover homing phases pair ownership and ending cull`() {
        fun negative16(value: Int) = (value and 0x8000) != 0
        fun steer(velocity: Int, targetAfterProjectile: Boolean) = when {
            targetAfterProjectile && negative16(velocity - 0x0800) -> (velocity + 0x40) and 0xFFFF
            !targetAfterProjectile && !negative16(velocity - 0xF801) -> (velocity - 0x40) and 0xFFFF
            else -> velocity
        }
        assertEquals(0x40, steer(0, true)); assertEquals(0x0800, steer(0x0800, true))
        assertEquals(0xFFC0, steer(0, false)); assertEquals(0xF7C1, steer(0xF801, false))
        fun plasmaPhase(radius: Int, phase: Int) = when (phase) {
            0 -> if ((radius + 4 and 0xFF) >= 0xC0) 1 else 0
            1 -> if ((radius - 4 and 0xFF) < 0x2D) 2 else 1
            else -> 2
        }
        assertEquals(0, plasmaPhase(0xB8, 0)); assertEquals(1, plasmaPhase(0xBC, 0))
        assertEquals(1, plasmaPhase(0x31, 1)); assertEquals(2, plasmaPhase(0x30, 1))
        val partners = mapOf(0 to 4, 2 to 6, 4 to 0, 6 to 2)
        assertEquals(listOf(4, 6, 0, 2), listOf(0, 2, 4, 6).map(partners::getValue))
        assertEquals(0, 0xA0 - 32 * 5); assertEquals(0x64, 20 * 5)
        assertTrue(0xF7 < 0xF8); assertFalse(0xF8 < 0xF8)
    }
}
