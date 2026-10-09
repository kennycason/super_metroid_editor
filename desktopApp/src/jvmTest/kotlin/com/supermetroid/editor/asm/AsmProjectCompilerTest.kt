package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteConflictException
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsmProjectCompilerTest {
    @Test
    fun `source ownership ranges are contiguous minimal and deterministic`() {
        val reference = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val project = byteArrayOf(9, 1, 8, 8, 4, 5, 0, 7)

        assertEquals(
            listOf(
                AsmSourceOwnedRange(0, 1),
                AsmSourceOwnedRange(2, 2),
                AsmSourceOwnedRange(6, 1),
            ),
            AsmProjectCompiler.changedRanges(reference, project),
        )
    }

    @Test
    fun `generated asset offsets are excluded from authored source ownership`() {
        val reference = byteArrayOf(0, 1, 2, 3, 4, 5)
        val project = byteArrayOf(9, 8, 7, 6, 4, 5)

        assertEquals(
            listOf(AsmSourceOwnedRange(0, 1), AsmSourceOwnedRange(3, 1)),
            AsmProjectCompiler.changedRanges(reference, project, listOf(1..2)),
        )
    }

    @Test
    fun `compiled source ownership rejects a later incompatible patch`() {
        val plan = RomWritePlan(byteArrayOf(0, 1, 2, 3))
        plan.claimCurrentRange(
            owner = "asm-source:project",
            label = "Compiled source",
            offset = 1,
            size = 1,
            kind = RomWriteKind.ASM_SOURCE,
        )

        val conflict = assertFailsWith<RomWriteConflictException> {
            plan.add(
                owner = "patch:example",
                label = "Example patch",
                offset = 1,
                bytes = listOf(0xFF),
                kind = RomWriteKind.FIXED_PATCH,
            )
        }

        assertTrue(conflict.message.orEmpty().contains("asm-source:project"))
        assertEquals(1, plan.romData[1].toInt())
    }

    @Test
    fun `saved Sandbox jump edit compiles to owned bank 90 bytes when local fixtures exist`() {
        val projectFile = findWorkspaceFile(
            "projects/Super Metroid Sandbox/Super Metroid Sandbox.smedit"
        ) ?: return
        val rom = File(projectFile.parentFile, "Super Metroid Sandbox.smc").takeIf(File::isFile) ?: return
        val sidecar = File(projectFile.parentFile, "Super Metroid Sandbox_smedit/asm")
        if (!File(sidecar, "workspace/src/bank_90.asm").isFile) return
        val loadedRom = rom.readBytes()
        val headerSize = if (loadedRom.size % 0x8000 == 0x200) 0x200 else 0

        val compiled = AsmProjectCompiler().compile(projectFile.absolutePath, loadedRom)
        val ownedOffsets = compiled.sourceOwnedRanges.flatMap { range ->
            (range.pcOffset until range.pcOffset + range.length).toList()
        }.toSet()

        assertTrue(0x081EB9 in ownedOffsets, "normal jump Y speed must be source-owned")
        assertTrue(0x081EC0 in ownedOffsets, "normal jump sub-speed high byte must be source-owned")
        assertTrue(compiled.assemblerVersion.contains("Asar 1.81"))

        val workspace = AsmProjectWorkspaceRepository().load(projectFile.absolutePath) ?: return
        val asset = AsmAssetManifest.loadBundled().firstOrNull { range ->
            range.length > 0 && File(workspace.workingDirectory, "data/${range.path}").isFile
        } ?: return
        val assetFile = File(workspace.workingDirectory, "data/${asset.path}")
        val override = assetFile.readBytes()
        override[0] = (override[0].toInt() xor 1).toByte()
        val claim = AsmGeneratedAssetClaim(
            owner = "asm-asset:${asset.path}",
            label = "Compiler integration asset",
            assetPath = asset.path,
            pcOffset = asset.pcOffset,
            bytes = byteArrayOf(override[0]),
        )
        val materialized = AsmProjectCompiler().compile(
            projectFile.absolutePath,
            loadedRom,
            AsmAssetMaterialization(
                assetOverrides = mapOf(asset.path to override),
                claims = listOf(claim),
                eligibleWrites = 1,
                skippedWrites = 0,
            ),
            patchMaterialization = run {
                val patchPc = 0x081EA2
                val patchByte = ((loadedRom[headerSize + patchPc].toInt() and 0xFF) xor 1).toByte()
                val patchClaim = AsmGeneratedPatchClaim(
                    owner = "patch:compiler-test",
                    label = "Compiler patch overlay",
                    pcOffset = patchPc,
                    bytes = byteArrayOf(patchByte),
                )
                val patchSnes = checkNotNull(pcToSnesLoRom(patchPc))
                AsmPatchMaterialization(
                    source = "org \$${patchSnes.toString(16).uppercase()}\ndb \$${(patchByte.toInt() and 0xFF).toString(16).uppercase()}\n",
                    claims = listOf(patchClaim),
                    eligibleWrites = 1,
                    skippedWrites = 0,
                )
            },
            referenceRomBody = compiled.referenceRomBody,
        )
        assertEquals(compiled.referenceRomSha256, materialized.referenceRomSha256)
        assertEquals(override[0], materialized.romBytes[headerSize + asset.pcOffset])
        assertEquals(listOf(claim), materialized.generatedAssetClaims)
        assertTrue(materialized.sourceOwnedRanges.none { asset.pcOffset in it.pcOffset until it.pcOffset + it.length })
        assertEquals(1, materialized.generatedPatchClaims.size)
        val patchClaim = materialized.generatedPatchClaims.single()
        assertEquals(patchClaim.bytes[0], materialized.romBytes[headerSize + patchClaim.pcOffset])
        assertTrue(materialized.sourceOwnedRanges.none {
            patchClaim.pcOffset in it.pcOffset until it.pcOffset + it.length
        })
    }

    private fun findWorkspaceFile(relativePath: String): File? {
        var cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = cursor?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            cursor = cursor?.parentFile
        }
        return null
    }
}
