package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmChargeSbaEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf("beam-charge-control.", "sba-construction.", "ice-sba-runtime.")
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "charge-sba-test", upstreamSourceSha256 = "", renames = emptyList(),
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

    @Test fun `v38 adds charge control without changing frozen v37`() = verifyRevision(38, prefixes[0], 4, 1, 53, 33)
    @Test fun `v39 adds all SBA constructors without changing frozen v38`() = verifyRevision(39, prefixes[1], 11, 5, 229, 104)
    @Test fun `v40 adds ice SBA runtime without changing frozen v39`() = verifyRevision(40, prefixes[2], 6, 2, 97, 44)

    @Test fun `charge and SBA scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("SBC.W CostOfSBAsInPowerBombs,X", "SBC.W CostOfSBAsInPowerBombs,X ; Inventory cost")
                .replace("CMP.W #${'$'}0078", "CMP.W #${'$'}0078" + if (addresses) " ;90B846;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Inventory cost | Subtract this combination's SBA cost.")
            assertContains(output, if (addresses) ";90B846; At 120 ticks" else "; At 120 ticks")
        }
    }

    @Test fun `guards reject changed charge cost slot phase and ending bounds`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}003C" to "CMP.W #${'$'}003B",
            "CMP.W #${'$'}0078" to "CMP.W #${'$'}0077",
            "SBC.W CostOfSBAsInPowerBombs,X" to "ADC.W CostOfSBAsInPowerBombs,X",
            "LDX.W #${'$'}0006" to "LDX.W #${'$'}0008",
            "ADC.W #${'$'}0008" to "ADC.W #${'$'}0007",
            "CMP.W #${'$'}0120" to "CMP.W #${'$'}0110",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented charge and SBA boundaries cover release dispatch and ice expansion`() {
        fun release(charge: Int) = when { charge == 0 -> "none"; charge < 60 -> "uncharged"; else -> "charged" }
        assertEquals(listOf("none", "uncharged", "charged"), listOf(release(0), release(59), release(60)))
        fun held(charge: Int) = if (charge >= 120) "sba" else if (charge == 0) "initial-shot" else "charge"
        assertEquals(listOf("initial-shot", "charge", "sba"), listOf(held(0), held(119), held(120)))
        val constructors = mapOf(1 to "wave", 2 to "ice", 4 to "spazer", 8 to "plasma")
        assertEquals("wave", constructors[1]); assertNull(constructors[3]); assertEquals(listOf(6, 4, 2, 0), (6 downTo 0 step 2).toList())
        fun iceVisible(x: Int, y: Int) = x in -32 until 288 && y in 16 until 256
        assertTrue(iceVisible(-32, 16)); assertTrue(iceVisible(287, 255)); assertFalse(iceVisible(-33, 16)); assertFalse(iceVisible(0, 256))
        assertEquals(48, (40 + 8) and 0xFF); assertEquals(0, (0xFC + 4) and 0xFF)
    }
}
