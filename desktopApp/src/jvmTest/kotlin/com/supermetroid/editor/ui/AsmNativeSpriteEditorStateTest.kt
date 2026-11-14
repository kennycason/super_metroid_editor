package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmAssetRange
import com.supermetroid.editor.asm.AsmNativeSpriteAssetAccess
import com.supermetroid.editor.asm.AsmNativeSpriteAssetBridge
import com.supermetroid.editor.asm.AsmNativeSpritePaletteAccess
import com.supermetroid.editor.asm.AsmNativeSpritePaletteBridge
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceMetadata
import com.supermetroid.editor.asm.AsmReferenceWorkspace
import com.supermetroid.editor.asm.AsmSourceParser
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.NativeSpriteAssets
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomVisualAssets
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TestRomHelper
import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue

class AsmNativeSpriteEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `ASM sprite pixels save live retire JSON and restore source`() {
        val fixture = fixture(tightEnvelope = false)
        val state = fixture.state
        val status = state.nativeSpriteEditingStatus(SOURCE_KEY)
        assertTrue(status.editable)
        assertTrue(status.sourceManaged)

        val sheet = state.nativeSprites.loadSheet(fixture.parser, SOURCE_KEY, PALETTE_ADDRESS)
        assertContentEquals(fixture.raw, sheet.raw)
        val pixels = sheet.pixels.copyOf().also { it[0] = sheet.palette[1] }
        assertTrue(state.nativeSprites.applyPixels(fixture.parser, sheet, pixels))
        assertFalse(fixture.project.customGfx.visualTileBlocks.containsKey(SOURCE_KEY))

