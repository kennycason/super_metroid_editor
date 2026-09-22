package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.rom.ProjectRoomExporter
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.projectRoomStateCondition
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewRoomCreationTest {
    @Test
    fun `incoming-door state conditions resolve workspace DoorDefs at build time`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("Door State Lab", false, area = 0, mapX = 4, mapY = 4, width = 1, height = 1, tileset = 0),
            source,
        )
        var workspace = editor.prepareWorkspaceParser(source)
        var room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        editor.addProjectRoomDoor(definition.previewRoomId, 0x91F8, 1, 0, 0, workspace)
        workspace = editor.prepareWorkspaceParser()
        room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        val previewDoorDef = definition.previewDoorDefPtrs.single()
        editor.addRoomState(
            "state-1",
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, previewDoorDef),
            workspace,
        )

        val output = source.copyRomData()
        val outputParser = RomParser(output)
        val allocator = RomFreeSpaceAllocator(output, outputParser::snesToPc, outputParser::pcToSnes, guardBytes = 2)
        requireNotNull(allocator.reserve(17, listOf(0x83), "test allocation shift"))
        val result = ProjectRoomExporter(
            editor.project,
            outputParser,
            output,
            freeSpaceAllocator = allocator,
        ).exportRooms()
        val physicalDoorDef = result.newRoomDoorDefPtrs.getValue(definition.id).single()
        val inspection = RomParser(output).inspectRoomStates(result.newRoomIds.getValue(definition.id))

        assertNotEquals(previewDoorDef, physicalDoorDef)
        assertEquals(physicalDoorDef, inspection.states.first().condition.argument)
    }

    @Test
    fun `new room uses the existing relocatable state authoring pipeline`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("State Lab", false, area = 0, mapX = 3, mapY = 3, width = 1, height = 1, tileset = 0),
            source,
        )
        val workspace = editor.prepareWorkspaceParser(source)
        val room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        editor.addRoomState(
            templateStateId = "state-1",
            condition = projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0E),
            romParser = workspace,
        )

        val output = source.copyRomData()
        val result = ProjectRoomExporter(editor.project, RomParser(output), output).exportRooms()
        val exported = RomParser(output)
        val inspection = exported.inspectRoomStates(result.newRoomIds.getValue(definition.id))

        assertTrue(inspection.isComplete)
        assertEquals(2, inspection.states.size)
        assertEquals(ProjectRoomStateConditionKind.EVENT_SET.name, inspection.states.first().condition.kind.name)
        assertTrue(inspection.states.last().condition.isDefault)
    }

    @Test
    fun `blank room becomes immediately editable in the isolated workspace`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest(
                name = "Test Chamber",
                cloneCurrentRoom = false,
                area = 1,
                mapX = 10,
                mapY = 6,
                width = 2,
                height = 1,
                tileset = 5,
            ),
            source,
        )

        val workspace = editor.prepareWorkspaceParser(source)
        val room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)

        assertEquals("room-1", definition.id)
        assertEquals(2, room.width)
        assertEquals(1, room.height)
        assertEquals(5, room.tileset)
        assertEquals(32, editor.workingBlocksWide)
        assertEquals(16, editor.workingBlocksTall)
        assertTrue(editor.workingPlms.isEmpty())
        assertTrue(editor.workingEnemies.isEmpty())
        assertTrue(editor.doorEntries.isEmpty())
        assertTrue(editor.canEditEmbeddedLayer2())
    }

    @Test
    fun `clone snapshots the visible state and optionally copies independent doors`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val sourceId = 0x91F8
        val sourceRoom = requireNotNull(source.readRoomHeader(sourceId))
        val editor = EditorState().also { it.testMode = true }
        editor.loadRoom(sourceId, source, sourceRoom)
        val visibleLevel = requireNotNull(editor.workingLevelData).copyOf()
        val visibleDoors = editor.doorEntries.size

        val definition = editor.createNewRoom(
            NewRoomCreationRequest(
                name = "Landing Site Variant",
                cloneCurrentRoom = true,
                copyDoors = true,
                area = sourceRoom.area,
                mapX = 1,
                mapY = 1,
                width = 1,
                height = 1,
                tileset = 0,
            ),
            source,
        )
        val workspace = editor.prepareWorkspaceParser(source)
        val clone = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))

        assertNotEquals(sourceId, definition.previewRoomId)
        assertEquals(sourceRoom.width, clone.width)
        assertEquals(sourceRoom.height, clone.height)
        assertEquals(visibleDoors, workspace.parseDoorList(clone.doorOut).size)
        assertContentEquals(visibleLevel, workspace.decompressLZ2(clone.levelDataPtr))
        if (visibleDoors > 0) {
            val originalPtr = source.parseDoorList(sourceRoom.doorOut).first().doorDefPtr
            val clonedPtr = workspace.parseDoorList(clone.doorOut).first().doorDefPtr
            assertNotEquals(originalPtr, clonedPtr, "cloned rooms must own their DoorDefs")
        }
    }

    @Test
    fun `project room door list grows and shrinks without raw pointers`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("Door Lab", false, area = 0, mapX = 2, mapY = 2, width = 1, height = 1, tileset = 0),
            source,
        )
        var workspace = editor.prepareWorkspaceParser(source)
        var room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)

        val index = editor.addProjectRoomDoor(definition.previewRoomId, 0x91F8, 1, 0, 0, workspace)
        assertEquals(0, index)
        workspace = editor.prepareWorkspaceParser()
        room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        assertEquals(0x91F8, editor.doorEntries.single().destRoomPtr)
        assertTrue(workspace.findDoorsLeadingTo(0x91F8).any { it.doorDefPtr == definition.previewDoorDefPtrs.single() })

        editor.removeProjectRoomDoor(definition.previewRoomId, 0)
        workspace = editor.prepareWorkspaceParser()
        room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        assertFalse(editor.doorEntries.isNotEmpty())
    }

    @Test
    fun `new room connection can target another area and failed UI add can roll back`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("Cross Area Lab", false, area = 0, mapX = 2, mapY = 2, width = 1, height = 1, tileset = 0),
            source,
        )
        val workspace = editor.prepareWorkspaceParser(source)
        val room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        val destination = workspace.roomCatalog.rooms.firstNotNullOf { info ->
            workspace.readRoomHeader(info.getRoomIdAsInt())
                ?.takeIf { it.area != definition.header.area }
                ?.roomId
        }

        val index = editor.addProjectRoomDoor(definition.previewRoomId, destination, 1, 0, 0, workspace)

        assertEquals(0, index)
        assertTrue(editor.doorEntries.single().bitflag and 0x40 != 0)
        editor.rollbackAddedProjectRoomDoor(definition.previewRoomId, index)
        assertTrue(definition.doors.isEmpty())
        assertTrue(editor.doorEntries.isEmpty())
    }

    @Test
    fun `creating a connection from a door tile relinks the whole doorway pattern`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("Tile Door Lab", false, area = 0, mapX = 2, mapY = 2, width = 1, height = 1, tileset = 0),
            source,
        )
        val workspace = editor.prepareWorkspaceParser(source)
        val room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        repeat(4) { y ->
            editor.setTileProperties(0, y, 0x9, 0)
            editor.setTileProperties(15, y, 0x9, 0)
        }
        editor.addProjectRoomDoor(definition.previewRoomId, 0x91F8, 1, 0, 0, workspace)
        val second = editor.addProjectRoomDoor(definition.previewRoomId, 0x92B3, 0, 0, 0, workspace)

        assertTrue(editor.isDoorConnectionSharedOutsideDoorway(15, 1, 0))
        assertEquals(1, editor.suggestedDoorDirection(0, 1))
        assertEquals(0, editor.suggestedDoorDirection(15, 1))
        val linked = editor.linkDoorwayTilesToConnection(15, 1, second)

        val level = requireNotNull(editor.workingLevelData)
        val layer1Size = (level[0].toInt() and 0xFF) or ((level[1].toInt() and 0xFF) shl 8)
        val btsStart = 2 + layer1Size
        assertEquals(4, linked)
        repeat(4) { y ->
            assertEquals(0, level[btsStart + y * 16].toInt() and 0xFF)
            assertEquals(second, level[btsStart + y * 16 + 15].toInt() and 0xFF)
        }
    }

    @Test
    fun `destination picker and diagnostics see unsaved doorway edits in a new room`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val destination = editor.createNewRoom(
            NewRoomCreationRequest(
                "Edited Destination", false, area = 0, mapX = 2, mapY = 2,
                width = 1, height = 1, tileset = 0,
            ),
            source,
        )
        var workspace = editor.prepareWorkspaceParser(source)
        editor.loadRoom(
            destination.previewRoomId,
            workspace,
            requireNotNull(workspace.readRoomHeader(destination.previewRoomId)),
        )
        repeat(4) { offset ->
            editor.setTileProperties(0, 4 + offset, 0x9, 0)
        }

        workspace = editor.prepareWorkspaceParser()
        val landingSite = requireNotNull(workspace.readRoomHeader(0x91F8))
        editor.loadRoom(0x91F8, workspace, landingSite)
        val openings = editor.effectiveDoorwayOpenings(
            destination.previewRoomId, direction = 0, romParser = workspace,
        )

        assertEquals(1, openings.size)
        assertEquals(0, openings.single().screenX)
        assertEquals(0, openings.single().screenY)
        assertEquals(0x0401, openings.single().doorCapCode)
        assertEquals(4, openings.single().tileCount)

        val sourceRoom = editor.createNewRoom(
            NewRoomCreationRequest(
                "Door Source", false, area = 0, mapX = 4, mapY = 2,
                width = 1, height = 1, tileset = 0,
            ),
            workspace,
        )
        workspace = editor.prepareWorkspaceParser()
        editor.loadRoom(
            sourceRoom.previewRoomId,
            workspace,
            requireNotNull(workspace.readRoomHeader(sourceRoom.previewRoomId)),
        )
        editor.addProjectRoomDoor(
            sourceRoom.previewRoomId,
            destination.previewRoomId,
            direction = 0,
            screenX = 0,
            screenY = 0,
            romParser = workspace,
        )

        assertEquals(0x0401, editor.doorEntries.single().doorCapCode)
        workspace = editor.prepareWorkspaceParser()
        val diagnostic = requireNotNull(
            doorDiagnosticsForRoom(
                sourceRoom.previewRoomId,
                workspace,
                editor,
                workspace.roomCatalog.rooms,
            )[0],
        )
        assertFalse(
            diagnostic.issues.any {
                it.kind == DoorConnectionIssueKind.DESTINATION_OPENING_MISSING
            },
        )
    }

    @Test
    fun `door removal preserves surviving condition identity and blocks referenced deletion`() {
        val source = TestRomHelper.loadRomParser() ?: return
        val editor = EditorState().also { it.testMode = true }
        val definition = editor.createNewRoom(
            NewRoomCreationRequest("Door Identity Lab", false, area = 0, mapX = 2, mapY = 2, width = 1, height = 1, tileset = 0),
            source,
        )
        var workspace = editor.prepareWorkspaceParser(source)
        var room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        editor.addProjectRoomDoor(definition.previewRoomId, 0x91F8, 1, 0, 0, workspace)
        editor.addProjectRoomDoor(definition.previewRoomId, 0x92B3, 0, 0, 0, workspace)
        workspace = editor.prepareWorkspaceParser()
        room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        val survivingDoorBeforeRemoval = definition.previewDoorDefPtrs[1]
        val conditionState = editor.addRoomState(
            "state-1",
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, survivingDoorBeforeRemoval),
            workspace,
        )

        editor.removeProjectRoomDoor(definition.previewRoomId, 0)
        workspace = editor.prepareWorkspaceParser()
        room = requireNotNull(workspace.readRoomHeader(definition.previewRoomId))
        editor.loadRoom(definition.previewRoomId, workspace, room)
        val survivingDoorAfterRemoval = definition.previewDoorDefPtrs.single()
        val condition = requireNotNull(editor.project.rooms[editor.project.roomKey(definition.previewRoomId)])
            .states.first { it.id == conditionState }.condition

        assertEquals(survivingDoorAfterRemoval, condition.argument)
        assertFailsWith<IllegalArgumentException> {
            editor.removeProjectRoomDoor(definition.previewRoomId, 0)
        }
        assertEquals(1, definition.doors.size)
    }
}
