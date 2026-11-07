package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmAudioRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("audio-runtime.") }
    private fun recipe() = current.copy(
        revision = "audio-runtime-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("audio-runtime.") },
        lineDocumentation = groups,
    )
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File =
        File(temporary, "src-${temporary.list().orEmpty().size}").apply {
            mkdirs()
            File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
            texts.forEach { (file, text) -> File(this, file).writeText(text) }
        }

    @Test fun `frozen v12 adds VRAM read music and sound runtime notes without changing frozen v11`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-11.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v12 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-12.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-12", v12.revision)
        assertEquals(previous, v12.copy(
            revision = previous.revision,
            documentation = v12.documentation.filterNot { it.id.startsWith("audio-runtime.") },
            lineDocumentation = v12.lineDocumentation.filterNot { it.id.startsWith("audio-runtime.") },
        ))
        assertEquals(v12.documentation.filter { it.id.startsWith("audio-runtime.") },
            current.documentation.filter { it.id.startsWith("audio-runtime.") })
        assertEquals(v12.lineDocumentation.filter { it.id.startsWith("audio-runtime.") }, groups)
        val frozenGroups = v12.lineDocumentation.filter { it.id.startsWith("audio-runtime.") }
        assertEquals(14, v12.documentation.count { it.id.startsWith("audio-runtime.") })
        assertEquals(10, frozenGroups.size)
        assertEquals(157, frozenGroups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(230, frozenGroups.sumOf { it.lines.size })
        assertEquals(setOf("bank_80.asm", "bank_82.asm"), frozenGroups.map { it.fileId }.toSet())
        val prose = v12.documentation.filter { it.id.startsWith("audio-runtime.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all audio runtime scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W ${'$'}4310", "STA.W ${'$'}4310 ; Configure channel")
                .replace("LDA.W APU_MusicEntry", "LDA.W APU_MusicEntry" + if (addresses) " ;808F18;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val bank80 = result.getValue("bank_80.asm")
            assertContains(bank80, "; Configure channel | Configure channel 1's transfer direction")
            assertContains(bank80, if (addresses) ";808F18; Read the active" else "; Read the active")
            val output = result.values.joinToString("\n")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("audio-runtime.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed record rings state dispatch acknowledgements and overlapping clears`() {
        val original = sources()
        for ((old, changed) in listOf(
            "ADC.B #${'$'}09" to "ADC.B #${'$'}08",
            "AND.W #${'$'}000E" to "AND.W #${'$'}000F",
            "JSR.W (.pointers,X)" to "JMP.W (.pointers,X)",
            "db ${'$'}00,${'$'}10,${'$'}20" to "db ${'$'}00,${'$'}20,${'$'}10",
            "BRA SoundState_0_SendAPUSoundRequestFromQueue" to "BRA SoundState_4_ResetSoundState",
            "STZ.W APU_SoundQueueStartIndexLib3" to "STZ.W APU_SoundQueueStartIndexLib2",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented queue and handshake arithmetic covers wrap delay and reset boundaries`() {
        assertEquals((0..7).map { it * 2 }, generateSequence(0) { (it + 2).and(0xE) }.take(8).toList())
        assertEquals(0, (0xE + 2).and(0xE))
        for (records in 0..28) assertEquals(records * 9, records * (2 + 1 + 1 + 3 + 2))

        for (library in 0..2) {
            assertEquals(library * 0x10, listOf(0x00, 0x10, 0x20)[library])
        }
        assertEquals((0..15).toList(), generateSequence(0) { (it + 1).and(0xF) }.take(16).toList())
        assertEquals(0, (15 + 1).and(0xF))
        assertEquals(listOf(1, 0), generateSequence(2) { (it - 1).takeIf { next -> next >= 0 } }.drop(1).toList())
        assertEquals(0xFF, (0 - 1).and(0xFF), "Zero downtime underflows and enables state dispatch")

        val clearedPairs = listOf(0x0643..0x0644, 0x0645..0x0646, 0x0647..0x0648, 0x0649..0x064A)
        assertEquals((0x0643..0x064A).toList(), clearedPairs.flatMap { it.toList() })
        assertEquals(0x064B, 0x064B, "The final byte clear resets library 3's state")
    }
}
