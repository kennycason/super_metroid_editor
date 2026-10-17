package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmProjectRoomPropertiesTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val project: SmEditProject, val editor: EditorState, val workspace: AsmWorkspaceState,
        val bridge: AsmPlmSourceBridge, val base: RomParser, val preview: RomParser, val definition: ProjectNewRoom) {
        val room get() = project.rooms.getValue(project.roomKey(definition.previewRoomId))
        fun input() = bridge.properties.document(bridge.sourceText()).resource(room)
    }
    private suspend fun fixture(): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native source stays intact\n")
        File(src, DOOR_SOURCE_FILE).writeText("; Native door source\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
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
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path).apply {
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

    @Test fun `new room source is inspectable before build and first typing GUI edits invalid drafts and preview relocation retain durable identity`() = runBlocking {
        val f = fixture()
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val revision = f.workspace.sourceBufferRevision
        val source = f.bridge.sourceText()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { f.definition.name })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)), f.editor.statusMessage)
        val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        val prefix = AsmRoomPropertiesSource.projectPrefix(f.definition.id)
        assertContains(draft.text, "macro ${prefix}Header()")
        assertFalse(draft.text.contains(AsmRoomPropertiesSource.prefix(f.definition.previewRoomId)))
        assertEquals(source, f.bridge.sourceText()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft.text.replace("db ${'$'}00 ; Music track", "db ${'$'}07 ; Music track / keep note")), f.workspace.error)
        f.editor.refreshLinkedPlms()
        val state = f.editor.currentPlmStateId()!!
        assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertTrue(f.room.states.all { it.stateDataChange == null })
        f.editor.setRoomStateDataChange(state, f.input().states.getValue(state).copy(musicTrack = 9), f.preview)
        assertEquals(9, f.input().states.getValue(state).musicTrack)
        assertContains(f.bridge.sourceText(), "; Music track / keep note")
        assertTrue(f.editor.undo(), f.editor.statusMessage); assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        val good = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, good.replace("db ${'$'}09 ; Music track", "db missing ; Music track"))
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.roomPropertiesPreviewProblem)
        assertEquals(9, f.room.states.single().asmStatePropertiesProjection!!.musicTrack)
        val bad = f.bridge.sourceText()
        f.editor.setRoomHeaderChange(RoomHeaderChange(upScroller = 0x72))
        assertEquals(bad, f.bridge.sourceText())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); f.editor.refreshLinkedPlms()
        val oldId = f.definition.previewRoomId
        val second = f.editor.createNewRoom(NewRoomCreationRequest("Earlier allocation", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        f.project.newRooms.remove(second); f.project.newRooms.add(0, second)
        val relocated = f.editor.prepareWorkspaceParser()
        assertNotEquals(oldId, f.definition.previewRoomId)
        f.editor.loadRoom(f.definition.previewRoomId, relocated, relocated.readRoomHeader(f.definition.previewRoomId)!!)
        assertNull(f.editor.roomPropertiesPreviewProblem)
        assertEquals(9, f.input().states.getValue(state).musicTrack)
        assertEquals(good, f.bridge.sourceText())
        val target = assertNotNull(AsmRoomPropertiesSource.targetAt(good.lines(), f.bridge.properties.line(f.definition.previewRoomId, state)))
        assertEquals(f.definition.id, target.projectRoomId)
        assertEquals(f.definition.previewRoomId, target.resolveRoomId(f.project))
        assertEquals(state, target.stateId)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms(); assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertFalse(f.editor.hasManagedRoomProperties())
        assertEquals(f.definition.previewRoomId, f.workspace.roomSourceInspection?.roomId, "Ownership undo must reopen this room, not the sibling now using its old address")
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms(); assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertNull(f.workspace.roomSourceInspection)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms(); assertEquals(9, f.input().states.getValue(state).musicTrack)
        val authored = f.bridge.sourceText()
        val foreign = RomParser(relocated.copyRomData().also { it[relocated.snesToPc(0x8F0000 or f.definition.previewRoomId) + 6] = 0x33 })
        f.editor.loadRoom(f.definition.previewRoomId, foreign, foreign.readRoomHeader(f.definition.previewRoomId)!!)
        assertFalse(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)))
        f.editor.setRoomStateDataChange(state, StateDataChange(musicTrack = 10), foreign)
        assertEquals(authored, f.bridge.sourceText(), "An unrelated/stale parser must not acquire the project room's source authority")
    }

    @Test fun `new room state membership keeps scalar declarations and reciprocal history without adopting native selectors`() = runBlocking {
        val f = fixture()
        val default = f.editor.roomSourceSnapshot().states.single().id
        f.editor.setRoomStateDataChange(default, StateDataChange(musicTrack = 7), f.preview)
        val added = f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.preview)
        assertEquals(2, f.room.states.size)
        assertEquals(0, f.room.asmStateSelectorsSourceVersion)
        assertEquals(7, f.input().states.getValue(added).musicTrack)
        f.editor.switchRoomState(added, f.preview)
        f.editor.setRoomStateDataChange(added, f.input().states.getValue(added).copy(musicTrack = 9), f.preview)
        assertEquals(9, f.input().states.getValue(added).musicTrack)
        assertEquals(7, f.input().states.getValue(default).musicTrack)
        val second = f.editor.createNewRoom(NewRoomCreationRequest("Earlier allocation", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        f.project.newRooms.remove(second); f.project.newRooms.add(0, second)
        val relocated = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(f.definition.previewRoomId, relocated, relocated.readRoomHeader(f.definition.previewRoomId)!!)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(listOf(default), f.room.states.map { it.id })
        assertEquals(default, f.editor.currentPlmStateId())
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(added, f.editor.currentPlmStateId())
        assertEquals(9, f.input().states.getValue(added).musicTrack)
        f.editor.deleteRoomState(added, relocated)
        assertEquals(listOf(default), f.room.states.map { it.id })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(9, f.input().states.getValue(added).musicTrack)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(setOf(default), f.input().states.keys)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val room = cold.rooms.getValue(cold.roomKey(f.definition.previewRoomId))
        assertEquals(7, AsmRoomPropertiesSource(f.bridge.sourceText(), AsmRoomPropertiesSource.projectIdentities(cold)).resource(room).states.getValue(default).musicTrack)
    }

    @Test fun `joint new room operations remain explicit no write guards after metadata adoption`() = runBlocking {
        val f = fixture()
        val state = f.editor.roomSourceSnapshot().states.single().id
        f.editor.setRoomStateDataChange(state, StateDataChange(musicTrack = 7), f.preview)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.editor.resizeRoom(1, 1, 2, 1))
        assertContains(f.editor.statusMessage, "Project-room Resize")
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.preview))
        assertContains(f.editor.statusMessage, "Project-room Reset")
        assertFalse(f.editor.applyBiomeTheme(com.supermetroid.editor.procgen.BiomeTheme.KEEP.copy(tilesetId = 1), f.preview))
        assertContains(f.editor.statusMessage, "Project-room themes")
        assertFalse(f.editor.applyGeneratedRoomOperation("Themed generation", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123)),
            emptyList(), emptyList(), com.supermetroid.editor.procgen.BiomeTheme.KEEP.copy(tilesetId = 1)))
        assertContains(f.editor.statusMessage, "Project-room themes")
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(source, f.bridge.sourceText()); assertContentEquals(pixels, f.editor.workingLevelData)
    }
}
