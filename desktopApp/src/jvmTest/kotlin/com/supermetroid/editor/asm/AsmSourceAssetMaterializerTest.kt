package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsmSourceAssetMaterializerTest {
    @Test
    fun `fixed graphics write becomes a generated source asset claim`() {
        val root = Files.createTempDirectory("smedit-materializer-test-")
        try {
            root.resolve("gfx/example.bin").parent.toFile().mkdirs()
            root.resolve("gfx/example.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
            val report = report(
                RomWriteIntent(
                    owner = "graphics:example",
                    label = "Example graphics",
                    offset = 0x102,
                    bytes = listOf(0xAA, 0xBB),
                    kind = RomWriteKind.GRAPHICS,
                )
            )

            val result = AsmSourceAssetMaterializer(
                assetRanges = listOf(AsmAssetRange("gfx/example.bin", 0x100, 8))
            ).materializeDataRoot(root.toFile(), report)

            assertEquals(1, result.eligibleWrites)
            assertEquals(0, result.skippedWrites)
            assertContentEquals(
                byteArrayOf(0, 1, 0xAA.toByte(), 0xBB.toByte(), 4, 5, 6, 7),
                result.assetOverrides.getValue("gfx/example.bin"),
            )
            assertEquals(1, result.claims.size)
            assertEquals("asm-asset:gfx/example.bin", result.claims.single().owner)
            assertEquals(0x102, result.claims.single().pcOffset)
            assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), result.claims.single().bytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `allocated and ambiguous writes remain post compile`() {
        val root = Files.createTempDirectory("smedit-materializer-test-")
        try {
            root.resolve("data.bin").writeBytes(ByteArray(8))
            val report = report(
                RomWriteIntent(
                    owner = "graphics:relocated",
                    label = "Relocated graphics",
                    offset = 0x202,
                    bytes = listOf(1, 2),
                    kind = RomWriteKind.GRAPHICS,
                ),
                RomWriteIntent(
                    owner = "graphics:relocated",
                    label = "Allocated range",
                    offset = 0x200,
                    bytes = List(8) { 0 },
                    kind = RomWriteKind.ALLOCATION,
                ),
                RomWriteIntent(
                    owner = "text:inline",
                    label = "Inline text",
                    offset = 0x300,
                    bytes = listOf(3),
                    kind = RomWriteKind.TEXT,
                ),
            )

            val result = AsmSourceAssetMaterializer(
                assetRanges = listOf(AsmAssetRange("data.bin", 0x200, 8))
            ).materializeDataRoot(root.toFile(), report)

            assertEquals(2, result.eligibleWrites)
            assertEquals(2, result.skippedWrites)
            assertTrue(result.isEmpty)
            assertTrue(result.assetOverrides.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun report(vararg writes: RomWriteIntent) = RomWritePlanReport(
        writes = writes.toList(),
        resources = emptyList(),
        owners = emptyList(),
        unverifiedFixedWrites = emptyList(),
    )
}
