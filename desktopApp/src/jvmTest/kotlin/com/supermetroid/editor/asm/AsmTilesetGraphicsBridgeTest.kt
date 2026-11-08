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

class AsmTilesetGraphicsBridgeTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `visual tiles and metatiles save direct source assets with aliases and shared CRE`() {
        val fixture = fixture()
        val area = fixture.bridge.access(fixture.project.path, AsmTilesetGraphicsKind.AREA_TILES, 0)
        val editable = assertIs<AsmTilesetGraphicsAccess.Editable>(area)
        assertEquals(listOf(0, 1), editable.affectedTilesetIds)
        assertFalse(editable.modified)
        assertContentEquals(fixture.areaTiles, editable.rawData)
        assertContentEquals(fixture.areaTiles, editable.baselineRawData)

        val editedArea = editable.rawData.copyOf().also { it[0] = 0x31 }
        val saved = fixture.bridge.save(
            fixture.project.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
            editedArea,
            editable.sourceBytes,
        )
        assertTrue(saved.modified)
        assertContentEquals(editedArea, assertIs<AsmTilesetGraphicsAccess.Editable>(
            fixture.bridge.access(fixture.project.path, AsmTilesetGraphicsKind.AREA_TILES, 1),
        ).rawData)

        val common = assertIs<AsmTilesetGraphicsAccess.Editable>(fixture.bridge.access(
            fixture.project.path,
            AsmTilesetGraphicsKind.COMMON_METATILES,
        ))
        assertEquals(29, common.affectedTilesetIds.size)
        val editedCommon = common.rawData.copyOf().also { it[1] = 0x22 }
        fixture.bridge.save(
            fixture.project.path,
            AsmTilesetGraphicsKind.COMMON_METATILES,
            null,
            editedCommon,
            common.sourceBytes,
        )
        assertEquals(setOf("Tiles_Shared.bin", "CRE_TileTable_Compressed.bin"),
            fixture.repository.assetOverridePaths(fixture.project.path))

        val restored = fixture.bridge.restore(
            fixture.project.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            1,
            saved.sourceBytes,
        )
        assertContentEquals(fixture.areaTiles, restored.rawData)
        assertFalse(restored.modified)
    }

    @Test
    fun `graphics source saves fail closed for stale oversized reshaped and custom resources`() {
        val fixture = fixture(tightAreaEnvelope = true)
        val opened = assertIs<AsmTilesetGraphicsAccess.Editable>(fixture.bridge.access(
            fixture.project.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
        ))
        val incompressible = ByteArray(opened.rawData.size) { index -> ((index * 73 + index / 5) and 0xFF).toByte() }
        val tooLarge = assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(
                fixture.project.path,
                AsmTilesetGraphicsKind.AREA_TILES,
                0,
                incompressible,
                opened.sourceBytes,
            )
        }
        assertTrue(tooLarge.message.orEmpty().contains("fixed"))
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(
                fixture.project.path,
                AsmTilesetGraphicsKind.AREA_TILES,
                0,
                opened.rawData.copyOf(opened.rawData.size - 32),
                opened.sourceBytes,
            )
        }

        val externalRaw = ByteArray(opened.rawData.size)
        val external = fixture.bridge.save(
            fixture.project.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
            externalRaw,
            opened.sourceBytes,
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.save(
                fixture.project.path,
                AsmTilesetGraphicsKind.AREA_TILES,
                0,
                opened.rawData,
                opened.sourceBytes,
            )
        }
        assertTrue(external.modified)

        val source = File(fixture.workspace.workingDirectory, "src/bank_8F.asm")
        val customText = source.readText().replace("dl Tiles_Shared", "dl ${'$'}C18000")
        source.writeText(customText)
        File(fixture.workspace.root, "overrides/src/bank_8F.asm").apply {
            parentFile.mkdirs()
            writeText(customText)
        }
        assertIs<AsmTilesetGraphicsAccess.AsmOnly>(fixture.bridge.access(
            fixture.project.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            0,
        ))
    }

    private data class Fixture(
        val project: File,
        val workspace: AsmProjectWorkspace,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmTilesetGraphicsBridge,
        val areaTiles: ByteArray,
    )

    private fun fixture(tightAreaEnvelope: Boolean = false): Fixture {
        val areaTiles = ByteArray(64) { (it and 3).toByte() }
        val areaTable = ByteArray(16) { (it * 3).toByte() }
        val commonTiles = ByteArray(64) { (it and 7).toByte() }
        val commonTable = ByteArray(16) { (it * 5).toByte() }
        val rawByPath = linkedMapOf(
            "Tiles_Shared.bin" to areaTiles,
            "TileTables_Shared.bin" to areaTable,
            "CRE_Tiles_Compressed.bin" to commonTiles,
            "CRE_TileTable_Compressed.bin" to commonTable,
        )
        var pc = 0x100
        val assets = rawByPath.map { (path, raw) ->
            val compressed = LZ5Compressor.compress(raw)
            val capacity = compressed.size + if (tightAreaEnvelope && path == "Tiles_Shared.bin") 2 else 16
            AsmAssetRange(path, pc, capacity).also { pc += capacity }
        }
        val root = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val source = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(source, "main.asm").writeText("incsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
        File(source, "bank_8F.asm").writeText(
            """
            Tileset_Table_0_Test:
                dl TileTables_Shared
                dl Tiles_Shared
                dl Palette_Unused
            Tileset_Table_1_Alias:
                dl TileTables_Shared
                dl Tiles_Shared
                dl Palette_Unused
            """.trimIndent(),
        )
        File(source, "bank_B9..CE.asm").writeText(
            """
            CRE_Tiles_Compressed:
                incbin "../data/CRE_Tiles_Compressed.bin"
            CRE_TileTable_Compressed:
                incbin "../data/CRE_TileTable_Compressed.bin"
            Tiles_Shared:
                incbin "../data/Tiles_Shared.bin"
            TileTables_Shared:
                incbin "../data/TileTables_Shared.bin"
            """.trimIndent(),
        )
        assets.forEach { asset ->
            val compressed = LZ5Compressor.compress(rawByPath.getValue(asset.path))
            File(data, asset.path).writeBytes(ByteArray(asset.length) { 0xFF.toByte() }.also(compressed::copyInto))
        }
        val metadata = AsmReferenceMetadata(
            AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT,
            "fixture",
            "fixture.sfc",
            "ab".repeat(32),
            0x300000,
            assets.size,
        )
        val reference = AsmReferenceWorkspace(root, metadata, AsmSourceParser().parse(source, data, assets))
        val project = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(assets, starterModules = emptyList())
        val workspace = repository.initialize(project.path, reference)
        return Fixture(project, workspace, repository, AsmTilesetGraphicsBridge(repository), areaTiles)
    }
}
