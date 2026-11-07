package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmMissileFiringEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "missile-firing."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun frozen(revision: Int) = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-$revision.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "missile-firing-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v24 adds missile and super-link firing without changing frozen v23`() {
        val v23 = frozen(23); val v24 = frozen(24)
        assertEquals("super-metroid-ntsc-24", v24.revision)
        assertEquals(v23, v24.copy(revision = v23.revision,
            documentation = v24.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v24.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v24.documentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id },
            current.documentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id })
        assertEquals(v24.lineDocumentation.filter { it.id.startsWith(prefix) }.sortedBy { it.id },
            groups.sortedBy { it.id })
        assertEquals(7, v24.documentation.count { it.id.startsWith(prefix) })
        assertEquals(2, groups.size)
        assertEquals(148, groups.sumOf { it.lines.size })
        assertEquals(76, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(setOf("HUDSelectionHandler_Missiles_SuperMissiles", "Spawn_SuperMissileLink"), groups.map { it.symbol }.toSet())
    }

    @Test fun `missile firing preserves statements comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("DEC.W Missiles", "DEC.W Missiles ; Spend ammo")
                .replace("LDA.W AutoCancelHUDItemIndex", "LDA.W AutoCancelHUDItemIndex" + if (addresses) " ;90BF20;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; Spend ammo | Consume one normal missile")
            assertContains(output, if (addresses) ";90BF20; Check whether the HUD" else "; Check whether the HUD")
        }
    }

    @Test fun `guards reject changed ammo type cooldown auto-cancel and link contracts`() {
        val original = sources()
        for ((old, changed) in listOf(
            "LDA.W #${'$'}0014" to "LDA.W #${'$'}000A",
            "ORA.W #${'$'}8000" to "ORA.W #${'$'}4000",
            "BIT.W #${'$'}0200" to "BIT.W #${'$'}0100",
            "LDA.W ProjectileCooldowns_NonBeamProjectiles,Y" to "LDA.W ProjectileCooldowns_Uncharged,Y",
            "ORA.W #${'$'}8200" to "ORA.W #${'$'}8100",
            "AND.W #${'$'}FF00" to "AND.W #${'$'}00FF",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented missile type ammo selection and link packing cover boundaries`() {
        fun projectileType(existing: Int, selected: Int) = existing or (selected shl 8) or 0x8000
        assertEquals(0x8105, projectileType(0x0005, 1)); assertEquals(0x8205, projectileType(0x0005, 2))
        fun selectedAfterShot(selected: Int, remaining: Int, autoCancel: Boolean) =
            if (autoCancel || remaining == 0) 0 else selected
        assertEquals(0, selectedAfterShot(1, 4, true)); assertEquals(0, selectedAfterShot(2, 0, false))
        assertEquals(2, selectedAfterShot(2, 4, false))
        fun packLink(variable: Int, linkOffset: Int) = variable.and(0xFF00) + linkOffset
        assertEquals(0xAB08, packLink(0xAB03, 8))
        assertEquals(listOf(0, 2, 4, 6, 8), (0 until 0xA step 2).toList())
    }
}
