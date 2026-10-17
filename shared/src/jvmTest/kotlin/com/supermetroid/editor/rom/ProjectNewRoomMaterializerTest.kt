package com.supermetroid.editor.rom

import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.ProjectDoorDefinition
import com.supermetroid.editor.data.ProjectNewRoom
import com.supermetroid.editor.data.ProjectNewRoomHeader
import com.supermetroid.editor.data.ProjectNewRoomOrigin
import com.supermetroid.editor.data.ProjectNewRoomState
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import com.supermetroid.editor.data.TileEdit
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectNewRoomMaterializerTest {
    @Test
    fun `project room survives native project save and reopen`() {
        val original = SmEditProject("base.smc").also {
            it.newRooms += blankRoom("room-save", "Saved Room", 3, 2).also { room ->
                room.previewRoomId = 0xF100
                room.doors += ProjectDoorDefinition("rom:91F8", 0, 0, 1, 2, 0x8000, 0)
            }
        }
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(SmEditProject.serializer(), original)

        val reopened = SmEditProjectFormat.decode(text)

        assertEquals(SmEditProject.CURRENT_PROJECT_FORMAT_VERSION, reopened.projectFormatVersion)
        assertEquals("room-save", reopened.newRooms.single().id)
        assertEquals("Saved Room", reopened.newRooms.single().name)
        assertEquals(3, reopened.newRooms.single().header.width)
        assertEquals("rom:91F8", reopened.newRooms.single().doors.single().destination)
        assertContentEquals(
            blankLevel(3, 2),
            Base64.getDecoder().decode(reopened.newRooms.single().initialState.levelDataBase64),
        )
    }

    @Test
    fun `blank room materializes as a parser-readable native room graph`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val project = SmEditProject("base.smc").also {
            val room = blankRoom("room-1", "Blank Room", 2, 1)
            it.newRooms += room.copy(initialState = room.initialState.copy(enemyKillCount = 7))
        }

        val result = ProjectNewRoomMaterializer(project, RomParser(rom), rom).materialize()
        val roomId = result.roomIdsByProjectId.getValue("room-1")
        val parser = RomParser(rom)
        val room = parser.readRoomHeader(roomId)

        assertEquals(2, room?.width)
        assertEquals(1, room?.height)
        assertEquals(7, room?.tileset)
        assertEquals(1, parser.inspectRoomStates(roomId).states.size)
        assertTrue(parser.inspectRoomStates(roomId).states.single().condition.isDefault)
        assertContentEquals(blankLevel(2, 1), parser.decompressLZ2(requireNotNull(room).levelDataPtr))
        assertTrue(parser.parsePlmSet(room.plmSetPtr).isEmpty())
        assertTrue(parser.parseEnemyPopulation(room.enemySetPtr).isEmpty())
        // The native reader returns early for empty populations; inspect the stored byte.
        assertEquals(7, parser.readByteAt(parser.snesToPc(0xA10000 or room.enemySetPtr) + 2))
        assertTrue(parser.parseEnemyGfxSet(room.enemyGfxPtr).isEmpty())
        assertTrue(parser.parseDoorList(room.doorOut).isEmpty())
        assertEquals(0, parser.parseFxEntries(room.fxPtr).single().fxType)
    }

    @Test
    fun `project room doors resolve forward and cyclic stable identities`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val first = blankRoom("room-a", "Room A", 1, 1).also {
            it.doors += ProjectDoorDefinition("project:room-b", 0x0000, 0, 0, 0, 0x8000, 0)
        }
        val second = blankRoom("room-b", "Room B", 1, 1).also {
            it.doors += ProjectDoorDefinition("project:room-a", 0x0100, 0, 0, 0, 0x8000, 0)
        }
        val project = SmEditProject("base.smc", newRooms = mutableListOf(first, second))

        val result = ProjectNewRoomMaterializer(project, RomParser(rom), rom).materialize()
        val parser = RomParser(rom)
        val a = result.roomIdsByProjectId.getValue("room-a")
        val b = result.roomIdsByProjectId.getValue("room-b")

        assertNotEquals(a, b)
        assertEquals(b, parser.parseDoorList(requireNotNull(parser.readRoomHeader(a)).doorOut).single().destRoomPtr)
        assertEquals(a, parser.parseDoorList(requireNotNull(parser.readRoomHeader(b)).doorOut).single().destRoomPtr)
    }

    @Test
    fun `copied door flags follow actual areas without changing destination rooms or saved definitions`() {
        val base = TestRomHelper.loadRomBytes() ?: return
        val first = blankRoom("room-a", "Room A", 1, 1).also {
            it.previewRoomId = 0xF100
            it.header.area = 0
            it.doors += ProjectDoorDefinition("project:room-b", 0x0301, 0, 0, 0, 0x8000, 0)
            it.doors += ProjectDoorDefinition("rom:91F8", 0x0441, 0, 0, 0, 0x8000, 0)
        }
        val second = blankRoom("room-b", "Room B", 1, 1).also {
            it.previewRoomId = 0xF200
            it.header.area = 1
            it.doors += ProjectDoorDefinition("project:room-a", 0x0742, 0, 0, 0, 0x8000, 0)
        }
        val project = SmEditProject("base.smc", newRooms = mutableListOf(first, second))
        val before = Json.encodeToString(SmEditProject.serializer(), project)
        fun build(overrides: Map<Int, Int>): List<List<Int>> {
            val bytes = base.copyOf()
            val parser = RomParser(bytes)
            val target = requireNotNull(parser.readRoomHeader(0x91F8))
            val targetDoors = parser.parseDoorList(target.doorOut)
            val result = ProjectNewRoomMaterializer(project, parser, bytes, roomAreaOverrides = overrides).materialize()
            assertEquals(targetDoors, parser.parseDoorList(target.doorOut), "No reciprocal destination edits")
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), project), "Placement does not rewrite seeds")
            return listOf(first, second).map { room ->
                parser.parseDoorList(requireNotNull(parser.readRoomHeader(result.roomIdsByProjectId.getValue(room.id))).doorOut).map { it.bitflag }
            }
        }
        assertEquals(listOf(listOf(0x0341, 0x0401), listOf(0x0742)), build(emptyMap()))
        assertEquals(listOf(listOf(0x0301, 0x0401), listOf(0x0702)), build(mapOf(0xF100 to 2, 0xF200 to 2, 0x91F8 to 2)))
    }

    @Test
    fun `new room accepts normal project edits after physical allocation`() {
        val sourceRom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val definition = blankRoom("room-edit", "Editable Room", 1, 1)
        val project = SmEditProject("base.smc", newRooms = mutableListOf(definition))
        val workspace = sourceRom.copyOf()
        val preview = ProjectNewRoomMaterializer(project, RomParser(workspace), workspace)
            .materialize().roomIdsByProjectId.getValue(definition.id)
        definition.previewRoomId = preview
        project.getOrCreateRoom(preview).operations += EditOperation(
            description = "Paint new room",
            edits = listOf(TileEdit(0, 0, 0, 0x8123, 0, 0x45)),
        )

        val exportedBytes = sourceRom.copyOf()
        val exported = ProjectRoomExporter(project, RomParser(exportedBytes), exportedBytes).exportRooms()
        val physicalId = exported.newRoomIds.getValue(definition.id)
        val parser = RomParser(exportedBytes)
        val room = requireNotNull(parser.readRoomHeader(physicalId))
        val level = parser.decompressLZ2(room.levelDataPtr)

        assertEquals(0x23, level[2].toInt() and 0xFF)
        assertEquals(0x81, level[3].toInt() and 0xFF)
        assertEquals(0x45, level[2 + 16 * 16 * 2].toInt() and 0xFF)
        assertTrue(physicalId.toString(16).uppercase().padStart(4, '0') in exported.roomsPatched)
    }

    private fun blankRoom(id: String, name: String, width: Int, height: Int): ProjectNewRoom =
        ProjectNewRoom(
            id = id,
            name = name,
            handle = id,
            origin = ProjectNewRoomOrigin.BLANK,
            header = ProjectNewRoomHeader(
                index = 0xE0 + id.last().code % 16,
                area = 0,
                mapX = 1,
                mapY = 1,
                width = width,
                height = height,
                creBitflag = 1,
            ),
            initialState = ProjectNewRoomState(
                levelDataBase64 = Base64.getEncoder().encodeToString(blankLevel(width, height)),
                tileset = 7,
                scrollData = MutableList(width * height) { 1 },
            ),
        )

    private fun blankLevel(width: Int, height: Int): ByteArray {
        val blocks = width * 16 * height * 16
        val layer1Bytes = blocks * 2
        return ByteArray(2 + layer1Bytes + blocks).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes ushr 8) and 0xFF).toByte()
        }
    }
}
