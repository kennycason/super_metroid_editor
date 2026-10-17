package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmProjectRoomLayoutTest {
    @TempDir lateinit var directory: File
    private fun SourceRoomLayoutResource.word(x: Int, y: Int): Int {
        val index = 2 + (y * width + x) * 2
        return values[index] or (values[index + 1] shl 8)
    }
    private data class Fixture(val project: SmEditProject, val editor: EditorState, val workspace: AsmWorkspaceState,
        val bridge: AsmPlmSourceBridge, val base: RomParser, val preview: RomParser, val definition: ProjectNewRoom) {
        val room get() = project.rooms.getValue(project.roomKey(definition.previewRoomId))
        fun inputs() = bridge.layouts.document(bridge.sourceText()).resources(room, 16, 16)
        fun adopt() = assertTrue(editor.ensureLayoutSource(definition.name), editor.statusMessage)
    }
    private suspend fun fixture(): Fixture {
        val rootDirectory = File(directory, "room-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val src = File(rootDirectory, "reference/src").apply { mkdirs() }
        val data = File(rootDirectory, "reference/data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native source stays intact\n")
        File(src, DOOR_SOURCE_FILE).writeText("; Native door source\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(rootDirectory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val rom = saveSourceTestRom()
        for (start in listOf(0x1F000, 0x7F000, 0x10F000, 0x1A7000, 0x207000, 0x277000)) rom.fill(-1, start, start + 0x1000)
        val parser = RomParser(rom)
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        for (area in 0..6) {
            val pc = parser.snesToPc(MinimapData.AREA_MAP_POINTER_TABLE) + area * 3
            val address = MinimapData.AREA_MAP_ADDRESSES[area]
            word(pc, address and 65535); rom[pc + 2] = (address ushr 16).toByte()
            word(parser.snesToPc(MinimapData.MAP_DATA_POINTER_TABLE) + area * 2, MinimapData.MAP_STATION_ADDRESSES[area] and 65535)
        }
        val project = SmEditProject(File(rootDirectory, "base.sfc").apply { writeBytes(rom) }.path).apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog("", macros, emptyMap(), parser, emptyMap()), workspace)
        val definition = editor.createNewRoom(NewRoomCreationRequest("Project chamber", false, area = 1, mapX = 40, mapY = 20,
            width = 1, height = 1, tileset = 0), parser)
        val preview = editor.prepareWorkspaceParser(parser)
        editor.loadRoom(definition.previewRoomId, preview, preview.readRoomHeader(definition.previewRoomId)!!)
        return Fixture(project, editor, workspace, bridge, parser, preview, definition)
    }

    @Test fun `prebuild layout inspection is pure and adoption raw edits painting invalid drafts and source history share one authority`() = runBlocking {
        val f = fixture()
        f.editor.applyBulkEdits("Before adoption", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123, f.editor.readBts(2, 2), 7)))
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText()
        val revision = f.workspace.sourceBufferRevision
        val pixels = f.editor.workingLevelData!!.copyOf()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { f.definition.name })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT)), f.editor.statusMessage)
        val inspection = assertNotNull(f.workspace.roomSourceInspection)
        assertContains(inspection.text, AsmRoomLayoutSource.projectPrefix(f.definition.id))
        assertFalse(inspection.text.contains(AsmRoomLayoutSource.prefix(f.definition.previewRoomId)))
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(source, f.bridge.sourceText()); assertEquals(revision, f.workspace.sourceBufferRevision)
        val draft = assertNotNull(inspection.automaticEdit)
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft.text + "; My new-room layout note\n"), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection)
        assertEquals(1, f.room.asmLayoutSourceVersion)
        val state = f.editor.currentPlmStateId()!!
        assertContentEquals(pixels, f.inputs().getValue(state).bytes())
        assertTrue(f.room.levelResourceOperations.isEmpty())
        assertTrue(f.room.states.all { it.operations.none { op -> op.edits.isNotEmpty() } })
        val adopted = f.bridge.sourceText()
        val raw = adopted.replaceFirst("${'$'}8123", "${'$'}8456").replaceFirst("; BTS y=2", "; BTS y=2 | keep my notes")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, adopted, raw); f.editor.refreshLinkedPlms()
        assertEquals(0x8456, f.editor.readBlockWord(2, 2)); assertNull(f.editor.layoutPreviewProblem)
        f.editor.applyBulkEdits("Paint authored layout", listOf(TileEdit(2, 2, 0x8456, 0x8678, 7, 0x12)))
        assertContains(f.bridge.sourceText(), "${'$'}8678"); assertContains(f.bridge.sourceText(), "keep my notes")
        assertEquals(0x12, f.editor.readBts(2, 2))
        assertTrue(f.editor.undo(), f.editor.statusMessage); assertEquals(0x8456, f.editor.readBlockWord(2, 2))
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        val good = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, good.replaceFirst("${'$'}8678", "missing_layout_literal"))
        f.editor.refreshLinkedPlms(); assertNotNull(f.editor.layoutPreviewProblem)
        assertEquals(0x8678, f.editor.readBlockWord(2, 2), "Invalid drafts retain an explicitly marked last-good preview")
        val invalid = f.bridge.sourceText(); val invalidModel = Json.encodeToString(SmEditProject.serializer(), f.project)
        f.editor.applyBulkEdits("Blocked", listOf(TileEdit(2, 2, 0x8678, 0x8001, 0x12, 0)))
        assertEquals(invalid, f.bridge.sourceText()); assertEquals(invalidModel, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(invalidModel)
        assertFails { AsmRoomLayoutSource(invalid, AsmProjectRoomLabels.identities(cold)).resources(cold.rooms.getValue(cold.roomKey(f.definition.previewRoomId)), 16, 16) }
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        assertNull(f.editor.layoutPreviewProblem); assertEquals(0x8678, f.editor.readBlockWord(2, 2))
    }

    @Test fun `new state lifecycle preserves private layout wrappers with and without scalar source`() = runBlocking {
        for (properties in listOf(false, true)) {
            val f = fixture()
            val default = f.editor.roomSourceSnapshot().states.single().id
            if (properties) f.editor.setRoomStateDataChange(default, StateDataChange(musicTrack = 7), f.preview)
            f.adopt()
            val added = f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.preview)
            assertEquals(0, f.room.asmStateSelectorsSourceVersion)
            assertEquals(f.inputs().getValue(default).identity, f.inputs().getValue(added).identity)
            f.editor.switchRoomState(added, f.preview)
            val text = f.bridge.sourceText()
            val wrapper = AsmRoomLayoutSource.stateName(f.definition.previewRoomId, added, f.definition.id)
            val body = authoredMacroBody(text, wrapper)
            f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text,
                text.replaceFirst("macro $wrapper()\n$body", "macro $wrapper()\n$body    ; Keep this state's notes\n"))
            f.editor.refreshLinkedPlms()
            assertTrue(f.editor.makeCurrentLayoutUnique(f.preview), f.editor.statusMessage)
            assertContains(authoredMacroBody(f.bridge.sourceText(), wrapper), "Keep this state's notes")
            val original = f.editor.readBlockWord(2, 2)
            f.editor.applyBulkEdits("Private room state", listOf(TileEdit(2, 2, original, 0x8123)))
            assertNotEquals(f.inputs().getValue(default).identity, f.inputs().getValue(added).identity)
            assertEquals(0x8123, f.inputs().getValue(added).word(2, 2))
            assertEquals(original, f.inputs().getValue(default).word(2, 2))
            val copy = f.editor.addRoomState(added, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), f.preview)
            assertEquals(f.inputs().getValue(added), f.inputs().getValue(copy))
            f.editor.deleteRoomState(added, f.preview)
            assertEquals(setOf(default, copy), f.inputs().keys)
            assertTrue(f.editor.undo(), f.editor.statusMessage); assertEquals(0x8123, f.inputs().getValue(added).word(2, 2))
            assertTrue(f.editor.redo(), f.editor.statusMessage)
            if (properties) assertEquals(setOf(default, copy), f.bridge.properties.view(f.definition.previewRoomId).resource!!.states.keys)
            assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        }
    }

    @Test fun `layout adoption edits semantic links and undo follow project identity when preview addresses move`() = runBlocking {
        val f = fixture()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { f.definition.name })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT)), f.editor.statusMessage)
        val initial = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, initial.text + "; First layout edit\n"), f.editor.statusMessage)
        val default = f.editor.currentPlmStateId()!!
        val original = f.editor.readBlockWord(2, 2)
        f.editor.applyBulkEdits("Before relocation", listOf(TileEdit(2, 2, original, 0x8123)))
        val bank = f.bridge.sourceText(); val oldId = f.definition.previewRoomId
        val sibling = f.editor.createNewRoom(NewRoomCreationRequest("Earlier allocation", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        f.project.newRooms.remove(sibling); f.project.newRooms.add(0, sibling)
        val relocated = f.editor.prepareWorkspaceParser()
        assertNotEquals(oldId, f.definition.previewRoomId); assertEquals(oldId, sibling.previewRoomId)
        f.editor.loadRoom(f.definition.previewRoomId, relocated, relocated.readRoomHeader(f.definition.previewRoomId)!!)
        assertEquals(0x8123, f.editor.readBlockWord(2, 2)); assertEquals(bank, f.bridge.sourceText())
        val target = assertNotNull(AsmRoomLayoutSource.targetAt(bank.lines(), f.bridge.layouts.line(f.definition.previewRoomId, default)))
        assertEquals(f.definition.id, target.projectRoomId); assertEquals(f.definition.previewRoomId, target.resolveRoomId(f.project))
        assertEquals(f.room.states.single().resources.level, target.layoutId)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        assertEquals(original, f.editor.readBlockWord(2, 2))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        assertFalse(f.editor.hasManagedLayouts()); assertEquals(0, f.project.rooms[f.project.roomKey(oldId)]?.asmLayoutSourceVersion ?: 0)
        assertEquals(f.definition.previewRoomId, f.workspace.roomSourceInspection?.roomId)
        assertTrue(f.editor.undoStack.isEmpty(), "Source-driven ownership undo follows the relocated room's GUI history too")
        assertEquals(original, f.editor.readBlockWord(2, 2))
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        assertEquals(0x8123, f.editor.readBlockWord(2, 2))
        val foreign = RomParser(relocated.copyRomData().also { it[relocated.snesToPc(0x8F0000 or f.definition.previewRoomId) + 6] = 0x33 })
        f.editor.loadRoom(f.definition.previewRoomId, foreign, foreign.readRoomHeader(f.definition.previewRoomId)!!)
        assertFalse(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT)))
        f.editor.applyBulkEdits("Foreign parser blocked", listOf(TileEdit(2, 2, 0x8123, 0x8002)))
        assertEquals(bank, f.bridge.sourceText())
    }

    @Test fun `cold typed export remaps project layouts to independently allocated physical rooms and keeps compound guards no write`() = runBlocking {
        val f = fixture(); f.adopt()
        f.editor.applyBulkEdits("Source geometry", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123, 0, 0x12)))
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val bank = f.bridge.sourceText(); val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.editor.resizeRoom(1, 1, 2, 1))
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.preview))
        assertFalse(f.editor.applyBiomeTheme(com.supermetroid.editor.procgen.BiomeTheme.KEEP.copy(tilesetId = 1), f.preview))
        assertFalse(f.editor.applyGeneratedRoomOperation("Compound generation", listOf(TileEdit(2, 2, 0x8123, 0x8001)), emptyList(), emptyList()))
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(bank, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
        val cold = Json.decodeFromString<SmEditProject>(model)
        val room = cold.rooms.getValue(cold.roomKey(f.definition.previewRoomId))
        val layouts = AsmRoomLayoutSource(bank, AsmProjectRoomLabels.identities(cold)).resources(room, 16, 16)
        val rom = f.base.copyRomData()
        rom[f.base.snesToPc(0x8F0000 or f.definition.previewRoomId)] = 0x42 // Force an unrelated checked placement.
        val built = ProjectRoomExporter(cold, RomParser(rom), rom, sourceLayouts = mapOf(room.roomId to layouts)).exportRooms()
        val placed = built.newRoomIds.getValue(f.definition.id)
        assertNotEquals(f.definition.previewRoomId, placed)
        val parser = RomParser(rom)
        val state = parser.parseRoomStatesWithData(placed).single()
        assertContentEquals(pixels, parser.decompressLZ2(state.levelDataPtr))
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), cold), "Build placements and decoded source are disposable, not saved values")
    }

    @Test fun `structural door changes cannot introduce GUI tile deltas into a new source-owned layout`() = runBlocking {
        val f = fixture()
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, 0x91F8, 0, 0, 0, f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(f.definition.previewRoomId, preview, preview.readRoomHeader(f.definition.previewRoomId)!!)
        f.adopt()
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText(); val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.editor.canEditDoorStructure(f.definition.previewRoomId))
        assertContains(assertFailsWith<IllegalArgumentException> {
            f.editor.removeProjectRoomDoor(f.definition.previewRoomId, 0)
        }.message!!, "joint layout/source")
        assertContains(assertFailsWith<IllegalArgumentException> {
            f.editor.addProjectRoomDoor(f.definition.previewRoomId, 0x91F8, 0, 0, 0, preview)
        }.message!!, "joint layout/source")
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(source, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
    }

    @Test fun `ASM-first unloaded adoption preserves another canvas and its earlier history`() = runBlocking {
        val f = fixture()
        val sibling = f.editor.createNewRoom(NewRoomCreationRequest("Other canvas", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(sibling.previewRoomId, preview, preview.readRoomHeader(sibling.previewRoomId)!!)
        f.editor.applyBulkEdits("Unrelated canvas", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123)))
        val pixels = f.editor.workingLevelData!!.copyOf(); val history = f.editor.undoStack.toList()
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val request = RoomAsmSourceRequest(RoomAsmSourceKind.LAYOUT)
        val text = f.editor.inspectRoomSource(f.definition.name, request, f.definition.previewRoomId)
        assertContains(text, AsmRoomLayoutSource.projectPrefix(f.definition.id))
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertTrue(f.editor.adoptInspectedRoomSource(request, f.definition.name, f.definition.previewRoomId), f.editor.statusMessage)
        assertEquals(sibling.previewRoomId, f.editor.currentRoomId); assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(history, f.editor.undoStack); assertEquals(1, f.room.asmLayoutSourceVersion)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(0, f.room.asmLayoutSourceVersion)
        assertEquals(history, f.editor.undoStack); assertContentEquals(pixels, f.editor.workingLevelData)
    }

    @Test fun `deleted project identity cannot redirect source history or semantic links to a reused preview address`() = runBlocking {
        val f = fixture(); f.adopt()
        val bank = f.bridge.sourceText(); val state = f.editor.currentPlmStateId()!!
        val target = assertNotNull(AsmRoomLayoutSource.targetAt(bank.lines(), f.bridge.layouts.line(f.definition.previewRoomId, state)))
        val oldId = f.definition.previewRoomId
        assertTrue(f.editor.deleteProjectRoom(oldId, f.preview).canDelete)
        val sibling = f.editor.createNewRoom(NewRoomCreationRequest("Replacement identity", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        assertEquals(oldId, sibling.previewRoomId)
        f.editor.loadRoom(sibling.previewRoomId, preview, preview.readRoomHeader(sibling.previewRoomId)!!)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        assertNull(target.resolveRoomId(f.project))
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), "A deleted owner must fail before changing its old source buffer")
        assertEquals(bank, f.bridge.sourceText())
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertFalse(f.editor.hasManagedLayouts())
    }
}
