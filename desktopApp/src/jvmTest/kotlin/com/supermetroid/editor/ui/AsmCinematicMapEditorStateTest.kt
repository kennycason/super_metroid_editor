package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmAssetRange
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmReferenceContract
import com.supermetroid.editor.asm.AsmReferenceMetadata
import com.supermetroid.editor.asm.AsmReferenceWorkspace
import com.supermetroid.editor.asm.AsmSourceParser
import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.snesLoRomToPc
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomVisualAssets
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmCinematicMapEditorStateTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `scene maps save preview aliases guard drafts undo and restore`() {
        val fixture = fixture(tightEnvelope = false)
        val title = RomVisualAssets.ASSETS.single { it.name == "Title screen" }
        val background = RomVisualAssets.ASSETS.single { it.name == "Title background" }
        val sheet = fixture.state.cinematicMaps.load(title)
        assertContentEquals(fixture.raw, sheet.rawData)
        assertTrue(fixture.state.cinematicMapEditingStatus(MAP.key).editable)

        val edited = MAP.withEntry(sheet.rawData, 17, 3, 0x6A)
        val originalSource = fixture.workspaceState.projectSourceText(SOURCE_FILE)
        val draft = "$originalSource\n; unsaved declaration note\n"
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, originalSource, draft)
        assertFalse(fixture.state.cinematicMaps.apply(sheet, edited))
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, draft, originalSource)

        assertTrue(fixture.state.cinematicMaps.apply(sheet, edited))
        assertContentEquals(edited, fixture.state.cinematicMaps.load(background).rawData)
        assertContentEquals(edited, MAP.read(fixture.state.nativeSprites.previewParser(fixture.parser)))
        assertTrue(fixture.workspaceState.projectAssetChangesSnapshot().any { it.assetPath == ASSET_PATH })

        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, originalSource, draft)
        assertFalse(fixture.state.undo())
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, draft, originalSource)
        assertTrue(fixture.state.undo())
        assertContentEquals(fixture.raw, fixture.state.cinematicMaps.load(title).rawData)
        assertTrue(fixture.state.redo())
        assertContentEquals(edited, fixture.state.cinematicMaps.load(title).rawData)

        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, originalSource, draft)
        assertFalse(fixture.state.cinematicMaps.restore(MAP.key))
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, draft, originalSource)
        assertTrue(fixture.state.cinematicMaps.restore(MAP.key))
        assertContentEquals(fixture.raw, fixture.state.cinematicMaps.load(title).rawData)
    }

    @Test
    fun `scene map growth relocates with guarded atomic undo and project reporting`() {
        val fixture = fixture(tightEnvelope = true)
        val title = RomVisualAssets.ASSETS.single { it.name == "Title screen" }
        val sheet = fixture.state.cinematicMaps.load(title)
        val noisy = ByteArray(MAP.rawBytes).also { Random(0x2B).nextBytes(it) }

        assertFalse(fixture.state.cinematicMaps.apply(sheet, noisy))
        val proposal = assertIs<TilesetAssetRelocationProposal.CinematicMap>(
            fixture.state.pendingTilesetAssetRelocation,
        )
        assertTrue(proposal.requiredBytes > proposal.inPlaceCapacity)

        val originalSource = fixture.workspaceState.projectSourceText(SOURCE_FILE)
        val draft = "$originalSource\n; unsaved relocation note\n"
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, originalSource, draft)
        assertFalse(fixture.state.confirmTilesetAssetRelocation())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        fixture.workspaceState.replaceProjectSource(SOURCE_FILE, draft, originalSource)

        assertFalse(fixture.state.cinematicMaps.apply(sheet, noisy))
        assertNotNull(fixture.state.pendingTilesetAssetRelocation)
        assertTrue(fixture.state.confirmTilesetAssetRelocation())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).single().kind == "cinematic-map")
        assertTrue(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))
        assertTrue(fixture.workspaceState.projectAssetChangesSnapshot().any {
            it.managed && it.originalAssetPath == ASSET_PATH
        })
        assertContentEquals(noisy, MAP.read(fixture.state.nativeSprites.previewParser(fixture.parser)))

        assertTrue(fixture.state.undo())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertFalse(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))
        assertTrue(fixture.state.redo())
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isNotEmpty())
        assertTrue(fixture.workspaceState.projectSourceText(SOURCE_FILE).contains("smedit:managed-asset-v1 begin"))

        assertTrue(fixture.state.cinematicMaps.restore(MAP.key))
        assertTrue(fixture.repository.managedAssets(fixture.projectFile.path).isEmpty())
        assertContentEquals(fixture.raw, fixture.state.cinematicMaps.load(title).rawData)
    }

    private data class Fixture(
        val projectFile: File,
        val parser: RomParser,
        val raw: ByteArray,
        val repository: AsmProjectWorkspaceRepository,
        val workspaceState: AsmWorkspaceState,
        val state: EditorState,
    )

    private fun fixture(tightEnvelope: Boolean): Fixture {
        val raw = ByteArray(MAP.rawBytes) { (it / 64 and 3).toByte() }
        val compressed = LZ5Compressor.compress(raw)
        val capacity = compressed.size + if (tightEnvelope) 1 else 64
        val pc = requireNotNull(snesLoRomToPc(MAP.address))
        val asset = AsmAssetRange(ASSET_PATH, pc, capacity)
        val referenceRoot = File(temporary, "reference-${temporary.listFiles().orEmpty().size}")
        val sourceRoot = File(referenceRoot, "src").apply { mkdirs() }
        val dataRoot = File(referenceRoot, "data").apply { mkdirs() }
        File(sourceRoot, "main.asm").writeText("incsrc $SOURCE_FILE\nincsrc $POINTER_FILE\n")
        File(sourceRoot, SOURCE_FILE).writeText(
            """
            ${MAP.sourceLabel}:
                incbin "../data/$ASSET_PATH"
            """.trimIndent() + "\n",
        )
        File(sourceRoot, POINTER_FILE).writeText(
            """
            LoadTitleMap:
                LDA.W #${MAP.sourceLabel}>>8&${'$'}FF00
                LDA.W #${MAP.sourceLabel}
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
        }
        val state = EditorState().apply { initForProject(projectFile, project) }
        val workspaceState = AsmWorkspaceState(projectRepository = repository)
        runBlocking { workspaceState.bindProject(projectFile.path, enabled = true, progressive = true) }
        state.attachAsmWorkspaceState(workspaceState)
        val rom = ByteArray(0x300000)
        ByteArray(capacity) { 0xFF.toByte() }.also(compressed::copyInto).copyInto(rom, pc)
        return Fixture(projectFile, RomParser(rom), raw, repository, workspaceState, state)
    }

    private companion object {
        val MAP = CinematicMapAssets.source("96FC04")
        const val ASSET_PATH = "Title_Mode7_Tilemap.bin"
        const val SOURCE_FILE = "bank_94..99.asm"
        const val POINTER_FILE = "bank_8B.asm"
    }
}
