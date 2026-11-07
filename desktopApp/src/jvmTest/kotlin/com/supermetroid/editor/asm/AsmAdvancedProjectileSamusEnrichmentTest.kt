package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmAdvancedProjectileSamusEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefixes = listOf(
        "beam-collision-reflection.", "shinespark-control.", "shinespark-movement.",
        "shinespark-crash.", "crystal-flash.", "bomb-spread-runtime."
    )
    private fun selected(id: String) = prefixes.any(id::startsWith)
    private val groups get() = current.lineDocumentation.filter { selected(it.id) }
    private fun frozen(number: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$number.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "advanced-projectile-samus-test", upstreamSourceSha256 = "", renames = emptyList(),
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

    @Test fun `v48 adds initial collision and reflection without changing frozen v47`() = verifyRevision(48, prefixes[0], 9, 11, 118, 78)
    @Test fun `v49 adds shinespark control without changing frozen v48`() = verifyRevision(49, prefixes[1], 6, 5, 123, 66)
    @Test fun `v50 adds shinespark movement without changing frozen v49`() = verifyRevision(50, prefixes[2], 9, 3, 228, 86)
    @Test fun `v51 adds shinespark crash without changing frozen v50`() = verifyRevision(51, prefixes[3], 9, 7, 204, 96)
    @Test fun `v52 adds crystal flash without changing frozen v51`() = verifyRevision(52, prefixes[4], 10, 7, 207, 91)
    @Test fun `v53 adds bomb spread runtime without changing frozen v52`() = verifyRevision(53, prefixes[5], 7, 3, 185, 76)

    @Test fun `advanced projectile and Samus scopes preserve statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W SamusBoostCounter-1", "STA.W SamusBoostCounter-1" + if (addresses) " ;90D00F;" else "")
                .replace("LDA.W #${'$'}0430", "LDA.W #${'$'}0430 ; Existing input note")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Existing input note | Build the fixed crystal-flash held-button mask.")
            assertContains(output, if (addresses) ";90D00F; Write the adjacent" else "; Write the adjacent")
        }
    }

    @Test fun `guards reject changed collision reflection movement resource and spread boundaries`() {
        val original = sources()
        for ((old, changed) in listOf(
            "BCS .return" to "BCC .return",
            "LDA.W #${'$'}00F0" to "LDA.W #${'$'}00E0",
            "STA.W SamusBoostCounter-1" to "STA.W SamusBoostCounter",
            "CMP.W #${'$'}001E" to "CMP.W #${'$'}001D",
            "ORA.W #${'$'}0200" to "ORA.W #${'$'}0100",
            "CMP.W #${'$'}0033" to "CMP.W #${'$'}0032",
            "LDA.W #${'$'}8500" to "LDA.W #${'$'}8400",
            "BIT.W #${'$'}8000" to "BIT.W #${'$'}4000",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented boundaries cover direction flight crash healing and spread shape`() {
        val noWaveDirections = groups.single { it.id == "beam-collision-reflection.NoWave.lines" }.lines
            .count { it.expectedCode.startsWith("dw InitialBeamBlockCollision") }
        val waveDirections = groups.single { it.id == "beam-collision-reflection.Wave.lines" }.lines
            .count { it.expectedCode.startsWith("dw InitialWaveBeamBlockCollision") }
        assertEquals(10, noWaveDirections); assertEquals(10, waveDirections)
        fun shinesparkContinues(energy: Int, collision: Boolean) = energy >= 30 && !collision
        assertFalse(shinesparkContinues(29, false)); assertTrue(shinesparkContinues(30, false)); assertFalse(shinesparkContinues(99, true))
        assertEquals(16, (0 until 4).fold(0) { radius, _ -> radius + 4 })
        assertEquals(0, (0 until 4).fold(16) { radius, _ -> radius - 4 })
        fun crystalEligible(energy: Int, reserve: Int, ammo: List<Int>) = energy < 51 && reserve == 0 && ammo.all { it >= 10 }
        assertTrue(crystalEligible(50, 0, listOf(10, 10, 10))); assertFalse(crystalEligible(51, 0, listOf(10, 10, 10)))
        assertFalse(crystalEligible(1, 1, listOf(10, 10, 10))); assertEquals(1500, 3 * 10 * 50)
        fun chargeBoost(counter: Int) = (counter ushr 6) and 3
        assertEquals(listOf(0, 1, 2, 3), listOf(0x00, 0x40, 0x80, 0xC0).map(::chargeBoost))
        assertEquals("db ${'$'}78,${'$'}00,${'$'}6E,${'$'}00,${'$'}64,${'$'}00,${'$'}6E,${'$'}00,${'$'}78,${'$'}00",
            groups.single { it.id == "bomb-spread-runtime.Data.lines" }.lines[1].expectedCode)
    }
}
