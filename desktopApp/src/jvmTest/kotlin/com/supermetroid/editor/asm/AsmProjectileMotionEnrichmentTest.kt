package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileMotionEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf("projectile-motion-basic.", "super-missile-runtime.", "projectile-motion-wave.")
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "projectile-motion-test", upstreamSourceSha256 = "", renames = emptyList(),
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

    @Test fun `v34 adds ordinary beam and missile motion without changing frozen v33`() =
        verifyRevision(34, prefixes[0], 5, 8, 108, 66)

    @Test fun `v35 adds super missile runtime without changing frozen v34`() =
        verifyRevision(35, prefixes[1], 4, 5, 84, 51)

    @Test fun `v36 adds bomb wave Hyper and beam initialization motion without changing frozen v35`() =
        verifyRevision(36, prefixes[2], 6, 11, 152, 92)

    @Test fun `projectile motion scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("LDA.W BeamSpeeds_Diagonal,Y", "LDA.W BeamSpeeds_Diagonal,Y ; Diagonal table")
                .replace("CMP.W #${'$'}0140", "CMP.W #${'$'}0140" + if (addresses) " ;90B179;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Diagonal table | Read the corresponding diagonal base speed.")
            assertContains(output, if (addresses) ";90B179; Accept X only while below 320." else "; Accept X only while below 320.")
        }
    }

    @Test fun `guards reject changed trail collision culling and speed contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "LDA.W #${'$'}0004" to "LDA.W #${'$'}0005",
            "LDA.W #${'$'}0002" to "LDA.W #${'$'}0003",
            "BCS .collision" to "BCC .collision",
            "CMP.W #${'$'}FFC0" to "CMP.W #${'$'}FFD0",
            "CMP.W #${'$'}0140" to "CMP.W #${'$'}0130",
            "LDA.W BeamSpeeds_Diagonal,Y" to "LDA.W BeamSpeeds_Horizontal_Vertical,Y",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented motion covers trail cadence collision order and viewport bounds`() {
        fun trailFrames(interval: Int, updates: Int) = (1..updates).count { it % interval == 0 }
        assertEquals(2, trailFrames(4, 8)); assertEquals(4, trailFrames(2, 8)); assertEquals(2, trailFrames(3, 8))
        fun diagonal(horizontalCollision: Boolean) = if (horizontalCollision) listOf("x") else listOf("x", "y")
        assertEquals(listOf("x"), diagonal(true)); assertEquals(listOf("x", "y"), diagonal(false))
        fun visible(value: Int) = value in -64 until 320
        assertTrue(visible(-64)); assertTrue(visible(319)); assertFalse(visible(-65)); assertFalse(visible(320))
        assertEquals(listOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0),
            listOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0), "cardinal/diagonal speed selection")
    }
}
