package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmAssetRange
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceMetadata
import com.supermetroid.editor.asm.AsmReferenceWorkspace
import com.supermetroid.editor.asm.AsmSourceParser
import com.supermetroid.editor.asm.AsmTilesetGraphicsAccess
import com.supermetroid.editor.asm.AsmTilesetGraphicsBridge
import com.supermetroid.editor.asm.AsmTilesetGraphicsKind
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.LZ5Compressor
import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmTilesetGraphicsEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `ASM mode visual graphics save retires JSON and updates every source alias`() {
        val originalTiles = ByteArray(64) { (it and 3).toByte() }
        val editedTiles = ByteArray(64) { ((it / 4) and 7).toByte() }
        val rawAssets = linkedMapOf(
            "Tiles_Shared.bin" to originalTiles,
            "TileTables_Shared.bin" to ByteArray(16),
            "CRE_Tiles_Compressed.bin" to ByteArray(64),
            "CRE_TileTable_Compressed.bin" to ByteArray(16),
        )
        var pc = 0x100
        val assets = rawAssets.map { (path, raw) ->
            val capacity = maxOf(
                LZ5Compressor.compress(raw).size,
                if (path == "Tiles_Shared.bin") LZ5Compressor.compress(editedTiles).size else 0,
            ) + 8
            AsmAssetRange(path, pc, capacity).also { pc += capacity }
        }
        val referenceRoot = File(temporary, "reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(sourceRoot, "main.asm").writeText("incsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
        File(sourceRoot, "bank_8F.asm").writeText(
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
        File(sourceRoot, "bank_B9..CE.asm").writeText(
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
            val compressed = LZ5Compressor.compress(rawAssets.getValue(asset.path))
            File(dataRoot, asset.path).writeBytes(ByteArray(asset.length) { 0xFF.toByte() }.also(compressed::copyInto))
        }
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                "ab".repeat(32),
                0x300000,
                assets.size,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, assets),
        )
        val projectFile = File(temporary, "hack/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(assets, starterModules = emptyList())
        repository.initialize(projectFile.path, reference)
        val project = SmEditProject(romPath = "fixture.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            customGfx.varGfx["0"] = Base64.getEncoder().encodeToString(editedTiles)
        }
        val state = EditorState().apply { initForProject(projectFile, project) }
        val workspaceState = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspaceState.bindProject(projectFile.path, enabled = true, progressive = true) }
        state.attachAsmWorkspaceState(workspaceState)

        val status = state.tilesetGraphicsEditingStatus(AsmTilesetGraphicsKind.AREA_TILES, 0)
        assertTrue(status.editable)
        assertTrue(status.sourceManaged)
        assertEquals(listOf(0, 1), status.affectedTilesetIds)

        assertTrue(state.saveTilesetGraphicsOverride(AsmTilesetGraphicsKind.AREA_TILES, editedTiles, 0))
        assertFalse(project.customGfx.varGfx.containsKey("0"), "The visual save retires the JSON authority")
        assertEquals(setOf("Tiles_Shared.bin"), repository.assetOverridePaths(projectFile.path))
        val alias = assertIs<AsmTilesetGraphicsAccess.Editable>(AsmTilesetGraphicsBridge(repository).access(
            projectFile.path,
            AsmTilesetGraphicsKind.AREA_TILES,
            1,
        ))
        assertContentEquals(editedTiles, alias.rawData)
        assertTrue(state.hasCustomVarGfx())

        assertTrue(state.resetCurrentTilesetOverrides(areaTiles = true, commonTiles = false, palette = false))
        assertTrue(repository.assetOverridePaths(projectFile.path).isEmpty())
        assertContentEquals(originalTiles, assertIs<AsmTilesetGraphicsAccess.Editable>(
            AsmTilesetGraphicsBridge(repository).access(projectFile.path, AsmTilesetGraphicsKind.AREA_TILES, 0),
        ).rawData)

        val before = workspaceState.projectSourceText("bank_8F.asm")
        workspaceState.replaceProjectSource(
            "bank_8F.asm",
            before,
            before.replace("dl Tiles_Shared", "dl ${'$'}C18000"),
        )
        assertTrue(
            state.tilesetGraphicsEditingStatus(AsmTilesetGraphicsKind.AREA_TILES, 0).editable,
            "Unsaved ASM drafts must not redirect durable visual asset writes",
        )
        runBlocking { assertTrue(workspaceState.saveSource("bank_8F.asm")) }
        val savedStatus = state.tilesetGraphicsEditingStatus(AsmTilesetGraphicsKind.AREA_TILES, 0)
        assertFalse(savedStatus.editable, "A saved source-pointer change must invalidate the visual cache")
        assertTrue(savedStatus.message.orEmpty().contains("direct source asset"))
    }
}
