package com.supermetroid.editor.asm

import com.supermetroid.editor.api.DesktopSmeditMutationController
import com.supermetroid.editor.api.DesktopSmeditWorkspaceAccess
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.TileBrush
import com.supermetroid.editor.ui.LayoutEditScope
import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.rom.SourceSaveResource
import com.supermetroid.editor.rom.words
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AsmPlmSourceBridgeTest {
    @TempDir lateinit var directory: File
    private val source = "PLMPopulation_Test: ;8F8000;\n    %PLMPopEntry(${ '$' }EEDB, ${ '$' }02, ${ '$' }03, ${ '$' }0005) ; pickup\n    dw ${ '$' }0000\n"
    private val macros = "macro PLMPopEntry(ID, X, Y, Param)\n dw <ID>\n db <X>, <Y>\n dw <Param>\nendmacro\n"

    private fun scopeEditor(bridge: AsmPlmSourceBridge, project: SmEditProject) = EditorState().apply {
        testMode = true
        initForProject(File(directory, "Test.smedit"), project)
        asmPlmSourceBridge = bridge
        loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
    }

    private fun stampBrush(id: Int, parameter: Int = 0, tile: Int = 42) = TileBrush(
        listOf(listOf(tile, tile + 1)), plmOverrides = mapOf(0L to (id to parameter)))

    @Test fun `pattern drag publishes tiles and objects once with reciprocal source and GUI undo`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        val before = bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.paintAt(12, 8), editor.statusMessage)
        assertEquals(before, bridge.sourceText(), "Dragging is a transient preview, not a series of source edits")
        assertTrue(editor.endStroke(), editor.statusMessage)
        val after = bridge.sourceText()
        val painted = editor.workingLevelData!!.copyOf()
        val room = project.rooms.getValue("91F8")
        assertEquals(1, editor.undoStack.size)
        assertEquals(4, room.levelResourceOperations.values.flatten().flatMap { it.edits }.size)
        assertTrue(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() && it.operations.isEmpty() })
        room.states.forEach { state ->
            val additions = bridge.managedView(room.roomId, state.id).document!!.entries.filter { it.id == 0xEEDF }
            assertEquals(2, additions.size)
            assertEquals(2, additions.map { it.param }.distinct().size)
        }
        assertTrue(editor.undo(), editor.statusMessage)
        assertEquals(before, bridge.sourceText())
        assertContentEquals(pixels, editor.workingLevelData)
        assertTrue(room.levelResourceOperations.isEmpty())
        assertTrue(editor.redo(), editor.statusMessage)
        assertEquals(after, bridge.sourceText())
        assertContentEquals(painted, editor.workingLevelData)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertContentEquals(pixels, editor.workingLevelData)
        assertTrue(editor.undoStack.isEmpty())
        assertEquals(1, editor.redoStack.size)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        assertContentEquals(painted, editor.workingLevelData)
        assertEquals(1, editor.undoStack.size)
        assertTrue(editor.redoStack.isEmpty())
        assertTrue(workspace.saveAllSources())
        val reopened = scopeEditor(bridge, project)
        assertContentEquals(painted, reopened.workingLevelData)
        assertEquals(editor.workingPlms, reopened.workingPlms)
    }

    @Test fun `private gate stamps detach only PLM lists and retain their companion as one edit`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.selectItemEditScope(ItemStateScope.THIS_STATE)
        val room = project.rooms.getValue("91F8")
        val other = room.states.first { it.id != editor.currentPlmStateId() }.id
        editor.brush = stampBrush(0xC836, 8)
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(setOf(0xC836, 0xC82A), editor.workingPlms.filter { it.x == 8 && it.y == 8 }.map { it.id }.toSet())
        assertTrue(bridge.managedView(room.roomId, other).document!!.entries.none { RomParser.isGatePlm(it.id) })
        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        assertEquals(0, editor.readBlockWord(8, 8))
        assertTrue(editor.redo(), editor.statusMessage)
        assertEquals(2, editor.workingPlms.count { RomParser.isGatePlm(it.id) })
    }

    @Test fun `source conflicts or invalid drafts roll back the whole unpublished pattern without overwriting text`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        val pixels = editor.workingLevelData!!.copyOf()
        val before = bridge.sourceText()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8))
        workspace.replaceProjectSource(PLM_SOURCE_FILE, before, before + "; independent edit\n")
        assertFalse(editor.endStroke())
        assertContains(editor.statusMessage, "changed during painting")
        assertContentEquals(pixels, editor.workingLevelData)
        assertEquals(before + "; independent edit\n", bridge.sourceText())
        assertTrue(editor.undoStack.isEmpty())
        val current = bridge.sourceText()
        val invalid = current.replace("dw \$0000 ; End of PLM list", "dw \$0001 ; invalid terminator")
        assertNotEquals(current, invalid)
        workspace.replaceProjectSource(PLM_SOURCE_FILE, current, invalid)
        editor.beginStroke()
        assertFalse(editor.paintAt(8, 8))
        assertFalse(editor.endStroke())
        assertContentEquals(pixels, editor.workingLevelData)
        assertEquals(invalid, bridge.sourceText())
    }

    @Test fun `a rejected stamp keeps earlier drag stamps and unchanged graphics can still add a PLM`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val other = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        bridge.editManaged(0x91F8, other, false, { false }, RomParser.PlmEntry(0xB6DF, 12, 8, 0))
        editor.brush = stampBrush(0xEEDF, tile = 0).copy(tiles = listOf(listOf(0)), blockType = 0)
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertFalse(editor.paintAt(12, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(1, editor.workingPlms.count { it.id == 0xEEDF })
        assertEquals(0, editor.readBlockWord(8, 8))
        assertEquals(0, editor.readBlockWord(12, 8))
        assertTrue(project.rooms.getValue("91F8").levelResourceOperations.isEmpty())
        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF })
    }

    @Test fun `save pattern drag reserves separate slots and publishes spawn resources with the tiles`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true, withSaveData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xB76F, 0x8000)
        val before = bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.paintAt(12, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(setOf(2, 3), editor.workingPlms.filter { it.id == 0xB76F }.map { it.param and 255 }.toSet())
        assertEquals(2, bridge.saveResources(0x91F8).size)
        assertEquals(7, bridge.saveSpawn(0x91F8, 0, 2)!!.words[2], "Retain the native door BTS word")
        assertTrue(project.rooms.getValue("91F8").saveStationSpawns.isEmpty())
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertEquals(before, bridge.sourceText())
        assertContentEquals(pixels, editor.workingLevelData)
        assertTrue(bridge.saveResources(0x91F8).isEmpty())
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        editor.refreshLinkedPlms()
        val station = editor.workingPlms.first { it.id == 0xB76F }
        val spawn = bridge.saveSpawn(0x91F8, 0, station.param and 255)
        editor.beginStroke()
        assertTrue(editor.paintAt(station.x, station.y))
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(2, bridge.saveResources(0x91F8).size, "Repainting retains the slot and custom spawn")
        assertEquals(spawn, bridge.saveSpawn(0x91F8, 0, station.param and 255))
    }

    @Test fun `source undo cannot leave newer geometry behind and works after changing room state`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        val before = bridge.sourceText()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8))
        assertTrue(editor.endStroke(), editor.statusMessage)
        val after = bridge.sourceText()
        editor.setTileProperties(9, 8, 0, 0)
        assertFalse(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(after, bridge.sourceText())
        assertContains(workspace.error!!, "newer layout edits")
        assertTrue(editor.undo())
        val other = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        editor.switchRoomState(other, bridge.catalog.parser)
        assertNotEquals(0, editor.readBlockWord(8, 8))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertEquals(before, bridge.sourceText())
        assertEquals(0, editor.readBlockWord(8, 8))
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error)
        assertNotEquals(0, editor.readBlockWord(8, 8))
    }

    @Test fun `ROM mode pattern strokes retain the delta backend and no source dependency`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        project.asmWorkspace.enabled = false
        project.asmWorkspace.buildMode = ProjectRomBuildMode.PATCHED_ROM
        val editor = scopeEditor(bridge, project)
        editor.asmPlmSourceBridge = null
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF })
        assertEquals(0, project.rooms.getValue("91F8").asmPlmSourceVersion)
        assertTrue(editor.undo())
        assertEquals(0, editor.readBlockWord(8, 8))
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF })
        assertTrue(editor.redo())
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF })
    }

    @Test fun `first source pattern adopts automatically and an interrupted drag discards only its preview`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(bridge.isManaged(0x91F8), "No separate Link action is needed")
        val adopted = bridge.sourceText()
        editor.beginStroke() // Lost mouse release / a new stroke starts.
        assertContentEquals(pixels, editor.workingLevelData)
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF })
        assertEquals(adopted, bridge.sourceText())
        assertTrue(editor.paintAt(12, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(listOf(12), editor.workingPlms.filter { it.id == 0xEEDF }.map { it.x })
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(pixels, editor.workingLevelData)
        assertEquals(adopted, bridge.sourceText())
    }

    @Test fun `an all states gate stamp cannot leave incompatible private layout geometry`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        editor.brush = stampBrush(0xC836, 8)
        val before = bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertFalse(editor.endStroke())
        assertContains(editor.statusMessage, "geometry differs")
        assertEquals(before, bridge.sourceText())
        assertContentEquals(pixels, editor.workingLevelData)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        editor.selectItemEditScope(ItemStateScope.THIS_STATE)
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(2, editor.workingPlms.count { RomParser.isGatePlm(it.id) })
    }

    @Test fun `a multi-cell save pattern fails atomically when only one slot remains`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true, withSaveData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        // Slots 0/1 are native; reserve 2..6 with ordinary source-owned stations.
        for (x in 4..8) editor.addPlm(0xB76F, x, 16, 0x8000)
        assertEquals(setOf(2, 3, 4, 5, 6), bridge.saveResources(0x91F8).map { it.slot }.toSet())
        editor.brush = TileBrush(listOf(listOf(42, 43)),
            plmOverrides = mapOf(0L to (0xB76F to 0x8000), 1L to (0xB76F to 0x8000)))
        val before = bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        val undoCount = editor.undoStack.size
        editor.beginStroke()
        assertFalse(editor.paintAt(12, 8), editor.statusMessage)
        assertFalse(editor.endStroke())
        assertContains(editor.statusMessage, "No free ordinary save slot")
        assertEquals(before, bridge.sourceText())
        assertContentEquals(pixels, editor.workingLevelData)
        assertEquals(undoCount, editor.undoStack.size)
        assertEquals(5, bridge.saveResources(0x91F8).size)
    }

    @Test fun `equal source text from a newer edit cannot masquerade as the compound undo step`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        editor.brush = stampBrush(0xEEDF)
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(8, 8))
        assertTrue(editor.endStroke(), editor.statusMessage)
        val after = bridge.sourceText()
        val painted = editor.workingLevelData!!.copyOf()
        workspace.replaceProjectSource(PLM_SOURCE_FILE, after, after + "; temporary edit\n")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, after + "; temporary edit\n", after)
        assertFalse(editor.undo(), "Do not silently undo an unrelated text edit just because bytes match")
        assertEquals(after, bridge.sourceText())
        assertContentEquals(painted, editor.workingLevelData)
        assertEquals(1, editor.undoStack.size)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(pixels, editor.workingLevelData)
    }

    @Test fun `repainting an unchanged source placement keeps notes aliases and source history intact`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val original = bridge.sourceText()
        val noted = original.replace("; PLM: Missile (Visible)", "; PLM: Missile (Visible) ; personal note")
        workspace.replaceProjectSource(PLM_SOURCE_FILE, original, noted)
        val history = workspace.sourceUndoHead(PLM_SOURCE_FILE)
        editor.brush = stampBrush(0xEEDB, 5)
        val pixels = editor.workingLevelData!!.copyOf()
        editor.beginStroke()
        assertTrue(editor.paintAt(2, 3), editor.statusMessage)
        assertTrue(editor.endStroke(), editor.statusMessage)
        assertEquals(noted, bridge.sourceText())
        assertSame(history, workspace.sourceUndoHead(PLM_SOURCE_FILE))
        assertTrue(editor.undo(), editor.statusMessage)
        assertContentEquals(pixels, editor.workingLevelData)
        assertEquals(noted, bridge.sourceText())
    }

    @Test fun `floating patterns honor the layout scope prompt and rejected linked resources stay unplaced`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.brush = stampBrush(0xEEDF)
        assertTrue(editor.beginFloatingSelectionFromBrushAt(8, 8))
        assertFalse(editor.commitFloatingSelection())
        assertNotNull(editor.pendingLayoutEditScope)
        editor.resolvePendingLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        assertNull(editor.floatingSelection)
        assertTrue(editor.workingPlms.any { it.id == 0xEEDF && it.x == 8 && it.y == 8 })
        assertTrue(editor.undo(), editor.statusMessage)
        assertTrue(editor.workingPlms.none { it.id == 0xEEDF })
        val before = bridge.sourceText()
        val pixels = editor.workingLevelData!!.copyOf()
        editor.brush = stampBrush(0xB703)
        editor.beginFloatingSelectionFromBrushAt(12, 8)
        assertFalse(editor.commitFloatingSelection())
        assertNotNull(editor.floatingSelection)
        assertContains(editor.statusMessage, "Add scroll trigger")
        assertEquals(before, bridge.sourceText())
        assertContentEquals(pixels, editor.workingLevelData)
    }

    @Test fun `new PLMs default all states with complete gates and future conditions`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        assertEquals(com.supermetroid.editor.data.ItemStateScope.ALL_STATES, editor.itemEditScope)
        listOf(0xEEDF, 0xB6DF, 0xB6EB, 0xB6D3, 0xC836, 0xC8A2, 0xB647).forEachIndexed { index, id ->
            editor.addPlm(id, 5 + index, 8, if (id == 0xC836) 8 else 0)
        }
        val additions = editor.workingPlms.filter { it.x in 5..11 }
        assertEquals(8, additions.size, "The shot gate includes its closed body")
        assertTrue(additions.all { editor.plmStateMembership(it).allStates })
        val room = project.rooms.getValue("91F8")
        val added = editor.addRoomState(room.states.last().id,
            com.supermetroid.editor.rom.projectRoomStateCondition(com.supermetroid.editor.data.ProjectRoomStateConditionKind.EVENT_SET, 63), bridge.catalog.parser)
        assertTrue(bridge.managedView(0x91F8, added).document!!.entries.containsAll(additions))
        assertTrue(room.plmChanges.isEmpty() && room.states.all { it.plmChanges.isEmpty() })
    }

    @Test fun `existing private gates stay private when the new placement preference is all states`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.selectItemEditScope(com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        editor.addPlm(0xC836, 8, 8, 8)
        editor.selectItemEditScope(com.supermetroid.editor.data.ItemStateScope.ALL_STATES)
        editor.addPlm(0xC836, 8, 8, 10)
        val current = editor.currentPlmStateId()!!
        val other = project.rooms.getValue("91F8").states.first { it.id != current }.id
        assertEquals(10, editor.workingPlms.single { it.id == 0xC836 }.param)
        assertFalse(editor.plmStateMembership(editor.workingPlms.single { it.id == 0xC836 }).allStates)
        assertTrue(bridge.managedView(0x91F8, other).document!!.entries.none { RomParser.isGatePlm(it.id) })
        val gate = editor.workingPlms.single { it.id == 0xC836 }
        editor.applyPlmToAllStates(gate)
        assertTrue(editor.plmStateMembership(gate).allStates)
        editor.removePlm(8, 8, gate.id, com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        assertEquals(2, bridge.managedView(0x91F8, other).document!!.entries.count { RomParser.isGatePlm(it.id) })
        assertTrue(editor.undo())
        assertTrue(editor.workingPlms.contains(gate))
        assertTrue(editor.plmStateMembership(gate).allStates)
    }

    @Test fun `default all state additions cannot silently replace another states station or door variant`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        val current = editor.currentPlmStateId()!!
        val other = project.rooms.getValue("91F8").states.first { it.id != current }.id
        val refill = RomParser.PlmEntry(0xB6DF, 8, 8, 0)
        bridge.editManaged(0x91F8, other, false, { false }, refill)
        val before = bridge.sourceText()
        editor.addPlm(0xB6EB, 8, 8, 0)
        assertEquals(before, bridge.sourceText())
        assertContains(editor.statusMessage, "occupies this position")
        editor.addPlm(0xB6EB, 8, 8, 0, com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        assertTrue(editor.workingPlms.any { it.id == 0xB6EB })
        assertTrue(bridge.managedView(0x91F8, other).document!!.entries.contains(refill))
        bridge.editManaged(0x91F8, current, false, { false }, RomParser.PlmEntry(0xC8A2, 12, 8, 0))
        bridge.editManaged(0x91F8, other, false, { false }, RomParser.PlmEntry(0xC85A, 12, 8, 0))
        editor.refreshLinkedPlms()
        editor.addPlm(0xC848, 12, 8, 0)
        assertTrue(bridge.managedView(0x91F8, other).document!!.entries.any { it.id == 0xC85A })
        val changed = editor.workingPlms.single { RomParser.isDoorCapPlm(it.id) }
        val caps = bridge.sourceText()
        editor.applyPlmToAllStates(changed)
        assertEquals(caps, bridge.sourceText())
        assertContains(editor.statusMessage, "different")
    }

    @Test fun `save scope isolates placements but retains one spawn until final removal`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true, withSaveData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        val helperState = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        val helper = RomParser.PlmEntry(0xDB44, 8, 8, 8)
        bridge.editManaged(0x91F8, helperState, false, { false }, helper)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val station = editor.workingPlms.single { it.id == 0xB76F }
        assertTrue(editor.plmStateMembership(station).allStates)
        editor.removePlm(8, 8, station.id, com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        assertFalse(bridge.saveSpawn(0x91F8, 0, 2)!!.cleared)
        assertTrue(bridge.managedView(0x91F8, helperState).document!!.entries.contains(helper), "Script-only setup rows are neither physical conflicts nor replacement targets")
        val other = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        assertTrue(bridge.managedView(0x91F8, other).document!!.entries.contains(station))
        assertTrue(editor.undo())
        editor.removePlm(8, 8, station.id)
        assertTrue(bridge.saveSpawn(0x91F8, 0, 2)!!.cleared)
        assertTrue(project.rooms.getValue("91F8").states.all { branch -> bridge.managedView(0x91F8, branch.id).document!!.entries.none { it.id == station.id } })
        assertTrue(editor.undo())
        assertFalse(bridge.saveSpawn(0x91F8, 0, 2)!!.cleared)
    }

    @Test fun `scroll scope includes command identity not the zero preview pointer`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        val commands = listOf(com.supermetroid.editor.data.ScrollCommand(0, 1))
        editor.addScrollTriggerWithCommands(8, 8, commands)
        val trigger = editor.workingPlms.single { it.id == 0xB703 }
        assertTrue(editor.plmStateMembership(trigger).allStates)
        val other = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        val private = listOf(com.supermetroid.editor.data.ScrollCommand(0, 0))
        bridge.editScroll(0x91F8, other, 8, 8, private)
        editor.refreshLinkedPlms()
        assertEquals(trigger, bridge.managedView(0x91F8, other).document!!.entries.single { it.id == 0xB703 })
        assertFalse(editor.plmStateMembership(trigger).allStates)
        assertFalse(bridge.managedIsInAllStates(0x91F8, editor.currentPlmStateId()!!, trigger))
        val next = listOf(com.supermetroid.editor.data.ScrollCommand(1, 2))
        assertTrue(editor.updateScrollTriggerCommands(trigger, next))
        assertEquals(next, editor.scrollCommandsFor(trigger))
        assertEquals(private, bridge.scrollCommands(0x91F8, other, trigger))
        val before = bridge.sourceText()
        editor.applyPlmToAllStates(trigger)
        assertEquals(before, bridge.sourceText(), "Distinct command streams must be resolved explicitly")
    }

    @Test fun `explicit edit this state detaches a complete shared gate in one undo`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.addPlm(0xC836, 8, 8, 8)
        val gate = editor.workingPlms.single { it.id == 0xC836 }
        editor.selectPlmEditScope(gate, com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        editor.addPlm(0xC836, 8, 8, 10)
        val current = editor.currentPlmStateId()!!
        val other = project.rooms.getValue("91F8").states.first { it.id != current }.id
        assertEquals(10, editor.workingPlms.single { it.id == 0xC836 }.param)
        assertEquals(8, bridge.managedView(0x91F8, other).document!!.entries.single { it.id == 0xC836 }.param)
        assertTrue(editor.undo())
        assertEquals(8, editor.workingPlms.single { it.id == 0xC836 }.param)
        assertTrue(editor.plmStateMembership(gate).allStates)
    }

    @Test fun `gate body scope and deletion follow the complete gate rather than equal body rows`() = runBlocking {
        val (_, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        editor.addPlm(0xC836, 8, 8, 8)
        val body = editor.workingPlms.single { it.id == 0xC82A }
        editor.selectPlmEditScope(body, com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        editor.addPlm(0xC836, 8, 8, 10)
        assertFalse(editor.plmStateMembership(body).allStates)
        editor.removePlm(8, 8, body.id)
        assertTrue(editor.workingPlms.none { RomParser.isGatePlm(it.id) })
        val other = project.rooms.getValue("91F8").states.first { it.id != editor.currentPlmStateId() }.id
        assertEquals(8, bridge.managedView(0x91F8, other).document!!.entries.single { it.id == 0xC836 }.param)
        assertTrue(editor.undo())
        assertEquals(10, editor.workingPlms.single { it.id == 0xC836 }.param)
        assertEquals(1, editor.workingPlms.count { it.id == 0xC82A })
    }

    @Test fun `save station placement spawn fields ASM edits undo reopen and cache share one authority`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true, withSaveData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(File(directory, "Test.smedit"), project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        editor.addPlm(0xB76F, 8, 8, 0x8000)
        val station = editor.workingPlms.single { it.id == 0xB76F }
        assertEquals(0x8002, station.param, "Skip gunship and foreign occupied slots")
        val original = bridge.saveSpawn(0x91F8, 0, 2)!!
        assertEquals(0x8000, original.words[1])
        assertEquals(7, original.words[2], "Preserve the base record's door BTS, like the ROM exporter")
        assertTrue(project.rooms.getValue("91F8").saveStationSpawns.isEmpty())
        editor.updateSaveStationSpawnFields(0, 2, -32, 144, 256, 0, bridge.catalog.parser)
        val fields = bridge.saveSpawn(0x91F8, 0, 2)!!
        assertEquals(listOf(256, 0, 144, 0xFFE0), fields.words.drop(3))
        assertEquals(-32, editor.effectiveSaveStationSpawn(0, 2, bridge.catalog.parser)!!.samusXSigned)
        assertTrue(editor.undo())
        assertEquals(original, bridge.saveSpawn(0x91F8, 0, 2), "One Apply action undoes camera and position together")
        assertTrue(editor.redo())
        assertEquals(fields, bridge.saveSpawn(0x91F8, 0, 2))
        val beforeInvalid = bridge.sourceText()
        editor.updateSaveStationSpawnFields(0, 2, 900000, 144, 256, 0, bridge.catalog.parser)
        assertEquals(beforeInvalid, bridge.sourceText(), "Out-of-range input must not silently wrap")
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.saveTarget(0x91F8, 0, 2))
        assertContains(bridge.sourceText().lines()[target.lineIndex], "macro SmeditObjects_91F8_Save_0_2")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, bridge.sourceText().replace("dw ${'$'}FFE0", "dw ${'$'}FFF0"))
        editor.refreshLinkedPlms()
        assertEquals(-16, editor.effectiveSaveStationSpawn(0, 2, bridge.catalog.parser)!!.samusXSigned)
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        restored.preloadProjections()
        assertEquals(0xFFF0, restored.saveSpawn(0x91F8, 0, 2)!!.words[6])
        val stable = restored.sourceText()
        reopened.updateSourceEditText(PLM_SOURCE_FILE, stable.replace("dw ${'$'}FFF0", "dw ${'$'}9000000900"))
        assertNotNull(restored.managedView(0x91F8, editor.currentPlmStateId()!!).problem)
        assertFails { restored.updateSaveSpawn(fields) }
        assertTrue(reopened.saveAllSources(), "Invalid source drafts must remain saveable")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, stable)
        editor.refreshLinkedPlms()
        editor.removePlm(station.x, station.y, station.id)
        assertTrue(bridge.saveSpawn(0x91F8, 0, 2)!!.cleared)
        assertTrue(editor.undo())
        assertFalse(bridge.saveSpawn(0x91F8, 0, 2)!!.cleared)
        assertTrue(editor.workingPlms.contains(station))
    }

    @Test fun `legacy GUI save overrides migrate and restore with source ownership and metadata in one undo`() = runBlocking {
        val (workspace, bridge, project) = fixture(withSaveData = true)
        val room = project.getOrCreateRoom(0x91F8)
        val change = com.supermetroid.editor.data.SaveStationSpawnChange(0, 2, 0x91F8, 0x8000, 0, 0, 104, 0xFFE0)
        room.saveStationSpawns += change
        room.plmChanges += PlmChange("add", 0xB76F, 8, 8, 0x8002)
        bridge.migrateRoom(0x91F8, "Landing Site")
        assertTrue(room.saveStationSpawns.isEmpty())
        assertEquals(0xFFE0, bridge.saveSpawn(0x91F8, 0, 2)!!.words[6])
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(listOf(change), room.saveStationSpawns)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(room.saveStationSpawns.isEmpty())
        assertEquals(0xFFE0, bridge.saveSpawn(0x91F8, 0, 2)!!.words[6])
    }

    @Test fun `ordinary stations gates door caps and extensions all round trip through the same PLM rows`() = runBlocking {
        val (workspace, bridge, _) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val ids = listOf(0xB6DF, 0xB6EB, 0xB6D3, 0xC836, 0xC8A2, 0xB647)
        ids.forEachIndexed { index, id -> bridge.editManaged(0x91F8, "state-1", false, { false }, RomParser.PlmEntry(id, 4 + index, 8, if (id == 0xC836) 8 else 0)) }
        val doc = bridge.managedView(0x91F8, "state-1").document!!
        assertTrue(ids.all { id -> doc.entries.any { it.id == id } })
        val gateIndex = doc.entries.indexOfFirst { it.id == 0xC836 }
        workspace.updateSourceEditText(PLM_SOURCE_FILE, doc.replace(gateIndex, doc.entries[gateIndex].copy(param = 10)))
        assertEquals(10, bridge.managedView(0x91F8, "state-1").document!!.entries.single { it.id == 0xC836 }.param)
        assertTrue(workspace.saveAllSources())
        assertContains(bridge.sourceText(), "Gate: Green (right)")
        assertTrue(bridge.managedView(0x91F8, "state-2").document!!.entries.none { it.id in ids })
    }

    @Test fun `new unpaired save rows expose a noneditable invalid draft`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true, withSaveData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(File(directory, "Test.smedit"), project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        val stateId = editor.currentPlmStateId()!!
        val source = AsmRoomObjectSource(bridge.sourceText())
        val unpaired = source.edit(project.rooms.getValue("91F8"), stateId, false, { false }, RomParser.PlmEntry(0xB76F, 8, 8, 0x8002))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, unpaired)
        editor.refreshLinkedPlms()
        assertContains(bridge.managedView(0x91F8, stateId).problem!!, "paired spawn record")
        editor.addPlm(0xB6DF, 9, 8, 0)
        assertEquals(unpaired, bridge.sourceText(), "A last-valid preview must not be an editable current model")
        assertTrue(workspace.saveAllSources(), "Invalid drafts remain saveable")
    }

    @Test fun `save reservations in another area do not consume this areas same numbered slot`() = runBlocking {
        val (_, bridge, project) = fixture(withSaveData = true, withSecondArea = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        bridge.migrateRoom(0xA000, "Second area fixture")
        val room = project.rooms.getValue("A000")
        val save = SourceSaveResource(AsmSaveStationSource.name(0xA000, 1, 2), 0xA000, 1, 2,
            listOf(0xA000, 0x8010, 0, 0, 0, 104, 0))
        bridge.editSaveStation(0xA000, room.states.single().id, { false }, RomParser.PlmEntry(0xB76F, 8, 8, 0x8002), save)
        assertEquals(2, bridge.availableSaveSlot(0x91F8, 0))
        assertEquals(0, bridge.availableSaveSlot(0xA000, 1))
    }

    @Test fun `scroll commands synchronize GUI ASM undo reopen and verified cache without duplicate project values`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = EditorState().apply { testMode = true }
        editor.initForProject(File(directory, "Test.smedit"), project)
        editor.asmPlmSourceBridge = bridge
        editor.loadRoom(0x91F8, bridge.catalog.parser, bridge.catalog.parser.readRoomHeader(0x91F8)!!)
        val stateId = assertNotNull(editor.currentPlmStateId())
        val otherStateId = project.rooms.getValue("91F8").states.single { it.id != stateId }.id
        val commands = listOf(com.supermetroid.editor.data.ScrollCommand(0, 1), com.supermetroid.editor.data.ScrollCommand(3, 2))
        editor.selectItemEditScope(com.supermetroid.editor.data.ItemStateScope.THIS_STATE)
        editor.addScrollTriggerWithCommands(4, 5, commands)
        val trigger = editor.workingPlms.single { it.id == 0xB703 }
        assertEquals(commands, editor.scrollCommandsFor(trigger))
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.scrollTarget(0x91F8, stateId, trigger))
        assertContains(bridge.sourceText().lines()[target.lineIndex], "macro SmeditObjects_91F8_Scroll_")
        val beforeEdit = bridge.sourceText()
        val next = listOf(com.supermetroid.editor.data.ScrollCommand(1, 0))
        assertTrue(editor.updateScrollTriggerCommands(trigger, next, beforeEdit))
        assertEquals(next, editor.scrollCommandsFor(trigger))
        editor.undo()
        assertEquals(commands, editor.scrollCommandsFor(trigger))
        editor.redo()
        assertEquals(next, editor.scrollCommandsFor(trigger))
        val guiSource = bridge.sourceText()
        val previewRevision = editor.editVersion
        workspace.updateSourceEditText(PLM_SOURCE_FILE, guiSource.replace("db ${'$'}01, ${'$'}00", "db ${'$'}01, ${'$'}02"))
        editor.refreshLinkedPlms()
        assertTrue(editor.editVersion > previewRevision, "Command-only ASM edits must invalidate runtime scroll diagnostics")
        val asmCommands = listOf(com.supermetroid.editor.data.ScrollCommand(1, 2))
        assertEquals(asmCommands, editor.scrollCommandsFor(trigger))
        assertFalse(editor.updateScrollTriggerCommands(trigger, commands, guiSource), "Stale GUI drafts must not overwrite ASM")
        assertTrue(workspace.saveAllSources())
        assertContains(bridge.sourceText(), "Screen (2, 1) → Green")
        val json = Json { encodeDefaults = true }
        val restoredProject = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { restoredProject }
        restored.preloadProjections()
        assertEquals(asmCommands, restored.scrollCommands(0x91F8, stateId, trigger))
        assertTrue(restoredProject.rooms.values.all { it.customScrollCommands.isEmpty() && it.states.all { s -> s.customScrollCommands.isEmpty() } })
        assertTrue(restored.managedView(0x91F8, otherStateId).document!!.entries.none { it.id == 0xB703 })
        val valid = restored.sourceText()
        reopened.updateSourceEditText(PLM_SOURCE_FILE, valid.replace("db ${'$'}01, ${'$'}02", "db ${'$'}01, ${'$'}09"))
        assertNotNull(restored.managedView(0x91F8, stateId).problem)
        assertFails { restored.editScroll(0x91F8, stateId, 4, 5, commands) }
        assertTrue(reopened.saveAllSources(), "Invalid drafts remain saveable; they are not buildable")
    }

    @Test fun `custom scroll migration undo redo restores both source and legacy metadata`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        val commands = listOf(com.supermetroid.editor.data.ScrollCommand(0, 1))
        room.customScrollCommands["cmd_0"] = commands.toMutableList()
        room.plmChanges += PlmChange("add", 0xB703, 4, 5, 0xCC00)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val trigger = bridge.managedView(0x91F8, "state-1").document!!.entries.single { it.id == 0xB703 }
        val legacy = RomParser.PlmEntry(0xB703, 4, 5, 0xCC00)
        val target = placementAfterObjectMigration(legacy, bridge.managedView(0x91F8, "state-1").document!!.entries)
        assertEquals(trigger, target)
        assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(0x91F8, "state-1", target!!))
        assertNull(placementAfterObjectMigration(legacy, listOf(trigger, trigger)), "Ambiguous migrations must not guess")
        assertNull(placementAfterObjectMigration(legacy.copy(param = 0x9000), listOf(trigger)), "Only migrated command IDs may be remapped")
        assertEquals(commands, bridge.scrollCommands(0x91F8, "state-1", trigger))
        assertTrue(room.customScrollCommands.isEmpty())
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(commands, room.customScrollCommands["cmd_0"]?.toList())
        assertEquals(0xCC00, room.plmChanges.single().param)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(room.customScrollCommands.isEmpty())
        assertEquals(commands, bridge.scrollCommands(0x91F8, "state-1", trigger))
    }

    private suspend fun fixture(withLevelData: Boolean = false, withSaveData: Boolean = false, withSecondArea: Boolean = false): Triple<AsmWorkspaceState, AsmPlmSourceBridge, SmEditProject> {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\n")
        File(src, PLM_SOURCE_FILE).writeText(source)
        File(src, "macros.asm").writeText(macros)
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val projectFile = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(projectFile.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(projectFile.path, true)
        val rom = if (withSaveData) saveSourceTestRom() else objectSourceTestRom()
        if (withSecondArea) {
            fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
            rom[0x7A001] = 1; rom[0x7A004] = 1; rom[0x7A005] = 1
            word(0x7A00B, 0xE5E6); word(0x7A00D + 20, 0x8000)
            word(0x18010, 0xA000)
        }
        if (withLevelData) {
            val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 } // 2048 layer-1 bytes + BTS
            LZ5Compressor.compress(level).copyInto(rom, 0x200000)
            for (pc in listOf(0x791F8 + 18, 0x79300)) {
                rom[pc] = 0; rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
            }
        }
        val bytes = RomParser.serializePlmSet(listOf(RomParser.PlmEntry(0xEEDB, 2, 3, 5)))
        bytes.forEachIndexed { i, value -> rom[0x78000 + i] = value.toByte() }
        val consumers = mapOf(0x8000 to RomParser(rom).findAllStateDataOffsets(0x91F8).mapIndexed { index, pc ->
            AsmPlmConsumer(0x91F8, "Landing Site", index, pc)
        })
        val catalog = AsmPlmSourceCatalog(source, macros, emptyMap(), RomParser(rom), consumers)
        val project = SmEditProject("test.sfc")
        project.asmWorkspace.enabled = true
        project.asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        return Triple(workspace, AsmPlmSourceBridge(catalog, workspace) { project }, project)
    }

    @Test fun `migration has atomic source undo redo and persists values only in source`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val edits = project.getOrCreateRoom(0x91F8)
        val addition = PlmChange("add", 0xEEDF, 8, 9, 12)
        edits.plmChanges += addition
        bridge.migrateRoom(0x91F8, "Landing Site")
        assertTrue(bridge.isManaged(0x91F8))
        assertTrue(edits.plmChanges.isEmpty())
        val entries = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries
        assertEquals(2, entries.size)
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertFalse(bridge.isManaged(0x91F8))
        assertEquals(listOf(addition), edits.plmChanges)
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertTrue(bridge.isManaged(0x91F8))
        assertTrue(edits.plmChanges.isEmpty())
        assertEquals(entries, bridge.managedView(0x91F8, "state-1").document?.entries)
        assertTrue(workspace.saveAllSources())
        val json = Json { encodeDefaults = true }
        val restoredProject = json.decodeFromString<SmEditProject>(json.encodeToString(SmEditProject.serializer(), project))
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { restoredProject }
        assertEquals(entries, restored.managedView(0x91F8, "state-1").document?.entries)
        assertTrue(restoredProject.rooms.getValue("91F8").plmChanges.isEmpty())
        assertTrue(restored.catalog.sourceBindings(restoredProject, reopened.projectSourceText(PLM_SOURCE_FILE), macros).isEmpty())
    }

    @Test fun `AI item placement plans source exactly and supports undo redo`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val editor = scopeEditor(bridge, project)
        val access = DesktopSmeditWorkspaceAccess(
            editorState = editor,
            asmWorkspaceState = workspace,
            parser = { bridge.catalog.parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )
        val controller = DesktopSmeditMutationController(editor, access) { bridge.catalog.parser }
        val before = bridge.sourceText()

        val planning = controller.plan("place_item", buildJsonObject {
            put("room_id", "0x91F8")
            put("plm_id", 0xEEDB)
            put("x", 4)
            put("y", 6)
            put("state_scope", "all_states")
        })

        assertFalse(planning.result.isError, planning.result.output.toString())
        assertEquals(before, bridge.sourceText(), "Planning must not publish source")
        controller.approve(planning.plan!!.id)
        val after = bridge.sourceText()
        assertNotEquals(before, after)
        project.rooms.getValue("91F8").states.forEach { state ->
            assertTrue(bridge.managedView(0x91F8, state.id).document!!.entries.any {
                it.id == 0xEEDB && it.x == 4 && it.y == 6 && it.param == 0x51
            })
        }
        controller.undo()
        assertEquals(before, bridge.sourceText())
        controller.redo()
        assertEquals(after, bridge.sourceText())
    }

    @Test fun `cached projections stay live in both directions and unrelated source edits reuse the room model`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        bridge.preloadProjections()
        val initial = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        workspace.updateSourceEditText("main.asm", workspace.projectSourceText("main.asm") + "; unrelated edit\n")
        assertSame(initial, bridge.managedView(0x91F8, "state-1").document)
        bridge.editManaged(0x91F8, "state-1", true, { false }, RomParser.PlmEntry(0xEF0B, 8, 9, 12))
        val gui = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        assertEquals(2, gui.entries.size)
        val changed = gui.entries.last().copy(id = 0xEEE7)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, gui.replace(1, changed))
        assertEquals(changed, bridge.managedView(0x91F8, "state-1").document!!.entries.last())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        restored.preloadProjections()
        val saved = assertNotNull(restored.managedView(0x91F8, "state-1").document)
        assertEquals(changed, saved.entries.last())
        reopened.updateSourceEditText(PLM_SOURCE_FILE, saved.text.replace("    dw", "    invalid draft\n    dw"))
        val invalid = restored.managedView(0x91F8, "state-1")
        assertNotNull(invalid.problem)
        assertSame(saved, invalid.document)
        assertIs<AsmPlmPlacementTarget.Unavailable>(restored.managedPlacement(0x91F8, "state-1", changed))
        reopened.awaitSourceIndexing()
    }

    @Test fun `partial indexes and stale clean buffers do not hide external helper consumers`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val entry = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries.single()
        val helper = "SmeditObjects_91F8_Common_9"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(entry), "%$helper()") +
            "\nmacro $helper()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        // Seed a clean buffer, then modify its authored file externally without updating the mirror.
        workspace.projectSourceText("main.asm")
        val root = assertNotNull(workspace.projectWorkspace).root
        File(root, "overrides/src/main.asm").apply { parentFile.mkdirs(); writeText("; external caller mentions $helper\n") }
        bridge.editManaged(0x91F8, "state-1", true, { it == entry }, null)
        assertTrue(AsmRoomObjectSource(bridge.sourceText()).contains(helper))
        assertTrue(workspace.saveAllSources())
        val partial = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        partial.bindProject(File(directory, "Test.smedit").path, true, progressive = true)
        val next = AsmPlmSourceBridge(bridge.catalog, partial) { project }
        next.editManaged(0x91F8, "state-1", true, { false }, null)
        assertTrue(AsmRoomObjectSource(next.sourceText()).contains(helper))
        partial.awaitSourceIndexing()
    }

    @Test fun `source-only room edits keep invalid draft visible and never resume independent GUI deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val before = bridge.sourceText()
        val extra = RomParser.PlmEntry(0xEEDF, 8, 9, 12)
        bridge.editManaged(0x91F8, "state-1", false, { false }, extra)
        val after = bridge.sourceText()
        assertEquals(2, bridge.managedView(0x91F8, "state-1").document?.entries?.size)
        assertEquals(1, bridge.managedView(0x91F8, "state-2").document?.entries?.size)
        bridge.undoSource(after)
        assertEquals(before, bridge.sourceText())
        bridge.redoSource(before)
        assertEquals(after, bridge.sourceText())
        assertTrue(project.rooms.getValue("91F8").states.all { it.plmChanges.isEmpty() })
        workspace.updateSourceEditText(PLM_SOURCE_FILE, after.replace("${'$'}EEDF", "unknown"))
        assertNotNull(bridge.managedView(0x91F8, "state-1").problem)
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.managedPlacement(0x91F8, "state-1", extra))
        assertFails { bridge.editManaged(0x91F8, "state-1", false, { false }, extra) }
    }

    @Test fun `legacy single-state cleanup is one undo step and survives save reopen`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        val state = room.states.single().id
        bridge.migrateRoom(room.roomId, "Test room")
        val entry = assertNotNull(bridge.managedView(room.roomId, state).document).entries.single()
        val simple = bridge.sourceText()
        val name = "SmeditObjects_91F8_Common_5"
        val legacy = simple.replace("    ${AsmRoomObjectSource.renderEntry(entry)} ; PLM:", "    %$name() ; PLM:") +
            "\nmacro $name()\n    ${AsmRoomObjectSource.renderEntry(entry)} ; authored note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_2()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        val document = assertNotNull(bridge.managedView(room.roomId, state).document)
        assertEquals(legacy, bridge.sourceText(), "Reading legacy source must not rewrite it")
        bridge.updateManaged(room.roomId, state, document, 0, entry.copy(x = 7))
        val cleaned = bridge.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "authored note")
        assertTrue(bridge.managedIsInAllStates(room.roomId, state, entry.copy(x = 7)), "A sole state needs no common helper to show All states")
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(room.roomId, state, entry.copy(x = 7)))
        assertContains(cleaned.lines()[target.lineIndex], AsmRoomObjectSource.renderEntry(entry.copy(x = 7)))
        bridge.undoSource(cleaned)
        assertEquals(legacy, bridge.sourceText())
        bridge.redoSource(legacy)
        assertEquals(cleaned, bridge.sourceText())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertEquals(cleaned, restored.sourceText())
        assertEquals(listOf(entry.copy(x = 7)), restored.managedView(room.roomId, state).document?.entries)
    }

    @Test fun `project save tidies already saved legacy helpers without requiring a GUI placement edit`() = runBlocking {
        val (_, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val entry = assertNotNull(bridge.managedView(room.roomId, room.states.single().id).document).entries.single()
        val common = "SmeditObjects_91F8_Common_5"
        val legacy = bridge.sourceText().replace("    ${AsmRoomObjectSource.renderEntry(entry)} ; PLM:", "    %$common() ; PLM:") +
            "\nmacro $common()\n    ${AsmRoomObjectSource.renderEntry(entry)} ; my note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_2()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        // Reproduce an older project whose canonical source already contains the leftovers.
        AsmProjectWorkspaceRepository(emptyList(), emptyList()).saveSource(File(directory, "Test.smedit").path, PLM_SOURCE_FILE, legacy)
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertFalse(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(reopened.saveAllSources())
        val cleaned = restored.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "my note")
        assertEquals(listOf(entry), restored.managedView(room.roomId, room.states.single().id).document?.entries)
        restored.undoSource(cleaned)
        assertEquals(legacy, restored.sourceText())
        restored.redoSource(legacy)
        assertEquals(cleaned, restored.sourceText())
        // Saving an invalid draft remains possible: cleanup cannot manufacture a replacement.
        val invalid = cleaned.replace("    dw", "    v\n    dw")
        reopened.updateSourceEditText(PLM_SOURCE_FILE, invalid)
        assertTrue(reopened.saveSource(PLM_SOURCE_FILE))
        assertEquals(invalid, restored.sourceText())
    }

    @Test fun `stray text in a managed room blocks GUI placement without losing source then recovers`() = runBlocking {
        val (workspace, bridge, project) = fixture(withLevelData = true)
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val editor = EditorState().apply {
            testMode = true
            initForProject(File(directory, "Test.smedit"), project)
            asmPlmSourceBridge = bridge
            loadRoom(room.roomId, bridge.catalog.parser, assertNotNull(bridge.catalog.parser.readRoomHeader(room.roomId)))
            initTestLevel(32, 32)
        }
        editor.refreshLinkedPlms()
        val before = editor.workingPlms.toList()
        val valid = bridge.sourceText()
        val list = AsmRoomObjectSource(valid).table(room.roomId, room.states.single().id).resource
        val broken = valid.replaceRange(list.bodyStart, list.bodyStart, "    v\n")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, broken)
        editor.addPlm(0xEF23, 8, 9, 0)
        assertEquals(broken, bridge.sourceText())
        assertEquals(before, editor.workingPlms)
        assertContains(editor.statusMessage, "unsupported object source: v")
        assertContains(editor.statusMessage, "Object edit blocked: bank_8F.asm:${valid.take(list.bodyStart).count { it == '\n' } + 1}:")
        assertTrue(editor.statusIsError)
        assertTrue(editor.undoStack.isEmpty(), "Rejected placements must not enter undo history")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, valid)
        editor.addPlm(0xEF23, 8, 9, 0)
        assertTrue(editor.workingPlms.any { it.id == 0xEF23 && it.x == 8 && it.y == 9 })
        assertFalse(editor.statusIsError, "A successful placement clears the old blocker")
    }

    @Test fun `saving legacy multi-state objects produces direct rows with local ASM and explicit all-state GUI edits`() = runBlocking {
        val (_, bridge, project) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val room = project.rooms.getValue("91F8")
        val first = room.states.first().id
        val second = room.states.last().id
        val local = RomParser.PlmEntry(0xEEE7, 8, 9, 12)
        bridge.editManaged(room.roomId, first, false, { false }, local)
        val gravity = RomParser.PlmEntry(0xEF0B, 0x38, 0x49, 0x57)
        bridge.editManaged(room.roomId, first, true, { false }, gravity)
        val expected = room.states.associate { it.id to bridge.managedView(room.roomId, it.id).document!!.entries }
        val helper = "SmeditObjects_91F8_Common_2"
        val legacy = bridge.sourceText()
            .replace("; Values live here. Each list contains its PLMPopEntry rows; state wrappers select lists.",
                "; Values live here. State wrappers share lists; Common macros are All states items.")
            .replace(AsmRoomObjectSource.renderEntry(gravity), "%$helper()") +
            "\nmacro $helper()\n    ${AsmRoomObjectSource.renderEntry(gravity)} ; my shared pickup note\nendmacro\n" +
            "\nmacro SmeditObjects_91F8_Common_99()\n    ${AsmRoomObjectSource.renderEntry(local)}\nendmacro\n"
        val path = File(directory, "Test.smedit").path
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.saveSource(path, PLM_SOURCE_FILE, legacy)
        val reopened = AsmWorkspaceState(projectRepository = repository)
        reopened.bindProject(path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertFalse(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(reopened.saveAllSources())
        val cleaned = restored.sourceText()
        assertFalse(cleaned.contains("Common_"))
        assertContains(cleaned, "; Values live here. Each list contains its PLMPopEntry rows; state wrappers select lists.")
        assertEquals(2, cleaned.lineSequence().count {
            it.contains("%PLMPopEntry(${'$'}EF0B, ${'$'}38, ${'$'}49, ${'$'}0057) ; PLM: Gravity Suit (Visible)")
        })
        for (state in room.states) {
            assertEquals(expected[state.id], restored.managedView(room.roomId, state.id).document?.entries)
            assertTrue(restored.managedIsInAllStates(room.roomId, state.id, gravity))
            val target = assertIs<AsmPlmPlacementTarget.Source>(restored.managedPlacement(room.roomId, state.id, gravity))
            assertContains(cleaned.lines()[target.lineIndex], AsmRoomObjectSource.renderEntry(gravity))
        }
        restored.undoSource(cleaned)
        assertEquals(legacy, restored.sourceText())
        restored.redoSource(legacy)
        assertEquals(cleaned, restored.sourceText())

        // Equal direct rows are not secretly coupled: editing one list leaves the other untouched.
        val doc = assertNotNull(restored.managedView(room.roomId, first).document)
        val changed = gravity.copy(id = 0xEEF7)
        reopened.updateSourceEditText(PLM_SOURCE_FILE, doc.replace(doc.entries.indexOf(gravity), changed))
        assertTrue(restored.managedView(room.roomId, first).document!!.entries.contains(changed))
        assertTrue(restored.managedView(room.roomId, second).document!!.entries.contains(gravity))
        assertFalse(restored.managedIsInAllStates(room.roomId, first, changed))
        assertFalse(restored.managedIsInAllStates(room.roomId, second, gravity))

        val common = gravity.copy(param = 0x58)
        restored.editManaged(room.roomId, first, true, { it == gravity || it == changed }, common)
        for (state in room.states) {
            assertTrue(restored.managedIsInAllStates(room.roomId, state.id, common))
            assertEquals(1, restored.managedView(room.roomId, state.id).document!!.entries.count { it == common })
        }
        assertTrue(restored.managedView(room.roomId, first).document!!.entries.contains(local))
        assertFalse(restored.managedView(room.roomId, second).document!!.entries.contains(local))
        restored.cloneManagedState(room.roomId, second, "new-state")
        room.states += room.states.last().copy(id = "new-state")
        assertEquals(restored.managedView(room.roomId, second).document?.entries,
            restored.managedView(room.roomId, "new-state").document?.entries)
        assertFalse(restored.sourceText().contains("Common_"))
        assertTrue(reopened.saveAllSources())
    }

    @Test fun `disabled custom module unsaved references protect helpers until removed`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val entry = RomParser.PlmEntry(0xEEDF, 8, 9, 12)
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", entry.id, entry.x, entry.y, entry.param)
        bridge.migrateRoom(0x91F8, "Test room")
        val common = "SmeditObjects_91F8_Common_1"
        val legacy = bridge.sourceText().replace(AsmRoomObjectSource.renderEntry(entry), "%$common()") +
            "\nmacro $common()\n    ${AsmRoomObjectSource.renderEntry(entry)}\nendmacro\n"
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        assertTrue(workspace.createProjectModule("Test consumer"))
        val module = workspace.projectModuleFileIds.single()
        assertTrue(workspace.setProjectModuleEnabled(module, false))
        workspace.updateSourceEditText(module, "macro MyConsumer()\n    %$common()\nendmacro\n")
        bridge.editManaged(0x91F8, "state-1", true, { it == entry }, null)
        assertTrue(workspace.saveAllSources())
        assertTrue(AsmRoomObjectSource(bridge.sourceText()).contains(common))
        assertTrue(project.rooms.getValue("91F8").states.all { state ->
            bridge.managedView(0x91F8, state.id).document?.entries?.none { it == entry } == true
        })
        workspace.updateSourceEditText(module, "; custom reference removed\n")
        bridge.editManaged(0x91F8, "state-1", true, { false }, null)
        assertFalse(AsmRoomObjectSource(bridge.sourceText()).contains(common))
    }

    @Test fun `direct shared list scope survives a new state and becomes local after forking`() = runBlocking {
        val (_, bridge, project) = fixture()
        val room = project.getOrCreateRoom(0x91F8)
        room.ensureStateManifest(bridge.catalog.parser)
        room.states.removeAt(0)
        bridge.migrateRoom(room.roomId, "Test room")
        val original = room.states.single()
        val entry = assertNotNull(bridge.managedView(room.roomId, original.id).document).entries.single()
        assertTrue(bridge.managedIsInAllStates(room.roomId, original.id, entry))
        bridge.cloneManagedState(room.roomId, original.id, "new-state")
        room.states.add(original.copy(id = "new-state"))
        assertTrue(bridge.managedIsInAllStates(room.roomId, "new-state", entry))
        bridge.editManaged(room.roomId, "new-state", false, { it == entry }, entry.copy(id = 0xEEDF))
        assertFalse(bridge.managedIsInAllStates(room.roomId, "new-state", entry.copy(id = 0xEEDF)))
        assertEquals(listOf(entry), bridge.managedView(room.roomId, original.id).document?.entries)
    }

    @Test fun `managed comments use custom names and refresh together with GUI scalar edits and undo`() = runBlocking {
        val (_, bridge, project) = fixture()
        bridge.describePlm = { if (it.id == 0xF300) "Test pickup (Visible)" else RomParser.plmDisplayName(it.id, it.param) }
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xF300, 8, 9, 12)
        bridge.migrateRoom(0x91F8, "Landing Site")
        val before = bridge.sourceText()
        assertTrue(before.startsWith(source)) // Original bank's handwritten comment is untouched.
        assertContains(before, "%PLMPopEntry(${'$'}F300, ${'$'}08, ${'$'}09, ${'$'}000C) ; PLM: Test pickup (Visible)")
        assertFalse(before.contains("Common_"))
        val document = assertNotNull(bridge.managedView(0x91F8, "state-1").document)
        val replacement = document.entries.last().copy(id = 0xEEF7)
        bridge.updateManaged(0x91F8, "state-1", document, document.entries.lastIndex, replacement)
        val after = bridge.sourceText()
        assertFalse(after.contains("Test pickup"))
        assertContains(after, "%PLMPopEntry(${'$'}EEF7, ${'$'}08, ${'$'}09, ${'$'}000C) ; PLM: Speed Booster (Visible)")
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(0x91F8, "state-2", replacement))
        assertContains(after.lines()[target.lineIndex], "%PLMPopEntry(${'$'}EEF7")
        assertEquals(after, bridge.sourceText()) // Reading/navigating never edits a bank.
        bridge.undoSource(after)
        assertEquals(before, bridge.sourceText())
        bridge.redoSource(before)
        assertEquals(after, bridge.sourceText())
    }

    @Test fun `existing uncommented authored rooms gain comments on a GUI edit not on navigation`() = runBlocking {
        val (workspace, bridge, _) = fixture()
        bridge.migrateRoom(0x91F8, "Landing Site")
        val legacy = bridge.sourceText().replace(Regex(" ; PLM: [^\\r\\n]*"), "")
        workspace.updateSourceEditText(PLM_SOURCE_FILE, legacy)
        val entry = assertNotNull(bridge.managedView(0x91F8, "state-1").document).entries.single()
        assertIs<AsmPlmPlacementTarget.Source>(bridge.managedPlacement(0x91F8, "state-1", entry))
        assertEquals(legacy, bridge.sourceText())
        bridge.editManaged(0x91F8, "state-1", false, { false }, entry.copy(id = 0xEEDF, x = 8))
        val after = bridge.sourceText()
        assertContains(after, "; PLM: Missile (Visible)")
        assertContains(after, "; PLM: Super Missile (Visible)")
        assertTrue(after.startsWith(source))
        bridge.undoSource(after)
        assertEquals(legacy, bridge.sourceText())
    }

    @Test fun `migration cannot overwrite original source edits or undo across changed state membership`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}04"))
        assertFails { bridge.migrateRoom(0x91F8, "Landing Site") }
        assertTrue(project.rooms.isEmpty())
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        bridge.migrateRoom(0x91F8, "Landing Site")
        val migrated = bridge.sourceText()
        val room = project.rooms.getValue("91F8")
        room.states.add(0, room.states.first().copy(id = "unexpected"))
        assertFalse(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(migrated, bridge.sourceText())
        assertTrue(bridge.isManaged(0x91F8))
    }

    @Test fun `GUI and source edit one document with shared undo and no GUI deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.link(0x8000)
        val first = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, first, 0, first.entries[0].copy(x = 4))
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("${'$'}04"))
        assertTrue(project.rooms.isEmpty(), "No second object model/delta stream may be persisted")
        assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(2, bridge.view(0x8000).document?.entries?.first()?.x)
        assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE))
        assertEquals(4, bridge.view(0x8000).document?.entries?.first()?.x)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, workspace.projectSourceText(PLM_SOURCE_FILE).replace("${'$'}04", "${'$'}07"))
        assertEquals(7, bridge.view(0x8000).document?.entries?.first()?.x)
        assertFailsWith<IllegalArgumentException> { bridge.update(0x8000, first, 0, first.entries[0].copy(x = 9)) }
        assertEquals(7, bridge.view(0x8000).document?.entries?.first()?.x)
    }

    @Test fun `ASM edits acquire authority automatically and undo releases it without linking`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        assertEquals(AsmPlmAuthority.UNCHANGED, bridge.authority(0x8000).authority)
        assertFalse(bridge.isLinked(0x8000))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}06"))
        assertEquals(AsmPlmAuthority.SOURCE, bridge.authority(0x8000).authority)
        assertEquals(6, bridge.view(0x8000).document?.entries?.first()?.x)
        assertTrue(bridge.isLinked(0x8000))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty(), "Automatic ownership is derived, not another saved toggle")
        assertEquals(listOf(bridge.catalog.binding(0x8000)), bridge.catalog.sourceBindings(project,
            workspace.projectSourceText(PLM_SOURCE_FILE), macros))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        assertFalse(bridge.isLinked(0x8000))
        assertEquals(2, bridge.view(0x8000).document?.entries?.first()?.x)
    }

    @Test fun `opening fields does not take ownership and applying an edit needs no Link step`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val first = assertNotNull(bridge.view(0x8000).document)
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        bridge.update(0x8000, first, 0, first.entries[0].copy(y = 12))
        assertEquals(AsmPlmAuthority.SOURCE, bridge.authority(0x8000).authority)
        assertTrue(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertTrue(workspace.saveAllSources())
        val reopened = AsmWorkspaceState(projectRepository = AsmProjectWorkspaceRepository(emptyList(), emptyList()))
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val restored = AsmPlmSourceBridge(bridge.catalog, reopened) { project }
        assertTrue(restored.isLinked(0x8000))
        assertEquals(12, restored.view(0x8000).document?.entries?.first()?.y)
    }

    @Test fun `GUI additions and rewritten states own unchanged source without consuming their deltas`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        val edits = project.getOrCreateRoom(0x91F8)
        val item = PlmChange("add", 0xEEDF, 8, 9, 12)
        edits.plmChanges += item
        assertEquals(AsmPlmAuthority.ROOM_EDITOR, bridge.authority(0x8000).authority)
        assertFalse(bridge.isLinked(0x8000))
        assertNull(bridge.view(0x8000).document, "Original ASM must never overwrite the effective GUI preview")
        assertTrue(bridge.catalog.sourceBindings(project, source, macros).isEmpty())
        edits.stateGraphChanged = true
        assertTrue(bridge.authority(0x8000).explanation.orEmpty().contains("state graph"))
        assertTrue(bridge.catalog.sourceBindings(project, source, macros).isEmpty())
        assertEquals(listOf(item), edits.plmChanges)
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
    }

    @Test fun `both changed is an explicit conflict even before a room was opened or linked`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDF, 8, 9, 12)
        val changed = source.replace("${'$'}02", "${'$'}09")
        val failure = assertFailsWith<IllegalArgumentException> { bridge.catalog.sourceBindings(project, changed, macros) }
        assertTrue(failure.message.orEmpty().contains("Both versions are retained"))
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertEquals(1, project.getOrCreateRoom(0x91F8).plmChanges.size)
    }

    @Test fun `comment and literal spelling changes do not take objects away from room editing`() = runBlocking {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDF, 8, 9, 12)
        val reformatted = source.replace("${'$'}02", "%00000010").replace("pickup", "different comment")
        assertTrue(bridge.catalog.sourceBindings(project, reformatted, macros).isEmpty())
    }

    @Test fun `invalid and growing unlinked ASM lists block automatic builds rather than hide behind GUI mode`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        assertFails { bridge.catalog.sourceBindings(project, source.replace("${'$'}02", "${'$'}02+"), macros) }
        val growing = source.replace("    dw", "    %PLMPopEntry(1, 2, 3, 4)\n    dw")
        val problem = assertFailsWith<IllegalArgumentException> { bridge.catalog.sourceBindings(project, growing, macros) }
        assertTrue(problem.message.orEmpty().contains("relocation"))
    }

    @Test fun `source owned collection bits and invalid declaration navigation do not require opening the room`() = runBlocking {
        val (workspace, bridge) = fixture()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}0005", "${'$'}0100"))
        assertEquals(listOf(0x100), bridge.sourceOwnedEntries().map { it.param }.toList())
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertEquals(0, bridge.sourceLine(0x8000))
        assertNull(bridge.sourceLine(0x8006))
    }

    @Test fun `placement navigation uses live source and selected state without acquiring ownership`() = runBlocking<Unit> {
        val (workspace, bridge, project) = fixture()
        val entry = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
        for (stateIndex in 0..1) {
            val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x91F8, stateIndex, 0x8000, entry))
            assertEquals(1, target.lineIndex)
        }
        assertTrue(project.rooms.isEmpty())
        assertTrue(project.asmWorkspace.plmSourceBindings.isEmpty())
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 2, 0x8000, entry))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0xB5D5, 0, 0x8000, entry))
        workspace.updateSourceEditText(PLM_SOURCE_FILE, "; inserted line\n" + source.replace("${'$'}02", "${'$'}09"))
        val target = assertIs<AsmPlmPlacementTarget.Source>(bridge.placementTarget(0x91F8, 0, 0x8000, entry.copy(x = 9)))
        assertEquals(2, target.lineIndex)
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, entry))
    }

    @Test fun `GUI owned and conflicting placements cannot navigate to stale original source`() = runBlocking<Unit> {
        val (workspace, bridge, project) = fixture()
        val original = RomParser.PlmEntry(0xEEDB, 2, 3, 5)
        bridge.view(0x8000) // Populate last-valid preview, which navigation must never use.
        val edits = project.getOrCreateRoom(0x91F8)
        edits.plmChanges += PlmChange("add", 0xEEDB, 8, 9, 12)
        for (entry in listOf(original, original.copy(x = 8, y = 9, param = 12))) {
            val target = assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, entry))
            assertContains(target.reason, "room editor")
        }
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}04"))
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, original.copy(x = 4)))
        edits.plmChanges.clear()
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertNotNull(bridge.view(0x8000).document, "A last-valid preview exists, but is not current source")
        assertIs<AsmPlmPlacementTarget.Unavailable>(bridge.placementTarget(0x91F8, 0, 0x8000, original))
    }

    @Test fun `placement inspection survives back forward with its original room state and no file writes`() = runBlocking {
        val (workspace, _, _) = fixture()
        workspace.openSource(PLM_SOURCE_FILE, 1)
        val sourceLocation = workspace.locationSnapshot()
        val snapshot = AsmPlmPlacementSnapshot(0x91F8, "Landing Site", "Else / default",
            RomParser.PlmEntry(0xEEDB, 8, 9, 12), "Room editor data")
        workspace.openPlmPlacement(snapshot)
        val placementLocation = AsmWorkspaceLocation.PlmPlacement(snapshot)
        assertEquals(placementLocation, workspace.locationSnapshot())
        workspace.openSource(PLM_SOURCE_FILE, 0)
        workspace.goBack()
        assertEquals(placementLocation, workspace.locationSnapshot())
        assertEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode)
        workspace.goBack()
        assertEquals(sourceLocation, workspace.locationSnapshot())
        workspace.goForward()
        assertEquals(placementLocation, workspace.locationSnapshot())
        assertEquals(source, workspace.projectSourceText(PLM_SOURCE_FILE))
        assertFalse(workspace.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
        workspace.bindProject(File(directory, "Different.smedit").path, false)
        assertNull(workspace.plmPlacementSnapshot)
        assertEquals(AsmBrowserMode.SOURCE, workspace.browserMode)
        repeat(4) { workspace.goBack(); assertNotEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode) }
        repeat(4) { workspace.goForward(); assertNotEquals(AsmBrowserMode.PLM_PLACEMENT, workspace.browserMode) }
    }

    @Test fun `invalid draft keeps last valid preview and blocks GUI writes without discarding source`() = runBlocking {
        val (workspace, bridge) = fixture()
        bridge.link(0x8000)
        val first = assertNotNull(bridge.view(0x8000).document)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("${'$'}02", "${'$'}02+"))
        assertNotNull(bridge.view(0x8000).problem)
        assertEquals(first.entries, bridge.view(0x8000).document?.entries)
        assertFails { bridge.update(0x8000, first, 0, first.entries[0].copy(x = 6)) }
        assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("${'$'}02+"))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        assertNull(bridge.view(0x8000).problem)
    }

    @Test fun `ownership refuses existing GUI overrides and later override conflicts`() = runBlocking<Unit> {
        val (_, bridge, project) = fixture()
        project.getOrCreateRoom(0x91F8).plmChanges += PlmChange("add", 0xEEDB, 2, 2, 1)
        assertFails { bridge.link(0x8000) }
        project.rooms.clear()
        bridge.link(0x8000)
        project.getOrCreateRoom(0x91F8).stateGraphChanged = true
        assertNotNull(bridge.view(0x8000).problem)
    }

    @Test fun `list growth and macro layout changes cannot masquerade as supported GUI data`() = runBlocking {
        val (workspace, bridge) = fixture()
        bridge.link(0x8000)
        workspace.updateSourceEditText(PLM_SOURCE_FILE, source.replace("    dw", "    %PLMPopEntry(1, 2, 3, 4)\n    dw"))
        assertTrue(bridge.view(0x8000).problem.orEmpty().contains("relocation"))
        workspace.undoSourceEdit(PLM_SOURCE_FILE)
        workspace.updateSourceEditText("macros.asm", macros.replace("dw <Param>", "dl <Param>"))
        assertTrue(bridge.view(0x8000).problem.orEmpty().contains("custom layout"))
    }

    @Test fun `save and reopen retain source authority and reject stale disk writes`() = runBlocking {
        val (workspace, bridge, project) = fixture()
        bridge.link(0x8000)
        val before = assertNotNull(bridge.view(0x8000).document)
        bridge.update(0x8000, before, 0, before.entries[0].copy(x = 8))
        assertTrue(workspace.saveAllSources())
        val encoded = Json.encodeToString(SmEditProject.serializer(), project)
        val restored = Json.decodeFromString(SmEditProject.serializer(), encoded)
        assertEquals(project.asmWorkspace.plmSourceBindings, restored.asmWorkspace.plmSourceBindings)
        assertTrue(restored.rooms.isEmpty())
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        val reopened = AsmWorkspaceState(projectRepository = repository)
        reopened.bindProject(File(directory, "Test.smedit").path, true)
        val next = AsmPlmSourceBridge(bridge.catalog, reopened) { restored }
        assertEquals(8, next.view(0x8000).document?.entries?.first()?.x)
        assertFails { next.unlink(0x8000) }
        val disk = File(reopened.projectWorkspace!!.workingDirectory, "src/$PLM_SOURCE_FILE")
        disk.appendText("; external edit\n")
        reopened.updateSourceEditText(PLM_SOURCE_FILE, reopened.projectSourceText(PLM_SOURCE_FILE).replace("${'$'}08", "${'$'}09"))
        assertFalse(reopened.saveSource(PLM_SOURCE_FILE))
        assertTrue(disk.readText().endsWith("; external edit\n"))
        assertTrue(reopened.hasUnsavedSourceChanges(PLM_SOURCE_FILE))
    }
}
