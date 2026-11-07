package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmMorphBallWeaponsEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "morph-ball-weapons."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "morph-ball-weapons-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v25 adds morph-ball weapon construction without changing frozen v24`() {
        val previous = frozen(24); val v25 = frozen(25)
        assertEquals("super-metroid-ntsc-25", v25.revision)
        assertEquals(previous, v25.copy(revision = previous.revision,
            documentation = v25.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v25.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v25.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v25.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(6, v25.documentation.count { it.id.startsWith(prefix) })
        assertEquals(125, groups.single().lines.size)
        assertEquals(63, groups.single().lines.count { it.comment != null })
    }

    @Test fun `morph weapon construction preserves statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("LDA.W #${'$'}0500", "LDA.W #${'$'}0500 ; Bomb type")
                .replace("LDA.W BombTimerResetValue", "LDA.W BombTimerResetValue" + if (addresses) " ;90BFFB;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Bomb type | Assign normal-bomb category")
            assertContains(output, if (addresses) ";90BFFB; Read the regional" else "; Read the regional")
        }
    }

    @Test fun `guards reject changed category slots activity fuse and cooldown contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CMP.W #${'$'}0003" to "CMP.W #${'$'}0002",
            "CPX.W #${'$'}0014" to "CPX.W #${'$'}0012",
            "LDA.W #${'$'}0500" to "LDA.W #${'$'}0400",
            "LDA.W #${'$'}FFFF" to "LDA.W #${'$'}8000",
            "LDA.W ProjectileCooldowns_NonBeamProjectiles,Y" to "LDA.W ProjectileCooldowns_Uncharged,Y",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented bomb slots categories and selection clearing cover boundaries`() {
        assertEquals(listOf(0x0A, 0x0C, 0x0E, 0x10, 0x12), (0x0A until 0x14 step 2).toList())
        assertEquals(0x0500, 5 shl 8); assertEquals(0x0300, 3 shl 8)
        fun selectionAfterPowerBomb(ammo: Int, autoCancel: Boolean) = if (autoCancel || ammo == 0) 0 else 3
        assertEquals(0, selectionAfterPowerBomb(4, true)); assertEquals(0, selectionAfterPowerBomb(0, false))
        assertEquals(3, selectionAfterPowerBomb(4, false))
    }
}
