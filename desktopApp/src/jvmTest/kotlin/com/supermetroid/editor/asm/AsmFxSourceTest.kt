package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmFxSourceTest {
    @TempDir lateinit var directory: File
    private val doorFx = emptyDefaultFxEntry().copy(doorSelect = 0x9000, fxType = 4, liquidSurfaceStart = 0x0320, liquidSurfaceNew = 0x0100, liquidSpeed = 0xFFF0, liquidDelay = 30)
    private val defaultFx = emptyDefaultFxEntry().copy(fxType = 6, liquidSurfaceStart = 0x0200, paletteFxBitflags = 3, paletteBlend = 0x20)
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge, val project: SmEditProject)

    @Test fun `symbolic FX scalar structural and shared edits preserve identities notes CRLF and native stop rules`() {
        val room = RoomEdits(0x91F8, asmFxSourceVersion = 1).also { it.ensureStateManifest(RomParser(objectSourceTestRom())) }
        val reference = ProjectSourceDoorReference(0xA000, 8)
        val symbol = emptyDefaultFxEntry().copy(sourceDoor = reference, fxType = 4)
        val name = "SmeditFx_91F8_Table_1"
        for (tail in listOf(emptyList(), listOf(defaultFx))) {
            val table = SourceFxResource(name, listOf(symbol) + tail)
            val original = (AsmFxSource.renderTable(table) + room.states.joinToString("") {
                AsmFxSource.renderState(room.roomId, it.id, name)
            }).replace(reference.entryLabel + " ;", reference.entryLabel + " ; keep entrance note ;").replace("\n", "\r\n")
            assertEquals(table, AsmFxSource(original).resources(room).values.first())
            val state = room.states.first().id
            val changed = AsmFxSource(original).replaceEntry(room, state, 0, symbol.copy(liquidSpeed = 0xFFF0))
            assertEquals(0xFFF0, AsmFxSource(changed).resource(room.roomId, state).entries.first().liquidSpeed)
            assertEquals(table, AsmFxSource(changed).resource(room.roomId, room.states.last().id))
            assertContains(changed, reference.entryLabel + " ; keep entrance note ; Incoming door (0000 = Default)\r\n")
            val removed = AsmFxSource(changed).removeEntry(room, state, 0, reference)
            assertEquals(tail, AsmFxSource(removed).resource(room.roomId, state).entries)
            assertEquals(table, AsmFxSource(removed).resource(room.roomId, room.states.last().id))
            assertContains(removed, "keep entrance note")
            val readded = AsmFxSource(removed).replaceEntry(room, state, 0, symbol)
            assertEquals(listOf(symbol) + tail, AsmFxSource(readded).resource(room.roomId, state).entries)
            val newBody = authoredMacroBody(readded, AsmFxSource(readded).resource(room.roomId, state).identity)
            assertFalse(newBody.replace("\r\n", "").contains('\n'), "Inserted fields must keep CRLF")
            if (tail.isNotEmpty()) assertTrue(newBody.indexOf(reference.entryLabel) < newBody.indexOf("; Default FX"), "Default heading stays with Default")
            val symbolLine = AsmFxSource(readded).line(room.roomId, state, 0, reference)
            assertContains(readded.lineSequence().elementAt(symbolLine), "dw ${reference.entryLabel}")
            assertFails { AsmFxSource(original.replace(reference.entryLabel, "${reference.entryLabel}+2")).resources(room) }
            assertFails { AsmFxSource(original.replace("dw ${'$'}FFFF ; Liquid Start", "dw ${reference.entryLabel} ; Liquid Start")).resources(room) }
        }
    }
    private suspend fun fixture(noFx: Boolean = false): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("; Original object source\n")
        File(src, FX_SOURCE_FILE).writeText("; Original FX source remains intact\r\n")
        File(src, "macros.asm").writeText("macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = objectSourceTestRom().also { it.fill(-1, 0x1F000, 0x20000); it.fill(-1, 0x7F000, 0x80000) }
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            word(pc + 6, if (noFx) 0 else 0x8800)
        }
        word(0x19000, 0x91F8)
        SourceFxResource("original", listOf(doorFx, defaultFx)).bytes().copyInto(rom, 0x18800)
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc")
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val catalog = AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), File(src, "macros.asm").readText(), emptyMap(), parser, emptyMap())
        return Fixture(workspace, AsmPlmSourceBridge(catalog, workspace) { project }, project)
    }
    private fun Fixture.editor(): EditorState {
        val fixture = this
        return EditorState().apply {
            testMode = true
            initForProject(File(directory, "Test.smedit"), fixture.project)
            asmPlmSourceBridge = fixture.bridge
            loadRoom(0x91F8, fixture.bridge.catalog.parser, fixture.bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        }
    }

    @Test fun `adoption retires common and door overrides with atomic source metadata undo`() = runBlocking {
        val f = fixture()
        val room = f.project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(f.bridge.catalog.parser)
        room.fxChange = FxChange(paletteBlend = 0x40)
        room.states.last().fxChange = FxChange(liquidSurfaceStart = 0x0100)
        room.states.last().doorFxChanges["9000"] = FxChange(liquidSpeed = 0xFFE0)
        val before = f.bridge.fx.sourceText()
        f.bridge.fx.migrate(room.roomId, "Landing Site")
        val source = f.bridge.fx.view(room.roomId, room.states.last().id).resource!!
        assertEquals(0xFFE0, source.entries.first().liquidSpeed)
        assertEquals(0x0100, source.entries.last().liquidSurfaceStart)
        assertEquals(0x40, source.entries.last().paletteBlend)
        assertNull(room.fxChange)
        assertTrue(room.states.all { it.fxChange == null && it.doorFxChanges.isEmpty() })
        assertEquals(0, room.asmPlmSourceVersion)
        assertEquals(0, room.asmInitialScrollSourceVersion)
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains("SmeditFx_"))
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertEquals(before, f.bridge.fx.sourceText())
        assertEquals(0, room.asmFxSourceVersion)
        assertEquals(0x40, room.fxChange!!.paletteBlend)
        assertEquals(0xFFE0, room.states.last().doorFxChanges.getValue("9000").liquidSpeed)
        assertTrue(f.workspace.redoSourceEdit(FX_SOURCE_FILE))
        assertEquals(1, room.asmFxSourceVersion)
    }

    @Test fun `visual scalar edits are reciprocal isolate states and share source undo with PLMs and scrolls`() = runBlocking {
        val f = fixture()
        val editor = f.editor()
        val parser = f.bridge.catalog.parser
        val room = f.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        val current = editor.currentPlmStateId()!!
        val other = room.states.first { it.id != current }.id
        editor.setRoomStateFxChange(current, FxChange(liquidSurfaceStart = 0x0100, fxType = 2), parser)
        assertEquals(0x0100, editor.effectiveFxEntries(room.roomId, parser).last().liquidSurfaceStart)
        assertEquals(6, f.bridge.fx.view(room.roomId, other).resource!!.entries.last().fxType)
        assertEquals(doorFx, f.bridge.fx.view(room.roomId, current).resource!!.entries.first())
        assertTrue(editor.undo())
        assertEquals(6, editor.effectiveFxEntries(room.roomId, parser).last().fxType)
        assertTrue(editor.redo())
        assertEquals(2, editor.effectiveFxEntries(room.roomId, parser).last().fxType)
        editor.setRoomStateDoorFxChange(current, 0x9000, FxChange(liquidSpeed = 0xFFE0, liquidDelay = 60), parser)
        assertEquals(0xFFE0, editor.effectiveFxEntries(room.roomId, parser).first().liquidSpeed)
        assertEquals(0xFFF0, f.bridge.fx.view(room.roomId, other).resource!!.entries.first().liquidSpeed)
        editor.setScroll(0, 0, 0, 2)
        assertTrue(editor.undo(), "Undo targets bank_8F for the scroll edit")
        assertTrue(editor.undo(), "Next undo targets bank_83 for the FX edit")
        assertEquals(0xFFF0, editor.effectiveFxEntries(room.roomId, parser).first().liquidSpeed)
        val before = f.bridge.fx.sourceText()
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, before, before.replace("dw \$0100 ; Liquid Start", "dw \$0080 ; Liquid Start"))
        editor.refreshLinkedPlms()
        assertEquals(0x0080, editor.effectiveFxEntries(room.roomId, parser).last().liquidSurfaceStart)
        assertTrue(f.workspace.saveAllSources())
        val json = Json { encodeDefaults = true }
        val reopened = json.decodeFromString(SmEditProject.serializer(), json.encodeToString(SmEditProject.serializer(), f.project))
        val reopenedWorkspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopenedWorkspace.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val fresh = AsmPlmSourceBridge(f.bridge.catalog, reopenedWorkspace) { reopened }
        assertEquals(0x0080, fresh.fx.view(room.roomId, current).resource!!.entries.last().liquidSurfaceStart)
    }

    @Test fun `raw shared edits update aliases while all state GUI updates preserve resource sharing`() = runBlocking {
        val f = fixture()
        f.bridge.fx.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val before = f.bridge.fx.sourceText()
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, before, before.replace("db \$06 ; FX Type", "db \$02 ; FX Type"))
        room.states.forEach { assertEquals(2, f.bridge.fx.view(room.roomId, it.id).resource!!.entries.last().fxType) }
        val identities = room.states.map { f.bridge.fx.view(room.roomId, it.id).resource!!.identity }
        val editor = f.editor()
        editor.setFxChange(FxChange(liquidDelay = 99))
        assertEquals(identities, room.states.map { f.bridge.fx.view(room.roomId, it.id).resource!!.identity })
        room.states.forEach { assertEquals(99, f.bridge.fx.view(room.roomId, it.id).resource!!.entries.last().liquidDelay) }
        assertTrue(editor.undo())
        room.states.forEach { assertEquals(0, f.bridge.fx.view(room.roomId, it.id).resource!!.entries.last().liquidDelay) }
    }

    @Test fun `no FX stop records materialize private defaults without fabricating sibling effects`() = runBlocking {
        val f = fixture(noFx = true)
        val editor = f.editor()
        f.bridge.fx.migrate(0x91F8, "Empty")
        val room = f.project.rooms.getValue("91F8")
        assertTrue(room.states.all { f.bridge.fx.view(room.roomId, it.id).resource!!.entries.isEmpty() })
        val state = editor.currentPlmStateId()!!
        editor.setRoomStateFxChange(state, FxChange(fxType = 6, liquidSurfaceStart = 0x0180), f.bridge.catalog.parser)
        val current = f.bridge.fx.view(room.roomId, state)
        assertNull(current.problem, current.problem)
        assertEquals(0x0180, current.resource!!.entries.single().liquidSurfaceStart)
        assertTrue(room.states.filter { it.id != state }.all { f.bridge.fx.view(room.roomId, it.id).resource!!.entries.isEmpty() })
    }

    @Test fun `invalid and foreign door drafts remain saveable but cannot be overwritten from stale GUI`() = runBlocking {
        val f = fixture()
        f.bridge.fx.migrate(0x91F8, "Test")
        val editor = f.editor()
        val state = editor.currentPlmStateId()!!
        val before = f.bridge.fx.sourceText()
        val invalid = before.replace("db \$06 ; FX Type", "db \$03 ; FX Type")
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, before, invalid)
        editor.refreshLinkedPlms()
        assertNotNull(editor.fxPreviewProblem)
        val diagnostic = AsmBuildOutputParser.pipelineDiagnostic(editor.fxPreviewProblem!!)
        assertEquals(FX_SOURCE_FILE, diagnostic.fileId)
        assertContains(invalid.lines()[assertNotNull(diagnostic.lineIndex)], "db \$03 ; FX Type")
        assertEquals(6, editor.effectiveFxEntries(0x91F8, f.bridge.catalog.parser).last().fxType)
        editor.setRoomStateFxChange(state, FxChange(fxType = 2), f.bridge.catalog.parser)
        assertEquals(invalid, f.bridge.fx.sourceText())
        assertTrue(f.bridge.fx.line(0x91F8, state, 0) >= 0)
        assertTrue(f.workspace.saveAllSources())
        val foreign = invalid.replace("db \$03 ; FX Type", "db \$06 ; FX Type").replace("dw \$9000 ; Incoming door", "dw \$9010 ; Incoming door")
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, invalid, foreign)
        assertNotNull(f.bridge.fx.view(0x91F8, state).problem)
    }

    @Test fun `state clones inherit FX templates while startup never presents vanilla as authored data`() = runBlocking {
        val f = fixture()
        val editor = f.editor()
        assertTrue(editor.ensureFxSource("Test"))
        val room = f.project.rooms.getValue("91F8")
        val template = editor.currentPlmStateId()!!
        val added = editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 12), f.bridge.catalog.parser)
        assertEquals(f.bridge.fx.view(room.roomId, template).resource, f.bridge.fx.view(room.roomId, added).resource)
        f.bridge.fx.edit(room.roomId, added, 0, FxChange(fxType = 2))
        assertEquals(6, f.bridge.fx.view(room.roomId, template).resource!!.entries.last().fxType)
        editor.deleteRoomState(added, f.bridge.catalog.parser)
        assertNotEquals(added, editor.addRoomState(template, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 13), f.bridge.catalog.parser))
        editor.asmPlmSourceBridge = null
        editor.refreshLinkedPlms()
        assertNotNull(editor.fxPreviewProblem)
        assertTrue(editor.effectiveFxEntries(room.roomId, f.bridge.catalog.parser).isEmpty())
        editor.asmPlmSourceBridge = f.bridge
        editor.refreshLinkedPlms()
        assertNull(editor.fxPreviewProblem)
        assertEquals(6, editor.effectiveFxEntries(room.roomId, f.bridge.catalog.parser).last().fxType)
        assertTrue(editor.resetCurrentRoomToOriginal(f.bridge.catalog.parser), editor.statusMessage)
        assertTrue(editor.undo(), editor.statusMessage)
        editor.setProjectRomBuildMode(ProjectRomBuildMode.PATCHED_ROM)
        assertEquals(ProjectRomBuildMode.ASM_SOURCE, f.project.asmWorkspace.buildMode)
    }

    @Test fun `codec preserves CRLF comments ordering and native stop shapes and refuses unsupported layouts`() {
        val room = RoomEdits(0x91F8, asmFxSourceVersion = 1).also { it.ensureStateManifest(RomParser(objectSourceTestRom())) }
        val name = "SmeditFx_91F8_Table_1"
        for (entries in listOf(emptyList(), listOf(doorFx), listOf(defaultFx), listOf(doorFx, defaultFx))) {
            val resource = SourceFxResource(name, entries)
            val text = (AsmFxSource.renderTable(resource) + room.states.joinToString("") { AsmFxSource.renderState(room.roomId, it.id, name) }).replace("\n", "\r\n")
            assertEquals(resource, AsmFxSource(text).resources(room).values.first())
            val privateRoom = room.copy(states = mutableListOf(room.states.first()))
            val changed = AsmFxSource(text).replaceEntry(privateRoom, room.states.first().id, 0, defaultFx.copy(liquidSpeed = 0xFFE0))
            assertEquals(0xFFE0, AsmFxSource(changed).resource(room.roomId, room.states.first().id).entries.last().liquidSpeed)
            if (entries.lastOrNull()?.doorSelect == 0) assertContains(changed, "\r\n", message = "Existing scalar edits preserve CRLF")
        }
        val source = AsmFxSource.renderTable(SourceFxResource(name, listOf(defaultFx))) + room.states.joinToString("") { AsmFxSource.renderState(room.roomId, it.id, name) }
        listOf(source.replace("db \$06", "dw \$06"), source.replace("db \$06", "db \$03"), source.replace("db \$06", "db \$30"),
            source.replace("dw \$0200", "dw \$0200+1"), source + AsmFxSource.renderTable(SourceFxResource(name, listOf(defaultFx)))).forEach { bad ->
            assertFails { AsmFxSource(bad).resources(room) }
        }
        assertFails { SourceFxResource(name, listOf(defaultFx, doorFx)) }
        assertFails { SourceFxResource(name, listOf(doorFx, doorFx)) }
        assertFails { SourceFxResource(name, listOf(defaultFx.copy(liquidDelay = 256))) }
        assertFails { SourceFxResource(name, listOf(defaultFx.copy(doorSelect = 0xFFFF))) }
    }

    @Test fun `FX names are native verified and comments refresh without changing values or handwritten notes`() = runBlocking {
        val f = fixture()
        f.bridge.fx.migrate(0x91F8, "Test")
        val room = f.project.rooms.getValue("91F8")
        val state = room.states.last().id
        val original = f.bridge.fx.sourceText()
        assertContains(original, "db \$06 ; FX Type - Water")
        val edited = original.replace("db \$06 ; FX Type - Water", "db \$18 ; FX Type - Water ; keep my note").replace("\n", "\r\n")
        val source = AsmFxSource(edited)
        val before = source.resources(room)
        val comments = source.withComments(room)
        assertEquals(before, AsmFxSource(comments).resources(room))
        assertContains(comments, "db \$18 ; FX Type - Unused (no native FX handler); keep my note\r\n")
        assertEquals(comments, AsmFxSource(comments).withComments(room))
        assertEquals("Unused 0x18", before.getValue(state).entries.last().fxTypeName)
        assertEquals("Ceres Ridley", NativeFxTypes.names[0x28])
        assertEquals("Ceres Elevator", NativeFxTypes.names[0x2A])
        assertEquals((0..0x2C step 2).toSet(), NativeFxTypes.names.keys)
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, original, edited)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertContains(f.bridge.fx.sourceText(), "FX Type - Unused (no native FX handler)")
        f.bridge.fx.edit(room.roomId, state, 0, FxChange(fxType = 6))
        assertContains(f.bridge.fx.sourceText(), "db \$06 ; FX Type - Water; keep my note")
    }

    @Test fun `strict native reader and baseline guard reject truncated tables engine changes and hidden runtime records`() {
        val rom = objectSourceTestRom()
        fun word(pc: Int, v: Int) { rom[pc] = v.toByte(); rom[pc + 1] = (v ushr 8).toByte() }
        word(0x79300 + 6, 0x8800)
        SourceFxResource("native", listOf(doorFx, defaultFx)).bytes().copyInto(rom, 0x18800)
        word(0x19000, 0x91F8)
        val parser = RomParser(rom)
        val project = SmEditProject("test.sfc")
        val room = project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser); it.asmFxSourceVersion = 1 }
        protectFxBaseline(project, parser, RomParser(rom.copyOf()))
        listOf(0x79300 + 6, 0x18802, 0x4ABAD, 0x1AC18).forEach { pc ->
            val changed = rom.copyOf().also { it[pc] = (it[pc] + 1).toByte() }
            assertFails { protectFxBaseline(project, parser, RomParser(changed)) }
        }
        word(0x79300 + 6, 0xA0A4)
        assertFails { requireDoorRoutedFxOwnership(room, RomParser(rom)) }
        rom[0x1FFFF] = 0
        assertFails { readSourceFxResource(RomParser(rom), 0xFFFE, "truncated") }
    }

    @Test fun `checked FX allocations preserve aliases canonical offsets and complete pointer ownership`() {
        for (header in listOf(0, 0x200)) {
            val body = objectSourceTestRom().also { it.fill(-1, 0x1F000, 0x20000) }
            val original = ByteArray(header) + body
            val parser = RomParser(original)
            val project = SmEditProject("test.sfc")
            val room = project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser); it.asmFxSourceVersion = 1 }
            val resource = SourceFxResource("SmeditFx_91F8_Table_1", listOf(defaultFx))
            val inputs = mapOf(room.roomId to room.states.associate { it.id to resource })
            val target = original.copyOf()
            val result = ProjectRoomExporter(project, parser, target, sourceFx = inputs).exportRooms()
            val allocation = result.allocations.single()
            val built = RomParser(target)
            val states = built.parseRoomStatesWithData(room.roomId)
            assertEquals(1, states.map { it.fxPtr }.distinct().size)
            states.forEach { assertEquals(resource.entries, built.parseFxEntries(it.fxPtr)) }
            val intent = RomWriteIntent(ASM_ROOM_GRAPH_OWNER, allocation.label, allocation.pcOffset - header,
                resource.bytes().map { it.toInt() and 255 }, RomWriteKind.ALLOCATION)
            val report = RomWritePlanReport(listOf(intent), emptyList(), emptyList(), emptyList())
            val table = discoverAsmFxTables(project, report, target, inputs).single()
            assertEquals(3, table.claims().size)
            assertContains(table.renderSource(), "%${resource.identity}()")
            assertContains(table.renderSource(), "dw ${resource.identity}&\$FFFF")
            assertFalse(table.renderSource().contains("db "))
            assertFails { discoverAsmFxTables(project, report.copy(writes = emptyList()), target, inputs) }
            val overlap = RomWriteIntent("patch:test", "pointer", table.pointerOffsets.first(), listOf(0))
            assertFails { discoverAsmFxTables(project, report.copy(writes = report.writes + overlap), target, inputs) }
            assertFails { AsmRoomFxTable(resource, 0x1FFFF, table.pointerOffsets).claims() }
            assertFails { ProjectRoomExporter(project, parser, original.copyOf()).exportRooms() }
            assertTrue(parser.parseRoomStatesWithData(room.roomId).all { it.fxPtr == 0 })
        }
    }
}
