package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.ui.resizeLevelData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomResizeSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File)
    private suspend fun fixture(adopt: Boolean = true, nativeTrigger: Boolean = false, incoming: Boolean = false,
                                withSave: Boolean = false, mixedIncoming: Boolean = false, secondDestination: Int? = null): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n" + if (incoming) "incsrc bank_83.asm\n" else "")
        if (incoming) File(src, DOOR_SOURCE_FILE).writeText("; Keep original DoorDefs.\n")
        File(src, PLM_SOURCE_FILE).writeText("; Keep original source and custom notes.\n" + if (incoming)
            "DoorASM_Test:\n php\n sep #\$20\n lda.b #\$02\n sta.l \$7ECD23\n plp\n rts\n" else "")
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = (if (withSave) saveSourceTestRom() else objectSourceTestRom()).also {
            it.fill(0xFF.toByte(), 0x7F000, 0x80000)
            it.fill(0xFF.toByte(), 0x207000, 0x208000)
        }
        val level = ByteArray(2 + 32 * 32 * 5).also { it[1] = 8 }
        for (y in 0 until 32) for (x in 0 until 32) {
            val index = y * 32 + x
            val word = 0x8000 or ((x + y) and 0x3FF)
            level[2 + index * 2] = word.toByte(); level[3 + index * 2] = (word ushr 8).toByte()
            level[2 + 2048 + index] = (x and 3).toByte()
            level[2 + 3072 + index * 2] = ((x * 2 + y) and 255).toByte()
        }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) { rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte() }
        if (incoming) {
            fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
            rom[0x79C89 + 4] = 2; rom[0x79C89 + 5] = 2
            word(0x79C89 + 11, 0xE5E6); word(0x79C89 + 9, 0x9800)
            rom[0x79C96 + 1] = 0x80.toByte(); rom[0x79C96 + 2] = 0xC0.toByte()
            word(0x79800, 0x8000)
            word(0x18000, 0x91F8); word(0x18000 + 8, 0x8000); word(0x18000 + 10, 0x9500)
            val writes = listOf(DoorScrollWrite(2, 0x23))
            (if (mixedIncoming) buildDoorAsmClearingEnemyBg2Transfer(writes) else buildDoorScrollAsm(writes)).copyInto(rom, 0x79500)
            if (secondDestination != null) {
                word(0x79802, 0x8010); word(0x18010, secondDestination)
                word(0x18018, 0x8000); word(0x1801A, 0x9500)
            }
        }
        if (nativeTrigger) {
            val entries = listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5), RomParser.PlmEntry(0xB703, 4, 4, 0x9000))
            RomParser.serializePlmSet(entries).forEachIndexed { index, value -> rom[0x78000 + index] = value.toByte() }
            byteArrayOf(0, 0, 3, 2, 0x80.toByte()).copyInto(rom, 0x79000)
        }
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(parser)
        room.scrollChanges += ScrollChange(1, 0, 2, 0)
        if (!nativeTrigger) {
            room.plmChanges += PlmChange("add", 0xB703, 4, 4, 0xCC00)
            room.plmChanges += PlmChange("add", 0xB647, 4, 5, 0x8000)
            room.customScrollCommands["cmd_0"] = mutableListOf(ScrollCommand(0, 0), ScrollCommand(3, 2))
        }
        if (withSave) {
            room.plmChanges += PlmChange("add", 0xB76F, 8, 8, 0x8002)
            room.saveStationSpawns += SaveStationSpawnChange(0, 2, room.roomId, 0x8000, 256, 0, 128, 128)
        }
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros,
            if (incoming) mapOf("DoorASM_Test" to 0x9500) else emptyMap(), parser, emptyMap())
        val bridge = AsmPlmSourceBridge(catalog, workspace) { project }
        if (adopt) { bridge.migrateRoom(room.roomId, "Landing Site"); bridge.initialScrolls.migrate(room.roomId, "Landing Site") }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(room.roomId, parser, parser.readRoomHeader(room.roomId)!!)
            setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        }
        return Fixture(workspace, bridge, project, editor, file)
    }

    private fun Fixture.model() = Json.encodeToString(SmEditProject.serializer(), project)
    private fun Fixture.sources() = listOf(PLM_SOURCE_FILE, DOOR_SOURCE_FILE).associateWith(workspace::projectSourceText)
    private fun Fixture.program(index: Int = 0): AsmArrivalScrollProgram {
        val label = bridge.doors.view(0x9C89).resource!!.entries[index].arrival!!.label
        return assertNotNull(AsmArrivalScrollProgram.parse(authoredMacroBody(bridge.sourceText(), label.removeSuffix("_Entry"))))
    }

    @Test fun `untouched rooms stage their native manifest without attaching it on failure and undo removes the first edit record`() = runBlocking {
        val f = fixture(adopt = false)
        val parser = f.bridge.catalog.parser
        f.project.rooms.remove("91F8")
        f.editor.loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        assertFalse(f.project.rooms.containsKey("91F8"))
        val model = f.model(); val source = f.bridge.sourceText(); val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.editor.resizeRoom(2, 2, 15, 15))
        assertEquals(model, f.model()); assertEquals(source, f.bridge.sourceText())
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        assertEquals(1, room.asmPlmSourceVersion); assertEquals(48, f.editor.workingBlocksWide)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(source, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData); assertEquals(32, f.editor.workingBlocksWide)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertSame(room, f.project.rooms["91F8"]); assertEquals(48, f.editor.workingBlocksWide)
    }

    @Test fun `native scroll arrivals adopt atomically preserve native bytes and repeat resizes with reciprocal cross bank history`() = runBlocking {
        val f = fixture(incoming = true)
        val model = f.model(); val sources = f.sources(); val native = f.bridge.catalog.parser.copyRomData()
        assertFalse(f.project.rooms.containsKey("9C89"))
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val neighbor = f.project.rooms.getValue("9C89")
        assertEquals(1, neighbor.asmDoorSourceVersion); assertTrue(neighbor.doorChanges.isEmpty())
        assertEquals(listOf(DoorScrollWrite(2, 0x24)), f.program().writes)
        assertContains(f.bridge.sourceText(), "DoorASM_Test:\n php")
        assertContentEquals(native, f.bridge.catalog.parser.copyRomData())
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(32, f.editor.workingBlocksWide)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertSame(neighbor, f.project.rooms["9C89"])
        val text = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replace("sta.l \$7ECD24", "sta.l \$7ECD24 ; user camera note"))
        val first = f.model(); val firstSources = f.sources()
        assertTrue(f.editor.resizeRoom(3, 2, 4, 2), f.editor.statusMessage)
        assertEquals(listOf(DoorScrollWrite(2, 0x25)), f.program().writes)
        assertEquals(1, f.bridge.sourceText().windowed("user camera note".length).count { it == "user camera note" })
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(first, f.model()); assertEquals(firstSources, f.sources()); assertEquals(48, f.editor.workingBlocksWide)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(listOf(DoorScrollWrite(2, 0x25)), f.program().writes)
    }

    @Test fun `managed native scaffolds become inline scroll programs without losing notes or other door fields`() = runBlocking {
        val f = fixture(incoming = true)
        f.bridge.doors.createManagedArrival(0x9C89, 0, "Neighbor")
        val before = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before,
            before.replace("jmp.w DoorASM_Test", "jmp.w DoorASM_Test ; keep my intent").replace("\n", "\r\n"))
        val door = f.bridge.doors.view(0x9C89).resource!!.entries.single().entry()
        f.bridge.doors.edit(0x9C89, 0, door.copy(bitflag = 0x81, distFromDoor = 0x1234))
        val model = f.model(); val sources = f.sources()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val program = f.program()
        assertFalse(program.delegatesToNative); assertEquals("DoorASM_Test", program.nativeContext)
        assertEquals(listOf(DoorScrollWrite(2, 0x24)), program.writes)
        assertContains(f.bridge.sourceText(), "; keep my intent")
        val changed = f.bridge.doors.view(0x9C89).resource!!.entries.single().entry()
        assertEquals(door.copy(bitflag = 0x81, distFromDoor = 0x1234), changed)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
    }

    @Test fun `shared arrival copies only the resized consumer and preserves the other destination`() = runBlocking {
        val f = fixture(incoming = true, secondDestination = 0x9C89)
        f.bridge.doors.createManagedArrival(0x9C89, 1, "Neighbor")
        val original = f.bridge.doors.sourceText()
        val label = f.bridge.doors.view(0x9C89).resource!!.entries[1].arrival!!.label
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, original,
            AsmDoorSource(original, f.bridge.sourceText(), f.bridge.catalog.symbols).withArrival(0x9C89, 0, label))
        val sources = f.sources(); val model = f.model()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val doors = f.bridge.doors.view(0x9C89).resource!!.entries
        assertNotEquals(doors[0].arrival!!.label, doors[1].arrival!!.label)
        assertEquals(label, doors[1].arrival!!.label)
        assertEquals(listOf(DoorScrollWrite(2, 0x24)), f.program(0).writes)
        assertTrue(f.program(1).delegatesToNative)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
    }

    @Test fun `one arrival shared only by affected entrances updates once and keeps its identity`() = runBlocking {
        val f = fixture(incoming = true, secondDestination = 0x91F8)
        f.bridge.doors.createManagedArrival(0x9C89, 0, "Neighbor")
        val original = f.bridge.doors.sourceText()
        val label = f.bridge.doors.view(0x9C89).resource!!.entries[0].arrival!!.label
        f.workspace.replaceProjectSource(DOOR_SOURCE_FILE, original,
            AsmDoorSource(original, f.bridge.sourceText(), f.bridge.catalog.symbols).withArrival(0x9C89, 1, label))
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertTrue(f.bridge.doors.view(0x9C89).resource!!.entries.all { it.arrival!!.label == label })
        assertEquals(listOf(DoorScrollWrite(2, 0x24)), f.program().writes)
    }

    @Test fun `a word scroll store crossing the old row boundary becomes two correctly mapped byte stores`() = runBlocking {
        val f = fixture(incoming = true)
        f.bridge.doors.createManagedArrival(0x9C89, 0, "Neighbor")
        val text = f.bridge.sourceText()
        val body = authoredMacroBody(text, "SmeditArrival_9C89_Door_0")
        val replacement = "    ${AsmDoorArrivalsSource.MARKER}\n    php\n    lda.w #\$0201\n    sta.l \$7ECD21\n    plp\n    rts\n"
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replaceFirst(body, replacement))
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(listOf(DoorScrollWrite(1, 0x21), DoorScrollWrite(2, 0x23)), f.program().writes)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(replacement, authoredMacroBody(f.bridge.sourceText(), "SmeditArrival_9C89_Door_0"))
    }

    @Test fun `opaque edited native code shared references and cropped closing caps block without adopting any partial resource`() = runBlocking {
        val cases: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "cannot be proven" to { f -> val text = f.bridge.sourceText(); f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replace("sta.l \$7ECD23", "sta.l \$7ECD24")) },
            "shared outside" to { f ->
                f.bridge.doors.createManagedArrival(0x9C89, 0, "Neighbor")
                val text = f.bridge.sourceText()
                f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text + "\nmacro ExternalArrival()\n jsr.w SmeditArrival_9C89_Door_0_Entry\nendmacro\n")
            },
            "closing-door cap" to { f -> f.project.getOrCreateRoom(0x9C89).doorChanges += DoorChange(0, 0x91F8, 0x0481, 24, 0, 0, 0x8000, 0x9500) })
        for ((message, change) in cases) {
            val f = fixture(incoming = true); change(f)
            val model = f.model(); val sources = f.sources(); val revision = f.workspace.sourceBufferRevision
            val history = f.editor.undoStack.toList()
            assertFalse(f.editor.resizeRoom(2, 2, if (message == "closing-door cap") 1 else 3, 2))
            assertContains(f.editor.statusMessage, message)
            assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertEquals(history, f.editor.undoStack)
        }
    }

    @Test fun `opaque managed code survives height growth but blocks stride changes without disabling other editing`() = runBlocking {
        val f = fixture(incoming = true)
        f.bridge.doors.createManagedArrival(0x9C89, 0, "Neighbor")
        val text = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replace("jmp.w DoorASM_Test", "jsr.w DoorASM_Test\n    rts"))
        val body = authoredMacroBody(f.bridge.sourceText(), "SmeditArrival_9C89_Door_0")
        assertTrue(f.editor.resizeRoom(2, 2, 2, 3), f.editor.statusMessage)
        assertEquals(body, authoredMacroBody(f.bridge.sourceText(), "SmeditArrival_9C89_Door_0"))
        val model = f.model(); val sources = f.sources()
        assertFalse(f.editor.resizeRoom(2, 3, 3, 3)); assertContains(f.editor.statusMessage, "managed in ASM")
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        val word = f.editor.readBlockWord(8, 8)
        f.editor.setTileProperties(8, 8, 1, 3)
        assertNotEquals(word, f.editor.readBlockWord(8, 8))
    }

    @Test fun `external arrival references added after resize prevent undo from deleting live code`() = runBlocking {
        val f = fixture(incoming = true)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val macros = f.workspace.projectSourceText("macros.asm")
        f.workspace.replaceProjectSource("macros.asm", macros, macros + "\nmacro ExternalArrival()\n jsr.w SmeditArrival_9C89_Door_0_Entry\nendmacro\n")
        val model = f.model(); val sources = f.sources()
        assertFalse(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE))
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        assertTrue(f.workspace.undoSourceEdit("macros.asm"), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertFalse(f.project.rooms.containsKey("9C89"))
    }

    @Test fun `reset reuses coordinated geometry for neighboring arrivals and preserves their newer authored values`() = runBlocking {
        val f = fixture(incoming = true)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val text = f.bridge.sourceText()
        val body = authoredMacroBody(text, "SmeditArrival_9C89_Door_0")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replaceFirst(body,
            body.replaceFirst("lda.b #\$02", "lda.b #\$01 ; newer neighboring edit")))
        val model = f.model(); val sources = f.sources()
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), f.editor.statusMessage)
        assertFalse(f.project.rooms.getValue("91F8").hasEdits)
        assertEquals(listOf(DoorScrollWrite(1, 0x23)), f.program().writes)
        assertContains(f.bridge.sourceText(), "; newer neighboring edit")
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(48, f.editor.workingBlocksWide)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(listOf(DoorScrollWrite(1, 0x23)), f.program().writes)
    }

    @Test fun `reset refuses unknown neighboring geometry just as resize does without retiring room data`() = runBlocking {
        val f = fixture(incoming = true)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val text = f.bridge.sourceText()
        val body = authoredMacroBody(text, "SmeditArrival_9C89_Door_0")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, text, text.replaceFirst(body, body.replace("    rts", "    jsr.w DoorASM_Test\n    rts")))
        val model = f.model(); val sources = f.sources()
        assertFalse(f.editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser))
        assertContains(f.editor.statusMessage, "managed in ASM")
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(48, f.editor.workingBlocksWide)
    }

    @Test fun `cold reopened arrivals resize from the saved authored stride instead of native width`() = runBlocking {
        val f = fixture(incoming = true)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val owner = Json.decodeFromString<SmEditProject>(f.model())
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val editor = EditorState().apply { testMode = true; initForProject(f.file, owner) }
        val bridge = editor.bindRoomSources(f.bridge.catalog, workspace)
        editor.loadRoom(0x91F8, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        assertTrue(editor.resizeRoom(3, 2, 4, 2), editor.statusMessage)
        assertEquals(listOf(DoorScrollWrite(2, 0x25)), AsmArrivalScrollProgram.parse(authoredMacroBody(bridge.sourceText(), "SmeditArrival_9C89_Door_0"))!!.writes)
    }

    @Test fun `open map protects gestures and shares resize undo without moving or repainting cells`() = runBlocking {
        val f = fixture(incoming = true)
        val map = com.supermetroid.editor.ui.MinimapEditorState().apply { loadArea(f.bridge.catalog.parser, 0, f.editor) }
        val stroke = map.mapData.withTile(50, 20, 0x0F)
        map.pendingStroke = stroke
        val model = f.model(); val sources = f.sources()
        assertFalse(f.editor.resizeRoom(2, 2, 3, 2)); assertSame(stroke, map.pendingStroke)
        assertEquals(model, f.model()); assertEquals(sources, f.sources())
        map.pendingStroke = null
        val art = map.mapData; val reveal = map.stationData
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        map.initIfNeeded(f.bridge.catalog.parser, f.editor)
        assertTrue(map.undoStack.isNotEmpty()); assertEquals(art, map.mapData); assertEquals(reveal, map.stationData)
        map.undo(f.editor)
        assertEquals(model, f.model()); assertEquals(sources, f.sources()); assertEquals(32, f.editor.workingBlocksWide)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        map.pendingStroke = stroke
        assertFalse(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE)); assertSame(stroke, map.pendingStroke)
    }

    @Test fun `resize grows geometry BTS layer two and shared authored grids in one reciprocal undo step`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val source = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        val grid = f.editor.workingScrolls.copyOf()
        val head = f.workspace.sourceUndoHead(PLM_SOURCE_FILE)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertContentEquals(resizeLevelData(pixels, 2, 2, 3, 2), f.editor.workingLevelData)
        assertEquals(listOf(2, 0, 1, 1, 1, 1), f.editor.workingScrolls.toList())
        assertEquals(48, f.editor.workingBlocksWide)
        assertEquals(1, f.editor.undoStack.size)
        assertEquals(1, room.states.map { f.bridge.initialScrolls.view(room.roomId, it.id).resource!!.identity }.distinct().size)
        room.states.forEach { state ->
            val trigger = f.bridge.managedView(room.roomId, state.id).document!!.entries.single { it.id == 0xB703 }
            assertEquals(listOf(ScrollCommand(0, 0), ScrollCommand(4, 2)), f.bridge.scrollCommands(room.roomId, state.id, trigger))
        }
        val resizedSource = f.bridge.sourceText()
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, room)
        assertEquals(source, f.bridge.sourceText())
        assertSame(head, f.workspace.sourceUndoHead(PLM_SOURCE_FILE))
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertContentEquals(grid, f.editor.workingScrolls)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(resizedSource, f.bridge.sourceText())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(before, room)
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(48, f.editor.workingBlocksWide)
    }

    @Test fun `unadopted objects and grids migrate atomically and undo restores GUI values`() = runBlocking {
        val f = fixture(adopt = false)
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val entries = f.editor.workingPlms.toList()
        val source = f.bridge.sourceText()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(1, room.asmPlmSourceVersion)
        assertEquals(1, room.asmInitialScrollSourceVersion)
        assertTrue(room.plmChanges.isEmpty() && room.scrollChanges.isEmpty() && room.customScrollCommands.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(before, room)
        assertEquals(source, f.bridge.sourceText())
        assertEquals(entries, f.editor.workingPlms)
        assertFalse(f.workspace.canUndoSource(PLM_SOURCE_FILE))
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertTrue(f.editor.workingPlms.any { it.id == 0xB703 })
    }

    @Test fun `resize preserves independent layouts grids legacy state overrides and handwritten source`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val first = f.editor.currentPlmStateId()!!
        val other = room.states.first { it.id != first }
        f.editor.switchRoomState(other.id, f.bridge.catalog.parser)
        f.editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        f.editor.setTileProperties(8, 8, 1, 9)
        f.editor.setScroll(0, 0, 0, 2)
        f.bridge.editScroll(room.roomId, other.id, 4, 4, listOf(ScrollCommand(2, 0)))
        f.editor.refreshLinkedPlms()
        val privatePixels = f.editor.workingLevelData!!.copyOf()
        val privateGrid = f.editor.workingScrolls.toList()
        f.editor.switchRoomState(first, f.bridge.catalog.parser)
        val firstPixels = f.editor.workingLevelData!!.copyOf()
        val firstGrid = f.editor.workingScrolls.toList()
        val source = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, source, source.replace("; Screen (1,1)", "; hand-tuned camera ; Screen (1,1)"))
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(2, room.states.map { it.resources.level }.distinct().size)
        assertEquals(2, room.states.map { f.bridge.initialScrolls.view(room.roomId, it.id).resource!!.identity }.distinct().size)
        assertContains(f.bridge.sourceText(), "hand-tuned camera")
        val privateTrigger = f.bridge.managedView(room.roomId, other.id).document!!.entries.single { it.id == 0xB703 }
        assertEquals(listOf(ScrollCommand(3, 0)), f.bridge.scrollCommands(room.roomId, other.id, privateTrigger))
        val firstTrigger = f.bridge.managedView(room.roomId, first).document!!.entries.single { it.id == 0xB703 }
        assertEquals(listOf(ScrollCommand(0, 0), ScrollCommand(4, 2)), f.bridge.scrollCommands(room.roomId, first, firstTrigger))
        assertContentEquals(resizeLevelData(firstPixels, 2, 2, 3, 2), f.editor.workingLevelData)
        f.editor.switchRoomState(other.id, f.bridge.catalog.parser)
        assertContentEquals(resizeLevelData(privatePixels, 2, 2, 3, 2), f.editor.workingLevelData)
        assertEquals(listOf(privateGrid[0], privateGrid[1], 1, privateGrid[2], privateGrid[3], 1), f.editor.workingScrolls.toList())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertContentEquals(privatePixels, f.editor.workingLevelData)
        assertEquals(privateGrid, f.editor.workingScrolls.toList())
        f.editor.switchRoomState(first, f.bridge.catalog.parser)
        assertContentEquals(firstPixels, f.editor.workingLevelData)
        assertEquals(firstGrid, f.editor.workingScrolls.toList())
    }

    @Test fun `shrinking crops geometry and command screens and later growth does not resurrect cropped tiles`() = runBlocking {
        val f = fixture()
        val original = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.editor.resizeRoom(2, 2, 1, 2), f.editor.statusMessage)
        assertContentEquals(resizeLevelData(original, 2, 2, 1, 2), f.editor.workingLevelData)
        val state = f.editor.currentPlmStateId()!!
        val trigger = f.editor.workingPlms.single { it.id == 0xB703 }
        assertEquals(listOf(ScrollCommand(0, 0)), f.bridge.scrollCommands(0x91F8, state, trigger))
        val cropped = f.editor.workingLevelData!!.copyOf()
        assertTrue(f.editor.resizeRoom(1, 2, 2, 2), f.editor.statusMessage)
        val expected = resizeLevelData(cropped, 1, 2, 2, 2)
        assertContentEquals(expected, f.editor.workingLevelData)
        f.editor.switchRoomState(state, f.bridge.catalog.parser)
        assertContentEquals(expected, f.editor.workingLevelData)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertContentEquals(cropped, f.editor.workingLevelData)
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertContentEquals(original, f.editor.workingLevelData)
    }

    @Test fun `invalid drafts stale plans clipped objects and unsupported sizes leave source metadata and pixels unchanged`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        val before = snapshotRoomForResize(room)
        val pixels = f.editor.workingLevelData!!.copyOf()
        val source = f.bridge.sourceText()
        val broken = source.replace("db \$02 ; Screen", "db \$80 ; Screen")
        assertNotEquals(source, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, source, broken)
        assertFalse(f.editor.resizeRoom(2, 2, 3, 2))
        assertEquals(before, room)
        assertContentEquals(pixels, f.editor.workingLevelData)
        assertEquals(broken, f.bridge.sourceText())
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, broken, source)
        assertFalse(f.editor.resizeRoom(2, 2, 15, 15))
        assertEquals(before, room)
        val plan = f.bridge.planResize(room.roomId, "Landing Site", 3, 2, RomParser::isItemPlm)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, source, source + "; newer edit\n")
        var transitioned = false
        assertFails { f.bridge.applyResize(plan, {}, { transitioned = true }) }
        assertFalse(transitioned)
        assertEquals(before, room)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, f.bridge.sourceText(), source)
        f.bridge.editManaged(room.roomId, f.editor.currentPlmStateId()!!, false, { false }, RomParser.PlmEntry(0xEEDF, 24, 8, 12))
        f.editor.refreshLinkedPlms()
        val objects = f.bridge.sourceText()
        assertFalse(f.editor.resizeRoom(2, 2, 1, 2))
        assertContains(f.editor.statusMessage, "Objects")
        assertEquals(objects, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
    }

    @Test fun `newer geometry and room metadata prevent source undo from restoring a stale resize snapshot`() = runBlocking {
        val f = fixture()
        val room = f.project.rooms.getValue("91F8")
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val source = f.bridge.sourceText()
        f.editor.setTileProperties(8, 8, 1, 3)
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(48, f.editor.workingBlocksWide)
        assertEquals(source, f.bridge.sourceText())
        assertTrue(f.editor.undo())
        val header = room.roomHeaderChange
        room.roomHeaderChange = header!!.copy(upScroller = 0x90)
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(source, f.bridge.sourceText())
        room.roomHeaderChange = header
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(32, f.editor.workingBlocksWide)
    }

    @Test fun `native scroll streams are copied once and typed export preserves original ROM bytes`() = runBlocking {
        val f = fixture(nativeTrigger = true)
        val baseline = f.bridge.catalog.parser.copyRomData()
        val originalCommands = baseline.copyOfRange(0x79000, 0x79005)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        val source = AsmRoomObjectSource(f.bridge.sourceText())
        val objects = source.resources(room, 6)
        val grids = AsmInitialScrollSource(f.bridge.sourceText()).resources(room, 3, 2)
        assertEquals(1, objects.values.flatMap { it.scrollReferences.values }.map { it.identity }.distinct().size)
        val target = baseline.copyOf()
        ProjectRoomExporter(f.project, f.bridge.catalog.parser, target,
            sourcePlmSets = mapOf(room.roomId to objects), sourceInitialScrolls = mapOf(room.roomId to grids)).exportRooms()
        val built = RomParser(target)
        assertEquals(3, built.readRoomHeader(room.roomId)!!.width)
        room.states.zip(built.parseRoomStatesWithData(room.roomId)).forEach { (state, runtime) ->
            assertContentEquals(f.editor.workingLevelData, built.decompressLZ2(runtime.levelDataPtr))
            assertEquals(grids.getValue(state.id).values, built.parseScrollData(runtime.scrollPtr, 3, 2).toList())
            val trigger = built.parsePlmSet(runtime.plmSetPtr).single { it.id == 0xB703 }
            assertNotEquals(0x9000, trigger.param)
            assertEquals(listOf(Triple(0, 0, 0), Triple(4, 1, 2)), RomParser.decodeScrollCommands(built, trigger.param, 3))
        }
        assertContentEquals(originalCommands, target.copyOfRange(0x79000, 0x79005))
        assertContentEquals(baseline, f.bridge.catalog.parser.copyRomData())
    }

    @Test fun `saved resize reopens with identical geometry and reciprocal source editing`() = runBlocking {
        val f = fixture()
        f.editor.setTileProperties(8, 8, 1, 7)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val project = Json.decodeFromString(SmEditProject.serializer(), Json.encodeToString(SmEditProject.serializer(), f.project))
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        }
        assertContentEquals(f.editor.workingLevelData, editor.workingLevelData)
        assertContentEquals(f.editor.workingScrolls, editor.workingScrolls)
        val before = bridge.sourceText()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before.replace("db \$01 ; Screen (3,1)", "db \$00 ; Screen (3,1)"))
        editor.refreshLinkedPlms()
        assertEquals(0, editor.workingScrolls[2])
        editor.setScroll(2, 0, 2, 3)
        assertContains(bridge.sourceText(), "db \$02 ; Screen (3,1)")
    }

    @Test fun `hidden state actors and save cameras cannot be stranded by shrinking`() = runBlocking {
        val f = fixture(withSave = true)
        val room = f.project.rooms.getValue("91F8")
        val source = f.bridge.sourceText()
        val save = AsmRoomObjectSource(source).saveResources(room.roomId).single()
        assertFalse(f.editor.resizeRoom(2, 2, 1, 2))
        assertContains(f.editor.statusMessage, "save spawn")
        assertEquals(source, f.bridge.sourceText())
        val other = room.states.first { it.id != f.editor.currentPlmStateId() }
        other.enemyChanges += EnemyChange("add", 0xDCFF, 384, 128)
        assertFalse(f.editor.resizeRoom(2, 2, 1, 2))
        assertContains(f.editor.statusMessage, "Actors")
        assertEquals(source, f.bridge.sourceText())
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(save, AsmRoomObjectSource(f.bridge.sourceText()).saveResources(room.roomId).single())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(source, f.bridge.sourceText())
    }

    @Test fun `incoming door writes follow final GUI door changes and reject cropped targets and stale undo`() = runBlocking {
        val f = fixture(incoming = true)
        val before = f.bridge.sourceText()
        assertFalse(f.editor.resizeRoom(2, 2, 1, 2))
        assertContains(f.editor.statusMessage, "Incoming door ASM writes a removed screen")
        assertEquals(before, f.bridge.sourceText())
        // This entrance is edited after the destination room in project iteration order.
        val entrance = f.project.getOrCreateRoom(0x9C89)
        entrance.doorChanges += DoorChange(0, 0x91F8, 0, 0, 0, 0, 0x8000, 0x9500)
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        val room = f.project.rooms.getValue("91F8")
        val text = f.bridge.sourceText()
        val target = f.bridge.catalog.parser.copyRomData()
        val resource = AsmDoorSource(f.workspace.projectSourceText(DOOR_SOURCE_FILE), text, f.bridge.catalog.symbols)
            .resource(entrance, f.bridge.catalog.parser)
        val binding = resource.entries.single().arrival!!
        val program = AsmArrivalScrollProgram.parse(authoredMacroBody(text, binding.label.removeSuffix("_Entry")))!!
        buildDoorScrollAsm(program.writes).copyInto(target, 0x7F000)
        val resolved = resource.copy(entries = resource.entries.map { it.copy(change = it.change.copy(entryCode = 0xF000), arrival = binding.copy(snesAddress = 0x8FF000)) })
        ProjectRoomExporter(f.project, f.bridge.catalog.parser, target,
            sourcePlmSets = mapOf(room.roomId to AsmRoomObjectSource(text).resources(room, 6)),
            sourceInitialScrolls = mapOf(room.roomId to AsmInitialScrollSource(text).resources(room, 3, 2)),
            sourceDoors = mapOf(entrance.roomId to resolved)).exportRooms()
        val built = RomParser(target)
        val door = built.parseDoorList(0x9800).single()
        assertNotEquals(0x9500, door.entryCode)
        assertEquals(listOf(DoorScrollWrite(2, 0x24)), analyzeDoorScrollAsm(built, door.entryCode).writes)
        val original = f.bridge.doors.view(entrance.roomId).resource!!.entries.single().entry()
        f.bridge.doors.edit(entrance.roomId, 0, original.copy(screenX = 2))
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(48, f.editor.workingBlocksWide)
        assertTrue(f.workspace.undoSourceEdit(DOOR_SOURCE_FILE), f.workspace.error)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
    }

    @Test fun `native commands in a previously resized GUI room retain their original screen coordinates`() = runBlocking {
        val f = fixture(adopt = false, nativeTrigger = true)
        val room = f.project.rooms.getValue("91F8")
        room.roomHeaderChange = RoomHeaderChange(width = 3, height = 2)
        f.editor.loadRoom(room.roomId, f.bridge.catalog.parser, f.bridge.catalog.parser.readRoomHeader(room.roomId)!!)
        assertTrue(f.editor.resizeRoom(3, 2, 4, 2), f.editor.statusMessage)
        val trigger = f.editor.workingPlms.single { it.id == 0xB703 }
        assertEquals(listOf(ScrollCommand(0, 0), ScrollCommand(5, 2)),
            f.bridge.scrollCommands(room.roomId, f.editor.currentPlmStateId()!!, trigger))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(48, f.editor.workingBlocksWide)
        assertEquals(0, room.asmPlmSourceVersion)
    }

    @Test fun `mixed incoming door routines block width remapping without a partial resize`() = runBlocking {
        val f = fixture(incoming = true, mixedIncoming = true)
        val before = snapshotRoomForResize(f.project.rooms.getValue("91F8"))
        val source = f.bridge.sourceText()
        val pixels = f.editor.workingLevelData!!.copyOf()
        assertFalse(f.editor.resizeRoom(2, 2, 3, 2))
        assertContains(f.editor.statusMessage, "mixes scroll writes")
        assertEquals(before, f.project.rooms.getValue("91F8"))
        assertEquals(source, f.bridge.sourceText())
        assertContentEquals(pixels, f.editor.workingLevelData)
    }
}
