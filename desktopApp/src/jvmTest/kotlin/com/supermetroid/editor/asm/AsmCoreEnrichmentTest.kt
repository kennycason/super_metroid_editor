package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmCoreEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("core.") }
    private fun recipe() = current.copy(revision = "core-test", upstreamSourceSha256 = "", renames = emptyList(),
        // Boot and input sampling are block-only targets, covered against the real source by the integration gate.
        documentation = current.documentation.filter { note -> note.id.startsWith("core.") && groups.any { it.symbol == note.symbol } },
        lineDocumentation = groups)

    private fun sources(): Map<String, String> = groups.groupBy { it.fileId }.mapValues { (_, scopes) ->
        scopes.joinToString("\n") { group -> group.symbol + ":\n" + group.lines.joinToString("\n", postfix = "\n") { "    " + it.expectedCode } }
    }
    private fun source(texts: Map<String, String>): File = File(temporary, "src").apply {
        mkdirs()
        File(this, "main.asm").writeText(texts.keys.joinToString("\n", postfix = "\n") { "incsrc $it" })
        texts.forEach { (file, text) -> File(this, file).writeText(text) }
    }

    @Test fun `v5 adds core documentation without changing any frozen v4 enrichment or names`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-4.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v5 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-5.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals(previous, v5.copy(revision = previous.revision,
            documentation = v5.documentation.filterNot { it.id.startsWith("core.") },
            lineDocumentation = v5.lineDocumentation.filterNot { it.id.startsWith("core.") }))
        assertEquals(v5.documentation.filter { it.id.startsWith("core.") }, current.documentation.filter { it.id.startsWith("core.") })
        assertEquals(v5.lineDocumentation.filter { it.id.startsWith("core.") }, groups)
        assertEquals(15, current.documentation.count { it.id.startsWith("core.") })
        assertEquals(9, groups.size)
        assertEquals(182, groups.flatMap { it.lines }.count { it.comment != null })
        val prose = current.documentation.filter { it.id.startsWith("core.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `core comments preserve executable code original comments and source with or without address tags`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text.replace("STA.W NMI_Request", "STA.W NMI_Request ; Ready for NMI")
                .replace("STX.W NMI_Request", "STX.W NMI_Request" + if (addresses) " ;8095E7;" else "") }
            val root = source(before)
            val annotated = recipe().apply(root).texts
            before.forEach { (file, text) ->
                fun statements(value: String) = value.lineSequence().map(::asmStatementSignature).filter(String::isNotBlank).toList()
                assertEquals(statements(text), statements(annotated.getValue(file)))
                assertEquals(text, File(root, file).readText(), "Enrichment must not modify its upstream input")
            }
            assertContains(annotated.getValue("bank_80.asm"), "; Ready for NMI | Publish readiness")
            assertContains(annotated.getValue("bank_80.asm"), if (addresses) ";8095E7; Acknowledge" else "; Acknowledge")
            groups.forEach { group -> group.lines.mapNotNull { it.comment }.forEach { assertContains(annotated.getValue(group.fileId), it) } }
        }
    }

    @Test fun `core guards reject changed request widths branch paths state targets and counter placement`() {
        val original = sources()
        for ((old, changed) in listOf(
            "SEP #\$30" to "SEP #\$20",
            "BEQ .lag" to "BNE .lag",
            "STX.W NMI_Request\n" to "STZ.W NMI_Request\n",
            "INC.W NMI_Counter" to "INC.W NMI_FrameCounter",
            "LDX.B DP_IRQCmd" to "LDX.B DP_NextIRQCmd",
            "dw GameState_8_MainGameplay" to "dw GameState_7_MainGameplayFadingIn",
            "CMP.B #\$0F" to "CMP.B #\$8F",
            ".lag:" to ".unready:",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must exercise real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
        val root = source(original.mapValues { (_, text) -> text.replace(
            "STX.W NMI_Request\n    STX.W NMI_LagCounter", "STX.W NMI_LagCounter\n    STX.W NMI_Request") })
        assertFailsWith<IllegalArgumentException>("Identical opcodes at swapped semantic locations must not inherit comments") {
            recipe().apply(root)
        }
    }
}
