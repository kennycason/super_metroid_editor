package com.supermetroid.editor.rom

import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.TileEdit
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectRoomStatesTest {
    @Test
    fun `materialized state manifest has stable ids semantic conditions and resource links`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val room = SmEditProject("base.smc").getOrCreateRoom(0x91F8)

        val states = room.ensureStateManifest(parser)

        assertEquals(listOf("state-1", "state-2", "state-3", "state-4"), states.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), states.map { it.sourceStateIndex })
        assertEquals(ProjectRoomStateConditionKind.EVENT_SET, states.first().condition.kind)
        assertEquals(ProjectRoomStateConditionKind.DEFAULT, states.last().condition.kind)
        assertTrue(states.none { it.hasEdits })
        assertEquals(states.map { it.resources.level }.distinct().size, states.map {
            parser.readStateData(parser.findAllStateDataOffsets(0x91F8)[it.sourceStateIndex!!])
                .getValue("levelDataPtr")
        }.distinct().size)
    }

    @Test
    fun `state manifest and overrides survive project json round trip`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val project = SmEditProject("base.smc")
        val state = project.getOrCreateRoom(0xCD13).ensureStateManifest(parser).first()
        state.stateDataChange = StateDataChange(tileset = 7, musicData = 3, musicTrack = 5)
        state.fxChange = FxChange(fxType = 0x0C, tileAnimBitflags = 4)
        state.doorFxChanges["A18C"] = FxChange(fxType = 0x0A, paletteBlend = 3)

        val decoded = SmEditProjectFormat.decode(
            json,
            json.encodeToString(SmEditProject.serializer(), project),
        )
        val actual = decoded.rooms.getValue("CD13").states.first()

        assertEquals(state.id, actual.id)
        assertEquals(state.condition, actual.condition)
        assertEquals(state.resources, actual.resources)
        assertEquals(7, actual.stateDataChange?.tileset)
        assertEquals(0x0C, actual.fxChange?.fxType)
        assertEquals(0x0A, actual.doorFxChanges["A18C"]?.fxType)
        assertEquals(3, actual.doorFxChanges["A18C"]?.paletteBlend)
        assertEquals(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION, decoded.projectFormatVersion)
    }

    @Test
    fun `missing schema marker is detected as legacy without changing new project defaults`() {
        val json = Json { ignoreUnknownKeys = true }

        val legacy = SmEditProjectFormat.decode(json, """{"romPath":"base.smc","rooms":{}}""")
        val current = SmEditProject("base.smc")

        assertEquals(SmEditProjectFormat.LEGACY_FORMAT_VERSION, legacy.projectFormatVersion)
        assertEquals(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION, current.projectFormatVersion)
    }

    @Test
    fun `state property export changes only the selected state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = 0xCD13
        val before = parser.parseRoomStatesWithData(roomId)
        assertEquals(2, before.size)
        val replacement = (before[0].tileset + 1) % TileGraphics.NUM_TILESETS
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().stateDataChange =
            StateDataChange(tileset = replacement)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val after = RomParser(rom).parseRoomStatesWithData(roomId)
        assertEquals(replacement, after[0].tileset)
        assertEquals(before[1].tileset, after[1].tileset)
    }

    @Test
    fun `same-width semantic condition change preserves the state pointer`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = 0xCD13
        val before = parser.inspectRoomStates(roomId).states.first()
        assertEquals(5, before.condition.entrySizeBytes)
        val project = SmEditProject("base.smc")
        val state = project.getOrCreateRoom(roomId).ensureStateManifest(parser).first()
        state.condition = projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0E)
        state.conditionChanged = true

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val after = RomParser(rom).inspectRoomStates(roomId).states.first()
        assertEquals(RoomStateConditionKind.EVENT_SET, after.condition.kind)
        assertEquals(0x0E, after.condition.argument)
        assertEquals(before.stateDataPcOffset, after.stateDataPcOffset)
    }

    @Test
    fun `state FX export forks a table shared with a sibling state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence()
            .map { it.getRoomIdAsInt() to parser.parseRoomStatesWithData(it.getRoomIdAsInt()) }
            .firstOrNull { (_, states) ->
                states.size > 1 && states[0].fxPtr !in setOf(0, 0xFFFF) &&
                    states.drop(1).any { it.fxPtr == states[0].fxPtr } &&
                    parser.parseFxEntries(states[0].fxPtr).any { it.doorSelect == 0 }
            } ?: return
        val (roomId, before) = candidate
        val originalFx = parser.parseFxEntries(before[0].fxPtr).last { it.doorSelect == 0 }
        val replacement = if (originalFx.fxType == 0x0C) 0 else 0x0C
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().fxChange =
            FxChange(fxType = replacement)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val after = exported.parseRoomStatesWithData(roomId)
        assertNotEquals(after[0].fxPtr, after[1].fxPtr)
        assertEquals(replacement, exported.parseFxEntries(after[0].fxPtr).last { it.doorSelect == 0 }.fxType)
        assertEquals(originalFx.fxType, exported.parseFxEntries(after[1].fxPtr).last { it.doorSelect == 0 }.fxType)
    }

    @Test
    fun `state FX export creates a table when the state had no effects`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence()
            .map { it.getRoomIdAsInt() to parser.parseRoomStatesWithData(it.getRoomIdAsInt()) }
            .firstOrNull { (_, states) -> states.any { it.fxPtr == 0 || it.fxPtr == 0xFFFF } }
            ?: return
        val (roomId, states) = candidate
        val sourceIndex = states.indexOfFirst { it.fxPtr == 0 || it.fxPtr == 0xFFFF }
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser)[sourceIndex].fxChange = FxChange(fxType = 0x0A)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom).parseRoomStatesWithData(roomId)[sourceIndex]
        assertTrue(exported.fxPtr !in setOf(0, 0xFFFF))
        assertEquals(0x0A, RomParser(rom).parseFxEntries(exported.fxPtr).single().fxType)
    }

    @Test
    fun `door-specific FX export patches only the selected door entry`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence().flatMap { info ->
            val roomId = info.getRoomIdAsInt()
            parser.parseRoomStatesWithData(roomId).asSequence().mapIndexedNotNull { stateIndex, state ->
                val entries = parser.parseFxEntries(state.fxPtr)
                Triple(roomId, stateIndex, entries).takeIf { entries.any { it.doorSelect != 0 } }
            }
        }.firstOrNull() ?: return
        val (roomId, stateIndex, beforeEntries) = candidate
        val doorEntry = beforeEntries.first { it.doorSelect != 0 }
        val defaultEntry = beforeEntries.first { it.doorSelect == 0 }
        val replacement = if (doorEntry.fxType == 0x0A) 0x0C else 0x0A
        val project = SmEditProject("base.smc")
        val state = project.getOrCreateRoom(roomId).ensureStateManifest(parser)[stateIndex]
        val doorKey = doorEntry.doorSelect.toString(16).uppercase().padStart(4, '0')
        state.doorFxChanges[doorKey] = FxChange(fxType = replacement)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val stateAfter = exported.parseRoomStatesWithData(roomId)[stateIndex]
        val afterEntries = exported.parseFxEntries(stateAfter.fxPtr)
        assertEquals(replacement, afterEntries.first { it.doorSelect == doorEntry.doorSelect }.fxType)
        assertEquals(defaultEntry, afterEntries.first { it.doorSelect == 0 })
    }

    @Test
    fun `state layout export changes only the selected linked state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence()
            .map { it.getRoomIdAsInt() to parser.parseRoomStatesWithData(it.getRoomIdAsInt()) }
            .firstOrNull { (_, states) -> states.size > 1 && states[0].levelDataPtr == states[1].levelDataPtr }
            ?: return
        val (roomId, before) = candidate
        val levelBefore = parser.decompressLZ2(before[0].levelDataPtr)
        val oldWord = readU16(levelBefore, 2)
        val replacement = oldWord xor 1
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().operations +=
            EditOperation("test selected state", listOf(TileEdit(0, 0, oldWord, replacement)))

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val after = exported.parseRoomStatesWithData(roomId)
        assertNotEquals(after[0].levelDataPtr, after[1].levelDataPtr)
        assertEquals(replacement, readU16(exported.decompressLZ2(after[0].levelDataPtr), 2))
        assertContentEquals(levelBefore, exported.decompressLZ2(after[1].levelDataPtr))
    }

    @Test
    fun `state PLM export changes only the selected linked state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence()
            .map { it.getRoomIdAsInt() to parser.parseRoomStatesWithData(it.getRoomIdAsInt()) }
            .firstOrNull { (_, states) ->
                states.size > 1 && states[0].plmSetPtr == states[1].plmSetPtr &&
                    parser.parsePlmSet(states[0].plmSetPtr).isNotEmpty()
            } ?: return
        val (roomId, before) = candidate
        val original = parser.parsePlmSet(before[0].plmSetPtr)
        val removed = original.first()
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().plmChanges +=
            PlmChange("remove", removed.id, removed.x, removed.y, removed.param)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val after = exported.parseRoomStatesWithData(roomId)
        assertNotEquals(after[0].plmSetPtr, after[1].plmSetPtr)
        assertEquals(original.size - 1, exported.parsePlmSet(after[0].plmSetPtr).size)
        assertEquals(original, exported.parsePlmSet(after[1].plmSetPtr))
    }

    @Test
    fun `state scroll export changes only the selected linked state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence().mapNotNull { info ->
            val roomId = info.getRoomIdAsInt()
            val room = parser.readRoomHeader(roomId) ?: return@mapNotNull null
            val states = parser.parseRoomStatesWithData(roomId)
            Triple(roomId, room, states)
        }.firstOrNull { (_, room, states) ->
            room.width > 0 && room.height > 0 && states.size > 1 && states[0].scrollPtr == states[1].scrollPtr
        } ?: return
        val (roomId, room, before) = candidate
        val original = parser.parseScrollData(before[0].scrollPtr, room.width, room.height)
        val replacement = if (original[0] == 0) 1 else 0
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().scrollChanges +=
            ScrollChange(0, 0, original[0], replacement)

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val after = exported.parseRoomStatesWithData(roomId)
        assertNotEquals(after[0].scrollPtr, after[1].scrollPtr)
        assertEquals(replacement, exported.parseScrollData(after[0].scrollPtr, room.width, room.height)[0])
        assertContentEquals(original, exported.parseScrollData(after[1].scrollPtr, room.width, room.height))
    }

    @Test
    fun `state enemy export changes only the selected linked state`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val candidate = RoomRepository().getAllRooms().asSequence()
            .map { it.getRoomIdAsInt() to parser.parseRoomStatesWithData(it.getRoomIdAsInt()) }
            .firstOrNull { (_, states) ->
                states.size > 1 && states[0].enemySetPtr == states[1].enemySetPtr &&
                    parser.parseEnemyPopulation(states[0].enemySetPtr).isNotEmpty()
            } ?: return
        val (roomId, before) = candidate
        val original = parser.parseEnemyPopulation(before[0].enemySetPtr)
        val removed = original.first()
        val project = SmEditProject("base.smc")
        project.getOrCreateRoom(roomId).ensureStateManifest(parser).first().enemyChanges += EnemyChange(
            action = "remove",
            enemyId = removed.id,
            x = removed.x,
            y = removed.y,
            initParam = removed.initParam,
            properties = removed.properties,
            extra1 = removed.extra1,
            extra2 = removed.extra2,
            extra3 = removed.extra3,
            origX = removed.x,
            origY = removed.y,
        )

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val after = exported.parseRoomStatesWithData(roomId)
        assertNotEquals(after[0].enemySetPtr, after[1].enemySetPtr)
        assertEquals(original.size - 1, exported.parseEnemyPopulation(after[0].enemySetPtr).size)
        assertEquals(original, exported.parseEnemyPopulation(after[1].enemySetPtr))
    }
}
