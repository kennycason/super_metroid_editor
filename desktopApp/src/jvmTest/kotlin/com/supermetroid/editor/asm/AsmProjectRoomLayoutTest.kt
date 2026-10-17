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
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_A1.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native source stays intact\n")
        File(src, DOOR_SOURCE_FILE).writeText("; Native door source\n")
        File(src, ENEMY_SOURCE_FILE).writeText("; Native population source\n")
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

    @Test fun `project initial grids FX and paired enemy source use durable declarations and typed cold export`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.ensureInitialScrollSource(f.definition.name), f.editor.statusMessage)
        f.editor.setScroll(0, 0, 2, 1)
        assertTrue(f.editor.ensureFxSource(f.definition.name), f.editor.statusMessage)
        val state = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateFxChange(state, FxChange(fxType = 2, liquidSurfaceStart = 0x123), f.preview)
        assertTrue(f.editor.ensureEnemySource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.editor.ensureCurrentPlmSource(f.definition.name), f.editor.statusMessage)
        f.editor.addPlm(0xEEDB, 4, 4, 0x123, ItemStateScope.THIS_STATE)
        val ids = AsmProjectRoomLabels.identities(f.project)
        val id = f.definition.previewRoomId
        assertEquals(listOf(2), f.bridge.initialScrolls.view(id, state).resource!!.values)
        assertContains(f.bridge.sourceText(), AsmInitialScrollSource.prefix(id, f.definition.id))
        assertContains(f.bridge.fx.sourceText(), AsmFxSource.prefix(id, f.definition.id))
        assertContains(f.bridge.enemies.sourceText(), AsmEnemySource.prefix(id, f.definition.id))
        assertEquals(1, f.editor.workingPlms.size, f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bytes = f.base.copyRomData()
        val result = ProjectRoomExporter(cold, RomParser(bytes), bytes,
            sourcePlmSets = mapOf(id to AsmRoomObjectSource(f.bridge.sourceText(), emptyMap(), ids).resources(f.room, 1)),
            sourceInitialScrolls = mapOf(id to AsmInitialScrollSource(f.bridge.sourceText(), emptyMap(), ids).resources(f.room, 1, 1)),
            sourceFx = mapOf(id to AsmFxSource(f.bridge.fx.sourceText(), ids).resources(f.room)),
            sourceEnemies = mapOf(id to AsmEnemySource(f.bridge.enemies.sourceText(), emptyMap(), ids).resources(f.room))).exportRooms()
        val built = RomParser(bytes)
        val room = built.readRoomHeader(result.newRoomIds.getValue(f.definition.id))!!
        assertEquals(listOf(2), built.parseScrollData(room.roomScrollsPtr, 1, 1).toList())
        assertEquals(0x123, built.parseFxEntries(room.fxPtr).single().liquidSurfaceStart)
        assertTrue(built.parseEnemyPopulation(room.enemySetPtr).isEmpty())
        assertEquals(listOf(RomParser.PlmEntry(0xEEDB, 4, 4, 0x123)), built.parsePlmSet(room.plmSetPtr))
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
            assertEquals(1, f.room.asmStateSelectorsSourceVersion)
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

    @Test fun `new-room structured source inspection adopts only on first edit and ownership undo restores seed previews`() = runBlocking {
        for (kind in listOf(RoomAsmSourceKind.PLMS, RoomAsmSourceKind.INITIAL_SCROLLS, RoomAsmSourceKind.FX, RoomAsmSourceKind.ENEMIES)) {
            val f = fixture()
            val navigation = RoomAsmNavigation(f.editor, f.workspace, { f.definition.name })
            val model = Json.encodeToString(SmEditProject.serializer(), f.project)
            val revision = f.workspace.sourceBufferRevision
            assertTrue(navigation.open(RoomAsmSourceRequest(kind)), "$kind: ${f.editor.statusMessage}")
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
            assertEquals(revision, f.workspace.sourceBufferRevision)
            val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit, kind.name)
            assertTrue(f.workspace.updateSourceEditText(kind.fileId, draft.text + "; First edit\n"), "$kind: ${f.editor.statusMessage}")
            assertNull(f.workspace.roomSourceInspection)
            assertTrue(f.workspace.undoSourceEdit(kind.fileId), "$kind: ${f.workspace.error}")
            f.editor.refreshLinkedPlms()
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
            assertEquals(0, f.editor.workingPlms.size)
            assertTrue(f.workspace.redoSourceEdit(kind.fileId), "$kind: ${f.workspace.error}")
            f.editor.refreshLinkedPlms()
            assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        }
    }

    @Test fun `new-room state membership clones and retires all owned resource wrappers atomically`() = runBlocking {
        val f = fixture()
        f.adopt()
        assertTrue(f.editor.ensureCurrentPlmSource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.editor.ensureInitialScrollSource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.editor.ensureFxSource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.editor.ensureEnemySource(f.definition.name), f.editor.statusMessage)
        val default = f.editor.currentPlmStateId()!!
        f.editor.addPlm(0xEEDB, 4, 4, 0x123, ItemStateScope.THIS_STATE)
        val copy = f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.preview)
        val id = f.definition.previewRoomId
        assertEquals(setOf(default, copy), f.bridge.selectors.view(id).resource!!.order.toSet())
        for (state in listOf(default, copy)) {
            assertNull(f.bridge.managedView(id, state).problem)
            assertEquals(1, f.bridge.managedView(id, state).document!!.entries.size)
            assertNull(f.bridge.initialScrolls.view(id, state).problem)
            assertNull(f.bridge.fx.view(id, state).problem)
            assertNull(f.bridge.enemies.view(id, state).problem)
        }
        f.editor.deleteRoomState(copy, f.preview)
        assertEquals(listOf(default), f.bridge.selectors.view(id).resource!!.order)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertNull(f.bridge.enemies.view(id, copy).problem)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
    }

    @Test fun `new-room family ownership histories preserve unrelated adopted banks and invalid first drafts keep marked seed projections`() = runBlocking {
        val f = fixture()
        val state = f.editor.roomSourceSnapshot().states.single().id
        assertTrue(f.editor.ensureInitialScrollSource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.editor.ensureEnemySource(f.definition.name), f.editor.statusMessage)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(0, f.room.asmInitialScrollSourceVersion)
        assertEquals(1, f.room.asmEnemySourceVersion)
        val navigation = RoomAsmNavigation(f.editor, f.workspace, { f.definition.name })
        assertTrue(navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.INITIAL_SCROLLS)), f.editor.statusMessage)
        val draft = f.workspace.roomSourceInspection!!.automaticEdit!!
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft.text.replace("db ${'$'}01", "db broken_color")), f.editor.statusMessage)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.initialScrollPreviewProblem)
        assertEquals(listOf(1), f.bridge.initialScrolls.view(f.definition.previewRoomId, state).resource!!.values)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertContains(f.bridge.sourceText(), "broken_color")
        assertFails { AsmInitialScrollSource(f.bridge.sourceText(), emptyMap(), AsmProjectRoomLabels.identities(f.project)).resources(f.room, 1, 1) }
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(0, f.room.asmInitialScrollSourceVersion)
        assertEquals(1, f.room.asmEnemySourceVersion)
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

    @Test fun `cold typed export remaps project layouts to independently allocated physical rooms`() = runBlocking {
        val f = fixture(); f.adopt()
        f.editor.applyBulkEdits("Source geometry", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123, 0, 0x12)))
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val bank = f.bridge.sourceText(); val pixels = f.editor.workingLevelData!!.copyOf()
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
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.editor.canEditDoorStructure(f.definition.previewRoomId))
        f.editor.removeProjectRoomDoor(f.definition.previewRoomId, 0)
        assertTrue(f.bridge.doors.view(f.definition.previewRoomId).resource!!.entries.isEmpty())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(1, f.bridge.doors.view(f.definition.previewRoomId).resource!!.entries.size)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, 0x91F8, 0, 0, 0, preview)
        assertTrue(f.room.levelResourceOperations.isEmpty())
        assertTrue(f.room.states.all { state -> state.operations.none { it.edits.isNotEmpty() } })
        assertContentEquals(pixels, f.editor.workingLevelData)
    }

    @Test fun `new-room generation resize theme and reset coordinate authored resources and history`() = runBlocking {
        for (action in listOf("generate", "resize", "theme", "reset")) {
            val f = fixture(); f.adopt()
            f.editor.applyBulkEdits("Before compound", listOf(TileEdit(2, 2, f.editor.readBlockWord(2, 2), 0x8123)))
            val pixels = f.editor.workingLevelData!!.copyOf()
            when (action) {
                "generate" -> assertTrue(f.editor.applyGeneratedRoomOperation("Compound generation",
                    listOf(TileEdit(2, 2, 0x8123, 0x8001)), emptyList(), emptyList()), f.editor.statusMessage)
                "resize" -> assertTrue(f.editor.resizeRoom(1, 1, 2, 1), f.editor.statusMessage)
                "theme" -> assertTrue(f.editor.applyBiomeTheme(com.supermetroid.editor.procgen.BiomeTheme.KEEP.copy(tilesetId = 1), f.preview), f.editor.statusMessage)
                else -> assertTrue(f.editor.resetCurrentRoomToOriginal(f.preview), f.editor.statusMessage)
            }
            assertTrue(f.editor.undo(), "$action: ${f.editor.statusMessage}")
            assertContentEquals(pixels, f.editor.workingLevelData, action)
            assertTrue(f.editor.redo(), "$action: ${f.editor.statusMessage}")
            assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        }
    }

    @Test fun `project door destinations incoming selectors FX and save records resolve a cyclic graph after cold relocation`() = runBlocking {
        val f = fixture()
        val sibling = f.editor.createNewRoom(NewRoomCreationRequest("Partner", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        fun load(room: ProjectNewRoom) = f.editor.loadRoom(room.previewRoomId, preview, preview.readRoomHeader(room.previewRoomId)!!)
        load(f.definition)
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, sibling.previewRoomId, 0, 0, 0, preview)
        load(sibling)
        f.editor.addProjectRoomDoor(sibling.previewRoomId, f.definition.previewRoomId, 0, 0, 0, preview)
        load(f.definition)
        val incoming = ProjectSourceDoorReference(0, 0, sibling.id)
        assertEquals(incoming, f.editor.incomingDoorConditionOptions(f.definition.previewRoomId, preview).single().sourceDoor)
        val default = f.editor.currentPlmStateId()!!
        f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, sourceDoor = incoming), preview)
        f.editor.setRoomStateDoorFxChange(default, 0, FxChange(fxType = 2, liquidSurfaceStart = 0x123), preview, incoming)
        f.editor.addPlm(0xB76F, 8, 8, 0, ItemStateScope.ALL_STATES)
        assertEquals(1, f.editor.workingPlms.count { it.id == 0xB76F }, f.editor.statusMessage)
        val ids = AsmProjectRoomLabels.identities(f.project)
        val bank = f.bridge.sourceText()
        val doorBank = f.bridge.doors.sourceText()
        assertContains(doorBank, AsmProjectRoomLabels.entry(sibling.id))
        val saves = AsmRoomObjectSource(bank, emptyMap(), ids).saveResources(f.definition.previewRoomId)
        assertEquals(incoming, saves.single().sourceDoor)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bytes = f.base.copyRomData()
        bytes[f.base.snesToPc(0x8F0000 or f.definition.previewRoomId)] = 0x42
        val doors = AsmDoorSource(doorBank, bank, emptyMap(), ids, cold.newRooms)
        val layouts = AsmRoomLayoutSource(bank, ids)
        val objects = AsmRoomObjectSource(bank, emptyMap(), ids)
        val selectors = AsmRoomSelectorsSource(bank, ids)
        val fx = AsmFxSource(doorBank, ids)
        val exported = ProjectRoomExporter(cold, RomParser(bytes), bytes,
            sourceDoors = cold.rooms.values.filter { it.asmDoorSourceVersion != 0 }.associate { it.roomId to doors.resource(it, f.base) },
            sourceLayouts = cold.rooms.values.filter { it.asmLayoutSourceVersion != 0 }.associate { it.roomId to layouts.resources(it, 16, 16) },
            sourcePlmSets = cold.rooms.values.filter { it.asmPlmSourceVersion != 0 }.associate { it.roomId to objects.resources(it, 1) },
            sourceSelectors = cold.rooms.values.filter { it.asmStateSelectorsSourceVersion != 0 }.associate { it.roomId to selectors.resource(it) },
            sourceFx = cold.rooms.values.filter { it.asmFxSourceVersion != 0 }.associate { it.roomId to fx.resources(it) }).exportRooms()
        val built = RomParser(bytes)
        val a = exported.newRoomIds.getValue(f.definition.id); val b = exported.newRoomIds.getValue(sibling.id)
        assertNotEquals(f.definition.previewRoomId, a)
        val aHeader = built.readRoomHeader(a)!!; val bHeader = built.readRoomHeader(b)!!
        val bDoor = built.parseDoorList(bHeader.doorOut).single()
        assertEquals(a, bDoor.destRoomPtr)
        assertEquals(b, built.parseDoorList(aHeader.doorOut).single().destRoomPtr)
        val save = saves.single()
        assertEquals(a, built.readSaveEntry(save.area, save.slot)!!.roomId)
        assertEquals(bDoor.doorDefPtr, built.readSaveEntry(save.area, save.slot)!!.doorPtr)
        assertEquals(bDoor.doorDefPtr, built.parseFxEntries(aHeader.fxPtr).first().doorSelect)
        assertEquals(bDoor.doorDefPtr, built.inspectRoomStates(a).states.first().condition.argument)
    }

    @Test fun `clone snapshots authored scroll commands and retains symbolic arrival behavior without preview pointers`() = runBlocking {
        val f = fixture()
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, f.definition.previewRoomId, 0, 0, 0, f.preview)
        f.bridge.doors.createManagedArrival(f.definition.previewRoomId, 0, f.definition.name)
        f.editor.refreshLinkedPlms()
        f.editor.addScrollTriggerWithCommands(6, 6, listOf(ScrollCommand(0, 2)), ItemStateScope.ALL_STATES)
        assertEquals(1, f.editor.workingPlms.size, f.editor.statusMessage)
        val clone = f.editor.createNewRoom(NewRoomCreationRequest("Snapshot clone", true, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        assertEquals(mapOf(0 to listOf(ScrollCommand(0, 2))), clone.initialState.scrollCommands)
        assertEquals(0, clone.initialState.plms.single().param)
        assertEquals(f.editor.doorArrivalBindings[0]!!.label, clone.doors.single().arrivalAsmLabel)
        val preview = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(clone.previewRoomId, preview, preview.readRoomHeader(clone.previewRoomId)!!)
        val trigger = f.editor.workingPlms.single()
        assertEquals(listOf(ScrollCommand(0, 2)), f.editor.scrollCommandsFor(trigger))
        assertTrue(f.editor.ensureCurrentPlmSource(clone.name), f.editor.statusMessage)
        val id = f.editor.currentPlmStateId()!!
        assertEquals(listOf(ScrollCommand(0, 2)), f.bridge.scrollCommands(clone.previewRoomId, id, f.editor.workingPlms.single()))
        assertTrue(f.editor.updateScrollTriggerCommands(f.editor.workingPlms.single(), listOf(ScrollCommand(0, 1))), f.editor.statusMessage)
        assertEquals(mapOf(0 to listOf(ScrollCommand(0, 2))), clone.initialState.scrollCommands)
        assertTrue(f.editor.ensureDoorSource(clone.name), f.editor.statusMessage)
        assertEquals(clone.doors.single().arrivalAsmLabel, f.bridge.doors.view(clone.previewRoomId).resource!!.entries.single().arrival!!.label)
    }

    @Test fun `project-room deletion checks live symbolic consumers but does not mistake retired door macros for active links`() = runBlocking {
        val f = fixture()
        val sibling = f.editor.createNewRoom(NewRoomCreationRequest("Destination", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(f.definition.previewRoomId, preview, preview.readRoomHeader(f.definition.previewRoomId)!!)
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, sibling.previewRoomId, 0, 0, 0, preview)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        assertFalse(f.editor.previewProjectRoomDeletion(sibling.previewRoomId, preview)!!.canDelete)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        f.editor.removeProjectRoomDoor(f.definition.previewRoomId, 0)
        assertContains(f.bridge.doors.sourceText(), AsmProjectRoomLabels.entry(sibling.id), message = "Retired declarations preserve user source")
        assertTrue(f.editor.previewProjectRoomDeletion(sibling.previewRoomId, preview)!!.canDelete)
        f.editor.deleteProjectRoom(sibling.previewRoomId, preview)
        assertTrue(f.project.newRooms.none { it.id == sibling.id })
        val ids = AsmProjectRoomLabels.identities(f.project)
        val bytes = f.base.copyRomData()
        ProjectRoomExporter(f.project, RomParser(bytes), bytes,
            sourceDoors = mapOf(f.definition.previewRoomId to AsmDoorSource(f.bridge.doors.sourceText(), f.bridge.sourceText(), emptyMap(), ids, f.project.newRooms).resource(f.room, f.base)),
            sourceLayouts = mapOf(f.definition.previewRoomId to AsmRoomLayoutSource(f.bridge.sourceText(), ids).resources(f.room, 16, 16))).exportRooms()
    }

    @Test fun `new-room executable selector bodies retain managed-in-ASM policy without locking ordinary object data`() = runBlocking {
        val f = fixture(); f.adopt()
        val default = f.editor.currentPlmStateId()!!
        val state = f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.preview)
        val macro = AsmRoomSelectorsSource.conditionName(f.definition.previewRoomId, state, f.definition.id)
        val text = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replace(authoredMacroBody(text, macro),
            "    ; smedit:managed-predicate-v1\n    clc\n    rts\n"))
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertFalse(f.editor.selectorEditingAvailable())
        f.editor.addPlm(0xEEDB, 4, 4, 0x151, ItemStateScope.ALL_STATES)
        assertEquals(1, f.editor.workingPlms.size, f.editor.statusMessage)
        val before = f.bridge.sourceText()
        assertFails { f.editor.addRoomState(default, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), f.preview) }
        assertEquals(before, f.bridge.sourceText())
        val target = AsmProjectRoomLabels.sourceTarget(before.lines(), f.bridge.selectors.line(f.definition.previewRoomId, state))!!
        assertEquals(f.definition.id, target.projectRoomId)
        assertEquals(state, target.stateId)
    }

    @Test fun `cold relocated door validation uses the destinations authored dimensions not another previews address`() = runBlocking {
        val f = fixture()
        val destination = f.editor.createNewRoom(NewRoomCreationRequest("Expanded destination", false, area = 1, mapX = 42, mapY = 20,
            width = 1, height = 1, tileset = 0), f.preview)
        val preview = f.editor.prepareWorkspaceParser()
        f.editor.loadRoom(destination.previewRoomId, preview, preview.readRoomHeader(destination.previewRoomId)!!)
        assertTrue(f.editor.resizeRoom(1, 1, 2, 1), f.editor.statusMessage)
        f.editor.loadRoom(f.definition.previewRoomId, preview, preview.readRoomHeader(f.definition.previewRoomId)!!)
        f.editor.addProjectRoomDoor(f.definition.previewRoomId, destination.previewRoomId, 0, 1, 0, preview)
        val ids = AsmProjectRoomLabels.identities(f.project)
        val objects = AsmRoomObjectSource(f.bridge.sourceText(), emptyMap(), ids)
        val grids = AsmInitialScrollSource(f.bridge.sourceText(), emptyMap(), ids)
        val layouts = AsmRoomLayoutSource(f.bridge.sourceText(), ids)
        val doors = AsmDoorSource(f.bridge.doors.sourceText(), f.bridge.sourceText(), emptyMap(), ids, f.project.newRooms)
        val bytes = f.base.copyRomData()
        bytes[f.base.snesToPc(0x8F0000 or f.definition.previewRoomId)] = 0x42
        val exported = ProjectRoomExporter(f.project, RomParser(bytes), bytes,
            sourceDoors = f.project.rooms.values.filter { it.asmDoorSourceVersion != 0 }.associate { room -> room.roomId to doors.resource(room, f.base) { id -> sourceRoomDimensions(f.project, f.base, id) } },
            sourceLayouts = f.project.rooms.values.filter { it.asmLayoutSourceVersion != 0 }.associate { it.roomId to layouts.resources(it, 16, 16) },
            sourcePlmSets = f.project.rooms.values.filter { it.asmPlmSourceVersion != 0 }.associate { it.roomId to objects.resources(it, 2) },
            sourceInitialScrolls = f.project.rooms.values.filter { it.asmInitialScrollSourceVersion != 0 }.associate { it.roomId to grids.resources(it, 2, 1) }).exportRooms()
        val built = RomParser(bytes)
        val target = built.readRoomHeader(exported.newRoomIds.getValue(destination.id))!!
        val source = built.readRoomHeader(exported.newRoomIds.getValue(f.definition.id))!!
        assertEquals(2, target.width)
        assertEquals(target.roomId, built.parseDoorList(source.doorOut).single().destRoomPtr)
        assertEquals(1, built.parseDoorList(source.doorOut).single().screenX)
    }

    @Test fun `inherited native setup main and background bindings reject moved source anchors without rewriting the seed`() = runBlocking {
        val f = fixture()
        f.definition.initialState.mainAsmPtr = 0x9000
        f.definition.initialState.setupAsmPtr = 0x9010
        f.definition.initialState.bgDataPtr = 0x9020
        f.definition.initialState.xraySpecialCasingPtr = 0x9030
        val symbols = mapOf("RoomMainASM_Test" to 0x9000, "RoomSetupASM_Test" to 0x9010, "RoomBackgroundData_Test" to 0x9020, "RoomSpecialXray_Test" to 0x9030)
        val labels = symbols.mapValues { 0x8F0000 or it.value }
        validateProjectRoomSeedAnchors(f.project, labels, symbols)
        validateProjectRoomSeedAnchors(f.project, labels + ("OtherBank" to 0xA19000), symbols + ("OtherBank" to 0x9000),
            symbols.keys.joinToString("\n") { "$it:" })
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        for ((name, address) in labels) {
            assertContains(assertFailsWith<IllegalArgumentException> {
                validateProjectRoomSeedAnchors(f.project, labels + (name to address + 2), symbols)
            }.message!!, "stale pointer")
            assertFails { validateProjectRoomSeedAnchors(f.project, labels - name, symbols) }
        }
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
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
