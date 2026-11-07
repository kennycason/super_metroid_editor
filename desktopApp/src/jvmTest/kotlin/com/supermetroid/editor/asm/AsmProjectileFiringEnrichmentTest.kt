package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileFiringEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "projectile-firing."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(
        javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json")
    ).bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "projectile-firing-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v20 adds cooldown and firing reservations without changing frozen v19`() {
        val previous = frozen(19)
        val v20 = frozen(20)
        assertEquals("super-metroid-ntsc-20", v20.revision)
        assertEquals(previous, v20.copy(revision = previous.revision,
            documentation = v20.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v20.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v20.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v20.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(4, v20.documentation.count { it.id.startsWith(prefix) })
        assertEquals(3, groups.size)
        assertEquals(58, groups.sumOf { it.lines.size })
        assertEquals(33, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `firing scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("CMP.W #${'$'}0005", "CMP.W #${'$'}0005 ; Slot ceiling")
                .replace("LDA.W TimeIsFrozenFlag", "LDA.W TimeIsFrozenFlag" + if (addresses) " ;90AC1C;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.values.joinToString("\n")
            assertContains(output, "; Slot ceiling | At most five ordinary projectile slots may be owned.")
            assertContains(output, if (addresses) ";90AC1C; Read the global" else "; Read the global")
        }
    }

    @Test fun `guards reject changed cooldown limits masks and reservations`() {
        val original = sources()
        for ((old, changed) in listOf(
            "LDA.W #${'$'}0020" to "LDA.W #${'$'}001F",
            "CMP.W #${'$'}0005" to "CMP.W #${'$'}0004",
            "AND.W #${'$'}00FF" to "AND.W #${'$'}FFFF",
            "CMP.W #${'$'}0004" to "CMP.W #${'$'}0005",
            "INC" to "DEC",
        )) {
            assertTrue(original.values.any { old in it })
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented firing gates cover cooldown bytes and linked slot capacity`() {
        fun tick(value: Int, frozen: Boolean) = if (frozen) 0x20 else if (value <= 0) 0 else value - 1
        assertEquals(listOf(0, 0, 1, 0x20), listOf(0, -1, 2, 99).mapIndexed { index, value -> tick(value, index == 3) })
        fun beam(count: Int, cooldown: Int) = count < 5 && cooldown.and(0xFF) == 0
        assertTrue(beam(4, 0x0100))
        assertFalse(beam(5, 0))
        fun missile(count: Int, cooldown: Int, superMissile: Boolean) =
            count < (if (superMissile) 4 else 5) && cooldown.and(0xFF) == 0
        assertTrue(missile(3, 0, true))
        assertFalse(missile(4, 0, true))
        assertTrue(missile(4, 0, false))
    }
}
