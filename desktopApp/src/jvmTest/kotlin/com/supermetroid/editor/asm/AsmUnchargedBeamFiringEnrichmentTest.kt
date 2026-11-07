package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmUnchargedBeamFiringEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "beam-firing-uncharged."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "uncharged-beam-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v22 adds uncharged beam firing without changing frozen v21`() {
        val previous = frozen(21); val v22 = frozen(22)
        assertEquals("super-metroid-ntsc-22", v22.revision)
        assertEquals(previous, v22.copy(revision = previous.revision,
            documentation = v22.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v22.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v22.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v22.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(6, v22.documentation.count { it.id.startsWith(prefix) })
        assertEquals(1, groups.size)
        assertEquals(117, groups.sumOf { it.lines.size })
        assertEquals(63, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `uncharged firing preserves statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ORA.W #${'$'}8000", "ORA.W #${'$'}8000 ; Live projectile")
                .replace("LDA.W BeamAutoFireCooldowns,Y", "LDA.W BeamAutoFireCooldowns,Y" + if (addresses) " ;90B90B;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val output = recipe().apply(root).texts.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                val transformed = recipe().apply(root).texts.getValue(file)
                assertEquals(statements(text), statements(transformed)); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(transformed))
            }
            assertContains(output, "; Live projectile | Mark the projectile live")
            assertContains(output, if (addresses) ";90B90B; Read the held-shot" else "; Read the held-shot")
        }
    }

    @Test fun `guards reject changed slot type cooldown collision and motion tables`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CPX.W #${'$'}000A" to "CPX.W #${'$'}0008",
            "ORA.W #${'$'}8000" to "ORA.W #${'$'}4000",
            "BIT.W #${'$'}1000" to "BIT.W #${'$'}0800",
            "LDA.W BeamAutoFireCooldowns,Y" to "LDA.W ProjectileCooldowns_Uncharged,Y",
            "JSR.W InitialWaveBeamBlockCollision" to "JSR.W InitialBeamBlockCollision_NoWaveBeam",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented uncharged slot and cooldown selection covers firing boundaries`() {
        assertEquals(listOf(0, 2, 4, 6, 8), (0 until 0xA step 2).toList())
        fun ordinaryCooldown(chargeEquipped: Boolean, newShot: Boolean) = chargeEquipped || newShot
        assertFalse(ordinaryCooldown(false, false))
        assertTrue(ordinaryCooldown(true, false)); assertTrue(ordinaryCooldown(false, true))
        assertEquals(12, groups.single().lines.count { it.expectedCode.startsWith("dw ProjectilePreInstruction") })
    }
}
