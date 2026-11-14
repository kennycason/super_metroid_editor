package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextData
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTypewriterTextCompilerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `grown typewriter compiles in implicit bank after cold reopen and restores`() {
        val fixture = AsmProjectTestFixture.create(temporary)
        val entry = TextData.readAllText(fixture.rom).first { it.id == "tourian_escape" }
        val repository = AsmProjectWorkspaceRepository()
        val bridge = AsmTypewriterTextBridge(repository)
        val opened = assertIs<AsmTypewriterTextAccess.Editable>(
            bridge.accessAll(fixture.projectFile.path, listOf(entry)).getValue(entry.id),
        )
        val text = "TIME BOMB HAS BEEN SET!\nESCAPE FROM ZEBES IMMEDIATELY!"
        val raw = TextData.encodeTypewriterText(text, opened.rawData, preserveLineSlots = false)
        val relocated = bridge.relocate(fixture.projectFile.path, entry, raw, opened.snapshot())

        val compiled = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        val record = repository.managedAssets(fixture.projectFile.path).single()
        val claim = compiled.generatedAssetClaims.single { it.owner == "asm-managed-asset:${record.id}" }
        val placement = compiled.segmentPlacements.single { it.request.owner == claim.owner }
        val address = requireNotNull(
            AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels[record.sourceLabel],
        )
        assertEquals(0xA6, address ushr 16 and 0xFF)
        assertEquals(0xA6, placement.allocation.bank)
        assertContentEquals(raw, claim.bytes)
        assertContentEquals(raw, compiled.romBytes.copyOfRange(claim.pcOffset, claim.endExclusive))
        val consumerPc = requireNotNull(snesLoRomToPc(0xA6C24F))
        assertEquals(0xA9, compiled.romBytes[consumerPc].toInt() and 0xFF)
        assertEquals(address and 0xFFFF, readWord(compiled.romBytes, consumerPc + 1))

        AsmProjectCompiler.clearBuildCacheForTests()
        val cold = AsmProjectCompiler(AsmProjectWorkspaceRepository()).compile(
            fixture.projectFile.path,
            fixture.rom,
        )
        assertContentEquals(compiled.romBytes, cold.romBytes)
        assertTrue(cold.generatedAssetClaims.single().pcOffset == claim.pcOffset)

        val restored = bridge.restore(fixture.projectFile.path, entry, relocated.snapshot())
        assertFalse(restored.modified)
        val native = AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        assertTrue(native.generatedAssetClaims.none { it.owner == claim.owner })
        assertContentEquals(
            entry.rawBytes,
            native.romBytes.copyOfRange(entry.pcOffset, entry.pcOffset + entry.rawBytes.size),
        )
    }

    private fun readWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
