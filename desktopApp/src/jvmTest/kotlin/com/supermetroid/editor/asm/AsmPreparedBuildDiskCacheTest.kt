package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.rom.RomOverlapPolicy
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomSegmentPlacement
import com.supermetroid.editor.rom.RomSegmentRequest
import com.supermetroid.editor.rom.RomVerifiedFreeRange
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AsmPreparedBuildDiskCacheTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `verified prepared base survives process cache boundaries`() {
        val project = File(temporary, "cache-test.smedit").apply { writeText("{}") }
        val key = "a".repeat(64)
        val rom = ByteArray(0x8000) { (it and 0xFF).toByte() }
        val request = RomSegmentRequest(
            id = "test/segment",
            owner = "test",
            kind = RomSegmentKind.MANAGED_CODE,
            label = "Test segment",
            size = 4,
            alignment = 2,
            allowedBanks = listOf(0x80),
        )
        val base = AsmCompiledBuildBase(
            romBytes = rom,
            referenceRomBody = rom.copyOf(),
            referenceRomSha256 = "b".repeat(64),
            sourceOwnedRanges = listOf(AsmSourceOwnedRange(4, 3, "bank_80.asm")),
            generatedAssetClaims = listOf(AsmGeneratedAssetClaim("asset", "Asset", "data/a.bin", 8, byteArrayOf(8, 9))),
            generatedRoomClaims = listOf(AsmGeneratedRoomClaim("room", "Room", 12, byteArrayOf(12, 13))),
            generatedRoomIds = setOf("91F8"),
            generatedNewRoomIds = mapOf("new-room" to 0xE00000),
            generatedRoomSource = "; generated\n",
            generatedPatchClaims = listOf(AsmGeneratedPatchClaim("patch", "Patch", 16, byteArrayOf(16))),
            preassembledAssetWrites = listOf(
                RomWriteIntent(
                    owner = "asset",
                    label = "Asset",
                    offset = 8,
                    bytes = listOf(8, 9),
                    kind = RomWriteKind.ASM_ASSET,
                    expectedBefore = listOf(1, 2),
                    overlapPolicy = RomOverlapPolicy.ALLOW_IDENTICAL,
                ),
            ),
            segmentPlacements = listOf(
                RomSegmentPlacement(
                    request,
                    RomAllocation(0x80, 32, 0x808020, 4, "Test segment", 0xFF, request, 32),
                ),
            ),
            assemblerVersion = "asar test",
            compilerOutput = listOf("ok"),
            compilerSymbols = byteArrayOf(1, 2, 3),
            beamDamageLocations = mapOf("beam" to 20),
            patchDataLocations = mapOf("patch:key" to AsmPatchWordLocations(22, 24)),
            nativePatchLocations = mapOf("patch:key" to listOf(AsmDataPatchLocation(22, 2))),
            nativePatchWrites = mapOf("patch:key" to listOf(AsmDataPatchWrite(22, 2, 7))),
            curatedPatchIds = setOf("fast_doors"),
            curatedBaselineRomBody = rom.copyOf(),
            checkedHookDemoBaselineRomBody = rom.copyOf(),
            verifiedFreeRanges = listOf(RomVerifiedFreeRange(0x80, 28, 40, 0xFF, "Test free range")),
        )

        AsmPreparedBuildDiskCache.store(project.absolutePath, key, base)
        val loaded = requireNotNull(AsmPreparedBuildDiskCache.load(project.absolutePath, key))

        assertArrayEquals(base.romBytes, loaded.romBytes)
        assertEquals(base.sourceOwnedRanges, loaded.sourceOwnedRanges)
        assertEquals(base.generatedRoomIds, loaded.generatedRoomIds)
        assertEquals(base.preassembledAssetWrites, loaded.preassembledAssetWrites)
        assertEquals(base.segmentPlacements, loaded.segmentPlacements)
        assertEquals(base.patchDataLocations, loaded.patchDataLocations)
        assertArrayEquals(base.compilerSymbols, loaded.compilerSymbols)
        assertTrue(ProjectPaths.forProject(project.absolutePath).asmBuild.resolve("prepared/prepared-$key.bin").isFile)
    }

    @Test
    fun `corrupt or mismatched prepared base is only a cache miss`() {
        val project = File(temporary, "corrupt-test.smedit").apply { writeText("{}") }
        val key = "c".repeat(64)
        val rom = ByteArray(0x8000)
        AsmPreparedBuildDiskCache.store(
            project.absolutePath,
            key,
            AsmCompiledBuildBase(
                romBytes = rom,
                referenceRomBody = rom.copyOf(),
                referenceRomSha256 = "d".repeat(64),
                sourceOwnedRanges = emptyList(),
                assemblerVersion = "asar test",
                compilerOutput = emptyList(),
            ),
        )
        val file = ProjectPaths.forProject(project.absolutePath).asmBuild.resolve("prepared/prepared-$key.bin")
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 0x55).toByte() }
        Files.write(file.toPath(), bytes)

        assertNull(AsmPreparedBuildDiskCache.load(project.absolutePath, key))
        assertNull(AsmPreparedBuildDiskCache.load(project.absolutePath, "e".repeat(64)))
    }
}
