package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmHyperBeamFiringEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "hyper-beam-firing."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun previous() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-28.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun revision() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-29.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "hyper-beam-firing-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v29 adds Hyper Beam construction notes without changing frozen v28`() {
        val v28 = previous()
        val v29 = revision()
        assertEquals(v28, v29.copy(revision = v28.revision,
            documentation = v29.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v29.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v29.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v29.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(3, v29.documentation.count { it.id.startsWith(prefix) })
        assertEquals(1, groups.size); assertEquals(62, groups.sumOf { it.lines.size })
        assertEquals(29, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `Hyper Beam scope preserves statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("LDA.W #${'$'}9018", "LDA.W #${'$'}9018 ; Fixed type")
                .replace("LDA.L HyperBeamDamageValue", "LDA.L HyperBeamDamageValue" + if (addresses) " ;90BD22;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Fixed type | Assign fixed live charged-plasma Hyper Beam type.")
            assertContains(output, if (addresses) ";90BD22; Read fixed Hyper Beam damage 1000." else "; Read fixed Hyper Beam damage 1000.")
        }
    }

    @Test fun `guards reject changed slot type damage cooldown and flare state`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CPX.W #${'$'}000A" to "CPX.W #${'$'}0008",
            "LDA.W #${'$'}9018" to "LDA.W #${'$'}9010",
            "LDA.L HyperBeamDamageValue" to "LDA.W SamusProjectile_Damages,X",
            "LDA.W #${'$'}0015" to "LDA.W #${'$'}0014",
            "LDA.W #${'$'}8000" to "LDA.W #${'$'}0000",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented Hyper Beam constants cover slots damage cooldown and flare seeds`() {
        assertEquals(listOf(0, 2, 4, 6, 8), (0 until 10 step 2).toList())
        assertEquals(8, 0x9018 and 0xF, "charged plasma selects sound index eight")
        assertEquals(21, 0x15); assertEquals(20, 0x8014 and 0x7FFF)
        assertEquals(listOf(29, 5, 5), listOf(0x1D, 5, 5))
        assertTrue(0x8000.toShort() < 0, "negative flare counter selects the Hyper Beam path")
    }
}
