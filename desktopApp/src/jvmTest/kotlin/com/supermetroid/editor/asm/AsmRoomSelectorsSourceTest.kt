package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import com.supermetroid.editor.ui.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmRoomSelectorsSourceTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val workspace: AsmWorkspaceState, val bridge: AsmPlmSourceBridge,
                               val project: SmEditProject, val editor: EditorState, val file: File) {
        val room get() = project.rooms.getValue("91F8")
        val parser get() = bridge.catalog.parser
        fun input() = AsmRoomSelectorsSource(bridge.sourceText()).resource(room)
        fun adopt() = assertTrue(editor.ensureSelectorSource("Landing Site"), editor.statusMessage)
        fun adoptAll() {
            adopt()
            assertTrue(editor.ensureLayoutSource("Landing Site"), editor.statusMessage)
            assertTrue(editor.ensureRoomPropertiesSource("Landing Site"), editor.statusMessage)
            assertTrue(editor.ensureCurrentPlmSource("Landing Site"), editor.statusMessage)
            assertTrue(editor.ensureInitialScrollSource("Landing Site"), editor.statusMessage)
            assertTrue(editor.ensureFxSource("Landing Site"), editor.statusMessage)
            assertTrue(editor.ensureEnemySource("Landing Site"), editor.statusMessage)
        }
        fun texts() = listOf(PLM_SOURCE_FILE, FX_SOURCE_FILE, ENEMY_SOURCE_FILE).associateWith(workspace::projectSourceText)
        fun conditional() = room.states.first { it.sourceCondition?.kind != ProjectRoomStateConditionKind.DEFAULT }.id
        fun predicate(id: String, code: String, input: String = bridge.sourceText()): String {
            val name = AsmRoomSelectorsSource.conditionName(room.roomId, id)
            val d = Regex("(?m)^macro $name\\(\\)\\r?\\n([\\s\\S]*?)^endmacro").find(input)!!
            val newline = if (input.contains("\r\n")) "\r\n" else "\n"
            return input.replaceRange(d.groups[1]!!.range,
                "    ${AsmSelectorPredicateRecognizer.MARKER}$newline" + code.trimIndent().replace("\n", newline) + newline)
        }
    }
    private suspend fun fixture(): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_83.asm\nincsrc bank_A1.asm\n")
        File(src, "macros.asm").writeText(macros)
        File(src, PLM_SOURCE_FILE).writeText("; Original bank stays intact\n")
        File(src, FX_SOURCE_FILE).writeText("; original FX\n")
        File(src, ENEMY_SOURCE_FILE).writeText("; original actors\n")
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val rom = saveSourceTestRom()
        rom.fill(-1, 0x7F000, 0x80000); rom.fill(-1, 0x207000, 0x208000)
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            rom[pc + 9] = 0x88.toByte(); rom[pc + 11] = 0x88.toByte()
        }
        rom[0x108800] = -1; rom[0x108801] = -1
        rom[0x1A0800] = -1; rom[0x1A0801] = -1
        val parser = RomParser(rom)
        val project = SmEditProject(File(directory, "base.sfc").apply { writeBytes(rom) }.path)
        project.asmWorkspace.enabled = true; project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        val bridge = AsmPlmSourceBridge(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace) { project }
        val editor = EditorState().apply {
            testMode = true; initForProject(file, project); asmPlmSourceBridge = bridge
            loadRoom(0x91F8, parser, parser.readRoomHeader(0x91F8)!!)
        }
        project.getOrCreateRoom(0x91F8).ensureStateManifest(parser)
        return Fixture(workspace, bridge, project, editor, file)
    }

    @Test fun `symbolic door codec preserves nested flags comments CRLF and literal spellings without evaluating expressions`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        val reference = ProjectSourceDoorReference(0x91F8, 12)
        val incoming = projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, negated = true, sourceDoor = reference)
        val tree = projectRoomStateCondition(ProjectRoomStateConditionKind.ANY_OF, children = listOf(incoming,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7)))
        val original = AsmRoomSelectorsSource(f.bridge.sourceText()).editCondition(f.room, id, tree)
            .replace("dw ${reference.entryLabel}", "dw ${reference.entryLabel} ; user note").replace("\n", "\r\n")
        assertEquals(tree, AsmRoomSelectorsSource(original).resource(f.room).conditions.getValue(id))
        assertEquals(original, AsmRoomSelectorsSource(original).editCondition(f.room, id, tree))
        val other = tree.copy(children = listOf(incoming.copy(sourceDoor = reference.copy(identity = 13)), tree.children[1]))
        val changed = AsmRoomSelectorsSource(original).editCondition(f.room, id, other)
        assertEquals(original.replace(reference.entryLabel, reference.copy(identity = 13).entryLabel), changed)
        for (token in listOf("${reference.entryLabel}+2", "OtherLabel", "\$830000", "SmeditDoors_91F8_Door_999999999999_Entry")) {
            assertFails { AsmRoomSelectorsSource(original.replace(reference.entryLabel, token)).resource(f.room) }
        }
        val literal = AsmRoomSelectorsSource(original).editCondition(f.room, id,
            tree.copy(children = listOf(projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0x8000, true), tree.children[1])))
        assertContains(literal, "dw \$8000 ; user note")
        val eventToken = AsmRoomSelectorsSource(f.bridge.sourceText()).editCondition(f.room, id,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7)).replace("dw \$0007", "dw ${reference.entryLabel}")
        assertContains(assertFails { AsmRoomSelectorsSource(eventToken).resource(f.room) }.message.orEmpty(), "only valid")
    }

    @Test fun `negated native leaf selectors retain the compiled expression pointer rather than overwriting it with an argument`() = runBlocking {
        val f = fixture(); f.adopt()
        for ((kind, argument) in listOf(ProjectRoomStateConditionKind.INCOMING_DOOR to 0x8000,
            ProjectRoomStateConditionKind.EVENT_SET to 7, ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET to 1)) {
            val condition = projectRoomStateCondition(kind, argument, negated = true)
            val before = f.bridge.sourceText()
            f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, AsmRoomSelectorsSource(before).editCondition(f.room, f.conditional(), condition))
            val target = f.parser.copyRomData()
            ProjectRoomExporter(f.project, f.parser, target, sourceSelectors = mapOf(0x91F8 to f.input())).exportRooms()
            val states = RomParser(target).inspectRoomStates(0x91F8)
            assertTrue(states.isComplete, states.issues.joinToString { it.message })
            assertEquals(RoomStateConditionKind.valueOf(kind.name), states.states.first().condition.kind)
            assertEquals(argument, states.states.first().condition.argument)
            assertTrue(states.states.first().condition.negated)
        }
    }

    @Test fun `adoption preserves legacy condition edits and undo retires only source-owned current values`() = runBlocking {
        val f = fixture()
        val c = projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 25, negated = true)
        f.room.states.first().apply { condition = c; conditionChanged = true }
        val original = snapshotRoomForResize(f.room)
        val before = f.bridge.sourceText()
        f.adopt()
        assertEquals(c, f.input().conditions.getValue(f.conditional()))
        assertTrue(f.room.states.all { !it.conditionChanged && it.condition == AsmRoomSelectorsSource.baseline(it) })
        assertFalse(Json.encodeToString(SmEditProject.serializer(), f.project).contains("Projection"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText()); assertEquals(original, f.room)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(c, f.room.states.first().effectiveCondition)
    }

    @Test fun `raw predicates update GUI projections and GUI writes preserve comments with reciprocal undo`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        val before = f.bridge.sourceText()
        val raw = AsmRoomSelectorsSource(before).editCondition(f.room, id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7))
            .replace("; argument (", "; handwritten note / argument (")
        val version = f.editor.editVersion
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, raw)
        f.editor.refreshLinkedPlms()
        assertEquals(7, f.room.states.first().effectiveCondition.argument)
        assertTrue(f.editor.editVersion > version)
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), f.parser)
        assertEquals(8, f.input().conditions.getValue(id).argument)
        assertContains(f.bridge.sourceText(), "; handwritten note")
        assertEquals(1, f.room.states.first().condition.argument)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(raw, f.bridge.sourceText())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED), f.parser)
        assertContains(f.bridge.sourceText(), "; SPEED_BOOSTER_COLLECTED: kind, NOT, child count")
        assertContains(f.bridge.sourceText(), "; handwritten note")
        val edited = f.bridge.sourceText()
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, edited, edited + "; newer raw edit\n")
        assertFalse(f.editor.undo())
    }

    @Test fun `source branch order reorders stable identities without changing resource ownership and exports in source order`() = runBlocking {
        val f = fixture()
        val first = f.conditional()
        val second = f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 2), f.parser)
        val resources = f.room.states.associate { it.id to it.resources }
        val canonicalOrder = f.room.states.map { it.id }
        f.adopt()
        assertTrue(f.editor.moveRoomState(second, -1, f.parser), f.editor.statusMessage)
        assertEquals(listOf(second, first, canonicalOrder.last()), f.room.effectiveStates.map { it.id })
        assertEquals(canonicalOrder, f.room.states.map { it.id })
        assertEquals(resources, f.room.states.associate { it.id to it.resources })
        val target = f.parser.copyRomData()
        ProjectRoomExporter(f.project, f.parser, target, sourceSelectors = mapOf(0x91F8 to f.input())).exportRooms()
        val built = RomParser(target).parseRoomStates(0x91F8)
        assertEquals(listOf(2, 1, 0), built.map { it.conditionArg })
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(canonicalOrder, f.room.effectiveStates.map { it.id })
        assertFalse(f.editor.moveRoomState(canonicalOrder.last(), -1, f.parser))
        val added = f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 3), f.parser)
        assertEquals(3, f.input().conditions.getValue(added).argument)
        f.editor.deleteRoomState(added, f.parser)
        assertFalse(added in f.input().order)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(3, f.input().conditions.getValue(added).argument)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertFalse(added in f.input().order)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertTrue(added in f.input().order)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertFalse(added in f.input().order)
        Unit
    }

    @Test fun `compound conditions compile and reopen from source without persisting current predicate values`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        val condition = projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 25, negated = true)))
        f.editor.setRoomStateCondition(id, condition, f.parser)
        assertEquals(condition, f.input().conditions.getValue(id))
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val project = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertNull(project.rooms.getValue("91F8").states.first().asmConditionProjection)
        assertEquals(1, project.rooms.getValue("91F8").states.first().condition.argument)
        val workspace = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        workspace.bindProject(f.file.path, true)
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, workspace) { project }
        val editor = EditorState().apply { testMode = true; initForProject(f.file, project); asmPlmSourceBridge = bridge; loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!) }
        assertEquals(condition, project.rooms.getValue("91F8").states.first().effectiveCondition)
        assertTrue(editor.roomPropertiesPreviewAvailable())
        val target = f.parser.copyRomData()
        ProjectRoomExporter(project, f.parser, target, sourceSelectors = mapOf(0x91F8 to f.input())).exportRooms()
        val built = RomParser(target).inspectRoomStates(0x91F8).states.first().condition
        assertEquals(RoomStateConditionKind.ALL_OF, built.kind)
        assertEquals(7, built.children.first().argument)
        assertEquals(25, built.children.last().argument); assertTrue(built.children.last().negated)
    }

    @Test fun `source state addition clones every owned family atomically with shared bank and GUI undo`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val template = f.conditional()
        val previous = snapshotRoomForResize(f.room)
        val text = f.texts()
        val history = f.editor.undoStack.toList()
        val condition = projectRoomStateCondition(ProjectRoomStateConditionKind.ALL_OF, children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7, negated = true),
            projectRoomStateCondition(ProjectRoomStateConditionKind.ANY_OF, children = listOf(
                projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 5),
                projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED)))))
        val added = f.editor.addRoomState(template, condition, f.parser)
        assertEquals(condition, f.input().conditions.getValue(added))
        assertEquals(previous.states.first { it.id == template }.resources, f.room.states.first { it.id == added }.resources)
        assertEquals(added, f.editor.currentPlmStateId())
        assertEquals(history.size + 1, f.editor.undoStack.size)
        assertNull(f.editor.objectPreviewProblem); assertNull(f.editor.enemyPreviewProblem)
        assertNull(f.editor.fxPreviewProblem); assertNull(f.editor.initialScrollPreviewProblem)
        val after = f.texts()
        for (bank in text.keys) assertNotEquals(text.getValue(bank), after.getValue(bank), bank)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_SOURCE_FILE), f.workspace.error)
        assertEquals(previous, f.room); assertEquals(text, f.texts())
        assertEquals(history, f.editor.undoStack.toList())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(after, f.texts()); assertEquals(condition, f.input().conditions.getValue(added))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(text, f.texts())
        assertTrue(f.workspace.redoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertEquals(after, f.texts()); assertEquals(added, f.editor.currentPlmStateId())
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val portable = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertNull(portable.rooms.getValue("91F8").states.single { it.id == added }.asmConditionProjection)
        assertEquals(condition, AsmRoomSelectorsSource(f.bridge.sourceText()).resource(portable.rooms.getValue("91F8")).conditions.getValue(added))
    }

    @Test fun `state deletion retains shared helpers notes custom consumers and guards the entire cross bank history`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val id = f.editor.addRoomState(f.conditional(), projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        val stateName = AsmEnemySource.stateName(0x91F8, id)
        val beforeCustom = f.bridge.enemies.sourceText()
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, beforeCustom, beforeCustom + "\nmacro MyEnemyRecipe()\n    %$stateName()\nendmacro\n")
        val before = f.texts()
        val snapshot = snapshotRoomForResize(f.room)
        f.editor.deleteRoomState(id, f.parser)
        assertFalse(id in f.input().order)
        assertContains(f.bridge.enemies.sourceText(), "macro $stateName()", message = "A handwritten consumer pins the retired wrapper and its helpers")
        f.room.states.forEach { assertNull(f.bridge.enemies.view(f.room.roomId, it.id).problem) }
        val deleted = f.texts()
        val fx = f.bridge.fx.sourceText()
        f.workspace.replaceProjectSource(FX_SOURCE_FILE, fx, fx + "; newer draft\n")
        assertFalse(f.workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(deleted.getValue(PLM_SOURCE_FILE), f.bridge.sourceText())
        assertFalse(id in f.room.states.map { it.id })
        assertTrue(f.workspace.undoSourceEdit(FX_SOURCE_FILE))
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.texts()); assertEquals(snapshot, f.room)
        assertTrue(f.workspace.redoSourceEdit(FX_SOURCE_FILE), f.workspace.error)
        assertEquals(deleted, f.texts())
        val default = f.room.effectiveStates.last().id
        assertFails { f.editor.deleteRoomState(default, f.parser) }
        assertEquals(deleted, f.texts())
    }

    @Test fun `invalid participating source aborts state planning without any partial model source or history change`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val original = f.bridge.enemies.sourceText()
        val broken = original.replaceFirst("dw \$FFFF", "dw \$10000")
        f.workspace.replaceProjectSource(ENEMY_SOURCE_FILE, original, broken)
        val before = f.texts()
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val history = f.editor.undoStack.toList()
        val heads = before.keys.associateWith(f.workspace::sourceUndoHead)
        assertFails { f.editor.addRoomState(f.conditional(), projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9), f.parser) }
        assertFails { f.editor.deleteRoomState(f.conditional(), f.parser) }
        assertEquals(before, f.texts()); assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(history, f.editor.undoStack.toList())
        heads.forEach { (file, head) -> assertSame(head, f.workspace.sourceUndoHead(file)) }
    }

    @Test fun `deleting final state using an authored save releases its slot but keeps an undoable explicit zero record`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val state = f.conditional()
        val save = SourceSaveResource("SmeditObjects_91F8_Save_0_2", 0x91F8, 0, 2, listOf(0x91F8, 0x8000, 7, 256, 0, 144, 0xFFE0))
        val before = f.bridge.sourceText()
        var next = AsmRoomObjectSource(before).withSave(save)
        next = AsmRoomObjectSource(next).edit(f.room, state, false, { false }, RomParser.PlmEntry(0xB76F, 8, 8, 0x8002))
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, next)
        val clone = f.editor.addRoomState(state, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        f.editor.deleteRoomState(clone, f.parser)
        assertFalse(AsmRoomObjectSource(f.bridge.sourceText()).saveResources(0x91F8).single().cleared)
        f.editor.deleteRoomState(state, f.parser)
        assertTrue(AsmRoomObjectSource(f.bridge.sourceText()).saveResources(0x91F8).single().cleared)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertFalse(AsmRoomObjectSource(f.bridge.sourceText()).saveResources(0x91F8).single().cleared)
    }

    @Test fun `custom source pinning the last save list blocks deletion without zeroing a still used record`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val id = f.conditional()
        val save = SourceSaveResource("SmeditObjects_91F8_Save_0_2", 0x91F8, 0, 2, listOf(0x91F8, 0x8000, 7, 256, 0, 144, 0xFFE0))
        val before = f.bridge.sourceText()
        var after = AsmRoomObjectSource(before).withSave(save)
        after = AsmRoomObjectSource(after).edit(f.room, id, false, { false }, RomParser.PlmEntry(0xB76F, 8, 8, 0x8002))
        after += "\nmacro MyStationList()\n %${AsmRoomObjectSource.stateMacro(0x91F8, id)}()\nendmacro\n"
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, before, after)
        val snapshot = snapshotRoomForResize(f.room)
        val text = f.texts()
        assertFailsWith<IllegalArgumentException> { f.editor.deleteRoomState(id, f.parser) }
        assertEquals(text, f.texts()); assertEquals(snapshot, f.room)
    }

    @Test fun `deleting a selected branch follows source order rather than canonical identity order`() = runBlocking {
        val f = fixture(); f.adopt()
        val first = f.conditional()
        val added = f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        val second = f.editor.addRoomState(added, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 8), f.parser)
        assertTrue(f.editor.moveRoomState(second, -1, f.parser), f.editor.statusMessage)
        assertEquals(listOf(first, second, added, f.room.states.last().id), f.input().order)
        assertEquals(second, f.editor.currentPlmStateId())
        assertEquals(added, f.editor.deleteRoomState(second, f.parser))
        assertEquals(added, f.editor.currentPlmStateId())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(second, f.editor.currentPlmStateId())
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(added, f.editor.currentPlmStateId())
    }

    @Test fun `invalid drafts keep last valid previews block writes and builds but remain saveable`() = runBlocking {
        val f = fixture(); f.adopt()
        val good = f.bridge.sourceText()
        val previous = f.input()
        val broken = good.replaceFirst("db \$04, \$00, \$00", "db \$FF, \$00, \$00")
        assertNotEquals(good, broken)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, good, broken)
        f.editor.refreshLinkedPlms()
        assertNotNull(f.editor.selectorPreviewProblem)
        assertEquals(AsmSelectorProjectionStatus.STALE, f.editor.selectorProjectionStatus)
        assertFalse(f.editor.selectorEditingAvailable())
        assertEquals(previous.conditions.getValue(f.conditional()), f.room.states.first().effectiveCondition)
        f.editor.setRoomStateCondition(f.conditional(), projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9), f.parser)
        assertEquals(broken, f.bridge.sourceText())
        assertFails { f.input() }
        assertFails { ProjectRoomExporter(f.project, f.parser, f.parser.copyRomData()).exportRooms() }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val bridge = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { reopened }
        val editor = EditorState().apply { testMode = true; initForProject(f.file, reopened); asmPlmSourceBridge = bridge; loadRoom(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!) }
        assertNotNull(editor.selectorPreviewProblem)
        assertFalse(editor.roomPropertiesPreviewAvailable())
        assertEquals(AsmSelectorProjectionStatus.UNAVAILABLE, editor.selectorProjectionStatus)
        assertFalse(editor.selectorEditingAvailable())
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, broken, good)
        editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.CURRENT, editor.selectorProjectionStatus)
        assertTrue(editor.selectorEditingAvailable())
    }

    @Test fun `marked branch predicates preview without mutation and convert with reciprocal history and unchanged resources`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val id = f.conditional()
        val before = snapshotRoomForResize(f.room)
        val original = f.texts()
        val draft = f.predicate(id, """
            lda.w ${'$'}09A4 ; Need Morph
            bit.w #${'$'}0004
            beq .no
            lda.w ${'$'}09C8 ; and at least 25 missiles
            cmp.w #${'$'}0019
            bcc .no
            sec
            rts
        .no: clc
            rts
        """)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original.getValue(PLM_SOURCE_FILE), draft)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.RECOGNIZED, f.editor.selectorProjectionStatus)
        assertFalse(f.editor.selectorEditingAvailable())
        val preview = assertNotNull(f.editor.selectorConversionPreview)
        val history = f.editor.undoStack.toList()
        val revision = f.workspace.sourceBufferRevision
        val serialized = Json.encodeToString(SmEditProject.serializer(), f.project)
        repeat(3) { f.bridge.selectors.view(f.room.roomId); f.editor.refreshLinkedPlms() }
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(history, f.editor.undoStack.toList())
        assertEquals(serialized, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertContains(assertFails { f.input() }.message.orEmpty(), "explicit conversion")
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val reopened = Json.decodeFromString<SmEditProject>(serialized)
        val coldBridge = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { reopened }
        assertEquals(AsmSelectorProjectionStatus.RECOGNIZED, coldBridge.selectors.view(f.room.roomId).status)
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 9), f.parser)
        assertEquals(draft, f.bridge.sourceText())
        assertTrue(f.editor.convertRecognizedSelectors(preview), f.editor.statusMessage)
        assertEquals(AsmSelectorProjectionStatus.CURRENT, f.editor.selectorProjectionStatus)
        assertTrue(f.editor.selectorEditingAvailable())
        assertEquals(preview.selectors, f.input())
        assertContains(f.bridge.sourceText(), "; Original predicate:     lda.w ${'$'}09A4 ; Need Morph")
        assertContains(f.bridge.sourceText(), "; Original predicate: .no: clc")
        assertEquals(original.getValue(FX_SOURCE_FILE), f.workspace.projectSourceText(FX_SOURCE_FILE))
        assertEquals(original.getValue(ENEMY_SOURCE_FILE), f.workspace.projectSourceText(ENEMY_SOURCE_FILE))
        assertEquals(before.states.map { it.resources }, f.room.states.map { it.resources })
        assertEquals(serialized, Json.encodeToString(SmEditProject.serializer(), f.project))
        val converted = f.bridge.sourceText()
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(draft, f.bridge.sourceText())
        assertEquals(AsmSelectorProjectionStatus.RECOGNIZED, f.editor.selectorProjectionStatus)
        assertTrue(f.editor.redo(), f.editor.statusMessage)
        assertEquals(converted, f.bridge.sourceText())
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(draft, f.bridge.sourceText())
        assertTrue(f.workspace.redoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(converted, f.bridge.sourceText())
        assertEquals(listOf(4, 25), f.input().conditions.getValue(id).children.map { it.argument })
    }

    @Test fun `conversion refuses stale proposals and invalid siblings without partial publication`() = runBlocking {
        val f = fixture(); f.adopt()
        val original = f.bridge.sourceText()
        val draft = f.predicate(f.conditional(), "sec\nrts")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, original, draft)
        f.editor.refreshLinkedPlms()
        val proposal = assertNotNull(f.editor.selectorConversionPreview)
        val changed = draft.replace("sec\nrts", "clc\nrts")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, draft, changed)
        f.editor.refreshLinkedPlms()
        val history = f.editor.undoStack.toList()
        val metadata = snapshotRoomForResize(f.room)
        assertFalse(f.editor.convertRecognizedSelectors(proposal))
        assertContains(f.editor.statusMessage, "changed while")
        assertEquals(changed, f.bridge.sourceText()); assertEquals(metadata, f.room)
        assertEquals(history, f.editor.undoStack.toList())
        val invalid = changed.replace("db ${'$'}00, ${'$'}00, ${'$'}00", "db ${'$'}FF, ${'$'}00, ${'$'}00")
        assertNotEquals(changed, invalid)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, changed, invalid)
        f.editor.refreshLinkedPlms()
        assertNull(f.editor.selectorConversionPreview)
        assertFails { AsmRoomSelectorsSource(invalid).convertPredicates(f.room) }
        assertEquals(invalid, f.bridge.sourceText())
        val opaque = f.predicate(f.conditional(), "jsl MyCustomLogic\nsec\nrts", original)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, invalid, opaque)
        f.editor.refreshLinkedPlms()
        assertNull(f.editor.selectorConversionPreview)
        assertContains(f.editor.selectorPreviewProblem.orEmpty(), "Unsupported instruction")
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertFails { f.input() }
    }

    @Test fun `multi predicate conversion is atomic preserves CRLF comments order and rejects unmarked code`() = runBlocking {
        val f = fixture(); f.adopt()
        val first = f.conditional()
        val second = f.editor.addRoomState(first, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        val typed = f.bridge.sourceText().replace("\n", "\r\n")
        val draft = f.predicate(second, "clc\nrts", f.predicate(first, "sec\nrts", typed))
        val source = AsmRoomSelectorsSource(draft)
        val preview = assertNotNull(source.conversionPreview(f.room))
        assertEquals(setOf(first, second), preview.stateIds.toSet())
        val converted = source.convertPredicates(f.room)
        assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(converted))
        assertEquals(preview.selectors, AsmRoomSelectorsSource(converted).resource(f.room))
        assertEquals(f.room.effectiveStates.map { it.id }, preview.selectors.order)
        assertNull(AsmRoomSelectorsSource(converted).conversionPreview(f.room))
        assertNull(AsmRoomSelectorsSource(draft.replace(AsmSelectorPredicateRecognizer.MARKER, "; unmarked")).conversionPreview(f.room))
        assertFails { AsmRoomSelectorsSource(draft.replace(AsmSelectorPredicateRecognizer.MARKER, "; unmarked")).resource(f.room) }
        assertFails { AsmRoomSelectorsSource(draft.replace("clc", "sta.w ${'$'}09C8")).convertPredicates(f.room) }
    }

    @Test fun `explicit executable bindings keep only selector controls locked while other source resources remain reciprocal`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val id = f.conditional()
        val typed = f.bridge.sourceText()
        val draft = f.predicate(id, "dw MyPredicate").replace(AsmSelectorPredicateRecognizer.MARKER, AsmRoomSelectorsSource.ASM_PREDICATE_MARKER)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, typed, draft)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertNull(f.editor.selectorPreviewProblem, "Valid custom logic is not an invalid-source warning")
        assertEquals(mapOf(id to "MyPredicate"), f.editor.selectorAsmOnlyLabels)
        assertNull(f.room.states.first().asmConditionProjection)
        assertFalse(id in f.input().conditions)
        assertEquals("MyPredicate", f.input().routines.getValue(id).label)
        assertFalse(f.editor.selectorEditingAvailable())
        assertNull(f.editor.selectorConversionPreview)
        assertTrue(f.editor.roomPropertiesPreviewAvailable())
        assertFails { f.editor.addRoomState(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser) }
        assertFails { f.editor.deleteRoomState(id, f.parser) }
        assertEquals(draft, f.bridge.sourceText())
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        assertEquals(draft, f.bridge.sourceText())
        f.editor.setCurrentLayoutEditScope(com.supermetroid.editor.ui.LayoutEditScope.ALL_SHARING_STATES)
        f.editor.setTileProperties(0, 0, 1, 0x12)
        assertEquals(0x1000, f.editor.readBlockWord(0, 0)); assertEquals(0x12, f.editor.readBts(0, 0))
        f.editor.setRoomStateDataChange(id, StateDataChange(tileset = 0, musicData = 0, musicTrack = 7, bgScrolling = 0), f.parser)
        assertEquals(7, f.bridge.properties.view(f.room.roomId).resource!!.states.getValue(id).musicTrack)
        assertEquals("MyPredicate", f.input().routines.getValue(id).label)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val portable = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val cold = AsmPlmSourceBridge(f.bridge.catalog, f.workspace) { portable }
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, cold.selectors.view(f.room.roomId).status)
        assertEquals(f.room.states.map { it.resources }, portable.rooms.getValue("91F8").states.map { it.resources })
        val broken = f.bridge.sourceText().replace("dw MyPredicate", "dw MyPredicate+1")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, f.bridge.sourceText(), broken)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.STALE, f.editor.selectorProjectionStatus)
        assertFails { f.input() }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
    }

    @Test fun `executable markers reject ambiguous bodies default replacement and implicit routine guesses`() = runBlocking {
        val f = fixture(); f.adopt()
        val id = f.conditional()
        fun executable(code: String, state: String = id) = f.predicate(state, code)
            .replace(AsmSelectorPredicateRecognizer.MARKER, AsmRoomSelectorsSource.ASM_PREDICATE_MARKER)
        for (code in listOf("dw MyPredicate+1", "dw ${'$'}F000", "dw MyPredicate\ndw Other", "dw MyPredicate\nsec", "dw MyPredicate\n${AsmSelectorPredicateRecognizer.MARKER}",
            "dw MyPredicate\n${AsmRoomSelectorsSource.ASM_PREDICATE_MARKER}"))
            assertFails(code) { AsmRoomSelectorsSource(executable(code)).resource(f.room) }
        assertFails { AsmRoomSelectorsSource(executable("dw MyPredicate", f.room.states.last().id)).resource(f.room) }
        assertFails { AsmRoomSelectorsSource(f.predicate(id, "dw MyPredicate").replace(AsmSelectorPredicateRecognizer.MARKER, "; unmarked")).resource(f.room) }
    }

    @Test fun `inline managed predicates preserve PLM reciprocity and typed controls recover automatically after repair and undo`() = runBlocking {
        val f = fixture(); f.adoptAll()
        val id = f.conditional()
        f.editor.switchRoomState(id, f.parser)
        f.editor.addPlm(0xEEDB, 8, 8, 5, ItemStateScope.THIS_STATE)
        val typed = f.bridge.sourceText()
        val draft = f.predicate(id, "lda.w ${'$'}09C8\nand.w #${'$'}0001\ncmp.w #${'$'}0001\nrts")
            .replace(AsmSelectorPredicateRecognizer.MARKER, AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, typed, draft)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertNull(f.editor.selectorPreviewProblem)
        assertFalse(f.editor.selectorEditingAvailable())
        assertEquals(AsmRoomSelectorsSource.conditionName(f.room.roomId, id) + "_Predicate", f.input().routines.getValue(id).label)
        assertTrue(f.editor.roomPropertiesPreviewAvailable())
        val document = assertNotNull(f.bridge.managedView(f.room.roomId, id).document)
        val index = document.entries.indexOfFirst { it.id == 0xEEDB && it.x == 8 && it.y == 8 }
        assertTrue(index >= 0)
        val raw = document.replace(index, document.entries[index].copy(x = 9))
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, draft, raw)
        f.editor.refreshLinkedPlms()
        assertTrue(f.editor.workingPlms.any { it.id == 0xEEDB && it.x == 9 && it.y == 8 })
        val current = assertNotNull(f.bridge.managedView(f.room.roomId, id).document)
        val selected = current.entries.indexOfFirst { it.id == 0xEEDB && it.x == 9 && it.y == 8 }
        f.bridge.updateManaged(f.room.roomId, id, current, selected, current.entries[selected].copy(y = 9))
        f.editor.refreshLinkedPlms()
        assertTrue(f.bridge.managedView(f.room.roomId, id).document!!.entries.any { it.id == 0xEEDB && it.x == 9 && it.y == 9 })
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertEquals(f.input().routines, AsmRoomSelectorsSource(raw).resource(f.room).routines)
        val valid = f.bridge.sourceText()
        val broken = valid.replace("and.w #${'$'}0001", "org ${'$'}8FF000")
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, valid, broken)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.STALE, f.editor.selectorProjectionStatus)
        assertNotNull(f.editor.selectorPreviewProblem)
        assertFalse(f.editor.selectorEditingAvailable())
        assertNull(f.bridge.managedView(f.room.roomId, id).problem, "A bad selector must not invalidate a PLM table")
        assertFails { f.input() }
        val duringDraft = f.bridge.managedView(f.room.roomId, id).document!!
        f.bridge.updateManaged(f.room.roomId, id, duringDraft, selected, duringDraft.entries[selected].copy(param = 10))
        assertContains(f.bridge.sourceText(), "org ${'$'}8FF000", message = "A PLM edit must preserve the unfinished selector verbatim")
        assertFails { f.input() }
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        assertEquals(broken, f.bridge.sourceText())
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertNull(f.editor.selectorPreviewProblem)
        val name = AsmRoomSelectorsSource.conditionName(f.room.roomId, id)
        val originalBody = authoredMacroBody(typed, name)
        val converted = valid.replaceRange(Regex("(?m)^macro $name\\(\\)\\n([\\s\\S]*?)^endmacro").find(valid)!!.groups[1]!!.range, originalBody)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, valid, converted)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.CURRENT, f.editor.selectorProjectionStatus)
        assertTrue(f.editor.selectorEditingAvailable())
        assertTrue(f.editor.workingPlms.any { it.id == 0xEEDB && it.x == 9 && it.y == 9 })
        assertTrue(f.workspace.undoSourceEdit(PLM_SOURCE_FILE), f.workspace.error)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.ASM_ONLY, f.editor.selectorProjectionStatus)
        assertEquals(valid, f.bridge.sourceText())
        assertFails { AsmRoomSelectorsSource(f.predicate(f.room.states.last().id, "clc\nrts", typed)
            .replace(AsmSelectorPredicateRecognizer.MARKER, AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER)).resource(f.room) }
    }

    @Test fun `compiled opaque ROM remains inspectable without inventing an editable condition manifest`() = runBlocking {
        val f = fixture(); f.adopt()
        val draft = f.predicate(f.conditional(), "dw MyPredicate").replace(AsmSelectorPredicateRecognizer.MARKER, AsmRoomSelectorsSource.ASM_PREDICATE_MARKER)
        f.workspace.replaceProjectSource(PLM_SOURCE_FILE, f.bridge.sourceText(), draft)
        val source = f.input()
        val resolved = source.copy(routines = source.routines.mapValues { (_, routine) -> routine.copy(snesAddress = 0x8FF000, sizeBytes = 1) })
        val target = f.parser.copyRomData().also {
            SmEditAsmRoomPredicateFormat.engineBytes.copyInto(it, 0x7E5D2)
            it[0x7F000] = 0x60
        }
        ProjectRoomExporter(f.project, f.parser, target, sourceSelectors = mapOf(f.room.roomId to resolved)).exportRooms()
        val parser = RomParser(target)
        assertEquals(RoomStateConditionKind.ASM_ONLY, parser.inspectRoomStates(f.room.roomId).states.first().condition.kind)
        val imported = SmEditProject(f.project.romPath)
        val editor = EditorState().apply { testMode = true; initForProject(f.file, imported) }
        editor.loadRoom(f.room.roomId, parser, assertNotNull(parser.readRoomHeader(f.room.roomId)))
        assertFalse(editor.selectorEditingAvailable())
        assertNull(editor.currentLayoutEditingContext())
        val oldWord = editor.readBlockWord(0, 0)
        editor.setTileProperties(0, 0, 1, 7)
        assertEquals(oldWord, editor.readBlockWord(0, 0))
        assertContains(editor.statusMessage, "original source project")
        assertTrue(imported.rooms.values.all { it.states.isEmpty() })
    }

    @Test fun `a stale selector cache cannot hide an unmatched state identity or restore old branch order`() = runBlocking {
        val f = fixture(); f.adopt()
        assertNotNull(f.room.asmSelectorOrderProjection)
        val extra = f.room.states.first().copy(id = "unmatched-state", sourceStateIndex = null, templateSourceStateIndex = 0,
            sourceCondition = null, condition = projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER), asmConditionProjection = null)
        f.room.states.add(0, extra)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.UNAVAILABLE, f.editor.selectorProjectionStatus)
        assertFalse(f.editor.selectorEditingAvailable())
        assertNull(f.room.asmSelectorOrderProjection)
        assertTrue(f.room.states.all { it.asmConditionProjection == null })
        assertEquals(f.room.states.map { it.id }, f.room.effectiveStates.map { it.id })
        f.room.states.remove(extra)
        f.editor.refreshLinkedPlms()
        assertEquals(AsmSelectorProjectionStatus.CURRENT, f.editor.selectorProjectionStatus)
        assertTrue(f.editor.selectorEditingAvailable())
    }

    @Test fun `selectors coexist with layouts properties resize reset and context navigation`() = runBlocking {
        val f = fixture()
        assertTrue(f.editor.ensureLayoutSource("Landing Site"), f.editor.statusMessage)
        assertTrue(f.editor.ensureRoomPropertiesSource("Landing Site"), f.editor.statusMessage)
        f.adopt()
        val id = f.conditional()
        f.editor.setRoomStateCondition(id, projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7), f.parser)
        val before = f.bridge.sourceText()
        assertTrue(f.editor.resizeRoom(2, 2, 3, 2), f.editor.statusMessage)
        assertEquals(7, f.input().conditions.getValue(id).argument)
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(before, f.bridge.sourceText())
        val target = AsmRoomSelectorsSource.targetAt(before.lines(), f.bridge.selectors.line(0x91F8, id) + 1)
        assertEquals(AsmEditorTarget.Room(0x91F8, stateId = id), target)
        f.editor.requestLayoutNavigation(0x91F8, null, id)
        f.editor.loadRoomForCanvas(0x91F8, f.parser, f.parser.readRoomHeader(0x91F8)!!)
        assertEquals(id, f.editor.currentPlmStateId())
        assertTrue(f.editor.resetCurrentRoomToOriginal(f.parser), f.editor.statusMessage)
        assertEquals(0, f.room.asmStateSelectorsSourceVersion)
        assertFalse(f.bridge.sourceText().contains("macro SmeditSelectors_"))
        assertTrue(f.editor.undo(), f.editor.statusMessage)
        assertEquals(7, f.input().conditions.getValue(id).argument)
    }

    @Test fun `every writable vanilla selector graph round trips with stable kind IDs and literal validation`() {
        val parser = TestRomHelper.requireRomParser()
        assertEquals(ProjectRoomStateConditionKind.entries.toSet(), AsmRoomSelectorsSource.KINDS.toSet())
        for (info in parser.roomCatalog.rooms) {
            val id = info.getRoomIdAsInt()
            if (parser.readRoomHeader(id)!!.area !in 0..6) continue
            val room = RoomEdits(id).also { it.ensureStateManifest(parser); it.asmStateSelectorsSourceVersion = 1 }
            val selectors = SourceRoomSelectors(room.states.map { it.id }, room.states.associate { it.id to it.condition })
            val source = AsmRoomSelectorsSource.render(id, info.name, selectors)
            assertEquals(selectors, AsmRoomSelectorsSource(source).resource(room), info.name)
            assertFails { AsmRoomSelectorsSource(source + source).resource(room) }
        }
    }
}
