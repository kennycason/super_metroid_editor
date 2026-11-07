package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileLifecycleEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "projectile-lifecycle."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(
        javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json")
    ).bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(
        revision = "projectile-lifecycle-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) },
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

    @Test fun `v18 adds projectile lifecycle notes without changing frozen v17`() {
        val v17 = frozen(17); val v18 = frozen(18)
        assertEquals("super-metroid-ntsc-18", v18.revision)
        assertEquals(v17, v18.copy(
            revision = v17.revision,
            documentation = v18.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v18.lineDocumentation.filterNot { it.id.startsWith(prefix) },
        ))
        assertEquals(v18.documentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id },
            current.documentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id })
        assertEquals(v18.lineDocumentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id },
            groups.sortedBy { it.id })
        assertEquals(9, v18.documentation.count { it.id.startsWith(prefix) })
        assertEquals(7, groups.size)
        assertEquals(287, groups.sumOf { it.lines.size })
        assertEquals(175, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(setOf("bank_90.asm", "bank_93.asm"), groups.map { it.fileId }.toSet())
        assertEquals(setOf(
            "Reset_Projectile_Data", "Clear_Projectile", "Kill_Projectile", "Handle_Projectiles",
            "InitializeProjectile", "InitializeBomb", "Initialize_Beam_Missile_Explosion",
        ), groups.map { it.symbol }.toSet())
    }

    @Test fun `all projectile scopes preserve statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("ORA.W #${'$'}0700", "ORA.W #${'$'}0700 ; Explosion category")
                .replace("LDA.W SamusProjectile_CooldownTimer",
                    "LDA.W SamusProjectile_CooldownTimer" + if (addresses) " ;938131;" else "")
                .replace("\n", "\r\n") }
            val root = source(before)
            val result = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature)
                    .filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file)))
                assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            val output = result.values.joinToString("\n")
            assertContains(output, "; Explosion category | Mark the object as a beam explosion.")
            assertContains(output, if (addresses) ";938131; Read the global" else "; Read the global")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed slot split dispatch type and cooldown contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "CPX.W #${'$'}0014" to "CPX.W #${'$'}0012",
            "CPX.W #${'$'}000A" to "CPX.W #${'$'}000C",
            "LDA.W #${'$'}0012" to "LDA.W #${'$'}0010",
            "ORA.W #${'$'}0700" to "ORA.W #${'$'}0600",
            "BIT.W #${'$'}0200" to "BIT.W #${'$'}0100",
            "CMP.W #${'$'}0015" to "CMP.W #${'$'}0014",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented slot category and cooldown arithmetic covers lifecycle boundaries`() {
        assertEquals((0x12 downTo 0 step 2).toList(), listOf(0x12, 0x10, 0x0E, 0x0C, 0x0A, 8, 6, 4, 2, 0))
        assertEquals(5, (0x00 until 0x0A step 2).count())
        assertEquals(5, (0x0A until 0x14 step 2).count())
        fun explosionType(type: Int, category: Int) = (type and 0xF0FF) or category
        assertEquals(0xA70F, explosionType(0xA00F, 0x0700))
        assertEquals(0xB80F, explosionType(0xB20F, 0x0800))
        fun explosionCooldown(value: Int) = if (value >= 0x15) 0x14 else value
        assertEquals(listOf(0, 0x14, 0x14, 0x14), listOf(0, 0x14, 0x15, 0x30).map(::explosionCooldown))
        assertEquals(8, 0x0008, "beam and missile explosions use fixed damage 8")
    }
}
