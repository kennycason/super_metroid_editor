package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.ui.RoomAsmNavigation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Cross-resource contracts at the production room-link entry point, not substitute bridge wiring. */
class RoomAsmNavigationTest {
    @TempDir lateinit var directory: File
    private val missile = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
    private val station = RomParser.PlmEntry(0xB76F, 8, 8, 0x8002)
    private val actor = RomParser.EnemyEntry(0xD13F, 40, 56, 0x1234, 0x2800, 0x5678, 0x9ABC, 0xDEF0)
    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState,
                               val bridge: AsmPlmSourceBridge, val file: File) {
        val project get() = editor.project
        val parser get() = bridge.catalog.parser
        val navigation = RoomAsmNavigation(editor, workspace, { "Test room" })
        fun model() = Json.encodeToString(SmEditProject.serializer(), project)
        fun sources() = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, ENEMY_SOURCE_FILE).associateWith(workspace::projectSourceText)
        fun disk() = requireNotNull(workspace.projectWorkspace).workingDirectory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(requireNotNull(workspace.projectWorkspace).workingDirectory).path to it.readBytes().toList() }
        fun request(kind: RoomAsmSourceKind) = RoomAsmSourceRequest(kind,
            plm = when (kind) { RoomAsmSourceKind.PLMS -> editor.workingPlms.first { it.id == 0xEEDB }; RoomAsmSourceKind.SAVE_SPAWN -> editor.workingPlms.first { it.id == 0xB76F }; else -> null },
            enemy = if (kind == RoomAsmSourceKind.ENEMIES) editor.workingEnemies.first() else null)
        fun adopt(request: RoomAsmSourceRequest) {
            assertTrue(navigation.open(request), editor.statusMessage)
            assertNotNull(workspace.roomSourceInspection).startEditing()
            assertNull(workspace.roomSourceInspection, editor.statusMessage)
        }
        fun prepareLegacySave() {
            assertTrue(editor.ensureCurrentPlmSource("Test room"))
            project.rooms.getValue("91F8").saveStationSpawns += SaveStationSpawnChange(0, 2, 0x91F8, 0x8000, 0, 0, 144, 128)
        }
    }

    private suspend fun fixture(name: String, nativePlmSource: Boolean = false, secondRoom: Boolean = false): Fixture {
        val parent = File(directory, name).apply { mkdirs() }
        val root = File(parent, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        val original = if (nativePlmSource) "PLMPopulation_Test: ;8F8000;\n %PLMPopEntry(\$EEDB,\$02,\$03,\$0005)\n %PLMPopEntry(\$B76F,\$08,\$08,\$8002)\n dw \$0000\n" else "; Original object source\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_A1.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText(original)
        File(src, FX_SOURCE_FILE).writeText("; Original FX source\n")
        File(src, ENEMY_SOURCE_FILE).writeText("; Original actor source\n")
        val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0), AsmSourceParser().parse(src, data, emptyList()))
        val file = File(parent, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            word(pc + 6, 0x8800); word(pc + 8, 0x8800); word(pc + 10, 0x8800)
        }
        RomParser.serializePlmSet(listOf(missile, station)).forEachIndexed { index, value -> rom[0x78000 + index] = value.toByte() }
        SourceFxResource("original", listOf(emptyDefaultFxEntry().copy(fxType = 6))).bytes().copyInto(rom, 0x18800)
        SourceEnemyPopulation("original", listOf(actor, actor.copy(x = 80)), 7).bytes().copyInto(rom, 0x108800)
        SourceEnemyGraphics("original", listOf(RomParser.EnemyGfxEntry(actor.id, 1))).bytes().copyInto(rom, 0x1A0800)
        word(0x100000 + (actor.id and 0x7FFF), 32)
        val speciesPc = 0x100000 + (actor.id and 0x7FFF)
        rom[speciesPc + 12] = 0xA2.toByte(); word(speciesPc + 54, actor.id); rom[speciesPc + 56] = 0xAD.toByte()
        word(0x44E1, 0x91F8); word(0x44E3, 0x8000)
        if (secondRoom) {
            rom[0x7A004] = 2; rom[0x7A005] = 2; word(0x7A00B, 0xE5E6)
            val otherLevel = level.copyOf().also { it[2] = 0x56; it[3] = 0x04 }
            LZ5Compressor.compress(otherLevel).copyInto(rom, 0x201000)
            rom[0x7A00E] = 0x90.toByte(); rom[0x7A00F] = 0xC0.toByte()
        }
        val parser = RomParser(rom)
        val consumers = if (nativePlmSource) mapOf(0x8000 to parser.findAllStateDataOffsets(0x91F8).mapIndexed { index, pc ->
            AsmPlmConsumer(0x91F8, "Test room", index, pc) }) else emptyMap()
        val project = SmEditProject(File(parent, "base.sfc").apply { writeBytes(rom) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog(original, macros, emptyMap(), parser, consumers), workspace)
        editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        return Fixture(editor, workspace, bridge, file)
    }

    @Test fun `every unowned room link is pure and explicit adoption is one undoable idempotent transition`() = runBlocking {
        for (kind in RoomAsmSourceKind.entries) {
            val f = fixture(kind.name)
            if (kind == RoomAsmSourceKind.SAVE_SPAWN) f.prepareLegacySave()
            assertTrue(f.workspace.saveAllSources()); assertTrue(f.editor.saveProject())
            val request = f.request(kind)
            val model = f.model(); val sources = f.sources(); val disk = f.disk()
            val revision = f.workspace.sourceBufferRevision
            val guiHistory = f.editor.undoStack.toList()
            val sourceHistory = sources.keys.associateWith(f.workspace::sourceUndoHead)
            val dirty = f.editor.dirty
            val report = f.workspace.buildReport
            assertTrue(f.navigation.open(request), "$kind: ${f.editor.statusMessage}")
            val inspection = assertNotNull(f.workspace.roomSourceInspection, kind.name)
            assertEquals(kind.fileId, f.workspace.selectedFileId)
            val automatic = kind in setOf(RoomAsmSourceKind.DOORS, RoomAsmSourceKind.PROPERTIES, RoomAsmSourceKind.LAYOUT)
            assertEquals(automatic, f.workspace.isProjectSourceEditable)
            f.workspace.updateSourceEditText(kind.fileId, if (automatic) f.workspace.sourceEditorText(kind.fileId)
                else "This must not replace the preview or source")
            assertEquals(model, f.model(), kind.name); assertEquals(sources, f.sources(), kind.name)
            assertEquals(revision, f.workspace.sourceBufferRevision, kind.name)
            assertEquals(guiHistory, f.editor.undoStack, kind.name); assertEquals(dirty, f.editor.dirty, kind.name)
            assertEquals(sourceHistory, sources.keys.associateWith(f.workspace::sourceUndoHead), kind.name)
            assertSame(report, f.workspace.buildReport); assertEquals(disk, f.disk(), kind.name)
            inspection.startEditing()
            assertNull(f.workspace.roomSourceInspection, "$kind: ${f.editor.statusMessage}")
            assertTrue(f.editor.dirty, "$kind must notify the actual editor")
            assertEquals(guiHistory.size + 1, f.editor.undoStack.size, kind.name)
            val adopted = f.sources(); val adoptedModel = f.model(); val adoptedRevision = f.workspace.sourceBufferRevision
            assertTrue(f.navigation.open(request), f.editor.statusMessage)
            assertNull(f.workspace.roomSourceInspection)
            assertEquals(adopted, f.sources()); assertEquals(adoptedModel, f.model()); assertEquals(adoptedRevision, f.workspace.sourceBufferRevision)
            assertEquals(disk, f.disk(), "Adoption stages buffers, not files")
            assertTrue(f.editor.undo(), "$kind: ${f.editor.statusMessage}")
            assertEquals(sources, f.sources(), kind.name)
            assertTrue(f.editor.redo(), "$kind: ${f.editor.statusMessage}")
            assertEquals(adopted, f.sources(), kind.name)
            assertEquals(adoptedModel, f.model(), kind.name)
        }
    }

    @Test fun `source only ownership undo and redo notify a saved editor for every source family`() = runBlocking {
        for (kind in RoomAsmSourceKind.entries) {
            val f = fixture(kind.name)
            if (kind == RoomAsmSourceKind.SAVE_SPAWN) f.prepareLegacySave()
            f.adopt(f.request(kind))
            assertTrue(f.editor.saveProject()); assertFalse(f.editor.dirty)
            assertTrue(f.workspace.undoSourceEdit(kind.fileId), "$kind: ${f.workspace.error}")
            assertTrue(f.editor.dirty, "$kind undo must persist the ownership change, even without GUI undo")
            val liveRoom = f.project.rooms.getValue("91F8")
            assertTrue(f.editor.saveProject()); assertFalse(f.editor.dirty)
            assertSame(liveRoom, f.project.rooms["91F8"], "Save must not detach an identity retained by undo/redo")
            assertTrue(f.workspace.redoSourceEdit(kind.fileId), "$kind: ${f.workspace.error}")
            assertTrue(f.editor.dirty, "$kind redo must persist the ownership change")
        }
    }

    @Test fun `stale previews cannot adopt across model source state mode ROM or project changes`() = runBlocking {
        val changes: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "model" to { it.project.getOrCreateRoom(0x91F8).fxChange = FxChange(fxType = 2) },
            "source" to { it.workspace.replaceProjectSource(FX_SOURCE_FILE, it.bridge.fx.sourceText(), "; newer source\n") },
            "state" to { f -> val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(f.parser) }; f.editor.switchRoomState(room.states.first().id, f.parser) },
            "mode" to { it.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM },
            "parser" to { f -> f.editor.loadRoom(0x91F8, RomParser(f.parser.copyRomData()), f.parser.readRoomHeader(0x91F8)!!) },
            "rom reload" to { it.editor.initForProject(it.file, it.project) },
            "project" to { it.editor.initForProject(File(directory, "Other.smedit"), SmEditProject("other.sfc")) },
            "view" to { it.workspace.openSource(FX_SOURCE_FILE) },
        )
        for ((name, change) in changes) {
            val f = fixture(name)
            assertTrue(f.navigation.open(f.request(RoomAsmSourceKind.SELECTORS)))
            val preview = assertNotNull(f.workspace.roomSourceInspection)
            change(f)
            val model = f.model(); val sources = f.sources(); val revision = f.workspace.sourceBufferRevision
            val dirty = f.editor.dirty; val history = f.editor.undoStack.toList()
            preview.startEditing()
            assertContains(f.editor.statusMessage, "reopen", message = name)
            assertEquals(model, f.model(), name); assertEquals(sources, f.sources(), name)
            assertEquals(revision, f.workspace.sourceBufferRevision, name)
            assertEquals(history, f.editor.undoStack, name); assertEquals(dirty, f.editor.dirty, name)
        }
    }

    @Test fun `invalid actor state or door links cannot partially migrate or add a manifest`() = runBlocking {
        val f = fixture("invalid")
        val requests = listOf(RoomAsmSourceRequest(RoomAsmSourceKind.ENEMIES, enemy = actor.copy(x = 500)),
            RoomAsmSourceRequest(RoomAsmSourceKind.FX, stateId = "deleted-state"),
            RoomAsmSourceRequest(RoomAsmSourceKind.FX, door = 0x9000),
            RoomAsmSourceRequest(RoomAsmSourceKind.PLMS, plm = missile.copy(x = 30)))
        val model = f.model(); val sources = f.sources(); val revision = f.workspace.sourceBufferRevision
        for (request in requests) {
            assertFalse(f.navigation.open(request), request.toString())
            assertNull(f.workspace.roomSourceInspection)
            assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(f.editor.undoStack.isEmpty())
        }
        f.editor.updateEnemy(actor.copy(x = 500), actor.copy(x = 600))
        f.editor.removeEnemy(actor.copy(x = 500))
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `verified native PLM links open their exact existing code without adopting wrappers`() = runBlocking {
        val f = fixture("native", nativePlmSource = true)
        val model = f.model(); val sources = f.sources(); val revision = f.workspace.sourceBufferRevision
        assertTrue(f.navigation.open(f.request(RoomAsmSourceKind.PLMS)), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection)
        assertEquals(1, f.workspace.selectedLineIndex)
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertTrue(f.editor.undoStack.isEmpty()); assertFalse(f.editor.hasManagedPlms())
    }

    @Test fun `ambiguous duplicate PLMs are rejected before adopting declarations that cannot identify their source line`() = runBlocking {
        val f = fixture("duplicates")
        f.project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", missile.id, missile.x, missile.y, missile.param)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertEquals(2, f.editor.workingPlms.count { it == missile })
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        assertFalse(f.navigation.open(f.request(RoomAsmSourceKind.PLMS)))
        assertContains(f.editor.statusMessage, "duplicates")
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertFalse(f.editor.hasManagedPlms())
    }

    @Test fun `a save declaration without retired GUI metadata dirties source not an unchanged project model`() = runBlocking {
        val f = fixture("source-only-save")
        assertTrue(f.editor.ensureCurrentPlmSource("Test room"))
        assertTrue(f.workspace.saveAllSources()); assertTrue(f.editor.saveProject())
        val model = f.model()
        f.adopt(f.request(RoomAsmSourceKind.SAVE_SPAWN))
        assertEquals(model, f.model()); assertFalse(f.editor.dirty)
        assertTrue(f.workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(f.workspace.saveAllSources())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE)); assertFalse(f.editor.dirty)
        assertTrue(f.workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE)); assertFalse(f.editor.dirty)
        assertFalse(f.workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
    }

    @Test fun `returning from any source or preview preserves selected state and usable GUI undo`() = runBlocking {
        for (kind in RoomAsmSourceKind.entries) {
            val f = fixture(kind.name)
            val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(f.parser) }
            val selected = room.states.first().id
            f.editor.switchRoomState(selected, f.parser)
            f.editor.applyBulkEdits("Before ASM", listOf(TileEdit(5, 5, 0, 0x8123)))
            if (kind == RoomAsmSourceKind.SAVE_SPAWN) assertTrue(f.editor.ensureCurrentPlmSource("Test room"))
            val request = f.request(kind)
            assertTrue(f.navigation.open(request), "$kind: ${f.editor.statusMessage}")
            val history = f.editor.undoStack.toList()
            f.editor.loadRoomForCanvas(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
            assertEquals(selected, f.editor.currentPlmStateId()); assertEquals(history, f.editor.undoStack)
            assertTrue(f.editor.undo(), "$kind: ${f.editor.statusMessage}")
            assertEquals(0, f.editor.readBlockWord(5, 5))
            assertTrue(f.editor.redo())
            f.adopt(request)
            val adoptedHistory = f.editor.undoStack.toList()
            f.editor.loadRoomForCanvas(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
            assertEquals(selected, f.editor.currentPlmStateId()); assertEquals(adoptedHistory, f.editor.undoStack)
            assertTrue(f.editor.undo(), "$kind: ${f.editor.statusMessage}")
        }
    }

    @Test fun `read only source preview does not disable another resource's GUI undo on returning to the room`() = runBlocking {
        val f = fixture("mixed-preview")
        f.adopt(f.request(RoomAsmSourceKind.FX))
        val state = f.editor.currentPlmStateId()!!
        f.editor.setRoomStateFxChange(state, FxChange(fxType = 2), f.parser)
        assertEquals(2, f.editor.effectiveFxEntries(0x91F8, f.parser).last().fxType)
        assertTrue(f.navigation.open(f.request(RoomAsmSourceKind.SELECTORS)))
        val preview = assertNotNull(f.workspace.roomSourceInspection)
        assertFalse(f.workspace.undoSourceEdit(FX_SOURCE_FILE), "Read-only pane shortcuts cannot edit a hidden bank")
        f.editor.loadRoomForCanvas(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(6, f.editor.effectiveFxEntries(0x91F8, f.parser).last().fxType)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(2, f.editor.effectiveFxEntries(0x91F8, f.parser).last().fxType)
        val source = f.sources(); val model = f.model()
        preview.startEditing()
        assertContains(f.editor.statusMessage, "reopen")
        assertEquals(source, f.sources()); assertEquals(model, f.model())
    }

    @Test fun `preview history restores requested state context while project rebinding clears it`() = runBlocking {
        val f = fixture("preview-history")
        val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(f.parser) }
        val requested = room.states.first { it.id != f.editor.currentPlmStateId() }.id
        assertTrue(f.navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.PROPERTIES, requested)))
        val headerPreview = assertNotNull(f.workspace.roomSourceInspection)
        assertEquals(requested, headerPreview.stateId)
        assertTrue(f.navigation.open(RoomAsmSourceRequest(RoomAsmSourceKind.FX, requested)))
        val fxPreview = assertNotNull(f.workspace.roomSourceInspection)
        assertEquals(requested, fxPreview.stateId)
        assertTrue(f.workspace.canGoBack); f.workspace.goBack()
        val reopened = assertNotNull(f.workspace.roomSourceInspection)
        assertEquals(headerPreview.stateId, reopened.stateId); assertEquals(headerPreview.text, reopened.text)
        assertNotNull(reopened.automaticEdit, "Editable overlays are replanned on Back rather than reusing stale callbacks")
        assertTrue(f.workspace.canGoForward); f.workspace.goForward(); assertSame(fxPreview, f.workspace.roomSourceInspection)
        f.workspace.bindProject(File(directory, "Other.smedit").path, false)
        assertNull(f.workspace.roomSourceInspection)
        val model = f.model(); val revision = f.workspace.sourceBufferRevision
        fxPreview.startEditing()
        assertEquals(model, f.model()); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `canvas remount with a different parser reloads rather than preserving stale state or history`() = runBlocking {
        val f = fixture("parser-remount")
        f.adopt(f.request(RoomAsmSourceKind.FX))
        f.editor.setRoomStateFxChange(f.editor.currentPlmStateId()!!, FxChange(fxType = 2), f.parser)
        assertTrue(f.editor.undoStack.isNotEmpty())
        val parser = RomParser(f.parser.copyRomData())
        f.editor.loadRoomForCanvas(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertSame(parser, f.editor.roomSourceParser)
        assertTrue(f.editor.undoStack.isEmpty())
        assertFalse(f.navigation.open(f.request(RoomAsmSourceKind.FX)), "The old bridge cannot navigate a replaced ROM parser")
    }

    @Test fun `every ASM first resource can preview adopt undo and reopen without ever loading a visual room`() = runBlocking {
        for (kind in RoomAsmSourceKind.entries.filter { it != RoomAsmSourceKind.SAVE_SPAWN }) {
            val f = fixture("asm-first-$kind")
            f.editor.initForProject(f.file, f.project)
            f.editor.bindRoomSources(f.bridge.catalog, f.workspace)
            assertNull(f.editor.roomSourceParser)
            val model = f.model(); val source = f.sources(); val disk = f.disk(); val revision = f.workspace.sourceBufferRevision
            val link = AsmRoomSourceLink(0x91F8, "Test room", kind, sourceStateIndex = 0)
            assertTrue(f.navigation.openFromAsm(link), "$kind: ${f.editor.statusMessage}")
            val preview = assertNotNull(f.workspace.roomSourceInspection)
            assertEquals("state-1", preview.stateId)
            assertEquals(0, f.editor.currentRoomId); assertNull(f.editor.roomSourceParser)
            assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertFalse(f.editor.dirty); assertTrue(f.editor.undoStack.isEmpty())
            preview.startEditing()
            assertNull(f.workspace.roomSourceInspection, "$kind: ${f.editor.statusMessage}")
            assertEquals(kind.fileId, f.workspace.selectedFileId)
            assertEquals(0, f.editor.currentRoomId); assertNull(f.editor.roomSourceParser)
            assertTrue(f.editor.undoStack.isEmpty(), "ASM adoption must not manufacture GUI history for an unloaded canvas")
            assertEquals(disk, f.disk(), "Adoption only stages source")
            val adopted = f.sources()
            assertNotEquals(source, adopted)
            assertTrue(f.workspace.undoSourceEdit(kind.fileId), f.workspace.error)
            assertEquals(source, f.sources())
            assertTrue(f.workspace.redoSourceEdit(kind.fileId), f.workspace.error)
            assertEquals(adopted, f.sources())
            assertTrue(f.workspace.saveAllSources()); assertTrue(f.editor.saveProject())
            val savedRevision = f.workspace.sourceBufferRevision
            assertTrue(f.navigation.openFromAsm(link), f.editor.statusMessage)
            assertNull(f.workspace.roomSourceInspection)
            assertEquals(savedRevision, f.workspace.sourceBufferRevision); assertFalse(f.editor.dirty)
        }
    }

    @Test fun `ASM first door typing needs no opt in and restores an absent room on undo without touching the canvas`() = runBlocking {
        val f = fixture("automatic-asm-first", secondRoom = true)
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        f.project.rooms.remove("91F8") // The fixture initially visits this room; test a genuinely absent target.
        f.editor.applyBulkEdits("Keep my canvas", listOf(TileEdit(5, 5, 0, 0x8123)))
        val model = f.model(); val sources = f.sources(); val history = f.editor.undoStack.toList()
        val working = f.editor.workingLevelData!!.copyOf(); val state = f.editor.currentPlmStateId()
        val link = AsmRoomSourceLink(0x91F8, "Test room", RoomAsmSourceKind.DOORS)
        assertFalse(f.project.rooms.containsKey("91F8"))
        assertTrue(f.navigation.openFromAsm(link), f.editor.statusMessage)
        val staged = f.workspace.sourceEditorText(DOOR_SOURCE_FILE)
        val edited = staged.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}1234 ; Distance")
        assertNotEquals(staged, edited)
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, edited), f.editor.statusMessage)
        assertEquals(1, f.project.rooms.getValue("91F8").asmDoorSourceVersion)
        assertEquals(0x1234, f.bridge.doors.view(0x91F8).resource!!.entries.first().change.distFromDoor)
        assertEquals(history, f.editor.undoStack); assertEquals(0xA000, f.editor.currentRoomId)
        assertEquals(state, f.editor.currentPlmStateId()); assertContentEquals(working, f.editor.workingLevelData)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        assertEquals(history, f.editor.undoStack); assertContentEquals(working, f.editor.workingLevelData)
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(edited, f.workspace.projectSourceText(DOOR_SOURCE_FILE))
        assertEquals(history, f.editor.undoStack); assertEquals(state, f.editor.currentPlmStateId())
    }

    @Test fun `automatic doors can be typed from ASM without loading any visual room`() = runBlocking {
        val f = fixture("automatic-headless")
        f.editor.initForProject(f.file, f.project)
        f.editor.bindRoomSources(f.bridge.catalog, f.workspace)
        f.project.rooms.remove("91F8")
        val model = f.model()
        assertTrue(f.navigation.openFromAsm(AsmRoomSourceLink(0x91F8, "Test room", RoomAsmSourceKind.DOORS)), f.editor.statusMessage)
        val text = f.workspace.sourceEditorText(DOOR_SOURCE_FILE)
        assertTrue(f.workspace.updateSourceEditText(DOOR_SOURCE_FILE, text + "; my first door edit\n"), f.editor.statusMessage)
        assertEquals(0, f.editor.currentRoomId); assertNull(f.editor.roomSourceParser); assertTrue(f.editor.undoStack.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertFalse(f.project.rooms.containsKey("91F8"))
        assertTrue(f.workspace.redoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(1, f.project.rooms.getValue("91F8").asmDoorSourceVersion)
        assertEquals(0, f.editor.currentRoomId); assertNull(f.editor.roomSourceParser); assertTrue(f.editor.undoStack.isEmpty())
    }

    @Test fun `automatic header and state source can edit an absent room without replacing another canvas or its history`() = runBlocking {
        for (loadCanvas in listOf(false, true)) {
            val f = fixture("automatic-properties-$loadCanvas", secondRoom = true)
            if (loadCanvas) {
                f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
                f.editor.applyBulkEdits("Keep my canvas", listOf(TileEdit(5, 5, 0, 0x8123)))
            } else {
                f.editor.initForProject(f.file, f.project)
                f.editor.bindRoomSources(f.bridge.catalog, f.workspace)
            }
            f.project.rooms.remove("91F8")
            val model = f.model(); val sources = f.sources(); val history = f.editor.undoStack.toList()
            val roomId = f.editor.currentRoomId; val state = f.editor.currentPlmStateId()
            val working = f.editor.workingLevelData?.copyOf()
            val link = AsmRoomSourceLink(0x91F8, "Test room", RoomAsmSourceKind.PROPERTIES, sourceStateIndex = 0)
            assertTrue(f.navigation.openFromAsm(link), f.editor.statusMessage)
            assertFalse(f.project.rooms.containsKey("91F8")); assertEquals(model, f.model()); assertEquals(sources, f.sources())
            val planned = f.workspace.sourceEditorText(PLM_SOURCE_FILE)
            assertEquals(AsmRoomPropertiesSource(planned).line(0x91F8, "state-1"), f.workspace.selectedLineIndex)
            val edited = planned.replace("; Music track", "; My state field note")
            assertNotEquals(planned, edited)
            assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, edited), f.editor.statusMessage)
            assertEquals(1, f.project.rooms.getValue("91F8").asmRoomPropertiesSourceVersion)
            assertEquals(history, f.editor.undoStack); assertEquals(roomId, f.editor.currentRoomId); assertEquals(state, f.editor.currentPlmStateId())
            assertContentEquals(working, f.editor.workingLevelData)
            assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
            assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertFalse(f.project.rooms.containsKey("91F8"))
            assertEquals(history, f.editor.undoStack); assertContentEquals(working, f.editor.workingLevelData)
            assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
            assertEquals(edited, f.workspace.projectSourceText(PLM_SOURCE_FILE)); assertEquals(history, f.editor.undoStack)
        }
    }

    @Test fun `stale automatic property views never adopt after source model state mode parser or project changes`() = runBlocking {
        val changes: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "model" to { it.project.getOrCreateRoom(0x91F8).fxChange = FxChange(fxType = 2) },
            "source" to { it.workspace.replaceProjectSource(FX_SOURCE_FILE, it.bridge.fx.sourceText(), "; Newer source\n") },
            "state" to { f -> val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(f.parser) }; f.editor.switchRoomState(room.states.first().id, f.parser) },
            "mode" to { it.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM },
            "parser" to { f -> f.editor.loadRoom(0x91F8, RomParser(f.parser.copyRomData()), f.parser.readRoomHeader(0x91F8)!!) },
            "project" to { it.editor.initForProject(File(directory, "Other.smedit"), SmEditProject("other.sfc")) },
            "view" to { it.workspace.openSource(FX_SOURCE_FILE) })
        for ((name, change) in changes) {
            val f = fixture("stale-properties-$name")
            assertTrue(f.navigation.open(f.request(RoomAsmSourceKind.PROPERTIES)), f.editor.statusMessage)
            val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
            change(f)
            val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
            val history = f.editor.undoStack.toList(); val dirty = f.editor.dirty
            assertFalse(draft.apply(draft.text + "; My first edit\n"), name)
            assertContains(f.editor.statusMessage, "reopen", message = name)
            assertEquals(model, f.model(), name); assertEquals(source, f.sources(), name); assertEquals(revision, f.workspace.sourceBufferRevision, name)
            assertEquals(history, f.editor.undoStack, name); assertEquals(dirty, f.editor.dirty, name)
        }
    }

    @Test fun `ASM adoption of another room uses its geometry and leaves the active room state and GUI undo intact`() = runBlocking {
        val f = fixture("other-room", secondRoom = true)
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        f.editor.applyBulkEdits("Keep my GUI history", listOf(TileEdit(5, 5, 0, 0x8123)))
        val working = f.editor.workingLevelData!!.copyOf(); val history = f.editor.undoStack.toList()
        val state = f.editor.currentPlmStateId()
        val link = AsmRoomSourceLink(0x91F8, "Test room", RoomAsmSourceKind.LAYOUT, 0)
        assertTrue(f.navigation.openFromAsm(link), f.editor.statusMessage)
        assertEquals(history, f.editor.undoStack); assertEquals(state, f.editor.currentPlmStateId())
        val draft = assertNotNull(f.workspace.roomSourceInspection?.automaticEdit)
        assertTrue(f.workspace.updateSourceEditText(PLM_SOURCE_FILE, draft.text + "; Edited another room's layout\n"), f.editor.statusMessage)
        assertNull(f.workspace.roomSourceInspection, f.editor.statusMessage)
        assertEquals(0xA000, f.editor.currentRoomId); assertEquals(state, f.editor.currentPlmStateId())
        assertContentEquals(working, f.editor.workingLevelData); assertEquals(history, f.editor.undoStack)
        val target = f.project.rooms.getValue("91F8")
        val decoded = AsmRoomLayoutSource(f.bridge.sourceText()).resources(target, 32, 32).values.first().bytes()
        assertEquals(0, decoded[2].toInt(), "Do not serialize the other room's 0456 first tile")
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertContentEquals(working, f.editor.workingLevelData); assertEquals(history, f.editor.undoStack)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(0, f.editor.readBlockWord(5, 5))
    }

    @Test fun `failed ASM first adoption rolls back provisional room identity metadata`() = runBlocking {
        for (existing in listOf(false, true)) {
            val f = fixture("failed-asm-$existing")
            f.editor.initForProject(f.file, f.project)
            f.editor.bindRoomSources(f.bridge.catalog, f.workspace)
            if (existing) f.project.getOrCreateRoom(0x91F8).fxChange = FxChange(fxType = 2)
            val before = f.bridge.sourceText()
            f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before + "macro SmeditLayout_91F8_Collision()\nendmacro\n")
            val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
            assertFalse(f.navigation.openFromAsm(AsmRoomSourceLink(0x91F8, "Test room", RoomAsmSourceKind.LAYOUT)))
            assertContains(f.editor.statusMessage, "Retained layout declarations")
            assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertFalse(f.editor.dirty)
        }
    }
}
