package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmChargedBeamFiringEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "beam-firing-charged."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "charged-beam-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v23 adds charged beam firing without changing frozen v22`() {
        val previous = frozen(22); val v23 = frozen(23)
        assertEquals("super-metroid-ntsc-23", v23.revision)
        assertEquals(previous, v23.copy(revision = previous.revision,
            documentation = v23.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v23.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v23.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v23.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(5, v23.documentation.count { it.id.startsWith(prefix) })
        assertEquals(94, groups.single().lines.size)
        assertEquals(54, groups.single().lines.count { it.comment != null })
    }

    @Test fun `charged firing preserves statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ORA.W #${'$'}8010", "ORA.W #${'$'}8010 ; Charged live type")
                .replace("LDA.W ProjectileCooldowns_Uncharged,Y", "LDA.W ProjectileCooldowns_Uncharged,Y" + if (addresses) " ;90B9EE;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Charged live type | Mark the projectile live and charged.")
            assertContains(output, if (addresses) ";90B9EE; Read the retained" else "; Read the retained")
        }
    }

    @Test fun `guards reject changed charged type cooldown glow and motion contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "AND.W #${'$'}100F" to "AND.W #${'$'}000F",
            "ORA.W #${'$'}8010" to "ORA.W #${'$'}8000",
            "LDA.W ProjectileCooldowns_Uncharged,Y" to "LDA.W BeamAutoFireCooldowns,Y",
            "STA.W ChargedShotGlowTimer" to "STA.W SamusProjectile_CooldownTimer",
            "LDA.W FireChargeBeam_pointers,Y" to "LDA.W Fire_Uncharge_Beam_pointers,Y",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented charged type and preinstruction table cover combinations`() {
        fun chargedType(equipped: Int) = (equipped and 0x100F) or 0x8010
        assertEquals(0x901B, chargedType(0x100B)); assertEquals(0x8015, chargedType(0x0005))
        val pointers = groups.single().lines.filter { it.expectedCode.startsWith("dw ProjectilePreInstruction") }
        assertEquals(12, pointers.size)
        assertEquals(6, pointers.count { "WavePlasmaSpazer" in it.expectedCode })
    }
}
