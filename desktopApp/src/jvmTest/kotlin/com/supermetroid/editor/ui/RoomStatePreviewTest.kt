package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.rom.projectRoomStateCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomStatePreviewTest {
    @Test
    fun `first edit can explicitly fork a complete unique layout`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )

        editor.beginStroke()
        assertFalse(editor.paintAt(0, 0))
        editor.endStroke()

        assertEquals(oldWord, editor.readBlockWord(0, 0), "the edit must wait for a scope choice")
        assertTrue(editor.pendingLayoutEditScope?.requiresScopeChoice == true)
        editor.resolvePendingLayoutEditScope(LayoutEditScope.THIS_STATE)

        assertEquals(newWord, editor.readBlockWord(0, 0))
        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        val state = roomEdits.states.first()
        assertTrue(state.operations.isEmpty())
        assertEquals(1, roomEdits.states.count { it.resources.level == state.resources.level })
        assertEquals(1, roomEdits.levelResourceOperations.getValue(state.resources.level).size)
    }

    @Test
    fun `legacy state tile overlays migrate to complete unique layout resources`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states.map { state -> state.levelDataPtr }.distinct().size < states.size
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        val roomEdits = editor.project.getOrCreateRoom(roomId)
        roomEdits.ensureStateManifest(parser)
        val target = roomEdits.states.single { it.sourceStateIndex == editor.currentStateIndex }
        val originalResource = target.resources.level
        val oldWord = editor.readBlockWord(0, 0)
        val replacement = oldWord xor 1
        target.operations += EditOperation(
            "legacy private overlay",
            listOf(TileEdit(0, 0, oldWord, replacement, editor.readBts(0, 0), editor.readBts(0, 0))),
        )

        editor.loadRoom(roomId, parser, room)

        assertEquals(replacement, editor.readBlockWord(0, 0))
        assertFalse(target.resources.level == originalResource)
        assertEquals(1, roomEdits.states.count { it.resources.level == target.resources.level })
        assertTrue(target.operations.none { it.edits.isNotEmpty() })
        assertTrue(roomEdits.levelResourceOperations.getValue(target.resources.level).single().edits.isNotEmpty())
    }

    @Test
    fun `shared layout choice replays one resource edit in every linked state`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val context = checkNotNull(editor.currentLayoutEditingContext())
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )

        editor.beginStroke()
        editor.paintAt(0, 0)
        editor.endStroke()
        editor.resolvePendingLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)

        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        assertEquals(1, roomEdits.levelResourceOperations.getValue(context.resourceId).size)
        assertTrue(roomEdits.states.first().operations.isEmpty())
        val peerId = context.sharingStateIds.first { it != context.stateId }
        editor.switchRoomState(peerId, parser)
        assertEquals(newWord, editor.readBlockWord(0, 0))
        assertEquals(LayoutEditScope.ALL_SHARING_STATES, editor.currentLayoutEditingContext()?.scope)
    }

    @Test
    fun `shared pattern paint keeps its PLM state-owned through undo and redo`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val context = checkNotNull(editor.currentLayoutEditingContext())
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
                plmOverrides = mapOf(0L to (0xC8A2 to 0)),
            )
        )

        editor.beginStroke()
        assertTrue(editor.paintAt(0, 0))
        editor.endStroke()

        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        val state = roomEdits.states.single { it.id == context.stateId }
        val sharedOperation = roomEdits.levelResourceOperations.getValue(context.resourceId).single()
        assertTrue(sharedOperation.edits.isNotEmpty())
        assertTrue(sharedOperation.plmAdds.isEmpty(), "shared layout records must never own PLMs")
        assertTrue(state.operations.single().edits.isEmpty())
        assertEquals(1, state.operations.single().plmAdds.size)

        assertTrue(editor.undo())
        assertTrue(roomEdits.levelResourceOperations[context.resourceId].isNullOrEmpty())
        assertTrue(state.operations.isEmpty())
        assertTrue(editor.redo())
        assertTrue(roomEdits.levelResourceOperations.getValue(context.resourceId).single().plmAdds.isEmpty())
        assertTrue(state.operations.single().edits.isEmpty())
    }

    @Test
    fun `unique layout edits can be reverted without changing ownership`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )
        editor.beginStroke()
        assertTrue(editor.paintAt(0, 0))
        editor.endStroke()
        assertEquals(newWord, editor.readBlockWord(0, 0))

        assertTrue(editor.revertCurrentStateLayoutEdits(parser))

        assertEquals(oldWord, editor.readBlockWord(0, 0))
        assertFalse(checkNotNull(editor.currentLayoutEditingContext()).isShared)
        assertFalse(editor.currentLayoutEditingContext()?.hasResourceEdits == true)
    }

    @Test
    fun `selected tiles make each target layout unique`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val context = checkNotNull(editor.currentLayoutEditingContext())
        val peerId = context.sharingStateIds.first { it != context.stateId }
        editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )
        editor.beginStroke()
        editor.paintAt(0, 0)
        editor.endStroke()
        editor.mapSelStart = 0 to 0
        editor.mapSelEnd = 0 to 0

        assertEquals(1, editor.applySelectionToStates(setOf(peerId), parser))
        editor.switchRoomState(peerId, parser)

        assertEquals(newWord, editor.readBlockWord(0, 0))
        assertFalse(checkNotNull(editor.currentLayoutEditingContext()).isShared)
        assertTrue(editor.currentLayoutEditingContext()?.hasResourceEdits == true)
    }

    @Test
    fun `complete layout can be copied from another state`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            if (states.size < 2 || states[0].levelDataPtr == states[1].levelDataPtr) return@mapNotNull null
            val first = runCatching { parser.decompressLZ2(states[0].levelDataPtr) }.getOrNull()
                ?: return@mapNotNull null
            val second = runCatching { parser.decompressLZ2(states[1].levelDataPtr) }.getOrNull()
                ?: return@mapNotNull null
            Triple(roomId, room, states).takeIf { !first.contentEquals(second) }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val targetBefore = IntArray(editor.workingBlocksWide * editor.workingBlocksTall) { index ->
            editor.readBlockWord(index % editor.workingBlocksWide, index / editor.workingBlocksWide)
        }
        val changed = editor.copyLayoutFromState(
            checkNotNull(editor.currentLayoutEditingContext()).allStateIds[1],
            parser,
        )

        assertTrue(changed > 0)
        val targetAfter = IntArray(editor.workingBlocksWide * editor.workingBlocksTall) { index ->
            editor.readBlockWord(index % editor.workingBlocksWide, index / editor.workingBlocksWide)
        }
        assertFalse(targetBefore.contentEquals(targetAfter))
    }

    @Test
    fun `state can explicitly share another states complete layout`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf {
                states.size > 1 && states[0].levelDataPtr != states[1].levelDataPtr
            }
        }.firstOrNull() ?: return
        val (roomId, room, states) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val contextBefore = checkNotNull(editor.currentLayoutEditingContext())
        val sourceId = contextBefore.allStateIds[1]

        assertTrue(editor.shareCurrentLayoutWithState(sourceId, parser))

        val contextAfter = checkNotNull(editor.currentLayoutEditingContext())
        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        val target = roomEdits.states.single { it.id == contextAfter.stateId }
        val source = roomEdits.states.single { it.id == sourceId }
        assertEquals(source.resources.level, target.resources.level)
        assertTrue(target.levelResourceChanged)
        assertTrue(contextAfter.sharingStateCount >= 2)
        assertEquals(
            parser.decompressLZ2(states[1].levelDataPtr).copyOfRange(0, 34).toList(),
            editor.workingLevelData!!.copyOfRange(0, 34).toList(),
        )
    }

    @Test
    fun `a linked state with private edits can become a new shared layout source`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf { states.size >= 3 }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        editor.switchRoomState(0, parser)
        val ids = checkNotNull(editor.currentLayoutEditingContext()).allStateIds

        assertTrue(editor.shareCurrentLayoutWithState(ids[1], parser))
        editor.setCurrentLayoutEditScope(LayoutEditScope.THIS_STATE)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )
        editor.beginStroke()
        assertTrue(editor.paintAt(0, 0))
        editor.endStroke()

        editor.switchRoomState(ids[2], parser)
        assertTrue(editor.shareCurrentLayoutWithState(ids[0], parser))

        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        val source = roomEdits.states.single { it.id == ids[0] }
        val target = roomEdits.states.single { it.id == ids[2] }
        assertFalse(source.levelResourceChanged)
        assertTrue(target.levelResourceChanged)
        assertEquals(source.resources.level, target.resources.level)
        assertEquals(newWord, editor.readBlockWord(0, 0))
    }

    @Test
    fun `deleting a shared layout owner promotes another state without losing the layout`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states).takeIf { states.size >= 2 }
        }.firstOrNull() ?: return
        val (roomId, room, _) = candidate
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)
        val initialIds = editor.project.getOrCreateRoom(roomId).also { it.ensureStateManifest(parser) }.states.map { it.id }
        val ownerId = initialIds.first()
        val targetId = initialIds.last()
        editor.switchRoomState(targetId, parser)
        assertTrue(editor.shareCurrentLayoutWithState(ownerId, parser))
        editor.switchRoomState(ownerId, parser)
        editor.setCurrentLayoutEditScope(LayoutEditScope.ALL_SHARING_STATES)
        val oldWord = editor.readBlockWord(0, 0)
        val newWord = oldWord xor 1
        editor.setBrushForTest(
            TileBrush.single(newWord and 0x3FF, (newWord ushr 12) and 0xF, editor.readBts(0, 0)).copy(
                hFlip = newWord and 0x0400 != 0,
                vFlip = newWord and 0x0800 != 0,
            )
        )
        editor.beginStroke()
        assertTrue(editor.paintAt(0, 0))
        editor.endStroke()

        editor.deleteRoomState(ownerId, parser)
        val roomEdits = editor.project.rooms.getValue(editor.project.roomKey(roomId))
        val target = roomEdits.states.single { it.id == targetId }
        val sharingStates = roomEdits.states.filter { it.resources.level == target.resources.level }
        assertTrue(sharingStates.any { !it.levelResourceChanged })
        editor.switchRoomState(targetId, parser)
        assertEquals(newWord, editor.readBlockWord(0, 0))
    }

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
        val roomEdits = editor.project.getOrCreateRoom(roomId)
        roomEdits.operations += EditOperation(
            "common state preview fixture",
            listOf(TileEdit(x, y, oldWord, newWord, oldBts, oldBts)),
        )
        val operationsBefore = roomEdits.operations.toList()

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
