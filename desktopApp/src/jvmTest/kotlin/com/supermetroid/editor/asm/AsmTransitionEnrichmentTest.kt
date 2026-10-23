package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmTransitionEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("transitions.") }
    private fun recipe() = current.copy(revision = "transitions-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { note -> note.id.startsWith("transitions.") && groups.any { it.symbol == note.symbol } },
        lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File = File(temporary, "src").apply {
        mkdirs()
        File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v6 is additive over the exact frozen v5 recipe and preserves all existing names`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-5.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v6 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-6.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals(previous, v6.copy(revision = previous.revision,
            documentation = v6.documentation.filterNot { it.id.startsWith("transitions.") },
            lineDocumentation = v6.lineDocumentation.filterNot { it.id.startsWith("transitions.") }))
        assertEquals(v6.documentation.filter { it.id.startsWith("transitions.") }, current.documentation.filter { it.id.startsWith("transitions.") })
        assertEquals(v6.lineDocumentation.filter { it.id.startsWith("transitions.") }, groups)
        assertEquals(23, current.documentation.count { it.id.startsWith("transitions.") })
        assertEquals(19, groups.size)
        assertEquals(251, groups.flatMap { it.lines }.count { it.comment != null })
        val prose = current.documentation.filter { it.id.startsWith("transitions.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `pause and door scopes preserve code comments CRLF and source without address annotations`() {
        val before = sources().mapValues { (_, text) -> text
            .replace("JML.W [PauseHook_Unpause]", "JML.W [PauseHook_Unpause] ; Execute unpause hook")
            .replace("REP #\$30", "REP #\$30 ; >.<").replace("\n", "\r\n") }
        val root = source(before)
        val result = recipe().apply(root).texts
        before.forEach { (file, text) ->
            fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
            assertEquals(statements(text), statements(result.getValue(file)))
            assertEquals(text, File(root, file).readText(), "Upstream inputs must never be changed")
            assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)), "Inserted comments retain CRLF")
        }
        assertContains(result.getValue("bank_82.asm"), "; Execute unpause hook | Jump through the room's 24-bit unpause-hook pointer.")
        assertContains(result.getValue("bank_82.asm"), "; >.< | Use word-sized registers for the pause page selector.")
        groups.forEach { group -> group.lines.mapNotNull { it.comment }.forEach { assertContains(result.getValue(group.fileId), it) } }
    }

    @Test fun `guards reject altered return frames fade criteria queue masks alignment reads and phase ordering`() {
        val original = sources()
        for ((old, changed) in listOf(
            "PEA.W .returnPEA-1" to "PEA.W .returnPEA",
            "PEA.W .manualReturn-1" to "PEA.W .manualReturn",
            "JML.W [PauseHook_Pause]" to "JMP.W (PauseHook_Pause)",
            "AND.W #\$000F" to "AND.W #\$00FF",
            "CMP.B #\$80" to "CMP.B #\$00",
            "AND.B #\$0F" to "AND.B #\$1F",
            "Layer1YPosition-1" to "Layer1YPosition",
            "BIT.W #\$FF00" to "AND.W #\$FF00",
            "BMI .return" to "BEQ .return",
            "LDA.W #\$0008\n    STA.W GameState" to "LDA.W #\$0009\n    STA.W GameState",
        )) {
            assertTrue(original.values.any { old in it }, "Exercise an actual instruction: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
        val root = source(original.mapValues { (_, text) -> text.replace(
            "JSL.L Enable_HDMAObjects\n    JSL.L Enable_AnimatedTilesObjects",
            "JSL.L Enable_AnimatedTilesObjects\n    JSL.L Enable_HDMAObjects") })
        assertFailsWith<IllegalArgumentException> { recipe().apply(root) }
    }

    @Test fun `documented pixel-byte direction reaches a nearest boundary without moving an aligned coordinate`() {
        // Arithmetic contract only; compiled instructions and BIT-immediate flag use are checked by the native gate.
        for (coordinate in 0..0xFFFF) {
            val low = coordinate and 255
            val delta = when { low == 0 -> 0; low < 128 -> -1; else -> 1 }
            val nextLow = (coordinate + delta) and 255
            val before = minOf(low, 256 - low)
            val after = minOf(nextLow, 256 - nextLow)
            assertEquals(if (before == 0) 0 else before - 1, after, "Coordinate $coordinate")
        }
    }
}
