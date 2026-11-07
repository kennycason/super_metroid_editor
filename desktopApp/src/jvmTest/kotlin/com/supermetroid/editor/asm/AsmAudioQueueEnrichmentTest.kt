package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmAudioQueueEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("audio-queue.") }
    private fun recipe() = current.copy(
        revision = "audio-queue-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("audio-queue.") },
        lineDocumentation = groups,
    )
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (file, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" +
            group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } } +
            if (file == "bank_80.asm") """

QueueSound:
    PHX
QueueSound_Lib2_Max15:
    PHX
QueueSound_Lib3_Max15:
    PHX
""" else ""
    }
    private fun source(texts: Map<String, String>): File =
        File(temporary, "src-${temporary.list().orEmpty().size}").apply {
            mkdirs()
            File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
            texts.forEach { (file, text) -> File(this, file).writeText(text) }
        }

    @Test fun `v13 adds music and sound producers without changing frozen v12`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-12.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v13 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-13.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-13", v13.revision)
        assertEquals(previous, v13.copy(
            revision = previous.revision,
            documentation = v13.documentation.filterNot { it.id.startsWith("audio-queue.") },
            lineDocumentation = v13.lineDocumentation.filterNot { it.id.startsWith("audio-queue.") },
        ))
        val frozenGroups = v13.lineDocumentation.filter { it.id.startsWith("audio-queue.") }
        assertEquals(v13.documentation.filter { it.id.startsWith("audio-queue.") },
            current.documentation.filter { it.id.startsWith("audio-queue.") })
        assertEquals(frozenGroups, groups)
        assertEquals(15, v13.documentation.count { it.id.startsWith("audio-queue.") })
        assertEquals(5, frozenGroups.size)
        assertEquals(125, frozenGroups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(173, frozenGroups.sumOf { it.lines.size })
        assertEquals(setOf("bank_80.asm"), frozenGroups.map { it.fileId }.toSet())
        val prose = v13.documentation.filter { it.id.startsWith("audio-queue.") }.flatMap { it.paragraphs } +
            frozenGroups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all audio producer scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.W APU_MusicQueueEntries,X", "STA.W APU_MusicQueueEntries,X ; Queue request")
                .replace("STA.W APU_MaxQueuedSoundsAllowedLib1",
                    "STA.W APU_MaxQueuedSoundsAllowedLib1" + if (addresses) " ;809051;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.getValue("bank_80.asm")
            assertContains(output, "; Queue request | Store the track or negative music-data request.")
            assertContains(output, if (addresses) ";809051; Store the wrapper's" else "; Store the wrapper's")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("audio-queue.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed ring capacity delay gates insertion and priority paths`() {
        val original = sources()
        for ((old, changed) in listOf(
            "AND.W #${'$'}000E" to "AND.W #${'$'}000F",
            "CPX.W #${'$'}0028" to "CPX.W #${'$'}0027",
            "AND.B #${'$'}0F" to "AND.B #${'$'}0E",
            "BMI .return" to "BPL .return",
            "STZ.W APU_SoundQueueLib2,X" to "STA.W APU_SoundQueueLib2,X",
            "CMP.W APU_SoundQueueLib3,Y" to "CMP.W APU_SoundQueueLib2,Y",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented producer arithmetic covers music and sound ring boundaries`() {
        fun musicNext(offset: Int) = (offset + 2).and(0xE)
        assertEquals((0..7).map { it * 2 }, generateSequence(0, ::musicNext).take(8).toList())
        assertEquals(0, musicNext(0xE))
        fun fixedMusicCanQueue(next: Int, start: Int) = musicNext(next) != start
        assertFalse(fixedMusicCanQueue(0xE, 0))
        assertTrue(fixedMusicCanQueue(0xC, 0))
        assertEquals(List(9) { 8 } + (9..12).toList(), (0..12).map { delay ->
            if (delay >= 8) delay else 8
        })

        fun occupancy(next: Int, start: Int) = (next - start).and(0xF)
        fun soundNext(next: Int) = (next + 1).and(0xF)
        for (start in 0..15) for (count in 0..15) {
            val next = (start + count).and(0xF)
            assertEquals(count, occupancy(next, start))
            assertEquals((next + 1).and(0xF), soundNext(next))
        }
        for (limit in listOf(1, 3, 6, 9, 15)) {
            assertEquals(limit, (0..15).count { outstanding -> outstanding < limit })
            assertFalse((limit..15).any { outstanding -> outstanding < limit })
        }
        for (newId in 0..0xFF step 17) for (oldId in 0..0xFF step 19) {
            assertEquals(minOf(newId, oldId), if (newId < oldId) newId else oldId)
        }
    }
}