        val saved = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.projectFile.path, SOURCE_KEY),
        )
        assertTrue(saved.modified)
        assertFalse(saved.rawData.contentEquals(fixture.raw))
        assertTrue(fixture.workspaceState.projectAssetChangesSnapshot().any { it.assetPath == ASSET_PATH })
        val live = state.nativeSprites.loadSheet(fixture.parser, SOURCE_KEY, PALETTE_ADDRESS)
        assertContentEquals(saved.rawData, live.raw)
        assertContentEquals(saved.rawData, com.supermetroid.editor.rom.NativeSpriteAssets.source(SOURCE_KEY)
            .read(state.nativeSprites.previewParser(fixture.parser)))

        assertTrue(state.undo())
        assertContentEquals(fixture.raw, assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.projectFile.path, SOURCE_KEY),
        ).rawData)
        assertTrue(state.redo())
        assertContentEquals(saved.rawData, assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.projectFile.path, SOURCE_KEY),
        ).rawData)

        assertTrue(state.nativeSprites.resetTiles(SOURCE_KEY))
        val restored = assertIs<AsmNativeSpriteAssetAccess.Editable>(
            fixture.bridge.access(fixture.projectFile.path, SOURCE_KEY),
        )
        assertFalse(restored.modified)
        assertContentEquals(fixture.raw, restored.rawData)
    }

    @Test
    fun `ASM sprite growth offers guarded managed relocation`() {
        val fixture = fixture(tightEnvelope = true)
        val state = fixture.state
        val sheet = state.nativeSprites.loadSheet(fixture.parser, SOURCE_KEY, PALETTE_ADDRESS)
        val noisyPixels = sheet.pixels.copyOf().also { pixels ->
            sheet.indices.forEachIndexed { index, sourceIndex ->
                if (sourceIndex >= 0) pixels[index] = sheet.palette[(index * 7 + index / 3) and 0x0F]
            }
        }
        assertFalse(state.nativeSprites.applyPixels(fixture.parser, sheet, noisyPixels))
        val proposal = assertIs<TilesetAssetRelocationProposal.NativeSpriteGraphics>(
            state.pendingTilesetAssetRelocation,
        )
        assertTrue(proposal.requiredBytes > proposal.inPlaceCapacity)

        val originalSource = fixture.workspaceState.projectSourceText(SOURCE_FILE)
        fixture.workspaceState.replaceProjectSource(
            SOURCE_FILE,
            originalSource,
            "$originalSource\n; unsaved sprite declaration note\n",
        )
        assertFalse(state.confirmTilesetAssetRelocation())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        fixture.workspaceState.replaceProjectSource(
            SOURCE_FILE,
            "$originalSource\n; unsaved sprite declaration note\n",
            originalSource,
        )

        val reopened = state.nativeSprites.loadSheet(fixture.parser, SOURCE_KEY, PALETTE_ADDRESS)
        assertFalse(state.nativeSprites.applyPixels(fixture.parser, reopened, noisyPixels))
        assertNotNull(state.pendingTilesetAssetRelocation)
        assertTrue(state.confirmTilesetAssetRelocation())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).single().kind == "native-sprite-graphics")
        assertTrue(fixture.workspaceState.projectAssetChangesSnapshot().any { it.managed && it.originalAssetPath == ASSET_PATH })
        assertTrue(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))
        assertTrue(state.nativeSprites.hasTiles(SOURCE_KEY))

        assertTrue(state.undo())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertFalse(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))
        assertTrue(state.redo())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isNotEmpty())
        assertTrue(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))

        assertTrue(state.nativeSprites.resetTiles(SOURCE_KEY))
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertFalse(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))
    }

    @Test
    fun `ASM native palette saves live guards drafts and restores its literal row`() {
        val fixture = fixture(tightEnvelope = false)
        val state = fixture.state
        val status = state.nativeSpritePaletteEditingStatus(PALETTE_ADDRESS)
        assertTrue(status.editable)
        assertTrue(status.sourceManaged)

        val originalColors = state.nativeSprites.palette(fixture.parser, PALETTE_ADDRESS)
        val before = fixture.workspaceState.projectSourceText(PALETTE_FILE)
        val draft = "$before\n; unsaved palette note\n"
        fixture.workspaceState.replaceProjectSource(PALETTE_FILE, before, draft)
        assertFalse(state.nativeSprites.savePalette(
            fixture.parser,
            PALETTE_ADDRESS,
            originalColors.copyOf().also { it[1] = 0xFF00FF00.toInt() },
        ))
        fixture.workspaceState.replaceProjectSource(PALETTE_FILE, draft, before)

        assertTrue(state.nativeSprites.savePalette(
            fixture.parser,
            PALETTE_ADDRESS,
            originalColors.copyOf().also { it[1] = 0xFF00FF00.toInt() },
        ))
        assertFalse(fixture.project.customGfx.visualPalettes.containsKey("8CE1E9"))
        val saved = assertIs<AsmNativeSpritePaletteAccess.Editable>(
            AsmNativeSpritePaletteBridge(fixture.repository).access(fixture.projectFile.path, PALETTE_ADDRESS),
        )
        assertTrue(saved.modified)
        assertTrue(fixture.workspaceState.projectSourceChangesSnapshot().any { it.fileId == PALETTE_FILE })
        assertContentEquals(saved.rawPalette, RomVisualAssets.raw(
            state.nativeSprites.previewParser(fixture.parser),
            PALETTE_ADDRESS,
            32,
        ))
        assertTrue(fixture.workspaceState.projectSourceText(PALETTE_FILE).contains("\$03E0"))

        assertTrue(state.undo())
        assertFalse(assertIs<AsmNativeSpritePaletteAccess.Editable>(
            AsmNativeSpritePaletteBridge(fixture.repository).access(fixture.projectFile.path, PALETTE_ADDRESS),
        ).modified)
        assertTrue(state.redo())
        assertTrue(assertIs<AsmNativeSpritePaletteAccess.Editable>(
            AsmNativeSpritePaletteBridge(fixture.repository).access(fixture.projectFile.path, PALETTE_ADDRESS),
        ).modified)

        assertTrue(state.nativeSprites.resetPalette(fixture.parser, PALETTE_ADDRESS))
        val restored = assertIs<AsmNativeSpritePaletteAccess.Editable>(
            AsmNativeSpritePaletteBridge(fixture.repository).access(fixture.projectFile.path, PALETTE_ADDRESS),
        )
        assertFalse(restored.modified)
    }

    @Test
    fun `ASM-only native palette rows ignore stale broad visual overrides in preview`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val parser = RomParser(original!!)
        val nativePcs = NativeSpriteAssets.paletteRows(parser).mapTo(hashSetOf(), parser::snesToPc)
        val region = SpritePalettes.REGIONS.firstOrNull { candidate ->
            candidate.rowCount > 1 &&
                (0 until candidate.rowCount).any { candidate.offset + it * 32 in nativePcs } &&
                (0 until candidate.rowCount).any { candidate.offset + it * 32 !in nativePcs }
        }
        assertNotNull(region)
        region!!
        val sourceRow = (0 until region.rowCount).first { region.offset + it * 32 in nativePcs }
        val legacyRow = (0 until region.rowCount).first { region.offset + it * 32 !in nativePcs }
        val project = SmEditProject("fixture.sfc").also {
            it.customGfx.spritePalettes[region.id] =
                Base64.getEncoder().encodeToString(ByteArray(region.byteSize) { 0x22 })
        }
        val state = NativeSpriteEditorState(
            customGfx = { project.customGfx },
            onDirty = {},
            asmPaletteAccessAll = { addresses ->
                addresses.associateWith { address ->
                    AsmNativeSpritePaletteAccess.AsmOnly(address, "Custom ASM expression")
                }
            },
        )

        val preview = state.previewParser(parser).getRomData()

        assertContentEquals(
            original.copyOfRange(region.offset + sourceRow * 32, region.offset + (sourceRow + 1) * 32),
            preview.copyOfRange(region.offset + sourceRow * 32, region.offset + (sourceRow + 1) * 32),
        )
        assertContentEquals(
            ByteArray(32) { 0x22 },
            preview.copyOfRange(region.offset + legacyRow * 32, region.offset + (legacyRow + 1) * 32),
        )
    }

    private data class Fixture(
        val projectFile: File,
        val project: SmEditProject,
        val parser: RomParser,
        val raw: ByteArray,
        val repository: AsmProjectWorkspaceRepository,
        val bridge: AsmNativeSpriteAssetBridge,
        val workspaceState: AsmWorkspaceState,
        val state: EditorState,
    )

    private fun fixture(tightEnvelope: Boolean): Fixture {
        val raw = ByteArray(256)
        val compressed = LZ5Compressor.compress(raw)
        val capacity = compressed.size + if (tightEnvelope) 1 else 64
        val pc = 0x0A80D8
        val asset = AsmAssetRange(ASSET_PATH, pc, capacity)
        val referenceRoot = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(sourceRoot, "main.asm").writeText("incsrc $SOURCE_FILE\nincsrc $PALETTE_FILE\n")
        File(sourceRoot, SOURCE_FILE).writeText(
            """
            Pointer_Test:
                dl NativeSprite_Test
            NativeSprite_Test:
                incbin "../data/$ASSET_PATH"
            """.trimIndent(),
        )
        val paletteWords = (0 until 16).map { index ->
            val color = index or (index shl 5) or (index shl 10)
            "\$${color.toString(16).uppercase().padStart(4, '0')}"
        }
        File(sourceRoot, PALETTE_FILE).writeText(
            """
            Palettes_TitleScreen:
                dw ${paletteWords.take(8).joinToString(",")} ;8CE1E9;
                dw ${paletteWords.drop(8).joinToString(",")} ;8CE1F9;
            """.trimIndent() + "\n",
        )
        File(dataRoot, ASSET_PATH).writeBytes(
            ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto),
        )
        val reference = AsmReferenceWorkspace(
            referenceRoot,
            AsmReferenceMetadata(
                AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT,
                "fixture",
                "fixture.sfc",
                "ab".repeat(32),
                0x300000,
                1,
            ),
            AsmSourceParser().parse(sourceRoot, dataRoot, listOf(asset)),
        )
        val projectFile = File(temporary, "project-${temporary.listFiles().orEmpty().size}/Test.smedit").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        val repository = AsmProjectWorkspaceRepository(listOf(asset), starterModules = emptyList())
        repository.initialize(projectFile.path, reference)
        val project = SmEditProject(romPath = "fixture.sfc").apply {
            asmWorkspace.enabled = true
            asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            customGfx.visualTileBlocks[SOURCE_KEY] = Base64.getEncoder().encodeToString(ByteArray(raw.size) { 3 })
            customGfx.visualPalettes["8CE1E9"] = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        }
        val state = EditorState().apply { initForProject(projectFile, project) }
        val workspaceState = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspaceState.bindProject(projectFile.path, enabled = true, progressive = true) }
        state.attachAsmWorkspaceState(workspaceState)
        val rom = ByteArray(0x300000)
        ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto).copyInto(rom, pc)
        for (index in 1..15) {
            val color = index or (index shl 5) or (index shl 10)
            val palettePc = 0x0661E9 + index * 2
            rom[palettePc] = color.toByte()
            rom[palettePc + 1] = (color ushr 8).toByte()
        }
        return Fixture(
            projectFile,
            project,
            RomParser(rom),
            raw,
            repository,
            AsmNativeSpriteAssetBridge(repository),
            workspaceState,
            state,
        )
    }

    private companion object {
        const val SOURCE_KEY = "9580D8"
        const val ASSET_PATH = "Tiles_Title_Sprite.bin"
        const val SOURCE_FILE = "bank_95.asm"
        const val PALETTE_FILE = "bank_8C.asm"
        const val PALETTE_ADDRESS = 0x8CE1E9
    }
}
