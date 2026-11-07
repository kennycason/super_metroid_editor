package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmXrayGrappleEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf("xray-runtime.", "grapple-control.", "grapple-launch.", "grapple-exit.")
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "xray-grapple-test", upstreamSourceSha256 = "", renames = emptyList(),
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

    @Test fun `v54 adds X-ray runtime without changing frozen v53`() = verifyRevision(54, prefixes[0], 9, 5, 402, 102)
    @Test fun `v55 adds grapple control without changing frozen v54`() = verifyRevision(55, prefixes[1], 10, 8, 239, 82)
    @Test fun `v56 adds grapple launch without changing frozen v55`() = verifyRevision(56, prefixes[2], 5, 2, 209, 58)
    @Test fun `v57 adds grapple exits without changing frozen v56`() = verifyRevision(57, prefixes[3], 6, 4, 334, 69)

    @Test fun `X-ray and grapple scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W TimeIsFrozenFlag", "STA.W TimeIsFrozenFlag" + if (addresses) " ;91E21A;" else "")
                .replace("STA.W GrappleBeam_LengthDelta", "STA.W GrappleBeam_LengthDelta ; Existing grapple note")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, if (addresses) ";91E21A; Freeze gameplay" else "; Freeze gameplay")
            assertContains(output, "; Existing grapple note | Reverse length delta")
        }
    }

    @Test fun `guards reject changed X-ray gates grapple attachment and momentum boundaries`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}0008" to "CMP.W #${'$'}0009",
            "LDX.W #${'$'}01FE" to "LDX.W #${'$'}01FC",
            "CMP.W #${'$'}0080" to "CMP.W #${'$'}007F",
            "LDA.W #${'$'}001E" to "LDA.W #${'$'}001D",
            "LDA.W #${'$'}000C" to "LDA.W #${'$'}000B",
            "STA.W SamusXBaseSpeed" to "STA.W SamusXBaseSubSpeed",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented boundaries cover X-ray eligibility and grapple state transitions`() {
        fun xrayEligible(gameState: Int, verticalSpeed: Int, movementClass: Int, powerBombActive: Boolean) =
            gameState == 8 && verticalSpeed == 0 && movementClass != 0 && !powerBombActive
        assertTrue(xrayEligible(8, 0, 1, false)); assertFalse(xrayEligible(7, 0, 1, false))
        assertFalse(xrayEligible(8, 1, 1, false)); assertFalse(xrayEligible(8, 0, 0, false))
        assertEquals(listOf(0xD5, 0xD6, 0xD9, 0xDA), listOf(false, true).flatMap { crouching ->
            listOf(false, true).map { left -> if (crouching) if (left) 0xDA else 0xD9 else if (left) 0xD6 else 0xD5 }
        })
        fun grappleRelease(velocity: Int, angle: Int) = if (velocity == 0 && angle == 0x8000) "drop" else "propel"
        assertEquals("drop", grappleRelease(0, 0x8000)); assertEquals("propel", grappleRelease(1, 0x8000))
        assertEquals("propel", grappleRelease(0, 0x7FFF)); assertEquals(30, 0x1E)
    }
}
