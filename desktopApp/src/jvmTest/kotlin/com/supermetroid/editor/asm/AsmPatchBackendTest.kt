package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsmPatchBackendTest {
    @Test
    fun `Samus physics explicitly supports ROM and generated ASM backends`() {
        val patch = SmPatch(
            id = "config_samus_physics",
            name = "Samus Physics",
            enabled = true,
            configType = "samus_physics",
        )

        val support = AsmPatchBackendRegistry.supportFor(patch)

        assertEquals(PatchImplementationBackend.ROM_WRITES, support.rom)
        assertEquals(PatchImplementationBackend.ASM_GENERATED_OVERLAY, support.asm)
        assertEquals(setOf("patch:config_samus_physics"), AsmPatchBackendRegistry.sourceOwners(listOf(patch)))
    }

    @Test
    fun `validated config write renders as readable Asar overlay`() {
        val write = RomWriteIntent(
            owner = "patch:config_samus_physics",
            label = "Samus Physics",
            offset = 0x081EA2,
            bytes = listOf(0x2C),
            kind = RomWriteKind.CONFIG,
        )

        val result = AsmSourcePatchMaterializer().materialize(
            report = report(write),
            sourceOwners = setOf(write.owner),
            compiledRomSize = AsmProjectCompiler.ASM_ROM_SIZE,
        )

        assertFalse(result.isEmpty)
        assertEquals(1, result.claims.size)
        assertEquals(0x081EA2, result.claims.single().pcOffset)
        assertContentEquals(byteArrayOf(0x2C), result.claims.single().bytes)
        assertTrue(result.source.contains("org \$909EA2"))
        assertTrue(result.source.contains("db \$2C"))
        assertTrue(result.source.contains("GUI/project model is authoritative"))
    }

    @Test
    fun `overlapping patch records stay on ROM backend`() {
        val first = RomWriteIntent(
            owner = "patch:one",
            label = "One",
            offset = 0x100,
            bytes = listOf(1, 2),
            kind = RomWriteKind.FIXED_PATCH,
        )
        val second = RomWriteIntent(
            owner = "patch:two",
            label = "Two",
            offset = 0x101,
            bytes = listOf(2),
            kind = RomWriteKind.FIXED_PATCH,
        )
        val otherwiseSafeSameOwner = RomWriteIntent(
            owner = "patch:one",
            label = "One, second record",
            offset = 0x200,
            bytes = listOf(3),
            kind = RomWriteKind.FIXED_PATCH,
        )

        val result = AsmSourcePatchMaterializer().materialize(
            report = report(first, second, otherwiseSafeSameOwner),
            sourceOwners = setOf(first.owner, second.owner),
            compiledRomSize = AsmProjectCompiler.ASM_ROM_SIZE,
        )

        assertTrue(result.isEmpty)
        assertEquals(3, result.eligibleWrites)
        assertEquals(3, result.skippedWrites)
    }

    private fun report(vararg writes: RomWriteIntent) = RomWritePlanReport(
        writes = writes.toList(),
        resources = emptyList(),
        owners = emptyList(),
        unverifiedFixedWrites = emptyList(),
    )
}
