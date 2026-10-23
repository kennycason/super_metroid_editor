package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmRoomLoadingEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("rooms.") }
    private fun recipe() = current.copy(revision = "rooms-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { note -> note.id.startsWith("rooms.") && groups.any { it.symbol == note.symbol } },
        lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File = File(temporary, "src").apply {
        mkdirs()
        File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v7 adds room loading notes without changing the exact frozen v6 recipe or names`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-6.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v7 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-7.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals(previous, v7.copy(revision = previous.revision,
            documentation = v7.documentation.filterNot { it.id.startsWith("rooms.") },
            lineDocumentation = v7.lineDocumentation.filterNot { it.id.startsWith("rooms.") }))
        assertEquals(v7.documentation.filter { it.id.startsWith("rooms.") }, current.documentation.filter { it.id.startsWith("rooms.") })
        assertEquals(v7.lineDocumentation.filter { it.id.startsWith("rooms.") }, groups)
        assertEquals(16, current.documentation.count { it.id.startsWith("rooms.") })
        assertEquals(13, groups.size)
        assertEquals(337, groups.flatMap { it.lines }.count { it.comment != null })
        val prose = current.documentation.filter { it.id.startsWith("rooms.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `room and enemy scopes retain code original comments and CRLF without address tags`() {
        val before = sources().mapValues { (_, text) -> text
            .replace("JML.W [EnemyAIPointer]", "JML.W [EnemyAIPointer] ; Execute enemy initialisation AI")
            .replace("STA.W Enemy.health,Y", "STA.W Enemy.health,Y ; Header health")
            .replace("\n", "\r\n") }
        val root = source(before)
        val result = recipe().apply(root).texts
        before.forEach { (file, text) ->
            fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
            assertEquals(statements(text), statements(result.getValue(file)))
            assertEquals(text, File(root, file).readText())
            assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
        }
        assertContains(result.getValue("bank_A0.asm"), "; Header health | Copy header health before running enemy-specific Init AI.")
        assertContains(result.getValue("bank_A0.asm"), "; Execute enemy initialisation AI | Tail-jump through the long pointer;")
        groups.forEach { group -> group.lines.mapNotNull { it.comment }.forEach { assertContains(result.getValue(group.fileId), it) } }
    }

    @Test fun `guards reject changed header offsets population strides AI return flow and interrupt waits`() {
        val original = sources()
        for ((old, changed) in listOf(
            "ADC.W #\$000B" to "ADC.W #\$000D",
            "LDY.W #\$1400" to "LDY.W #\$2800",
            "ADC.W #\$0040" to "ADC.W #\$0020",
            "ADC.W #\$0010" to "ADC.W #\$000E",
            "ADC.W #\$0006" to "ADC.W #\$0008",
            "LDA.W \$0001,X\n    TAX\n    JMP.W Use_StatePointer_inX" to "LDA.W \$0002,X\n    TAX\n    JMP.W Use_StatePointer_inX",
            "PLA\n    RTL" to "PLA\n    RTS",
            "JML.W [EnemyAIPointer]" to "JMP.W (EnemyAIPointer)",
            "BMI .waitVRAM" to "BPL .waitVRAM",
            "BPL .waitDoor" to "BMI .waitDoor",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change actual scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
        val root = source(original.mapValues { (_, text) -> text.replace(
            "JSL.L Execute_Door_ASM\n    JSL.L Execute_Room_Setup_ASM",
            "JSL.L Execute_Room_Setup_ASM\n    JSL.L Execute_Door_ASM") })
        assertFailsWith<IllegalArgumentException> { recipe().apply(root) }
    }
}
