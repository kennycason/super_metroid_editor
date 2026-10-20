package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class AsmWaverEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("waver.") }
    private fun recipe() = current.copy(revision = "waver-test", upstreamSourceSha256 = "", renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("waver.") && it.fileId == "bank_A3.asm" }, lineDocumentation = groups)
    private fun sourceText(): String = groups.joinToString("\n") { group ->
        group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode }
    }
    private fun source(text: String): File = File(temporary, "src").apply {
        mkdirs()
        File(this, "main.asm").writeText("incsrc bank_A3.asm\n")
        File(this, "bank_A3.asm").writeText(text)
    }

    @Test fun `v4 extends the frozen v3 recipe without changing its renames or crawler documentation`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-3.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v4 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-4.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val withoutWaver = v4.copy(revision = previous.revision,
            documentation = v4.documentation.filterNot { it.id.startsWith("waver.") },
            lineDocumentation = v4.lineDocumentation.filterNot { it.id.startsWith("waver.") })
        assertEquals(previous, withoutWaver)
        assertEquals(v4.lineDocumentation.filter { it.id.startsWith("waver.") }, groups)
        assertEquals(v4.documentation.filter { it.id.startsWith("waver.") }, current.documentation.filter { it.id.startsWith("waver.") })
        assertEquals(10, current.documentation.count { it.id.startsWith("waver.") })
        assertEquals(9, groups.size)
        assertEquals(139, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(420, v4.lineDocumentation.flatMap { it.lines }.count { it.comment != null })
        assertTrue(current.documentation.filter { it.id.startsWith("waver.") }.flatMap { it.paragraphs }
            .none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
        assertTrue(groups.flatMap { it.lines }.mapNotNull { it.comment }
            .none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all Waver scopes annotate without address tags and retain existing comments names and code`() {
        val before = sourceText().replace("LDA.W #regional(\$0180, \$01D0)", "LDA.W #regional(\$0180, \$01D0) ; >.<")
        val root = source(before)
        val result = recipe().apply(root)
        val annotated = result.texts.getValue("bank_A3.asm")
        fun statements(text: String) = text.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
        assertEquals(statements(before), statements(annotated))
        groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(annotated, it) }
        assertContains(annotated, "; >.< | Load the regional 8.8 horizontal speed")
        assertContains(annotated, ".facingLeft:")
        assertContains(annotated, "Enemy.var0+1,X")
        assertEquals(before, File(root, "bank_A3.asm").readText(), "Enrichment must not edit upstream source")
    }

    @Test fun `Waver documentation fails closed when packed-speed phase or collision contracts change`() {
        val before = sourceText()
        for ((old, changed) in listOf(
            "Enemy.var0+1,X" to "Enemy.var0,X",
            "BCC .noWallCollision" to "BCS .noWallCollision",
            "ADC.W #\$0080" to "ADC.W #\$0040",
            "ADC.W #\$0002" to "ADC.W #\$0004",
            "CMP.W #\$0038" to "CMP.W #\$0040",
        )) {
            val root = source(before.replace(old, changed))
            assertFailsWith<IllegalArgumentException>(old) { recipe().apply(root) }
        }
    }

    @Test fun `documented split and overlapping-byte reconstruction retain both regional signed speeds`() {
        fun split(raw: Int): Pair<Int, Int> = ((raw shr 8).toByte().toInt() and 0xFFFF) to ((raw and 0xFF) shl 8)
        fun packed(integer: Int, fraction: Int) = ((integer and 0xFF) shl 8) or (fraction ushr 8)
        for ((raw, positive, negative) in listOf(
            Triple(0x0180, 0x0001 to 0x8000, 0xFFFE to 0x8000),
            Triple(0x01D0, 0x0001 to 0xD000, 0xFFFE to 0x3000),
        )) {
            assertEquals(positive, split(raw))
            val reversed = (-raw) and 0xFFFF
            assertEquals(negative, split(reversed))
            assertEquals(raw, packed(positive.first, positive.second))
            assertEquals(reversed, packed(negative.first, negative.second))
            assertEquals(raw, (-packed(negative.first, negative.second)) and 0xFFFF)
        }
    }
}
