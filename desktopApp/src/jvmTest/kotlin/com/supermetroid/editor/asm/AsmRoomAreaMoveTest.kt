package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomAreaMoveTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val map: MinimapEditorState, val file: File) {
        val project get() = editor.project
        val parser get() = bridge.catalog.parser
        fun model() = Json.encodeToString(SmEditProject.serializer(), project)
        fun sources() = listOf(PLM_SOURCE_FILE, DOOR_SOURCE_FILE).associateWith(workspace::projectSourceText)
        fun entries(id: Int) = editor.effectiveDoorsForRoom(id, parser)
        fun move(area: Int = 1) = assertNotNull(map.reassignRoomArea(0x91F8, area, editor))
        fun coldExport(): RomParser {
            val cold = Json.decodeFromString<SmEditProject>(model())
            val text = bridge.sourceText()
            val objects = AsmRoomObjectSource(text, bridge.catalog.symbols)
            val properties = AsmRoomPropertiesSource(text)
            val doors = AsmDoorSource(bridge.doors.sourceText(), text, bridge.catalog.symbols)
            val bytes = parser.copyRomData()
            ProjectRoomExporter(cold, parser, bytes,
                sourcePlmSets = cold.rooms.values.filter { it.asmPlmSourceVersion != 0 }.associate { it.roomId to objects.resources(it) },
                sourceRoomProperties = cold.rooms.values.filter { it.asmRoomPropertiesSourceVersion != 0 }.associate { it.roomId to properties.resource(it) },
                sourceDoors = cold.rooms.values.filter { it.asmDoorSourceVersion != 0 }.associate { it.roomId to doors.resource(it, parser) }).exportRooms()
            return RomParser(bytes)
        }
    }

    private suspend fun fixture(hasSave: Boolean = true, sharedDoor: Boolean = false, nestedRoom: Boolean = false): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Native room and arrival code\nDoorASM_Custom: ;8F9000;\n lda.w ${'$'}1234\n rts\n")
        File(src, DOOR_SOURCE_FILE).writeText("; Native connections and FX\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val conversion = RomParser(rom)
        for (area in 0..6) {
            val address = MinimapData.AREA_MAP_ADDRESSES[area]
            val pc = conversion.snesToPc(MinimapData.AREA_MAP_POINTER_TABLE) + area * 3
            word(pc, address and 65535); rom[pc + 2] = (address ushr 16).toByte()
            word(conversion.snesToPc(MinimapData.MAP_DATA_POINTER_TABLE) + area * 2, MinimapData.MAP_STATION_ADDRESSES[area] and 65535)
        }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
        }
        rom[0x791FA] = 2; rom[0x791FB] = 3
        word(0x78060, 0x8000); word(0x78062, 0x8010)
        fun otherRoom(id: Int, area: Int, list: Int, x: Int, y: Int) {
            val pc = 0x70000 + id
            rom[pc + 1] = area.toByte(); rom[pc + 2] = x.toByte(); rom[pc + 3] = y.toByte()
            rom[pc + 4] = 2; rom[pc + 5] = 2
            word(pc + 9, list); word(pc + 11, 0xE5E6)
            rom[pc + 14] = 0x80.toByte(); rom[pc + 15] = 0xC0.toByte(); word(pc + 13 + 20, 0x9000)
        }
        otherRoom(0xA000, 0, 0x8100, 10, 10)
        otherRoom(0xA100, 1, 0x8120, 2, 3) // Force automatic collision-free target placement.
        word(0x78100, 0x8020); word(0x78102, 0x8030)
        word(0x78120, if (sharedDoor) 0x8020 else 0x8040); word(0x78122, 0x8050)
        fun door(ptr: Int, destination: Int, flags: Int) {
            val pc = 0x10000 + ptr
            word(pc, destination); word(pc + 2, flags); word(pc + 8, 0x8000); word(pc + 10, 0x9000)
        }
        door(0x8000, 0xA000, 0x0081); door(0x8010, 0xA100, 0x00C1)
        door(0x8020, 0x91F8, 0x0081); door(0x8030, 0xA100, 0x00C1)
        door(0x8040, 0x91F8, 0x00C1); door(0x8050, 0xA000, 0x00C1)
        if (nestedRoom) {
            otherRoom(0xA200, 0, 0x8140, 2, 3)
            rom[0x7A204] = 1; rom[0x7A205] = 1
            door(0x8050, 0xA200, 0x00C1)
        }
        val missile = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
        val save = RomParser.PlmEntry(0xB76F, 8, 8, 0x8001)
        RomParser.serializePlmSet(if (hasSave) listOf(missile, save) else listOf(missile)).forEachIndexed { i, value -> rom[0x78000 + i] = value.toByte() }
        word(0x79300 + 20, 0x8800)
        RomParser.serializePlmSet(if (hasSave) listOf(save.copy(x = 9), missile.copy(param = 6)) else listOf(missile.copy(param = 6)))
            .forEachIndexed { i, value -> rom[0x78800 + i] = value.toByte() }
        word(0x44C5, 0) // Remove the synthetic gunship reference; this is an ordinary save room.
        (if (hasSave) listOf(0x91F8, 0x8020, 0x77, 0x100, 0x100, 0xFFF0, 0xFFE0) else List(7) { 0 })
            .forEachIndexed { i, value -> word(0x44C5 + 14 + i * 2, value) }
        word(0x44C5 + 28 + 4, 0) // saveSourceTestRom has an unrelated demo field here.
        word(0x45CF + 14, 0xA100) // Source index 1 is unavailable in the target area; use 0 instead.
        val parser = RomParser(rom)
        assertEquals(setOf(0x91F8, 0xA000, 0xA100) + if (nestedRoom) setOf(0xA200) else emptySet(),
            parser.roomCatalog.rooms.map { it.getRoomIdAsInt() }.toSet())
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path).apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        val bridge = editor.bindRoomSources(AsmPlmSourceCatalog("", macros, mapOf("DoorASM_Custom" to 0x9000), parser, emptyMap()), workspace)
        editor.loadRoom(0x91F8, parser, requireNotNull(parser.readRoomHeader(0x91F8)))
        persistMinimapData(parser, project, MinimapData.empty(0).withFill(2, 3, 3, 4, 0x0F))
        persistMapStationData(parser, project, MapStationData.empty(0).withValue(2, 3, true))
        val map = MinimapEditorState().apply { loadArea(parser, 0, editor, selectRoomId = 0x91F8) }
        return Fixture(editor, workspace, bridge, map, file)
    }

    @Test fun `same area previews cancelled drops and no motion never adopt or dirty source`() = runBlocking {
        val f = fixture()
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        val dirty = f.editor.dirty
        val stroke = f.map.mapData.withTile(50, 20, 0x0F)
        f.map.pendingStroke = stroke
        assertFalse(f.map.beginPrimaryRoomDrag(2, 3, f.editor)); assertSame(stroke, f.map.pendingStroke)
        f.map.pendingStroke = null
        assertTrue(f.map.beginPrimaryRoomDrag(3, 4, f.editor)) // Preserve the grab offset inside the room.
        f.map.updatePointerRoomMove(4, 4)
        assertEquals(3, f.map.moveBuffer?.currentX)
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
        f.map.undo(f.editor) // Escape/Undo cancels the preview, not an earlier project edit.
        assertNull(f.map.moveBuffer)
        assertTrue(f.map.beginPrimaryRoomDrag(2, 3, f.editor))
        f.map.endPrimaryRoomDrag(2, 3, f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(dirty, f.editor.dirty); assertTrue(f.map.undoStack.isEmpty()); assertTrue(f.editor.undoStack.isEmpty())
    }

    @Test fun `cross area save migration and reset preserve symbolic incoming door ownership through undo and cold export`() = runBlocking {
        val f = fixture()
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        assertTrue(f.editor.ensureDoorSource("Incoming room"))
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertTrue(f.editor.ensureCurrentPlmSource("Save room"))
        val reference = ProjectSourceDoorReference(0xA000, 0)
        val station = f.editor.workingPlms.single { it.id == 0xB76F }
        assertTrue(f.editor.updateSaveStationIncomingDoor(station, f.editor.incomingDoorConditionOptions(0x91F8, f.parser).single { it.sourceDoor == reference }), f.editor.statusMessage)
        val before = f.sources(); val model = f.model()
        f.move()
        val save = f.bridge.saveResources(0x91F8).single { !it.cleared }
        assertEquals(1, save.area); assertEquals(reference, save.sourceDoor)
        assertEquals(0x8020, f.coldExport().readSaveEntry(save.area, save.slot)!!.doorPtr)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.sources()); assertEquals(model, f.model())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(reference, f.bridge.saveResources(0x91F8).single { !it.cleared }.sourceDoor)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(reference, f.bridge.saveResources(0x91F8).single { !it.cleared }.sourceDoor)
        assertEquals(0x8020, f.coldExport().readSaveEntry(save.area, save.slot)!!.doorPtr)
    }

    @Test fun `same area arrow move adopts only the header and shares exact map reveal and canvas undo`() = runBlocking {
        val f = fixture()
        val model = f.model(); val source = f.sources(); val selected = f.editor.currentStateIndex
        val plms = f.editor.workingPlms.toList(); val level = f.editor.workingLevelData!!.copyOf()
        val map = f.map.mapData; val stations = f.map.stationData
        f.map.moveRoom(1, 0, f.editor) // A partially overlapping source/destination must work.
        assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        val moved = f.model(); val movedSource = f.sources()
        val room = f.project.rooms.getValue("91F8")
        assertEquals(1, room.asmRoomPropertiesSourceVersion); assertEquals(0, room.asmDoorSourceVersion); assertEquals(0, room.asmPlmSourceVersion)
        assertNull(room.roomHeaderChange); assertEquals(3, room.effectiveHeaderChange?.mapX); assertEquals(3, room.effectiveHeaderChange?.mapY)
        assertEquals(source.getValue(DOOR_SOURCE_FILE), f.bridge.doors.sourceText())
        assertNull(f.workspace.sourceUndoHead(DOOR_SOURCE_FILE), "A position-only move must not anchor unrelated bank history")
        assertEquals(selected, f.editor.currentStateIndex); assertEquals(plms, f.editor.workingPlms); assertContentEquals(level, f.editor.workingLevelData)
        val expectedMap = placeRoomTilesNonEmpty(clearRoomTiles(map, 2, 3, 2, 2), 3, 3, 2, 2, extractRoomTiles(map, 2, 3, 2, 2))
        assertEquals(expectedMap, f.map.mapData)
        assertFalse(f.map.stationData.isRevealed(2, 3)); assertTrue(f.map.stationData.isRevealed(3, 3))
        assertEquals(1, f.map.undoStack.size); assertEquals("Move room on map", f.editor.undoStack.single().description)
        val built = f.coldExport()
        assertEquals(3, built.readRoomHeader(0x91F8)?.mapX)
        assertEquals(f.parser.readSaveEntry(0, 1)!!.words(), built.readSaveEntry(0, 1)!!.words())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(map, f.map.mapData); assertEquals(stations, f.map.stationData)
        f.map.redo(f.editor)
        assertEquals(moved, f.model()); assertEquals(movedSource, f.sources())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
        f.map.redo(f.editor)
        assertEquals(moved, f.model()); assertEquals(movedSource, f.sources())
    }

    @Test fun `same area middle drag preserves authored notes private state data saves doors and another canvas`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.ensureRoomPropertiesSource("Room")); assertTrue(f.editor.ensureCurrentPlmSource("Room")); assertTrue(f.editor.ensureDoorSource("Room"))
        val original = f.bridge.sourceText()
        val noted = original.replace("; Map X", "; hand-authored position · Map X").replace("\n", "\r\n")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original, noted)
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        f.editor.applyBulkEdits("Other canvas", listOf(TileEdit(5, 5, 0, 0x8123)))
        val working = f.editor.workingLevelData!!.copyOf(); val canvasHistory = f.editor.undoStack.toList()
        val model = f.model(); val source = f.sources()
        val states = f.project.rooms.getValue("91F8").states.toList()
        val properties = AsmRoomPropertiesSource(noted)
        val room = f.project.rooms.getValue("91F8")
        val expected = properties.editHeader(room, properties.resource(room).header.copy(mapX = 5))
        f.map.activateTool(MinimapTool.PAINT, f.editor)
        assertTrue(f.map.beginMiddleRoomDrag(3, 4, f.editor))
        f.map.endMiddleRoomDrag(6, 4, f.editor)
        assertNull(f.map.moveBuffer); assertNull(f.map.selectedRoom); assertEquals(MinimapTool.PAINT, f.map.tool)
        assertEquals(expected, f.bridge.sourceText()); assertEquals(source.getValue(DOOR_SOURCE_FILE), f.bridge.doors.sourceText())
        assertTrue(room.states.zip(states).all { it.first === it.second })
        assertEquals(0xA000, f.editor.currentRoomId); assertContentEquals(working, f.editor.workingLevelData); assertEquals(canvasHistory, f.editor.undoStack)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(canvasHistory, f.editor.undoStack)
        assertTrue(f.editor.undo()); assertEquals(0, f.editor.readBlockWord(5, 5))
    }

    @Test fun `same area move refuses stale previews occupied drops and invalid drafts without publishing anything`() = runBlocking {
        val failures: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "occupied room" to { f -> f.map.moveRoom(5, 7, f.editor) }, // Destination (10,10), occupied by A000.
            "new map data" to { f -> persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 0).withTile(5, 3, 0x0F)) },
            "new reveal data" to { f -> persistMapStationData(f.parser, f.project, effectiveMapStationData(f.parser, f.project, 0).withValue(5, 3, true)) },
            "new header position" to { f -> val before = f.bridge.sourceText(); val room = f.project.rooms.getValue("91F8"); val source = AsmRoomPropertiesSource(before)
                f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, source.editHeader(room, source.resource(room).header.copy(mapX = 4))) },
            "invalid header" to { f -> val before = f.bridge.sourceText(); f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db ${'$'}00 ; Area", "db ${'$'}FF ; Area")) },
            "pending canvas edit" to { f -> f.editor.brush = TileBrush.single(42, blockType = 8); f.editor.beginStroke(); f.editor.paintAt(5, 5) },
            "changed mode" to { f -> f.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM })
        for ((name, modify) in failures) {
            val f = fixture(); assertTrue(f.editor.ensureRoomPropertiesSource("Room"))
            f.map.moveRoom(3, 0, f.editor); modify(f)
            val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
            val canvasHistory = f.editor.undoStack.toList(); val mapHistory = f.map.undoStack.toList()
            val working = f.editor.workingLevelData!!.copyOf()
            assertFalse(f.map.applyMove(f.editor), "$name: ${f.editor.statusMessage}")
            assertEquals(model, f.model(), name); assertEquals(source, f.sources(), name); assertEquals(revision, f.workspace.sourceBufferRevision, name)
            assertEquals(canvasHistory, f.editor.undoStack, name); assertEquals(mapHistory, f.map.undoStack, name)
            assertContentEquals(working, f.editor.workingLevelData, name)
            f.map.cancelMove(f.editor)
            assertEquals(effectiveMinimapData(f.parser, f.project, 0), f.map.mapData)
        }
    }

    @Test fun `same area history composes with cross area save migration and blocks newer edits before undo`() = runBlocking {
        val f = fixture()
        val model = f.model(); val source = f.sources()
        f.map.moveRoom(1, 0, f.editor); assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        val first = f.model(); val firstSource = f.sources()
        assertTrue(f.move().success, f.editor.statusMessage)
        f.map.openRoom(f.parser, 0x91F8, f.editor)
        f.map.moveRoom(1, 0, f.editor); assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        val final = f.model(); val finalSource = f.sources()
        val current = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, current, current + "; Newer source\n")
        f.map.undo(f.editor)
        assertEquals(final, f.model()); assertContains(f.editor.statusMessage, "newer")
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        f.map.selectedTile = 0x2F; f.map.paintTile(50, 20, f.editor)
        val painted = f.model()
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE)); assertEquals(painted, f.model()); assertEquals(finalSource, f.sources())
        f.map.undo(f.editor); assertEquals(final, f.model())
        repeat(2) { f.map.undo(f.editor) }
        assertEquals(first, f.model()); assertEquals(firstSource, f.sources())
        f.map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
        repeat(3) { f.map.redo(f.editor) }
        assertEquals(final, f.model()); assertEquals(finalSource, f.sources())
        assertEquals(1, f.coldExport().readRoomHeader(0x91F8)?.area)
    }

    @Test fun `same area moves preserve nested room cells and reject occupied reveals and padding row`() = runBlocking {
        val f = fixture()
        f.project.getOrCreateRoom(0xA000).roomHeaderChange = RoomHeaderChange(mapX = 2, mapY = 3, width = 1, height = 1)
        f.map.loadArea(f.parser, 0, f.editor, selectRoomId = 0x91F8)
        val model = f.model(); val source = f.sources()
        f.map.moveRoom(4, 0, f.editor)
        assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        assertEquals(0x0F, f.map.mapData.getTile(2, 3)); assertTrue(f.map.stationData.isRevealed(2, 3))
        assertTrue(isEmptyMinimapTile(f.map.mapData.getTile(6, 3))); assertFalse(f.map.stationData.isRevealed(6, 3))
        assertEquals(0x0F, f.map.mapData.getTile(7, 3))
        f.map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
        persistMapStationData(f.parser, f.project, f.map.stationData.withValue(6, 3, true))
        f.map.moveRoom(4, 0, f.editor)
        val occupied = f.model()
        assertFalse(f.map.applyMove(f.editor)); assertEquals(occupied, f.model()); assertEquals(source, f.sources())
        f.map.cancelMove(f.editor)
        f.map.moveRoom(100, 100, f.editor)
        assertEquals(62, f.map.moveBuffer?.currentX); assertEquals(29, f.map.moveBuffer?.currentY)
        assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        assertEquals(29, f.project.rooms.getValue("91F8").effectiveHeaderChange?.mapY)
    }

    @Test fun `area move automatically adopts headers doors and state scoped saves as one cross bank action`() = runBlocking {
        val f = fixture()
        val beforeModel = f.model(); val beforeSources = f.sources()
        val oldDoors = listOf(0x91F8, 0xA000, 0xA100).associateWith(f::entries)
        val state = f.editor.currentStateIndex
        val result = f.move()
        assertTrue(result.success, result.message); assertEquals(4, result.updatedDoorCount)
        val room = f.project.rooms.getValue("91F8")
        assertEquals(1, f.editor.activeRoomAreaForEditing()); assertEquals(state, f.editor.currentStateIndex)
        assertEquals(1, room.asmRoomPropertiesSourceVersion); assertEquals(1, room.asmDoorSourceVersion); assertEquals(1, room.asmPlmSourceVersion)
        assertNull(room.roomHeaderChange); assertTrue(room.doorChanges.isEmpty()); assertTrue(room.plmChanges.isEmpty()); assertTrue(room.saveStationSpawns.isEmpty())
        assertTrue(room.states.all { it.plmChanges.isEmpty() && it.stateDataChange == null })
        val saves = f.bridge.saveResources(0x91F8)
        assertTrue(saves.single { it.area == 0 }.cleared)
        val live = saves.single { it.area == 1 }
        assertEquals(0, live.slot); assertEquals(listOf(0x91F8, 0x8020, 0x77, 0x100, 0x100, 0xFFF0, 0xFFE0), live.words)
        for (s in room.states) {
            val view = f.bridge.managedView(0x91F8, s.id)
            assertNull(view.problem, view.problem); assertEquals(0x8000, view.document!!.entries.single { it.id == 0xB76F }.param)
        }
        for ((id, entries) in oldDoors) {
            val changed = f.entries(id)
            entries.zip(changed).forEach { (before, after) ->
                assertEquals(before.copy(bitflag = after.bitflag), after, "Every non-area field and DoorDef identity stays exact")
                val crosses = (if (id == 0x91F8) 1 else f.parser.readRoomHeader(id)!!.area) !=
                    (if (after.destRoomPtr == 0x91F8) 1 else f.parser.readRoomHeader(after.destRoomPtr)!!.area)
                assertEquals(crosses, after.bitflag and 0x40 != 0)
            }
        }
        assertNotEquals(2 to 3, result.destinationMapX to result.destinationMapY)
        assertEquals(0x0F, effectiveMinimapData(f.parser, f.project, 1).getTile(result.destinationMapX!!, result.destinationMapY!!))
        assertTrue(effectiveMapStationData(f.parser, f.project, 1).isRevealed(result.destinationMapX!!, result.destinationMapY!!))
        assertEquals(1, f.map.undoStack.size); assertEquals(1, f.editor.undoStack.size)
        val afterModel = f.model(); val afterSources = f.sources()
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(beforeModel, f.model()); assertEquals(beforeSources, f.sources())
        assertEquals(0, f.editor.activeRoomAreaForEditing()); assertEquals(1, f.map.redoStack.size); assertTrue(f.map.undoStack.isEmpty())
        f.map.redo(f.editor)
        assertEquals(afterModel, f.model()); assertEquals(afterSources, f.sources())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(beforeModel, f.model()); assertEquals(beforeSources, f.sources())
        f.map.redo(f.editor)
        assertEquals(afterModel, f.model()); assertEquals(afterSources, f.sources())
        val exported = f.coldExport()
        assertEquals(1, exported.readRoomHeader(0x91F8)?.area)
        assertEquals(List(7) { 0 }, exported.readSaveEntry(0, 1)!!.words())
        assertEquals(live.words, exported.readSaveEntry(1, 0)!!.words())
        assertEquals(f.editor.workingPlms, exported.parsePlmSet(exported.parseRoomStatesWithData(0x91F8)[state].plmSetPtr))
    }

    @Test fun `owned values private states notes and repeated area migrations survive cold source and projection reload`() = runBlocking {
        val f = fixture()
        val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(f.parser) }
        room.roomHeaderChange = RoomHeaderChange(index = 42, upScroller = 7)
        room.states.first().stateDataChange = StateDataChange(musicTrack = 13)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertTrue(f.editor.ensureRoomPropertiesSource("Moved room"), f.editor.statusMessage)
        assertTrue(f.editor.ensureCurrentPlmSource("Moved room"), f.editor.statusMessage)
        assertTrue(f.editor.ensureDoorSource("Moved room"), f.editor.statusMessage)
        val before = f.bridge.doors.sourceText()
        val noted = before.replace("; Flags, direction", "; my connection note · Flags, direction").replace("\n", "\r\n")
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, before, noted)
        f.editor.switchRoomState(room.states.first().id, f.parser)
        val selected = f.editor.currentPlmStateId(); val stateIdentity = room.states.toList()
        val originalNativeCode = f.bridge.sourceText().substringBefore("; SMEDIT")
        val baseline = f.model(); val baselineSources = f.sources()
        assertTrue(f.move().success, f.editor.statusMessage)
        assertSame(room, f.project.rooms.getValue("91F8")); assertTrue(room.states.zip(stateIdentity).all { it.first === it.second })
        assertEquals(selected, f.editor.currentPlmStateId())
        assertEquals(42, room.effectiveHeaderChange?.index); assertEquals(7, room.effectiveHeaderChange?.upScroller)
        assertEquals(13, room.states.first().effectiveStateDataChange?.musicTrack)
        assertContains(f.bridge.doors.sourceText(), "; my connection note")
        assertTrue(f.bridge.sourceText().startsWith(originalNativeCode), "Handwritten executable code is never rewritten")
        assertTrue(f.move(0).success, f.editor.statusMessage)
        assertTrue(f.move(1).success, f.editor.statusMessage)
        assertEquals(selected, f.editor.currentPlmStateId())
        val saves = f.bridge.saveResources(0x91F8)
        assertEquals(1, saves.count { !it.cleared }); assertTrue(saves.filter { it.area == 0 }.all { it.cleared })
        assertTrue(f.workspace.saveAllSources(), f.workspace.error); assertTrue(f.editor.saveProject())
        val cold = Json.decodeFromString<SmEditProject>(f.model())
        val coldRoom = cold.rooms.getValue("91F8")
        assertNull(coldRoom.asmHeaderPropertiesProjection)
        val fresh = AsmPlmSourceBridge(f.bridge.catalog, f.workspace, false) { cold }
        coldRoom.states.forEach { assertNull(fresh.managedView(0x91F8, it.id).problem) }
        val cache = AsmRoomProjectionRepository(File(directory, "cold-projections"))
        repeat(2) {
            val loaded = cache.load(f.bridge.sourceText(), f.workspace.projectSourceText("macros.asm"), listOf(coldRoom), f.bridge.catalog)
            assertEquals(coldRoom.states.map { it.id }.toSet(), loaded.getValue(0x91F8).documents.keys)
        }
        // ASM-first edits after reopen must not require a GUI header projection to select the live area.
        assertNull(coldRoom.asmHeaderPropertiesProjection)
        val spawnSource = fresh.sourceText()
        val spawn = fresh.saveResources(0x91F8).single { !it.cleared }
        val adjusted = spawn.copy(words = spawn.words.toMutableList().apply { this[3] += 16 })
        fresh.updateSaveSpawn(adjusted)
        assertEquals(adjusted, fresh.saveSpawn(0x91F8, adjusted.area, adjusted.slot))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(spawnSource, fresh.sourceText())
        assertEquals(1, f.coldExport().readRoomHeader(0x91F8)?.area)
        // Save independently enriches patch preconditions. Room history must leave those updates intact.
        val expectedBaseline = Json.encodeToString(SmEditProject.serializer(), Json.decodeFromString<SmEditProject>(baseline)
            .copy(patches = f.project.patches.toMutableList()))
        repeat(3) { f.map.undo(f.editor) }
        assertEquals(expectedBaseline, f.model()); assertEquals(baselineSources, f.sources())
        assertEquals(selected, f.editor.currentPlmStateId())
    }

    @Test fun `newer source map edits and floating previews block every part of area undo until resolved`() = runBlocking {
        val f = fixture()
        val original = f.model(); val originalPlms = f.editor.workingPlms.toList()
        assertTrue(f.move().success, f.editor.statusMessage)
        val moved = f.model(); val sources = f.sources()
        val latest83 = f.bridge.doors.sourceText() + "; new source edit\n"
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, sources.getValue(DOOR_SOURCE_FILE), latest83)
        f.map.undo(f.editor)
        assertEquals(moved, f.model()); assertEquals(1, f.map.undoStack.size)
        assertEquals(latest83, f.bridge.doors.sourceText())
        assertContains(f.editor.statusMessage, "newer")
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        f.map.selectedTile = 0x2F; f.map.paintTile(50, 20, f.editor)
        val painted = f.model()
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertContains(f.workspace.error.orEmpty(), "map edits")
        assertEquals(painted, f.model()); assertEquals(sources, f.sources())
        f.map.undo(f.editor) // Undo the ordinary map stroke, not the compound area move.
        assertEquals(moved, f.model())
        f.map.pendingStroke = f.map.mapData.withTile(50, 20, 0x10)
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE)); assertContains(f.workspace.error.orEmpty(), "map stroke")
        assertEquals(moved, f.model()); assertEquals(sources, f.sources())
        f.map.pendingStroke = null
        f.map.undo(f.editor)
        assertEquals(original, f.model()); assertEquals(originalPlms, f.editor.workingPlms)
        f.map.redo(f.editor)
        assertEquals(moved, f.model())
    }

    @Test fun `failed moves never attach staged headers doors objects maps or history`() = runBlocking {
        val failures: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "map space" to { f -> persistMinimapData(f.parser, f.project, MinimapData.empty(1).withFill(0, 0, 63, 30, 0x0F)) },
            "save space" to { f ->
                val other = f.project.getOrCreateRoom(0xA100)
                for (slot in 0..7) other.saveStationSpawns += SaveStationSpawnChange(1, slot, 0xA100, 0x8050, 0, 0, 0, 0)
            },
            "retained incoming source" to { f -> val before = f.bridge.doors.sourceText(); f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, before,
                before + "  MACRO SmeditDoors_A000_Door_0()\nendmacro\n") },
            "orphan GUI door override" to { f -> f.project.getOrCreateRoom(0xA000).doorChanges +=
                DoorChange(99, 0x91F8, 0x81, 0, 0, 0, 0x8000, 0x9000) },
            "invalid owned header" to { f -> assertTrue(f.editor.ensureRoomPropertiesSource("Room")); val before = f.bridge.sourceText()
                f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db ${'$'}00 ; Area", "db ${'$'}FF ; Area")) },
            "invalid save PLM source" to { f -> assertTrue(f.editor.ensureCurrentPlmSource("Room")); val before = f.bridge.sourceText()
                f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("${'$'}B76F, ${'$'}08, ${'$'}08, ${'$'}8001", "${'$'}B76F, ${'$'}08, ${'$'}08, ${'$'}8009")) })
        for ((name, change) in failures) {
            val f = fixture(); change(f)
            val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
            val history = f.editor.undoStack.toList(); val mapHistory = f.map.undoStack.toList()
            val result = f.move()
            assertFalse(result.success, "$name: ${result.message}")
            assertEquals(model, f.model(), name); assertEquals(source, f.sources(), name); assertEquals(revision, f.workspace.sourceBufferRevision, name)
            assertEquals(history, f.editor.undoStack, name); assertEquals(mapHistory, f.map.undoStack, name)
        }
    }

    @Test fun `reset after relocation returns native headers maps saves and flags with reciprocal exact undo redo`() = runBlocking {
        val f = fixture(); assertTrue(f.move().success, f.editor.statusMessage)
        // Edits made after the move are not rewound by Reset.
        val other = f.project.getOrCreateRoom(0xA000)
        other.stateDataChange = StateDataChange(musicTrack = 7)
        persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 1).withTile(50, 20, 0x0F))
        persistMapStationData(f.parser, f.project, effectiveMapStationData(f.parser, f.project, 1).withValue(50, 20, true))
        val moved = f.model(); val source = f.sources()
        val original = f.project.rooms.getValue("91F8")
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        val reset = f.model(); val resetSource = f.sources()
        assertFalse(f.project.rooms.getValue("91F8").hasEdits)
        assertSame(other, f.project.rooms["A000"]); assertEquals(7, other.stateDataChange?.musicTrack)
        assertEquals(0, f.editor.activeRoomAreaForEditing())
        assertEquals(f.parser.parsePlmSet(f.parser.parseRoomStatesWithData(0x91F8)[f.editor.currentStateIndex].plmSetPtr), f.editor.workingPlms)
        val built = f.coldExport()
        assertEquals(f.parser.readRoomHeader(0x91F8), built.readRoomHeader(0x91F8))
        for (id in listOf(0x91F8, 0xA000, 0xA100)) assertEquals(f.parser.parseDoorList(f.parser.readRoomHeader(id)!!.doorOut), f.entries(id))
        for (area in 0..1) for (slot in 0 until f.parser.saveStationSlotCount(area)) {
            assertEquals(f.parser.readSaveEntry(area, slot)!!.words(), built.readSaveEntry(area, slot)!!.words())
        }
        assertEquals(0x0F, effectiveMinimapData(f.parser, f.project, 1).getTile(50, 20))
        assertTrue(effectiveMapStationData(f.parser, f.project, 1).isRevealed(50, 20))
        for (area in 0..1) {
            val map = effectiveMinimapData(f.parser, f.project, area)
            val reveal = effectiveMapStationData(f.parser, f.project, area)
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                if (area == 1 && x == 50 && y == 20) continue
                assertEquals(f.parser.readMinimapTiles(area).getTile(x, y), map.getTile(x, y))
                assertEquals(f.parser.readMapStationData(area).isRevealed(x, y), reveal.isRevealed(x, y))
            }
        }
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(moved, f.model()); assertEquals(source, f.sources())
        assertSame(original, f.project.rooms["91F8"])
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(reset, f.model()); assertEquals(resetSource, f.sources())
        f.map.undo(f.editor)
        assertEquals(moved, f.model()); assertEquals(source, f.sources())
        f.map.redo(f.editor)
        assertEquals(reset, f.model()); assertEquals(resetSource, f.sources())
        f.map.initIfNeeded(f.parser, f.editor)
        assertTrue(f.map.undoStack.isNotEmpty(), "A source reset must remain part of map history after recomposition")
    }

    @Test fun `reset handles overlapping same area moves without adopting doors and preserves later map strokes`() = runBlocking {
        val f = fixture()
        f.map.moveRoom(1, 0, f.editor)
        assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 0).withTile(50, 20, 0x0F))
        val moved = f.model(); val source = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertFalse(f.project.rooms.getValue("91F8").hasEdits)
        assertNull(f.workspace.sourceUndoHead(DOOR_SOURCE_FILE))
        assertEquals(0x0F, effectiveMinimapData(f.parser, f.project, 0).getTile(50, 20))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(moved, f.model()); assertEquals(source, f.sources())
        assertFalse(f.editor.undo(), "The later independent map stroke must not be rewound by the earlier move")
    }

    @Test fun `reset relocation conflicts publish no source metadata map or history changes`() = runBlocking {
        val cases = listOf<Pair<String, (Fixture) -> Unit>>(
            "original map location" to { f -> f.project.getOrCreateRoom(0xA000).roomHeaderChange = RoomHeaderChange(mapX = 2, mapY = 3) },
            "new map art" to { f -> persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 0).withTile(2, 3, 0x12)) },
            "new reveal data" to { f -> persistMapStationData(f.parser, f.project, effectiveMapStationData(f.parser, f.project, 0).withValue(2, 3, true)) },
            "save slot" to { f -> f.project.getOrCreateRoom(0xA100).saveStationSpawns += SaveStationSpawnChange(1, 0, 0xA100, 0x8020, 0, 0, 0, 0) },
            "save slot" to { f -> f.project.getOrCreateRoom(0xA100).plmChanges += PlmChange("add", 0xB76F, 1, 1, 0) })
        for ((message, mutate) in cases) {
            val f = fixture(); assertTrue(f.move().success, f.editor.statusMessage); mutate(f)
            val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
            val history = f.editor.undoStack.toList()
            assertFalse(f.editor.resetCurrentRoomToOriginal(f.parser), message)
            assertContains(f.editor.statusMessage, message)
            assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertEquals(history, f.editor.undoStack)
        }
    }

    @Test fun `newer dependencies and build mode changes block whole relocation reset undo`() = runBlocking {
        val f = fixture(); assertTrue(f.move().success, f.editor.statusMessage)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        val source = f.sources()
        persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 0).withTile(50, 20, 0x0F))
        val model = f.model()
        assertFalse(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE)); assertEquals(model, f.model()); assertEquals(source, f.sources())
        persistMinimapData(f.parser, f.project, f.parser.readMinimapTiles(0))
        f.project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        val wrongMode = f.model()
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE)); assertEquals(wrongMode, f.model()); assertEquals(source, f.sources())
        f.project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
    }

    @Test fun `reset retains only necessary native outgoing area corrections when a neighbor remains moved and is idempotent`() = runBlocking {
        val f = fixture(hasSave = false)
        assertTrue(f.move().success, f.editor.statusMessage)
        f.project.getOrCreateRoom(0xA000).roomHeaderChange = RoomHeaderChange(area = 1)
        val model = f.model(); val source = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        assertEquals(1, room.asmDoorSourceVersion); assertEquals(0, room.asmRoomPropertiesSourceVersion)
        assertEquals(0xC1, f.entries(0x91F8).first().bitflag)
        assertEquals(0xC1, f.entries(0xA000).first().bitflag)
        assertEquals(1, f.project.rooms.getValue("A000").effectiveHeaderChange?.area)
        val reset = f.model(); val resetSource = f.sources(); val head = f.workspace.sourceUndoHead(PLM_SOURCE_FILE)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertSame(head, f.workspace.sourceUndoHead(PLM_SOURCE_FILE)); assertEquals(reset, f.model()); assertEquals(resetSource, f.sources())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
    }

    @Test fun `repeated moves can be reset with historical save releases after a cold reopen`() = runBlocking {
        val f = fixture()
        assertTrue(f.move().success, f.editor.statusMessage)
        assertTrue(f.move(2).success, f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(f.model())
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true, progressive = true)
        val editor = EditorState().apply { testMode = true; initForProject(f.file, cold) }
        editor.bindRoomSources(f.bridge.catalog, workspace)
        editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        val before = Json.encodeToString(SmEditProject.serializer(), cold)
        assertTrue(editor.resetCurrentRoomToOriginal(f.parser), editor.statusMessage)
        assertFalse(cold.rooms.getValue("91F8").hasEdits)
        assertFalse(Regex("(?m)^macro SmeditObjects_91F8_Save_").containsMatchIn(workspace.projectSourceText(PLM_SOURCE_FILE)))
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), cold))
    }

    @Test fun `pending map gestures block reset and its source undo without losing the gesture`() = runBlocking {
        val f = fixture()
        assertTrue(f.move().success, f.editor.statusMessage)
        val model = f.model(); val source = f.sources(); val history = f.map.undoStack.toList()
        val stroke = f.map.mapData.withTile(50, 20, 0x0F)
        f.map.pendingStroke = stroke
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.parser)); assertContains(f.editor.statusMessage, "map stroke")
        assertSame(stroke, f.map.pendingStroke); assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(history, f.map.undoStack)
        f.map.pendingStroke = null
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        val resetModel = f.model(); val resetSource = f.sources()
        f.map.pendingStroke = stroke
        assertFalse(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE))
        assertSame(stroke, f.map.pendingStroke); assertEquals(resetModel, f.model()); assertEquals(resetSource, f.sources())
        f.map.pendingStroke = null
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
    }

    @Test fun `position only reset leaves neighboring manually authored flags and arrival code exact`() = runBlocking {
        val f = fixture()
        val neighbor = f.project.getOrCreateRoom(0xA000).also { it.ensureStateManifest(f.parser) }
        f.bridge.doors.migrate(neighbor.roomId, "Neighbor")
        f.bridge.doors.edit(neighbor.roomId, 0, f.entries(neighbor.roomId).first().copy(bitflag = 0xC1, distFromDoor = 0x1234))
        val source83 = f.bridge.doors.sourceText(); val doors = f.entries(neighbor.roomId)
        f.map.moveRoom(1, 0, f.editor)
        assertTrue(f.map.applyMove(f.editor), f.editor.statusMessage)
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(source83, f.bridge.doors.sourceText()); assertEquals(doors, f.entries(neighbor.roomId))
        assertSame(neighbor, f.project.rooms["A000"])
    }

    @Test fun `relocation reset preserves native nested room map art and reveal edits`() = runBlocking {
        val f = fixture(nestedRoom = true)
        assertTrue(f.move().success, f.editor.statusMessage)
        persistMinimapData(f.parser, f.project, effectiveMinimapData(f.parser, f.project, 0).withTile(2, 3, 0x1C))
        persistMapStationData(f.parser, f.project, effectiveMapStationData(f.parser, f.project, 0).withValue(2, 3, true))
        val moved = f.model(); val source = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0x1C, effectiveMinimapData(f.parser, f.project, 0).getTile(2, 3))
        assertTrue(effectiveMapStationData(f.parser, f.project, 0).isRevealed(2, 3))
        assertEquals(0, f.project.rooms.getValue("91F8").effectiveHeaderChange?.area ?: f.parser.readRoomHeader(0x91F8)!!.area)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(moved, f.model()); assertEquals(source, f.sources())
    }

    @Test fun `final native geometry preflight protects authored closing door caps even when the arrival screen fits`() = runBlocking {
        val f = fixture()
        assertTrue(f.move().success, f.editor.statusMessage)
        f.project.rooms.getValue("91F8").roomHeaderChange = RoomHeaderChange(width = 3)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        val door = f.entries(0xA000).first()
        f.bridge.doors.edit(0xA000, 0, door.copy(bitflag = (4 shl 8) or (door.bitflag and 255), doorCapCode = 32))
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.parser))
        assertContains(f.editor.statusMessage, "closing-door cap")
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `externally retained DoorDefs block replacement rather than creating duplicate native correction macros`() = runBlocking {
        val f = fixture(hasSave = false)
        assertTrue(f.move().success, f.editor.statusMessage)
        f.project.getOrCreateRoom(0xA000).roomHeaderChange = RoomHeaderChange(area = 1)
        val text = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text,
            text + "\nmacro CustomDoorData()\n %SmeditDoors_91F8_Door_0()\nendmacro\n")
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.parser))
        assertContains(f.editor.statusMessage, "external source reference")
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
    }

    @Test fun `ordinary source reset also protects an open map gesture and shares reset history without changing map art`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.ensureCurrentPlmSource("Room"), f.editor.statusMessage)
        val model = f.model(); val source = f.sources()
        val stroke = f.map.mapData.withTile(50, 20, 0x0F)
        f.map.pendingStroke = stroke
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.parser)); assertSame(stroke, f.map.pendingStroke)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
        f.map.pendingStroke = null
        val map = f.map.mapData; val reveal = f.map.stationData
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(map, f.map.mapData); assertEquals(reveal, f.map.stationData)
        f.map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
        assertEquals(map, f.map.mapData); assertEquals(reveal, f.map.stationData)
    }

    @Test fun `area moves privately split incompatible shared flags without changing other consumers`() = runBlocking {
        val f = fixture(sharedDoor = true)
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        val result = f.move()
        assertTrue(result.success, result.message)
        val doorSource = AsmDoorSource(f.bridge.doors.sourceText(), f.bridge.sourceText(), f.bridge.catalog.symbols)
        val private = doorSource.resource(f.project.rooms.getValue("A000"), f.parser).entries.first()
        assertEquals(0, private.doorDefPtr); assertFalse(private.shared); assertEquals(0x40, private.change.bitflag and 0x40)
        assertEquals(0x8020, f.editor.effectiveDoorsForRoom(0xA100, f.parser).first().doorDefPtr)
        assertEquals(0, f.editor.effectiveDoorsForRoom(0xA100, f.parser).first().bitflag and 0x40)
        assertTrue(f.workspace.sourceBufferRevision > revision)
        f.map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
    }

    @Test fun `adopted canonical shared flags remain unchanged when one consumer moves privately`() = runBlocking {
        val f = fixture(hasSave = false, sharedDoor = true)
        for (id in listOf(0xA000, 0xA100)) {
            f.editor.loadRoom(id, f.parser, f.parser.readRoomHeader(id)!!)
            assertTrue(f.editor.ensureDoorSource("Shared consumer"), f.editor.statusMessage)
        }
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        val model = f.model(); val source = f.sources()
        val result = f.move()
        assertTrue(result.success, result.message)
        assertEquals(0, f.bridge.doors.sharedView().entries.getValue(0x8020).change.bitflag and 0x40)
        assertEquals(0, f.editor.effectiveDoorsForRoom(0xA000, f.parser).first().doorDefPtr)
        assertEquals(0x8020, f.editor.effectiveDoorsForRoom(0xA100, f.parser).first().doorDefPtr)
        f.map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(source, f.sources())
    }

    @Test fun `an invalid shared draft does not lock unrelated native door fields or get overwritten by adoption`() = runBlocking {
        val f = fixture(hasSave = false, sharedDoor = true)
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        assertTrue(f.editor.ensureDoorSource("Shared consumer"), f.editor.statusMessage)
        val text = f.bridge.doors.sourceText()
        val body = authoredMacroBody(text, AsmDoorSource.sharedName(0x8020))
        val broken = text.replaceFirst(body, body.replace("dw ${'$'}8000", "dw missing"))
        assertNotEquals(text, broken)
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, text, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.doorPreviewProblem)
        f.editor.loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertNull(f.editor.doorPreviewProblem)
        f.editor.updateDoor(0, f.editor.doorEntries[0].copy(distFromDoor = 0x1234))
        assertEquals(0x1234, f.editor.doorEntries[0].distFromDoor, f.editor.statusMessage)
        assertContains(f.bridge.doors.sourceText(), "dw missing")
        assertNotNull(f.bridge.doors.sharedView().problem)
        assertNull(f.bridge.doors.view(0x91F8).problem)
    }

    @Test fun `the current authored area and incoming targets are read before deciding whether to move`() = runBlocking {
        val f = fixture(hasSave = false)
        assertTrue(f.editor.ensureRoomPropertiesSource("Room"))
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.sourceText()
        val properties = AsmRoomPropertiesSource(before)
        val changed = properties.editHeader(room, properties.resource(room).header.copy(area = 1))
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, changed) // Do not refresh the GUI's last projection first.
        val model = f.model(); val source = f.sources(); val revision = f.workspace.sourceBufferRevision
        val noOp = f.move(1)
        assertTrue(noOp.success, noOp.message); assertEquals(1, noOp.sourceArea); assertEquals(1, noOp.targetArea)
        assertEquals(model, f.model()); assertEquals(source, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertTrue(f.map.undoStack.isEmpty())
        val moved = f.move(2)
        assertTrue(moved.success, moved.message); assertEquals(1, moved.sourceArea)
        assertEquals(0, room.asmPlmSourceVersion, "A room without save migration does not acquire unrelated PLM ownership")
        assertEquals(2, f.coldExport().readRoomHeader(0x91F8)?.area)
    }

    @Test fun `a map driven area move does not change another canvas state geometry or undo history`() = runBlocking {
        val f = fixture()
        f.editor.loadRoom(0xA000, f.parser, f.parser.readRoomHeader(0xA000)!!)
        f.editor.applyBulkEdits("Unrelated canvas edit", listOf(TileEdit(5, 5, 0, 0x8123)))
        val working = f.editor.workingLevelData!!.copyOf()
        val history = f.editor.undoStack.toList(); val selected = f.editor.currentPlmStateId()
        val model = f.model(); val sources = f.sources()
        val result = f.move()
        assertTrue(result.success, result.message)
        assertEquals(0xA000, f.editor.currentRoomId); assertEquals(selected, f.editor.currentPlmStateId())
        assertContentEquals(working, f.editor.workingLevelData); assertEquals(history, f.editor.undoStack)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        assertContentEquals(working, f.editor.workingLevelData); assertEquals(history, f.editor.undoStack)
        assertTrue(f.editor.undo(), f.editor.statusMessage); assertEquals(0, f.editor.readBlockWord(5, 5))
    }

    @Test fun `Room Info can initialize map history without opening the map and restore the shared move`() = runBlocking {
        val f = fixture()
        val unopenedMap = MinimapEditorState()
        val model = f.model(); val sources = f.sources()
        // The production Room Info callback uses the same lazy initialization and command as Map.
        unopenedMap.initIfNeeded(f.parser, f.editor)
        val moved = assertNotNull(unopenedMap.reassignRoomArea(0x91F8, 1, f.editor))
        assertTrue(moved.success, moved.message); assertEquals(1, unopenedMap.undoStack.size)
        assertEquals(0x91F8, f.editor.currentRoomId)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        assertTrue(unopenedMap.undoStack.isEmpty()); assertEquals(1, unopenedMap.redoStack.size)
        unopenedMap.redo(f.editor)
        assertEquals(1, f.editor.activeRoomAreaForEditing()); assertEquals(1, unopenedMap.undoStack.size)
    }

    @Test fun `only zero releases may remain outside the current save area and foreign slots cannot be cleared`() = runBlocking {
        val f = fixture(); assertTrue(f.move().success, f.editor.statusMessage)
        val current = f.bridge.sourceText()
        val old = f.bridge.saveResources(0x91F8).single { it.area == 0 }
        val revived = AsmRoomObjectSource(current).withSave(old.copy(words = f.parser.readSaveEntry(0, 1)!!.words()))
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, current, revived)
        assertNotNull(f.bridge.managedView(0x91F8, f.editor.currentPlmStateId()!!).problem)
        assertFails { f.coldExport() }
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        val foreign = SourceSaveResource(AsmSaveStationSource.name(0x91F8, 1, 1), 0x91F8, 1, 1, List(7) { 0 })
        val stolen = AsmRoomObjectSource(current).withSave(foreign)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, current, stolen)
        assertNotNull(f.bridge.managedView(0x91F8, f.editor.currentPlmStateId()!!).problem)
        assertFails { f.coldExport() }
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertNull(f.bridge.managedView(0x91F8, f.editor.currentPlmStateId()!!).problem)
    }
}
