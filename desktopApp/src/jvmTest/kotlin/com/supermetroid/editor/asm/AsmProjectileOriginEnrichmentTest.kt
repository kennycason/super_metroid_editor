package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmProjectileOriginEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val prefix = "projectile-origin."
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith(prefix) }
    private fun previous() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-27.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun revision() = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-28.json"))
        .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
    private fun recipe() = current.copy(revision = "projectile-origin-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith(prefix) }, lineDocumentation = groups)
    private fun sources() = groups.groupBy { it.fileId }.mapValues { (_, scopes) -> scopes.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    } }
    private fun source(texts: Map<String, String>) = File(temporary, "src-${temporary.list().orEmpty().size}").apply {
        mkdirs(); File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v28 adds projectile origin and direction notes without changing frozen v27`() {
        val v27 = previous()
        val v28 = revision()
        assertEquals(v27, v28.copy(revision = v27.revision,
            documentation = v28.documentation.filterNot { it.id.startsWith(prefix) },
            lineDocumentation = v28.lineDocumentation.filterNot { it.id.startsWith(prefix) }))
        assertEquals(v28.documentation.filter { it.id.startsWith(prefix) }, current.documentation.filter { it.id.startsWith(prefix) })
        assertEquals(v28.lineDocumentation.filter { it.id.startsWith(prefix) }, groups)
        assertEquals(3, v28.documentation.count { it.id.startsWith(prefix) })
        assertEquals(1, groups.size); assertEquals(80, groups.sumOf { it.lines.size })
        assertEquals(38, groups.flatMap { it.lines }.count { it.comment != null })
    }

    @Test fun `origin scope preserves statements comments CRLF and addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STZ.W PoseTransitionShotDirection", "STZ.W PoseTransitionShotDirection ; One shot")
                .replace("DEC.W SamusProjectile_ProjectileCounter", "DEC.W SamusProjectile_ProjectileCounter" + if (addresses) " ;90BA67;" else "")
                .replace("\n", "\r\n") }
            val root = source(before); val result = recipe().apply(root).texts; val output = result.values.joinToString("\n")
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(result.getValue(file))); assertEquals(text, File(root, file).readText())
                assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(result.getValue(file)))
            }
            assertContains(output, "; One shot | Consume the override exactly once.")
            assertContains(output, if (addresses) ";90BA67; Undo the firing gate" else "; Undo the firing gate")
        }
    }

    @Test fun `guards reject changed markers pose tables and reservation rollback`() {
        val original = sources()
        for ((old, changed) in listOf(
            "AND.W #${'$'}00FF" to "AND.W #${'$'}FFFF",
            "CMP.W #${'$'}0010" to "CMP.W #${'$'}0020",
            "CMP.W #${'$'}0075" to "CMP.W #${'$'}0074",
            "CMP.W #${'$'}0076" to "CMP.W #${'$'}0077",
            "DEC.W SamusProjectile_ProjectileCounter" to "INC.W SamusProjectile_ProjectileCounter",
        )) {
            assertTrue(original.values.any { old in it }); val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented origin selection covers markers tables and signed pose adjustment`() {
        fun direction(override: Int, current: Int, previous: Int) = when {
            override != 0 -> override and 0xFF
            current and 0xF0 == 0 -> current and 0xFF
            current == 0x10 && previous and 0xF0 == 0 -> previous and 0xFF
            else -> null
        }
        assertEquals(3, direction(0x103, 0x10, 7)); assertEquals(7, direction(0, 0x10, 7))
        assertNull(direction(0, 0x20, 7)); assertNull(direction(0, 0x10, 0xF1))
        fun alternate(pose: Int, movement: Int) = pose == 0x75 || pose == 0x76 || movement and 0xFF == 1
        assertTrue(alternate(0x75, 0)); assertTrue(alternate(0, 0x101)); assertFalse(alternate(0, 0))
        assertEquals(92, 100 + 5 - 13, "origin Y adds the direction offset then subtracts pose Y")
    }
}
