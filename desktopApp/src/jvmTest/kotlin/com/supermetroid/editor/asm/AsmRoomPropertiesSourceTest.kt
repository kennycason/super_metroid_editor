package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.RoomAsmNavigation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomPropertiesSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File) {
        val room get() = project.rooms.getValue("91F8")
        val parser get() = bridge.catalog.parser
        fun input() = AsmRoomPropertiesSource(bridge.sourceText()).resource(room)
        fun adopt() = assertTrue(editor.ensureRoomPropertiesSource("Landing Site"), editor.statusMessage)
    }
    private suspend fun fixture(built: Boolean = false, headered: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Original bank stays intact\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        if (built) AsmBuildArtifactRepository(repository).publishSuccess(file.path, ByteArray(0), listOf("Fixture build"), "fixture")
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = objectSourceTestRom()
        rom.fill(-1, 0x7F000, 0x80000); rom.fill(-1, 0x207000, 0x208000)
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        val romBytes = if (headered) ByteArray(512) + rom else rom
        val parser = RomParser(romBytes)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(romBytes) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        return Fixture(workspace, bridge, project, editor, file)
    }

    @Test fun `automatic scalar source is an editable overlay with no adoption build marker save or search side effects`() = runBlocking {
        val f = fixture(built = true)
        assertTrue(f.editor.saveProject())
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText(); val disk = f.workspace.projectWorkspace!!.workingDirectory.resolve("src/$PLM_SOURCE_FILE").readText()
        val report = f.workspace.buildReport; val revision = f.workspace.sourceBufferRevision
        assertNotNull(report); assertFalse(f.workspace.sourceBuildRequired)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)), f.editor.statusMessage)
        val preview = assertNotNull(f.workspace.roomSourceInspection)
        val draft = assertNotNull(preview.automaticEdit)
        assertTrue(f.workspace.isProjectSourceEditable); assertEquals(draft.lineIndex, f.workspace.selectedLineIndex)
        assertEquals(draft.text, f.workspace.sourceEditorText(PLM_SOURCE_FILE)); assertContains(f.workspace.sourceStatusSummary, "first edit")
        assertEquals(source, f.workspace.sourceTexts().single { it.fileId == PLM_SOURCE_FILE }.text)
        assertTrue(f.workspace.previewProjectSourceReplacement("SmeditRoomInfo_", "oops", true).matches.isEmpty())
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft.text))
        f.workspace.selectSourceEditorMatch(PLM_SOURCE_FILE, draft.lineIndex + 2, 7)
        assertSame(preview, f.workspace.roomSourceInspection)
        assertTrue(f.workspace.saveAllSources()); assertTrue(f.workspace.saveSource(PLM_SOURCE_FILE))
        assertEquals(disk, f.workspace.projectWorkspace!!.workingDirectory.resolve("src/$PLM_SOURCE_FILE").readText())
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(source, f.bridge.sourceText())
        assertEquals(revision, f.workspace.sourceBufferRevision); assertSame(report, f.workspace.buildReport)
        assertFalse(f.editor.dirty); assertFalse(f.workspace.sourceBuildRequired); assertFalse(f.workspace.hasProjectSourceChanges)
        assertTrue(f.editor.undoStack.isEmpty()); assertNull(f.workspace.sourceUndoHead(PLM_SOURCE_FILE))
        f.workspace.openSource(PLM_SOURCE_FILE); f.workspace.goBack()
        assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `first scalar edit preserves inherited GUI values private states notes and one reciprocal undo boundary`() = runBlocking {
        val f = fixture(built = true)
        f.room.roomHeaderChange = RoomHeaderChange(width = 2, height = 2, doorOut = 0x9400, upScroller = 0x72, creBitflag = 3)
        f.room.stateDataChange = StateDataChange(musicData = 0x18, bgScrolling = 0x0201)
        f.room.states.first().stateDataChange = StateDataChange(musicTrack = 7)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        f.editor.setCurrentLayoutEditScope(com.supermetroid.editor.ui.LayoutEditScope.ALL_SHARING_STATES)
        f.editor.applyBulkEdits("Older canvas edit", listOf(TileEdit(5, 5, 0, 0x8123)))
        assertTrue(f.editor.saveProject())
        val model = Json.encodeToString(SmEditProject.serializer(), f.project); val source = f.bridge.sourceText()
        val states = f.room.states.toList(); val selected = f.editor.currentPlmStateId(); val working = f.editor.workingLevelData!!.copyOf()
        val history = f.editor.undoStack.toList()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES, f.room.states.first().id)))
        val planned = f.workspace.sourceEditorText(PLM_SOURCE_FILE)
        val edited = planned.replace("db ${'$'}72 ; Up scroller", "db ${'$'}71 ; Up scroller · my note")
        assertNotEquals(planned, edited)
        val line = f.workspace.selectedLineIndex + 2
        f.workspace.selectSourcePosition(line, 8); val serial = f.workspace.navigationSerial
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, edited), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection); assertEquals(serial, f.workspace.navigationSerial); assertEquals(line, f.workspace.selectedLineIndex)
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion); assertEquals(RoomHeaderChange(width = 2, height = 2, doorOut = 0x9400), f.room.roomHeaderChange)
        assertNull(f.room.stateDataChange); assertTrue(f.room.states.all { it.stateDataChange == null })
        assertEquals(0x71, f.input().header.upScroller); assertEquals(3, f.input().header.creBitflag)
        assertTrue(f.input().states.values.all { it.musicData == 0x18 && it.bgScrolling == 0x0201 })
        assertEquals(7, f.input().states.getValue(f.room.states.first().id).musicTrack)
        assertEquals(selected, f.editor.currentPlmStateId()); assertContentEquals(working, f.editor.workingLevelData)
        assertTrue(f.room.states.zip(states).all { it.first === it.second })
        assertEquals(history.size + 1, f.editor.undoStack.size); assertTrue(f.workspace.sourceBuildRequired)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(source, f.bridge.sourceText())
        assertEquals(history, f.editor.undoStack); assertEquals(planned, f.workspace.sourceEditorText(PLM_SOURCE_FILE))
        assertFalse(f.workspace.sourceBuildRequired); assertEquals(line, f.workspace.selectedLineIndex)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(edited, f.bridge.sourceText()); assertNull(f.workspace.roomSourceInspection)
        f.editor.setRoomStateDataChange(selected!!, f.input().states.getValue(selected).copy(musicTrack = 9), f.parser)
        assertEquals(9, f.input().states.getValue(selected).musicTrack); assertContains(f.bridge.sourceText(), "my note")
        assertTrue(f.editor.undo(), f.editor.statusMessage); assertEquals(edited, f.bridge.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(history, f.editor.undoStack)
        assertContentEquals(working, f.editor.workingLevelData); assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(edited, f.bridge.sourceText())
    }

    @Test fun `incomplete first state typing keeps a last good preview can save repair and undo adoption`() = runBlocking {
        val f = fixture()
        val selected = f.editor.currentPlmStateId()!!
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES, selected)))
        val planned = f.workspace.sourceEditorText(PLM_SOURCE_FILE)
        val broken = planned.replace("db ${'$'}00 ; Music track", "db ${'$'} ; Music track")
        assertNotEquals(planned, broken)
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, broken), f.editor.statusMessage)
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion); assertNotNull(f.editor.roomPropertiesPreviewProblem)
        assertNotNull(f.room.asmHeaderPropertiesProjection); assertNotNull(f.room.states.single { it.id == selected }.asmStatePropertiesProjection)
        f.editor.setRoomStateDataChange(selected, StateDataChange(musicTrack = 9), f.parser)
        assertEquals(broken, f.bridge.sourceText()); assertFails { f.input() }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error); assertTrue(f.editor.saveProject())
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val fresh = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { cold }
        assertNull(fresh.properties.view(0x91F8).resource); assertNotNull(fresh.properties.view(0x91F8).problem)
        val repaired = planned.replace("db ${'$'}00 ; Music track", "db ${'$'}07 ; Music track")
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, repaired))
        f.editor.refreshLinkedPlms(); assertNull(f.editor.roomPropertiesPreviewProblem)
        assertEquals(7, f.input().states.getValue(selected).musicTrack)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE)); assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertFalse(f.editor.hasManagedRoomProperties()); assertNull(f.editor.roomPropertiesPreviewProblem)
        assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        Unit
    }

    @Test fun `undoing first state field editing restores native tileset scrolling and canvas without a reload`() = runBlocking {
        val f = fixture()
        val selected = f.editor.currentPlmStateId()!!
        fun scrolling() = f.editor.applyCurrentStateData(f.parser.readRoomHeader(0x91F8)!!, f.parser).bgScrolling
        val tileset = f.editor.currentTilesetId; val originalScrolling = scrolling()
        val working = f.editor.workingLevelData!!.copyOf()
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES, selected)))
        val planned = f.workspace.sourceEditorText(PLM_SOURCE_FILE)
        val staged = snapshotRoomForResize(f.room).also { it.asmRoomPropertiesSourceVersion = 1 }
        val source = AsmRoomPropertiesSource(planned)
        val edited = source.editStates(staged, mapOf(selected to source.resource(staged).states.getValue(selected).copy(tileset = 1, bgScrolling = 0x0201)))
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, edited), f.editor.statusMessage)
        assertEquals(1, f.editor.currentTilesetId); assertEquals(0x0201, scrolling())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(tileset, f.editor.currentTilesetId); assertEquals(originalScrolling, scrolling())
        assertEquals(selected, f.editor.currentPlmStateId()); assertContentEquals(working, f.editor.workingLevelData)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(1, f.editor.currentTilesetId); assertEquals(0x0201, scrolling())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(tileset, f.editor.currentTilesetId); assertEquals(originalScrolling, scrolling())
    }

    @Test fun `automatic scalar source preserves CRLF trailing notes and refuses retained declarations or newer model writes`() = runBlocking {
        val f = fixture()
        val original = f.bridge.sourceText()
        val before = original.replace("\n", "\r\n") + "; trailing note  \r\n  "
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original, before)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { "Landing Site" })
        val request = RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES)
        assertTrue(navigation.open(request), f.editor.statusMessage)
        val draft = f.workspace.sourceEditorText(PLM_SOURCE_FILE)
        assertTrue(draft.startsWith(before)); assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(draft))
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft + "; my note\r\n"))
        f.room.fxChange = FxChange(fxType = 2)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project); val source = f.bridge.sourceText(); val revision = f.workspace.sourceBufferRevision
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(source, f.bridge.sourceText()); assertEquals(revision, f.workspace.sourceBufferRevision)
        f.room.fxChange = null
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error); assertEquals(before, f.bridge.sourceText())
        f.workspace.finishRoomSourceInspection()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before + "  MACRO SmeditRoomInfo_91F8_Header()\r\nendmacro\r\n")
        val retainedModel = Json.encodeToString(SmEditProject.serializer(), f.project); val retained = f.bridge.sourceText()
        assertFalse(navigation.open(request)); assertContains(f.editor.statusMessage, "Retained")
        assertEquals(retainedModel, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(retained, f.bridge.sourceText())
    }

    @Test fun `native valued owned scalars still materialize complete byte claims and reject competing patches`() = runBlocking {
        val f = fixture(); f.project.patches.clear(); f.adopt()
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val logs = mutableListOf<String>()
        val inputs = mapOf(0x91F8 to f.input())
        fun build() = com.supermetroid.editor.ui.RomExporter(cold, f.parser, onLog = logs::add,
            logWritePlanDetails = false, sourceRoomProperties = inputs).build()
        val result = assertNotNull(build(), logs.joinToString("\n"))
        val materialized = AsmSourceRoomMaterializer().materialize(cold, result.writeReport, result.resultRom)
        assertEquals(setOf("91F8"), materialized.roomIds, "Source ownership must survive a zero-difference ROM plan")
        val headerPc = f.parser.snesToPc(0x8F91F8)
        val offsets = listOf(headerPc until headerPc + 4, headerPc + 6 until headerPc + 9) +
            f.parser.findAllStateDataOffsets(0x91F8).flatMap { listOf(it + 3 until it + 6, it + 12 until it + 14) }
        for (range in offsets) for (pc in range) assertTrue(result.writeReport.writes.any {
            it.owner == ASM_ROOM_GRAPH_OWNER && pc in it.offset..it.endInclusive
        }, "Authored scalar byte ${pc.toString(16)} must be claimed even when unchanged")
        cold.patches += SmPatch("owned-scalar-conflict", "Cannot overwrite an unchanged owned scalar", enabled = true,
            writes = mutableListOf(PatchWrite(headerPc.toLong(), listOf(f.parser.getRomData()[headerPc].toInt() and 255 xor 1),
                expectedBytes = listOf(f.parser.getRomData()[headerPc].toInt() and 255))))
        assertNull(build()); assertTrue(logs.any { "conflict" in it.lowercase() }, logs.joinToString("\n"))
    }

    @Test fun `scalar claims use actual relocated state records and headered ROM offsets without claiming pointers`() = runBlocking {
        val f = fixture(headered = true); f.adopt()
        val originalStates = f.parser.inspectRoomStates(0x91F8).states.map { it.stateDataPcOffset }
        f.editor.addRoomState(f.room.states.last().id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 2), f.parser)
        val selectors = assertNotNull(f.bridge.selectors.view(0x91F8).resource)
        val target = f.parser.copyRomData()
        val result = ProjectRoomExporter(f.project, f.parser, target,
            sourceRoomProperties = mapOf(0x91F8 to f.input()), sourceSelectors = mapOf(0x91F8 to selectors)).exportRooms()
        val expected = mutableSetOf<Int>()
        val headerPc = f.parser.snesToPc(0x8F91F8)
        expected.addAll(headerPc until headerPc + 4); expected.addAll(headerPc + 6 until headerPc + 9)
        val states = RomParser(target).inspectRoomStates(0x91F8).states
        assertEquals(f.room.states.size, states.size)
        assertTrue(states.none { it.stateDataPcOffset in originalStates })
        for (state in states) {
            val pc = assertNotNull(state.stateDataPcOffset)
            expected.addAll(pc + 3 until pc + 6)
            expected.addAll(pc + 12 until pc + 14)
        }
        assertEquals(expected, result.sourceRoomPropertyClaims.flatMap { (pc, size) -> (pc until pc + size).toList() }.toSet())
    }

    @Test fun `adoption flattens legacy header and state overrides without serializing duplicate values`() = runBlocking {
        val f = fixture()
        f.room.roomHeaderChange = RoomHeaderChange(upScroller = 0x72, creBitflag = 3, doorOut = 0x9400)
        f.room.stateDataChange = StateDataChange(musicData = 0x18)
        f.room.states.first().stateDataChange = StateDataChange(musicTrack = 7)
        val old = snapshotRoomForResize(f.room)
        val before = f.bridge.sourceText()
        f.adopt()
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(RoomHeaderChange(doorOut = 0x9400), f.room.roomHeaderChange)
        assertNull(f.room.stateDataChange)
        assertTrue(f.room.states.all { it.stateDataChange == null })
        assertEquals(0x72, f.input().header.upScroller)
        assertEquals(3, f.input().header.creBitflag)
        assertEquals(7, f.input().states.getValue(f.room.states.first().id).musicTrack)
        assertTrue(f.input().states.values.all { it.musicData == 0x18 })
        val json = Json.encodeToString(SmEditProject.serializer(), f.project)
        assertFalse(json.contains("PropertiesProjection"))
        val decoded = Json.decodeFromString<SmEditProject>(json).rooms.getValue("91F8")
        assertNull(decoded.asmHeaderPropertiesProjection)
        assertTrue(decoded.states.all { it.effectiveStateDataChange == null })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertEquals(old, f.room)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(0x72, f.room.effectiveHeaderChange?.upScroller)
    }

    @Test fun `raw ASM refreshes header and selected state and GUI writes preserve other literals and comments`() = runBlocking {
        val f = fixture(); f.adopt()
        val state = f.editor.currentPlmStateId()!!
        val other = f.room.states.first { it.id != state }.id
        val oldOther = f.input().states.getValue(other)
        val before = f.bridge.sourceText()
        val source = AsmRoomPropertiesSource(before)
        val header = source.editHeader(f.room, f.input().header.copy(upScroller = 0x71, creBitflag = 2))
        val raw = AsmRoomPropertiesSource(header).editStates(f.room,
            mapOf(state to f.input().states.getValue(state).copy(musicData = 0x18, musicTrack = 5, bgScrolling = 0x0201)))
            .replace("; Music data", "; Music data / handwritten annotation")
        val previewVersion = f.editor.editVersion
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        f.editor.refreshLinkedPlms()
        assertTrue(f.editor.editVersion > previewVersion, "Raw property changes must invalidate GUI consumers")
        val base = f.parser.readRoomHeader(0x91F8)!!
        assertEquals(0x71, f.editor.applyHeaderChanges(base).upScroller)
        assertEquals(2, f.editor.applyHeaderChanges(base).creBitflag)
        assertEquals(0x0201, f.editor.applyCurrentStateData(base, f.parser).bgScrolling)
        assertEquals(0x18, f.editor.applyCurrentStateData(base, f.parser).musicData)
        f.editor.setRoomStateDataChange(state, f.input().states.getValue(state).copy(musicTrack = 7), f.parser)
        assertEquals(7, f.input().states.getValue(state).musicTrack)
        assertEquals(0x18, f.input().states.getValue(state).musicData)
        assertEquals(oldOther, f.input().states.getValue(other))
        assertContains(f.bridge.sourceText(), "; Music data / handwritten annotation")
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(raw, f.bridge.sourceText())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        val edited = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, edited, edited + "; newer hand edit\n")
        assertFalse(f.editor.undo(), "A newer source edit must not be overwritten")
    }

    @Test fun `GUI header edits and reverting state fields to native update source without creating overrides`() = runBlocking {
        val f = fixture()
        f.editor.setRoomHeaderChange(RoomHeaderChange(upScroller = 0x70))
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(0x70, f.input().header.upScroller)
        assertNull(f.room.roomHeaderChange)
        val id = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(id, StateDataChange(musicData = 0x18, musicTrack = 7), f.parser)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
        f.editor.setRoomStateDataChange(id, null, f.parser)
        assertEquals(f.bridge.properties.nativeState(f.room, id), f.input().states.getValue(id))
        assertTrue(f.room.states.all { it.stateDataChange == null })
    }

    @Test fun `invalid saveable drafts retain last good values and block GUI writes cloning and builds`() = runBlocking {
        val f = fixture(); f.adopt()
        val good = f.bridge.sourceText()
        val resource = f.input()
        val broken = good.replaceFirst("db \$00 ; Area", "db \$FF ; Area")
        assertNotEquals(good, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.roomPropertiesPreviewProblem)
        assertEquals(resource.header, f.room.effectiveHeaderChange)
        f.editor.setRoomHeaderChange(RoomHeaderChange(upScroller = 4))
        assertEquals(broken, f.bridge.sourceText())
        assertFails { f.editor.addRoomState(f.room.states.last().id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 1), f.parser) }
        assertEquals(broken, f.bridge.sourceText())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertFails { f.input() }
        assertFails { ProjectRoomExporter(f.project, f.parser, f.parser.copyRomData()).exportRooms() }
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { reopened }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, reopened); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        }
        assertNotNull(editor.roomPropertiesPreviewProblem)
        assertFalse(editor.roomPropertiesPreviewAvailable())
    }

    @Test fun `saved source reloads and typed export writes every field without modifying the project model`() = runBlocking {
        val f = fixture(); f.adopt()
        val state = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(state, StateDataChange(tileset = 1, musicData = 0x18, musicTrack = 7, bgScrolling = 0x0201), f.parser)
        f.editor.setRoomHeaderChange(RoomHeaderChange(index = 0x12, mapX = 8, mapY = 9, upScroller = 0x71, downScroller = 0x72, creBitflag = 3))
        val inputs = f.input()
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val encoded = Json.encodeToString(SmEditProject.serializer(), f.project)
        val project = Json.decodeFromString<SmEditProject>(encoded)
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        }
        assertEquals(inputs.header, project.rooms.getValue("91F8").effectiveHeaderChange)
        editor.switchRoomState(state, f.parser)
        assertEquals(0x0201, editor.applyCurrentStateData(f.parser.readRoomHeader(0x91F8)!!, f.parser).bgScrolling)
        val beforeExport = Json.encodeToString(SmEditProject.serializer(), project)
        val target = f.parser.copyRomData()
        ProjectRoomExporter(project, f.parser, target, sourceRoomProperties = mapOf(0x91F8 to inputs)).exportRooms()
        val built = RomParser(target)
        val h = built.readRoomHeader(0x91F8)!!
        assertEquals(0x12, h.index); assertEquals(8, h.mapX); assertEquals(9, h.mapY)
        assertEquals(0x71, h.upScroller); assertEquals(0x72, h.downScroller); assertEquals(3, h.creBitflag)
        built.parseRoomStatesWithData(0x91F8).forEachIndexed { index, data ->
            val expected = inputs.states.getValue(f.room.states[index].id)
            assertEquals(expected, StateDataChange(data.tileset, data.musicData, data.musicTrack, data.bgScrolling))
        }
        assertEquals(beforeExport, Json.encodeToString(SmEditProject.serializer(), project))
    }

    @Test fun `properties coexist with layout resize theme reset and state cloning without dual ownership`() = runBlocking {
        val f = fixture(); f.adopt()
        assertTrue(f.editor.ensureLayoutSource("Landing Site"), f.editor.statusMessage)
        val id = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateDataChange(id, StateDataChange(musicTrack = 7), f.parser)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(3, f.room.roomHeaderChange?.width)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertNull(f.room.roomHeaderChange)
        val source = f.bridge.sourceText()
        assertTrue(f.editor.applyBiomeTheme(BiomeTheme.KEEP.copy(tilesetId = 1), f.parser), f.editor.statusMessage)
        assertEquals(1, f.input().states.getValue(id).tileset)
        assertTrue(f.room.states.all { it.stateDataChange == null })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(source, f.bridge.sourceText())
        val newId = f.editor.addRoomState(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 2), f.parser)
        assertEquals(f.input().states.getValue(id), f.input().states.getValue(newId))
        f.editor.deleteRoomState(newId, f.parser)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0, f.room.asmRoomPropertiesSourceVersion)
        assertFalse(f.bridge.sourceText().contains("macro SmeditRoomInfo_"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(1, f.room.asmRoomPropertiesSourceVersion)
        assertEquals(7, f.input().states.getValue(id).musicTrack)
    }

    @Test fun `unsupported direct structural header edits stop before model or source mutations`() = runBlocking {
        val f = fixture(); f.adopt()
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText()
        f.editor.setRoomHeaderChange(RoomHeaderChange(width = 3))
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(source, f.bridge.sourceText())
    }

    @Test fun `a room without door dependencies moves and resets using only its available header bank`() = runBlocking {
        val f = fixture(); f.adopt()
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val source = f.bridge.sourceText()
        assertTrue(f.editor.reassignRoomArea(0x91F8, 1, f.parser).success, f.editor.statusMessage)
        val moved = Json.encodeToString(SmEditProject.serializer(), f.project); val movedSource = f.bridge.sourceText()
        assertEquals(1, f.input().header.area)
        assertNull(f.workspace.sourceUndoHead(DOOR_SOURCE_FILE))
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertFalse(f.project.rooms.getValue("91F8").hasEdits)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(moved, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(movedSource, f.bridge.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project)); assertEquals(source, f.bridge.sourceText())
    }

    @Test fun `literal lens rejects duplicate expression reordered widths and invalid byte and word ranges`() = runBlocking {
        val f = fixture(); f.adopt()
        val text = f.bridge.sourceText()
        val macro = text.substring(text.indexOf("macro SmeditRoomInfo_"), text.indexOf("endmacro") + "endmacro".length) + "\n"
        assertFails { AsmRoomPropertiesSource(text + macro).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("; Area", "+1 ; Area")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("db ", "dw ")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("; Area", "000 ; Area")).resource(f.room) }
        assertFails { AsmRoomPropertiesSource(text.replaceFirst("dw ", "dw \$10000,")).resource(f.room) }
        val lines = text.lines()
        val id = f.room.states.first().id
        assertEquals(AsmEditorTarget.Room(0x91F8, stateId = id), AsmRoomPropertiesSource.targetAt(lines, f.bridge.properties.line(0x91F8, id) + 2))
        assertEquals(AsmEditorTarget.Room(0x91F8), AsmRoomPropertiesSource.targetAt(lines, f.bridge.properties.line(0x91F8) + 1))
        assertNull(AsmRoomPropertiesSource.targetAt(lines + listOf("macro Other()", " db 0"), lines.size + 1))
    }

    @Test fun `every writable vanilla catalog room retains all native scalar fields through typed source declarations`() {
        val parser = TestRomHelper.requireRomParser()
        for (info in parser.roomCatalog.rooms) {
            val id = info.getRoomIdAsInt()
            val header = assertNotNull(parser.readRoomHeader(id), info.name)
            // The existing exporter excludes the engine's debug/unused area 7.
            if (header.area !in 0..6) continue
            val room = RoomEdits(id).also { it.ensureStateManifest(parser); it.asmRoomPropertiesSourceVersion = 1 }
            val data = parser.parseRoomStatesWithData(id)
            val properties = SourceRoomProperties(RoomHeaderChange(index = header.index, area = header.area, mapX = header.mapX,
                mapY = header.mapY, upScroller = header.upScroller, downScroller = header.downScroller, creBitflag = header.creBitflag),
                room.states.associate { state ->
                    val native = data[state.baseSourceStateIndex()!!]
                    state.id to StateDataChange(native.tileset, native.musicData, native.musicTrack, native.bgScrolling)
                })
            assertEquals(properties, AsmRoomPropertiesSource(AsmRoomPropertiesSource.render(room, info.name, properties)).resource(room), info.name)
        }
    }
}
