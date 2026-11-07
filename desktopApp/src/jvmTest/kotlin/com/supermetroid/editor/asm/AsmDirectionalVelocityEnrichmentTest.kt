package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmDirectionalVelocityEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "directional-velocity."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "directional-velocity-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v32 adds all directional velocity helpers without changing frozen v31`() {
        val before = frozen(31); val revision = frozen(32)
        assertEquals(before, revision.copy(revision = before.revision,
            documentation = revision.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = revision.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(revision.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(revision.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, revision.documentation.count { it.id.startsWith(prefix) })
        assertEquals(8, groups.size); assertEquals(110, groups.sumOf { it.lines.size })
        assertEquals(49, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `directional scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W CameraYSubSpeed+1", "ADC.W CameraYSubSpeed+1 ; Preserve overlap")
                .replace("ORA.W #${'$'}C000", "ORA.W #${'$'}C000" + if (addresses) " ;90B228;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Preserve overlap | Add the original")
            assertContains(output, if (addresses) ";90B228; Force its upper bits" else "; Force its upper bits")
        }
    }

    @Test fun `guards reject changed overlap sign and cardinal-axis contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "LDA.W ProjSpeed_SubDistanceSamusMovedRight+1" to "LDA.W ProjSpeed_SubDistanceSamusMovedRight",
            "ORA.W #${'$'}C000" to "ORA.W #${'$'}8000",
            "ADC.W ProjSpeed_SubDistanceSamusMovedLeft+1" to "ADC.W ProjSpeed_SubDistanceSamusMovedLeft",
            "ADC.W CameraYSubSpeed+1" to "ADC.W CameraYSubSpeed",
            "STZ.W SamusProjectile_YVelocities,X" to "STZ.W SamusProjectile_XVelocities,X",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented unaligned reads retain both adjacent bytes`() {
        fun wordAtPlusOne(first: Int, second: Int) = (first ushr 8) or ((second and 0xFF) shl 8)
        assertEquals(0x5634, wordAtPlusOne(0x3412, 0x7856))
        fun negate(value: Int) = (-value) and 0xFFFF
        assertEquals(0xFC00, negate(0x0400))
        assertEquals(listOf("up", "up-right", "right", "down-right", "down", "down", "down-left", "left", "up-left", "up"),
            listOf("up", "up-right", "right", "down-right", "down", "down", "down-left", "left", "up-left", "up"))
    }
}
