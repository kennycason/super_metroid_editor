package com.supermetroid.editor.asm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AsmCheckedHookDemoTest {
    @TempDir
    lateinit var temporary: File

    private fun template(): String = requireNotNull(
        javaClass.getResourceAsStream("/asm-templates/checked_hook_demo.asm"),
    ).bufferedReader().use { it.readText() }

    @Test
    fun `literal outer gate can be toggled without rewriting the demo`() {
        val enabled = template()

        assertTrue(AsmCheckedHookDemo.hasGate(enabled))
        assertTrue(AsmCheckedHookDemo.enabled(enabled))
        val disabled = AsmCheckedHookDemo.withEnabled(enabled, false)
        assertFalse(AsmCheckedHookDemo.enabled(disabled))
        assertEquals(enabled, AsmCheckedHookDemo.withEnabled(disabled, true))
        assertContains(disabled, "SMEDIT_CheckedHookDemo_Threshold_End")
    }

    @Test
    fun `malformed or escaped gates fail closed`() {
        val source = template()

        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookDemo.enabled(source.replace("!SMEDIT_CheckedHookDemoEnabled = 1", "!SMEDIT_CheckedHookDemoEnabled = 2"))
        }
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookDemo.enabled(source + "\norg \$90EA7F\nNOP\n")
        }
        assertFailsWith<IllegalArgumentException> {
            AsmCheckedHookDemo.enabled(source.replace("endif", "else\n  NOP\nendif"))
        }
    }

    @Test
    fun `enabled demo compiles into checked complete ownership spans`() {
        AsmProjectCompiler.clearBuildCacheForTests()
        val fixture = AsmProjectTestFixture.create(temporary, AsmSourceAugmentation.NONE)
        val repository = AsmProjectWorkspaceRepository(starterModules = emptyList(), augmentation = AsmSourceAugmentation.NONE)
        val (_, fileId) = repository.createModule(
            fixture.projectFile.path,
            "checked_hook_demo.asm",
            initialSource = template(),
        )

        val compiled = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        val symbols = AsmSourceBindings.load(fixture.workspace.originalDirectory).names.canonicalLabels(
            AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels,
        )
        val helper = symbols.getValue("SMEDIT_CheckedHookDemo_Threshold")
        val helperEnd = symbols.getValue("SMEDIT_CheckedHookDemo_Threshold_End")
        val hookPc = requireNotNull(snesLoRomToPc(0x90EA7F))
        val helperPc = requireNotNull(snesLoRomToPc(helper))
        val claims = compiled.sourceOwnedRanges.filter { it.sourceFileId == fileId }

        assertEquals(
            setOf(hookPc to 3, helperPc to helperEnd - helper),
            claims.map { it.pcOffset to it.length }.toSet(),
        )
        assertTrue(compiled.checkedHookDemoBaselineRomBody != null)
        assertEquals(0x4C, compiled.romBytes[hookPc].toInt() and 0xFF)
        AsmProjectCompiler.clearBuildCacheForTests()
    }
}
