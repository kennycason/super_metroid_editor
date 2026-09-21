package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.projectRoomStateCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomStatePreviewTest {
    @Test
    fun `state authoring adds reorders previews and deletes a conditional branch`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId) ?: return
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(3, parser)
        val roomEdits = editor.project.rooms.getValue("91F8")
        val template = roomEdits.states.last()

        val addedId = editor.addRoomState(
            template.id,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0E),
            parser,
        )
        val added = roomEdits.states.single { it.id == addedId }

        assertEquals(5, roomEdits.states.size)
        assertEquals(template.sourceStateIndex, added.templateSourceStateIndex)
        assertEquals(template.resources, added.resources)
        assertEquals(addedId, roomEdits.states[roomEdits.states.lastIndex - 1].id)
        assertEquals(ProjectRoomStateConditionKind.DEFAULT, roomEdits.states.last().condition.kind)
        assertTrue(roomEdits.stateGraphChanged)

        editor.switchRoomState(addedId, parser)
        assertEquals(addedId, editor.currentStateId)
        assertEquals(template.sourceStateIndex, editor.currentStateIndex)

        assertTrue(editor.moveRoomState(addedId, -1, parser))
        assertFalse(editor.moveRoomState(roomEdits.states.last().id, -1, parser))
        editor.setRoomStateCondition(
            addedId,
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0xA18C),
            parser,
        )
        assertEquals(ProjectRoomStateConditionKind.INCOMING_DOOR, added.condition.kind)

        val nextId = editor.deleteRoomState(addedId, parser)
        assertEquals(4, roomEdits.states.size)
        assertTrue(roomEdits.states.any { it.id == nextId })
        assertThrows(IllegalArgumentException::class.java) {
            editor.deleteRoomState(roomEdits.states.last().id, parser)
        }
    }

    @Test
    fun `state resource sharing stays compact and details use condition names`() {
        val names = listOf(
            "Zebes timebomb is set",
            "Power Bombs collected",
            "Zebes is awake",
            "Default",
        )

        assertEquals(
            "Same in all 4 states",
            describeStateResourceSharing(names, listOf(0, 1, 2, 3)),
        )
        assertEquals(
            "Same in 3 of 4 states",
            describeStateResourceSharing(names, listOf(1, 2, 3)),
        )
        assertEquals(
            "Separate for this state",
            describeStateResourceSharing(names, listOf(0)),
        )
        assertEquals(
            "Power Bombs collected, Zebes is awake, Default",
            describeStateResourceMembers(names, listOf(1, 2, 3)),
        )
    }

    @Test
    fun `layer 2 motion decodes independent X and Y engine bytes`() {
        assertEquals("X: follows camera (1×); Y: follows camera (1×)", describeLayer2Scrolling(0x0000))
        assertEquals("X: fixed; Y: fixed", describeLayer2Scrolling(0x0101))
        assertEquals(
            "X: 1/2× camera speed, no tilemap streaming; Y: fixed",
            describeLayer2Scrolling(0x0181),
        )
        assertEquals(
            "X: 3/4× camera speed, no tilemap streaming; Y: 3/4× camera speed, no tilemap streaming",
            describeLayer2Scrolling(0xC1C1),
        )
    }

    @Test
    fun `state comparison groups related pointer fields into user-facing changes`() {
        val baseline = mapOf(
            "tileset" to 0,
            "musicData" to 6,
            "musicTrack" to 5,
            "fxPtr" to 0x9000,
            "levelDataPtr" to 0xC2C2BB,
            "bgDataPtr" to 0xB76A,
            "plmSetPtr" to 0x8000,
            "enemySetPtr" to 0x883D,
            "enemyGfxPtr" to 0x8193,
            "bgScrolling" to 0x0181,
            "roomScrollsPtr" to 0x9283,
            "xraySpecialCasingPtr" to 0,
            "mainAsmPtr" to 0xC116,
            "setupAsmPtr" to 0xC124,
        )
        val escape = baseline + mapOf(
            "musicData" to 0,
            "musicTrack" to 0,
            "fxPtr" to 0x9010,
            "plmSetPtr" to 0x8026,
            "enemySetPtr" to 0x8900,
            "enemyGfxPtr" to 0x8200,
            "mainAsmPtr" to 0xC200,
            "setupAsmPtr" to 0xC210,
        )

        assertEquals(
            listOf("Music", "Effects", "Placed Objects", "Enemy Actors", "Enemy Graphics", "Room Logic"),
            changedRoomStateSections(escape, baseline),
        )
        assertTrue(changedRoomStateSections(baseline, baseline).isEmpty())
    }

    @Test
    fun `Landing Site escape state reports the six semantic differences visible in the editor`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val states = parser.inspectRoomStates(0x91F8).states
        val stateData = states.map { state ->
            state.stateDataPcOffset?.let(parser::readStateData) ?: emptyMap()
        }

        assertEquals(
            listOf("Music", "Effects", "Placed Objects", "Enemy Actors", "Enemy Graphics", "Room Logic"),
            changedRoomStateSections(stateData.first(), stateData.last()),
        )
    }

    @Test
    fun `preview switches all state resources and returns to the default state`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0xCD13 // Phantoon's Room: pre-defeat tileset 4, post-defeat/default tileset 5
        val room = parser.readRoomHeader(roomId) ?: return
        val states = parser.parseRoomStatesWithData(roomId)
        assertEquals(2, states.size)
        assertEquals(listOf(4, 5), states.map { it.tileset })

        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        assertEquals(0, editor.currentStateIndex)
        assertEquals(4, editor.currentTilesetId)
        assertEquals(parser.parsePlmSet(states[0].plmSetPtr), editor.workingPlms)

        editor.switchRoomState(0, parser)
        assertEquals(0, editor.currentStateIndex)
        assertEquals(4, editor.currentTilesetId)
        assertTrue(
            parser.decompressLZ2(states[0].levelDataPtr).contentEquals(editor.workingLevelData!!),
            "conditional preview should load that state's level data",
        )
        assertEquals(parser.parsePlmSet(states[0].plmSetPtr), editor.workingPlms)
        assertEquals(parser.parseEnemyPopulation(states[0].enemySetPtr), editor.workingEnemies)

        editor.switchRoomState(1, parser)
        assertEquals(1, editor.currentStateIndex)
        assertEquals(5, editor.currentTilesetId)
        assertTrue(
            parser.decompressLZ2(states[1].levelDataPtr).contentEquals(editor.workingLevelData!!),
            "returning to default should restore the default state's level data",
        )
    }

    @Test
    fun `canvas edits are stored on the selected state instead of the whole room`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0xCD13
        val room = parser.readRoomHeader(roomId) ?: return
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)

        editor.switchRoomState(0, parser)
        editor.addEnemy(0xD07F, 32, 48)
        editor.setScroll(0, 0, if (editor.workingScrolls[0] == 0) 1 else 0, room.width)

        val roomEdits = editor.project.rooms.getValue("CD13")
        val firstState = roomEdits.states.single { it.sourceStateIndex == 0 }
        assertTrue(roomEdits.enemyChanges.isEmpty())
        assertTrue(roomEdits.scrollChanges.isEmpty())
        assertEquals(1, firstState.enemyChanges.size)
        assertEquals(1, firstState.scrollChanges.size)

        editor.switchRoomState(1, parser)
        editor.addEnemy(0xD0BF, 64, 80)

        val defaultState = roomEdits.states.single { it.sourceStateIndex == 1 }
        assertEquals(1, firstState.enemyChanges.size)
        assertEquals(1, defaultState.enemyChanges.size)
        assertEquals(0xD07F, firstState.enemyChanges.single().enemyId)
        assertEquals(0xD0BF, defaultState.enemyChanges.single().enemyId)
    }

    @Test
    fun `preview replays existing common room edits without recording a new edit`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0xCD13
        val room = parser.readRoomHeader(roomId) ?: return
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)

        val x = 2
        val y = 2
        val oldWord = editor.readBlockWord(x, y)
        val oldBts = editor.readBts(x, y)
        val newWord = oldWord xor 0x0001
        editor.applyBulkEdits(
            "state preview fixture",
            listOf(TileEdit(x, y, oldWord, newWord, oldBts, oldBts)),
        )
        val operationsBefore = editor.project.rooms.getValue("CD13").operations.toList()

        editor.switchRoomState(0, parser)

        assertEquals(newWord, editor.readBlockWord(x, y))
        assertEquals(operationsBefore, editor.project.rooms.getValue("CD13").operations)
    }

    @Test
    fun `selected state is applied to every state-owned canvas resource`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId) ?: return
        val states = parser.parseRoomStatesWithData(roomId)
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)

        editor.switchRoomState(0, parser)
        val escapeRoom = editor.applyCurrentStateData(room, parser)
        val escapeState = states.first()

        assertEquals(escapeState.fxPtr, escapeRoom.fxPtr)
        assertEquals(escapeState.bgDataPtr, escapeRoom.bgDataPtr)
        assertEquals(escapeState.bgScrolling, escapeRoom.bgScrolling)
        assertEquals(escapeState.xraySpecialCasingPtr, escapeRoom.xraySpecialCasingPtr)
        assertEquals(escapeState.mainAsmPtr, escapeRoom.mainAsmPtr)
        assertEquals(escapeState.setupAsmPtr, escapeRoom.setupAsmPtr)
        assertEquals(room.width, escapeRoom.width, "shared header fields must remain unchanged")
    }
}
