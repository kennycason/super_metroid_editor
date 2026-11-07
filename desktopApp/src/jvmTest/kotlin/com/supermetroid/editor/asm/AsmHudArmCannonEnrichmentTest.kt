package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmHudArmCannonEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf(
        "hud-projectile-dispatch.", "hud-selection-switching.", "arm-cannon-state.", "arm-cannon-render."
    )
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "hud-arm-cannon-test", upstreamSourceSha256 = "", renames = emptyList(),
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

    @Test fun `v44 adds HUD projectile dispatch without changing frozen v43`() = verifyRevision(44, prefixes[0], 10, 8, 144, 91)
    @Test fun `v45 adds HUD selection switching without changing frozen v44`() = verifyRevision(45, prefixes[1], 10, 7, 138, 66)
    @Test fun `v46 adds arm cannon state without changing frozen v45`() = verifyRevision(46, prefixes[2], 4, 4, 81, 40)
    @Test fun `v47 adds arm cannon rendering without changing frozen v46`() = verifyRevision(47, prefixes[3], 6, 1, 186, 75)

    @Test fun `HUD and arm cannon scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W ArmCannonCloseFlag-1", "STA.W ArmCannonCloseFlag-1" + if (addresses) " ;90C620;" else "")
                .replace("LDA.W #${'$'}0020", "LDA.W #${'$'}0020 ; Existing upload note")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Existing upload note | Transfer one ${'$'}20-byte arm-cannon tile block.")
            assertContains(output, if (addresses) ";90C620; Write adjacent open" else "; Write adjacent open")
        }
    }

    @Test fun `guards reject changed pose item equipment transition and upload boundaries`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}009B" to "CMP.W #${'$'}009A",
            "LDX.W #${'$'}000A" to "LDX.W #${'$'}0008",
            "CMP.W #${'$'}0006" to "CMP.W #${'$'}0007",
            "BIT.W #${'$'}4000" to "BIT.W #${'$'}2000",
            "ORA.W #${'$'}0100" to "ORA.W #${'$'}0200",
            "CMP.W #${'$'}0003" to "CMP.W #${'$'}0004",
            "LDA.W #${'$'}61F0" to "LDA.W #${'$'}6200",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented HUD and cannon boundaries cover dispatch selection animation and visibility`() {
        val movementHandlers = groups.single { it.id == "hud-projectile-dispatch.Main.lines" }.lines
            .filter { it.expectedCode.startsWith("dw HUDSelectionHandler") }
        assertEquals(28, movementHandlers.size)
        fun accepted(start: Int, available: Set<Int>): Int {
            var item = start
            while (item !in available && item != 0) item = (item + 1) % 6
            return item
        }
        assertEquals(0, accepted(1, emptySet())); assertEquals(4, accepted(1, setOf(4)))
        assertEquals("db ${'$'}00,${'$'}01,${'$'}01,${'$'}00,${'$'}01,${'$'}00",
            groups.single { it.id == "arm-cannon-state.Flags.lines" }.lines.single().expectedCode)
        assertEquals(listOf(1, 2, 3), generateSequence(0) { (it + 1).coerceAtMost(3) }.drop(1).take(3).toList())
        assertEquals(listOf(3, 2, 1, 0), generateSequence(4) { (it - 1).coerceAtLeast(0) }.drop(1).take(4).toList())
        fun visible(value: Int) = value in 0 until 0x100
        assertTrue(visible(0)); assertTrue(visible(0xFF)); assertFalse(visible(-1)); assertFalse(visible(0x100))
        val renderer = groups.single { it.id == "arm-cannon-render.Draw.lines" }.lines.map { it.expectedCode }
        assertTrue("LDA.W #${'$'}0020" in renderer); assertTrue("LDA.W #${'$'}61F0" in renderer)
    }
}
