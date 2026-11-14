package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmAssetRange
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceMetadata
import com.supermetroid.editor.asm.AsmReferenceWorkspace
import com.supermetroid.editor.asm.AsmSourceParser
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomParser
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

class AsmTilesetPaletteEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `ASM mode palette editor reads and saves the durable source asset without JSON split brain`() {
        val originalRaw = ByteArray(256) { (it and 0x0F).toByte() }
        val editedRaw = ByteArray(256) { ((it / 8) and 0x1F).toByte() }
        val capacity = maxOf(LZ5Compressor.compress(originalRaw).size, LZ5Compressor.compress(editedRaw).size) + 8
        val asset = AsmAssetRange("Palettes_Shared.bin", 0x100, capacity)
        val referenceRoot = File(temporary, "reference")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(sourceRoot, "main.asm").writeText("incsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
        File(sourceRoot, "bank_8F.asm").writeText(
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
        File(sourceRoot, "bank_B9..CE.asm").writeText(
            """
            org ${'$'}C28100
            Palettes_Shared:
            incbin "../data/Palettes_Shared.bin"
            """.trimIndent(),
        )
        val originalCompressed = LZ5Compressor.compress(originalRaw)
        File(dataRoot, asset.path).writeBytes(
            ByteArray(capacity) { 0xFF.toByte() }.also(originalCompressed::copyInto),
        )
        val metadata = AsmReferenceMetadata(
            AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT,
            "fixture",
            "fixture.sfc",
            "ab".repeat(32),
            0x300000,
            1,
        )
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            metadata,
            AsmSourceParser().parse(sourceRoot, dataRoot, listOf(asset)),
        )
        val projectFile = File(temporary, "hack/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(listOf(asset), starterModules = emptyList())
        repository.initialize(projectFile.path, reference)
        val project = SmEditProject(romPath = "fixture.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            customGfx.palettes["0"] = Base64.getEncoder().encodeToString(editedRaw)
        }
        val state = EditorState().apply { initForProject(projectFile, project) }
        val workspaceState = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspaceState.bindProject(projectFile.path, enabled = true, progressive = true) }
        state.attachAsmWorkspaceState(workspaceState)
        val parser = RomParser(ByteArray(0x300000))

        val status = state.tilesetPaletteEditingStatus(0)
        assertTrue(status.editable)
        assertTrue(status.sourceManaged)
        assertEquals(listOf(0, 1), status.affectedTilesetIds)
        assertContentEquals(bytesToColors(editedRaw), state.readTilesetPalette(0, parser))

        assertTrue(state.saveTilesetPaletteFromColors(0, bytesToColors(editedRaw)))
        assertFalse(project.customGfx.palettes.containsKey("0"), "The visual save retires the JSON authority")
        assertEquals(setOf(asset.path), repository.assetOverridePaths(projectFile.path))
        assertContentEquals(bytesToColors(editedRaw), state.readTilesetPalette(1, parser))
        assertTrue(state.hasCustomPalette(1), "A shared source asset reports modified for every alias")

        assertTrue(state.resetPaletteOverride(1))
        assertTrue(repository.assetOverridePaths(projectFile.path).isEmpty())
        assertContentEquals(bytesToColors(originalRaw), state.readTilesetPalette(0, parser))

        val oversized = ByteArray(256) { index -> ((index * 61 + index / 7) and 0xFF).toByte() }
        assertTrue(LZ5Compressor.compress(oversized).size > capacity)
        assertFalse(state.saveTilesetPaletteFromColors(0, bytesToColors(oversized)))
        val proposal = assertIs<TilesetAssetRelocationProposal.Palette>(state.pendingTilesetAssetRelocation)
        assertEquals(listOf(0, 1), proposal.affectedTilesetIds)
        assertTrue(state.confirmTilesetAssetRelocation())
        assertContentEquals(bytesToColors(oversized), state.readTilesetPalette(1, parser))
        assertTrue(state.resetPaletteOverride(0))
        assertTrue(repository.managedAssets(projectFile.path).isEmpty())

        val before = workspaceState.projectSourceText("bank_8F.asm")
        workspaceState.replaceProjectSource(
            "bank_8F.asm",
            before,
            before.replace("dl Palettes_Shared", "dl ${'$'}C2AD7C"),
        )
        assertTrue(
            state.tilesetPaletteEditingStatus(0).editable,
            "Unsaved ASM drafts must not redirect durable visual asset writes",
        )
        runBlocking { assertTrue(workspaceState.saveSource("bank_8F.asm")) }
        val savedStatus = state.tilesetPaletteEditingStatus(0)
        assertFalse(savedStatus.editable, "A saved source-pointer change must invalidate the visual cache")
        assertTrue(savedStatus.message.orEmpty().contains("direct source asset"))
    }

    private fun bytesToColors(raw: ByteArray): IntArray = IntArray(128) { index ->
        (raw[index * 2].toInt() and 0xFF) or ((raw[index * 2 + 1].toInt() and 0xFF) shl 8)
    }
}
