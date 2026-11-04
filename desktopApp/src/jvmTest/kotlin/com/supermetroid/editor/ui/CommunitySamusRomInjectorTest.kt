package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CommunitySamusInjectionArtifact
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.RomWriteConflictException
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanException
import com.supermetroid.editor.rom.RomConstants
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommunitySamusRomInjectorTest {
    @Test
    fun `managed expansion adds ff filled allocator banks and preserves a copier header`() {
        val header = ByteArray(512) { 0x5A }
        val base = header + ByteArray(RomConstants.ROM_SIZE) { 0x31 }

        val expanded = managedExpandedRomBase(base, header.size)

        assertEquals(header.size + RomConstants.EXPANDED_ROM_SIZE, expanded.size)
        assertContentEquals(header, expanded.copyOfRange(0, header.size))
        assertEquals(0x31, expanded[header.size + RomConstants.ROM_SIZE - 1].toInt() and 0xFF)
        assertEquals(0xFF, expanded.last().toInt() and 0xFF)
    }

    @Test
    fun `expands exact base and plans artifact as owned graphics`() {
        val original = byteArrayOf(1, 2, 3, 4)
        val artifact = loadedArtifact(
            baseHash = bytesSha256(original),
            baseSize = original.size,
            outputSize = 16,
            writes = listOf(
                PatchWrite(1, listOf(0xA1)),
                // The zero is IPS record padding that already matches the
                // expanded base, not a meaningful community-Samus mutation.
                PatchWrite(5, listOf(0xB2, 0x00, 0xC3)),
            ),
        )
        val expanded = CommunitySamusRomInjector.expandedBase(
            original,
            headerSize = 0,
            inputRomHash = bytesSha256(original),
            loaded = artifact,
        )
        assertEquals(16, expanded.size)
        assertContentEquals(
            expanded,
            CommunitySamusRomInjector.expandedBase(
                expanded,
                headerSize = 0,
                inputRomHash = bytesSha256(original),
                loaded = artifact,
            ),
        )

        val plan = RomWritePlan(expanded)
        assertEquals(2, CommunitySamusRomInjector.apply(plan, artifact, original))
        assertContentEquals(
            byteArrayOf(1, 0xA1.toByte(), 3, 4, 0, 0xB2.toByte(), 0, 0xC3.toByte()),
            plan.finalRom().copyOfRange(0, 8),
        )

        // Unchanged gaps between records remain available to compatible patches.
        plan.add("other", "compatible patch", 3, listOf(0x44))
        // So does unchanged padding carried inside an IPS record.
        plan.add("other", "compatible patch in record padding", 6, listOf(0x55))

        // Actual IPS bytes remain exclusively owned.
        assertFailsWith<RomWriteConflictException> {
            plan.add("other", "conflicting patch", 5, listOf(0x44))
        }

        // A generated ASM allocation may already occupy transport-only zero
        // padding before the artifact is staged. It remains allocator-owned.
        val sourcePlan = RomWritePlan(expanded)
        sourcePlan.add("room", "expanded allocation", 6, listOf(0x55))
        CommunitySamusRomInjector.apply(sourcePlan, artifact, original)
        assertEquals(0x55, sourcePlan.finalRom()[6].toInt() and 0xFF)
    }

    @Test
    fun `rejects a base ROM that does not match artifact contract`() {
        val original = byteArrayOf(1, 2, 3, 4)
        val artifact = loadedArtifact(
            baseHash = "00".repeat(32),
            baseSize = original.size,
            outputSize = 16,
            writes = listOf(PatchWrite(1, listOf(0xA1))),
        )
        assertFailsWith<RomWritePlanException> {
            CommunitySamusRomInjector.expandedBase(
                original,
                headerSize = 0,
                inputRomHash = bytesSha256(original),
                loaded = artifact,
            )
        }
    }

    private fun loadedArtifact(
        baseHash: String,
        baseSize: Int,
        outputSize: Int,
        writes: List<PatchWrite>,
    ): CommunitySamusSourceCodec.LoadedInjectionArtifact {
        val metadata = CommunitySamusInjectionArtifact(
            formatId = CommunitySamusInjectionArtifact.MAP_RANDOMIZER_IPS_V1,
            ipsBase64 = "",
            sha256 = "11".repeat(32),
            baseRomSha256 = baseHash,
            baseRomSize = baseSize,
            outputRomSize = outputSize,
            providerRevision = "22".repeat(20),
            sourceSheetSha256 = "33".repeat(32),
            sourceUrl = "https://example.test/samus.ips",
        )
        return CommunitySamusSourceCodec.LoadedInjectionArtifact(ByteArray(0), writes, metadata)
    }
}
