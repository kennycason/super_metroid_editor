package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("asm-reference-network")
class AsmReferenceIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `pinned source downloads and all assets derive from the configured ROM`() {
        val rom = TestRomHelper.requireRomBytes()
        val ranges = AsmAssetManifest.loadBundled()
        val workspace = AsmReferenceRepository(File(temporaryDirectory, "asm"))
            .installOrRefresh(rom, "integration-fixture.sfc")

        assertEquals(AsmReferenceContract.COMMIT, workspace.metadata.commit)
        assertEquals(AsmReferenceContract.ASSET_COUNT, workspace.metadata.assetCount)
        assertTrue(workspace.index.files.count(AsmSourceFile::isBank) >= 50)
        assertTrue(workspace.index.files.sumOf { it.sections.size } >= 10_000)
        assertTrue(workspace.index.labels.size >= 30_000)
        assertTrue(workspace.index.addressAtlas.anchors.size >= 10_000)
        assertTrue(workspace.index.assets.all { it.file.isFile })

        val representativeAnchor = workspace.index.addressAtlas.anchors
            .first { it.kind == AsmAddressAnchorKind.RECORDED }
        val resolution = workspace.index.addressAtlas.resolve(
            AsmAddressQuery(representativeAnchor.snesAddress, AsmAddressSpace.SNES),
        )
        assertTrue(representativeAnchor in resolution.exactSourceAnchors)

        val representative = listOf(ranges.first(), ranges[ranges.size / 2], ranges.last())
        representative.forEach { range ->
            assertContentEquals(
                rom.copyOfRange(range.pcOffset, range.endExclusive),
                File(workspace.root, "data/${range.path}").readBytes(),
                range.path,
            )
        }
        assertFalse(workspace.root.walkTopDown().any { it.isFile && it.extension.lowercase() in setOf("smc", "sfc") })
    }
}
