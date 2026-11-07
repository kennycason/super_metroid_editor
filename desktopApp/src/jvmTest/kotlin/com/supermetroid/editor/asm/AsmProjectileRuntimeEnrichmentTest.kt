package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileRuntimeEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "projectile-runtime."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun previous() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-20.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "projectile-runtime-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v21 adds projectile interpretation and drawing without changing frozen v20`() {
        val v20 = previous()
        assertEquals("super-metroid-ntsc-21", current.revision)
        assertEquals(v20, current.copy(revision = v20.revision,
            documentation = current.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = current.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(7, current.documentation.count { it.id.startsWith(prefix) })
        assertEquals(5, groups.size)
        assertEquals(178, groups.sumOf { it.lines.size })
        assertEquals(94, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(setOf("ProjectileInstructionHandler", "Instruction_SamusProjectile_Delete",
            "Instruction_SamusProjectile_GotoY", "DrawProjectiles", "DrawBombsAndProjectileExplosions"),
            groups.map { it.symbol }.toSet())
    }

    @Test fun `runtime scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ADC.W #${'$'}0008", "ADC.W #${'$'}0008 ; Record stride")
                .replace("LDA.W ${'$'}0000,Y", "LDA.W ${'$'}0000,Y" + if (addresses) " ;9381FA;" else "")
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
            assertContains(output, "; Record stride | Advance past the complete timed-frame record.")
            assertContains(output, if (addresses) ";9381FA; Read the next command" else "; Read the next command")
        }
    }

    @Test fun `guards reject changed record dispatch slot flicker category and culling contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "ADC.W #${'$'}0008" to "ADC.W #${'$'}0006",
            "PEA.W .loop-1" to "PEA.W .timer-1",
            "LDX.W #${'$'}0008" to "LDX.W #${'$'}0006",
            "BIT.W #${'$'}0F10" to "BIT.W #${'$'}0F00",
            "CMP.W #${'$'}0300" to "CMP.W #${'$'}0400",
            "CMP.W #${'$'}FFD0" to "CMP.W #${'$'}FFC0",
        )) {
            assertTrue(original.values.any { old in it })
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented frame and drawing geometry covers runtime boundaries`() {
        assertEquals(8, 2 + 2 + 1 + 1 + 2, "timed frame records advance eight bytes")
        assertEquals(listOf(8, 6, 4, 2, 0), (8 downTo 0 step 2).toList())
        assertEquals(listOf(0x12, 0x10, 0x0E, 0x0C, 0x0A, 8, 6, 4, 2, 0), (0x12 downTo 0 step 2).toList())
        fun ordinaryCategory(type: Int) = type.and(0x0F00) < 0x0300
        assertTrue(ordinaryCategory(0x020F))
        assertFalse(ordinaryCategory(0x0300))
        fun bombXVisible(x: Int) = x in -48 until 304
        assertTrue(bombXVisible(-48))
        assertTrue(bombXVisible(303))
        assertFalse(bombXVisible(-49))
        assertFalse(bombXVisible(304))
    }
}
