package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Compressor
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTilesetPaletteBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `visual palette save writes the direct source asset and reports aliases`() {
        val original = ByteArray(256) { (it and 0x0F).toByte() }
        val edited = ByteArray(256) { ((it / 16) and 0x0F).toByte() }
        val capacity = maxOf(LZ5Compressor.compress(original).size, LZ5Compressor.compress(edited).size) + 8
        val fixture = fixture(capacity, original)
        val before = fixture.bridge.access(fixture.project.path, 0)
        val editable = assertIs<AsmTilesetPaletteAccess.Editable>(before)

        assertEquals(listOf(0, 1), editable.affectedTilesetIds)
        assertFalse(editable.modified)
        assertContentEquals(original, editable.rawPalette)
        val saved = fixture.bridge.save(fixture.project.path, 0, edited, editable.sourceBytes)

        assertContentEquals(edited, saved.rawPalette)
        assertTrue(saved.modified)
        assertEquals(setOf("Palettes_Shared.bin"), fixture.repository.assetOverridePaths(fixture.project.path))
        assertContentEquals(edited, assertIs<AsmTilesetPaletteAccess.Editable>(
            fixture.bridge.access(fixture.project.path, 1),
        ).rawPalette)

        val restored = fixture.bridge.restore(fixture.project.path, 1, saved.sourceBytes)
        assertContentEquals(original, restored.rawPalette)
        assertFalse(restored.modified)
        assertTrue(fixture.repository.assetOverridePaths(fixture.project.path).isEmpty())
    }

    @Test
    fun `palette save fails closed for stale data oversized compression and custom source pointers`() {
        val original = ByteArray(256)
        val capacity = LZ5Compressor.compress(original).size + 2
        val fixture = fixture(capacity, original)
        val opened = assertIs<AsmTilesetPaletteAccess.Editable>(fixture.bridge.access(fixture.project.path, 0))
        val incompressible = ByteArray(256) { index -> ((index * 73 + index / 7) and 0xFF).toByte() }

        val tooLarge = assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, 0, incompressible, opened.sourceBytes)
        }
        assertTrue(tooLarge.message.orEmpty().contains("project space"))
        assertTrue(fixture.repository.assetOverridePaths(fixture.project.path).isEmpty())

        val externalRaw = ByteArray(256) { 3 }
        val externalCompressed = LZ5Compressor.compress(externalRaw)
        val externalSource = ByteArray(capacity) { 0xFF.toByte() }.also(externalCompressed::copyInto)
        fixture.repository.saveAsset(
            fixture.project.path,
            "Palettes_Shared.bin",
            externalSource,
            opened.sourceBytes,
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(fixture.project.path, 0, original, opened.sourceBytes)
        }

        File(fixture.workspace.workingDirectory, "src/bank_8F.asm").writeText(
            File(fixture.workspace.workingDirectory, "src/bank_8F.asm").readText()
                .replace("dl Palettes_Shared", "dl ${'$'}C2AD7C"),
        )
        File(fixture.workspace.root, "overrides/src/bank_8F.asm").apply {
            parentFile.mkdirs()
            writeText(File(fixture.workspace.workingDirectory, "src/bank_8F.asm").readText())
        }
        val custom = fixture.bridge.access(fixture.project.path, 0)
        assertIs<AsmTilesetPaletteAccess.AsmOnly>(custom)
        assertTrue(custom.reason.contains("ASM"))
    }

    @Test
    fun `oversized palette relocates and restoring removes source ownership`() {
        val original = ByteArray(256)
        val fixture = fixture(LZ5Compressor.compress(original).size + 2, original)
        val opened = assertIs<AsmTilesetPaletteAccess.Editable>(fixture.bridge.access(fixture.project.path, 0))
        val edited = ByteArray(256) { index -> ((index * 73 + index / 7) and 0xFF).toByte() }

        assertFailsWith<AsmAssetRelocationRequiredException> {
            fixture.bridge.save(fixture.project.path, 0, edited, opened.sourceBytes)
        }
        val relocated = fixture.bridge.relocate(fixture.project.path, 0, edited, opened.sourceBytes)
        assertTrue(relocated.managedAssetId != null)
        assertTrue(relocated.sourceBytes.size > relocated.capacity)
        assertContentEquals(edited, relocated.rawPalette)
        assertEquals(listOf(0, 1), relocated.affectedTilesetIds)

        val restored = fixture.bridge.restore(fixture.project.path, 1, relocated.sourceBytes)
        assertTrue(restored.managedAssetId == null)
        assertFalse(restored.modified)
        assertContentEquals(original, restored.rawPalette)
        assertTrue(fixture.repository.managedAssets(fixture.project.path).isEmpty())
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmTilesetPaletteBridge,
    )

    private fun fixture(capacity: Int, rawPalette: ByteArray): Fixture {
        val asset = AsmAssetRange("Palettes_Shared.bin", 0x100, capacity)
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
        File(source, "bank_8F.asm").writeText(
            """
            Tileset_Table_0_Test:
                dl DummyTable
                dl DummyTiles
                dl Palettes_Shared
            Tileset_Table_1_Alias:
                dl DummyTable
                dl DummyTiles
                dl Palettes_Shared
            """.trimIndent(),
        )
        File(source, "bank_B9..CE.asm").writeText(
            """
            org ${'$'}C28100
            Palettes_Shared:
            incbin "../data/Palettes_Shared.bin"
            """.trimIndent(),
        )
        val compressed = LZ5Compressor.compress(rawPalette)
        require(compressed.size <= capacity)
        File(data, asset.path).writeBytes(ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto))
        val metadata = AsmReferenceMetadata(
            AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT,
            "fixture",
            "fixture.sfc",
            "ab".repeat(32),
            0x300000,
            1,
        )
        val reference = AsmReferenceWorkspace(root, metadata, AsmSourceParser().parse(source, data, listOf(asset)))
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(listOf(asset), starterModules = emptyList())
        val workspace = repository.initialize(project.path, reference)
        return Fixture(project, workspace, repository, AsmTilesetPaletteBridge(repository))
    }
}
