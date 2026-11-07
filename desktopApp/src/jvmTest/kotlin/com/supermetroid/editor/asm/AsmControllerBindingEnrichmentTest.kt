package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AsmControllerBindingEnrichmentTest {
    @TempDir lateinit var temporary: File
    private val current get() = AsmSourceAugmentation.bundled()
    private val groups get() = current.lineDocumentation.filter { it.id.startsWith("controller-bindings.") }
    private fun recipe() = current.copy(
        revision = "controller-bindings-test",
        upstreamSourceSha256 = "",
        renames = emptyList(),
        documentation = current.documentation.filter { it.id.startsWith("controller-bindings.") },
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

    @Test fun `v15 adds input and binding ownership without changing frozen v14`() {
        val previous = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-14.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        val v15 = checkNotNull(javaClass.getResourceAsStream("/asm-baselines/super-metroid-ntsc-15.json"))
            .bufferedReader().use { Json.decodeFromString(AsmSourceAugmentation.serializer(), it.readText()) }
        assertEquals("super-metroid-ntsc-15", v15.revision)
        assertEquals(previous, v15.copy(
            revision = previous.revision,
            documentation = v15.documentation.filterNot { it.id.startsWith("controller-bindings.") },
            lineDocumentation = v15.lineDocumentation.filterNot { it.id.startsWith("controller-bindings.") },
        ))
        assertEquals(v15.documentation.filter { it.id.startsWith("controller-bindings.") },
            current.documentation.filter { it.id.startsWith("controller-bindings.") })
        assertEquals(v15.lineDocumentation.filter { it.id.startsWith("controller-bindings.") }, groups)
        assertEquals(9, v15.documentation.count { it.id.startsWith("controller-bindings.") })
        assertEquals(7, groups.size)
        assertEquals(191, groups.flatMap { it.lines }.count { it.comment != null })
        assertEquals(302, groups.sumOf { it.lines.size })
        assertEquals(setOf("bank_80.asm", "bank_82.asm"), groups.map { it.fileId }.toSet())
        assertEquals(setOf(
            "ReadControllerInput",
            "GameOptions_ControllerSettings_ResetToDefault",
            "GameOptionsMenu_ControllerBindings",
            "Configurable_Controller_Binding_RAM_Addresses",
            "Save_GameOptionsMenu_ControllerBindings",
            "Controller_Input_Bitmasks",
            "GameOptions_ControllerSettings_SetBinding",
        ), groups.map { it.symbol }.toSet())
        val prose = v15.documentation.filter { it.id.startsWith("controller-bindings.") }.flatMap { it.paragraphs } +
            groups.flatMap { it.lines }.mapNotNull { it.comment }
        assertTrue(prose.none { it.contains("SMEDIT", ignoreCase = true) || it.contains("GUI") })
    }

    @Test fun `all controller scopes preserve code comments CRLF and optional addresses`() {
        for (addresses in listOf(false, true)) {
            val before = sources().mapValues { (_, text) -> text
                .replace("STA.B DP_Controller1Input", "STA.B DP_Controller1Input ; Publish input")
                .replace("LDA.B DP_Controller1New", "LDA.B DP_Controller1New" + if (addresses) " ;82F224;" else "")
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
            assertContains(output, "; Publish input | Publish the currently held button mask.")
            assertContains(output, if (addresses) ";82F224; Read edge-only" else "; Read edge-only")
            groups.flatMap { it.lines }.mapNotNull { it.comment }.forEach { assertContains(output, it) }
            current.documentation.filter { it.id.startsWith("controller-bindings.") }.flatMap { it.paragraphs }
                .forEach { assertContains(output, it) }
        }
    }

    @Test fun `guards reject changed edge defaults action order masks and swap geometry`() {
        val original = sources()
        for ((old, changed) in listOf(
            "AND.B DP_Controller1Input" to "ORA.B DP_Controller1Input",
            "STA.W ItemCancelBinding" to "STA.W ItemSelectBinding",
            "dw ItemSelectBinding" to "dw ItemCancelBinding",
            "dw ${'$'}2000" to "dw ${'$'}4000",
            "LDX.W #${'$'}0005" to "LDX.W #${'$'}0006",
        )) {
            assertTrue(original.values.any { old in it }, "Mutation must change real scoped code: $old")
            val root = source(original.mapValues { (_, text) -> text.replace(old, changed) })
            assertFailsWith<IllegalArgumentException>(old) { recipe().copy(documentation = emptyList()).apply(root) }
        }
    }

    @Test fun `documented edge mapping and swap preserve controller contracts`() {
        for (currentMask in listOf(0, 1, 0x40, 0x80, 0x3030, 0xFFFF)) {
            for (previousMask in listOf(0, 1, 0x40, 0x2000, 0xFFFF)) {
                val rising = (currentMask xor previousMask) and currentMask
                assertEquals(currentMask and previousMask.inv() and 0xFFFF, rising)
            }
        }

        val masks = listOf(0x0040, 0x0080, 0x8000, 0x2000, 0x4000, 0x0020, 0x0010)
        assertEquals(7, masks.distinct().size)
        assertEquals(0x2000, masks[3], "compact index 3 is Select")
        assertEquals(0x4000, masks[4], "compact index 4 is Y")

        val bindings = mutableListOf(0, 1, 2, 3, 4, 5, 6)
        val currentAction = 4
        val selectedButton = 3
        val previousOwner = bindings.indexOf(selectedButton)
        val displaced = bindings[currentAction]
        bindings[currentAction] = selectedButton
        bindings[previousOwner] = displaced
        assertEquals(listOf(0, 1, 2, 4, 3, 5, 6), bindings)
        assertEquals((0..6).toSet(), bindings.toSet(), "the menu remains a permutation")
    }
}
