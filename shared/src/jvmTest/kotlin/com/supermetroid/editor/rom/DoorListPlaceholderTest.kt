package com.supermetroid.editor.rom

import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoorListPlaceholderTest {
    private val businessCenter = 0xA7DE

    @Test
    fun `zero destination elevator sentinel preserves later door slots`() {
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        fun word(pc: Int, value: Int) {
            rom[pc] = value.toByte()
            rom[pc + 1] = (value ushr 8).toByte()
        }
        val doorListPtr = 0x9000
        val listPc = parser.snesToPc(0x8F0000 or doorListPtr)
        val sentinelPtr = 0xA18A
        val connectionPtr = 0x9000
        word(listPc, sentinelPtr)
        word(listPc + 2, connectionPtr)
        word(listPc + 4, 0)
        word(parser.snesToPc(0x830000 or sentinelPtr), 0)
        val connectionPc = parser.snesToPc(0x830000 or connectionPtr)
        word(connectionPc, 0x91F8)
        word(connectionPc + 2, 0x0004)
        word(connectionPc + 4, 0x0601)
        word(connectionPc + 8, 0x8000)

        val slots = parser.parseDoorList(doorListPtr)

        assertEquals(2, slots.size)
        assertTrue(slots[0].isElevatorPlaceholder)
        assertEquals(sentinelPtr, slots[0].doorDefPtr)
        assertEquals(0x91F8, slots[1].destRoomPtr)
        assertEquals(null, parser.doorEntryPcOffset(doorListPtr, 0))
        assertEquals(connectionPc, parser.doorEntryPcOffset(doorListPtr, 1))
    }

    @Test
    fun `Tourian and Maridia elevators preserve shared zero destination slot`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val tourian = requireNotNull(parser.readRoomHeader(0xDAAE))
        val tourianSlots = parser.parseDoorList(tourian.doorOut)
        assertEquals(4, tourianSlots.size)
        assertTrue(tourianSlots[2].isElevatorPlaceholder)
        assertEquals(0xA18A, tourianSlots[2].doorDefPtr)
        assertEquals(0xDF1B, tourianSlots[3].destRoomPtr)

        val maridia = requireNotNull(parser.readRoomHeader(0xD30B))
        val maridiaSlots = parser.parseDoorList(maridia.doorOut)
        assertEquals(4, maridiaSlots.size)
        assertTrue(maridiaSlots[3].isElevatorPlaceholder)
        assertEquals(0xA18A, maridiaSlots[3].doorDefPtr)
    }

    @Test
    fun `Business Center preserves native elevator slot and parses later BTS connections`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val room = requireNotNull(parser.readRoomHeader(businessCenter))

        val slots = parser.parseDoorList(room.doorOut)

        assertEquals(8, slots.size)
        assertTrue(slots[5].isElevatorPlaceholder)
        assertFalse(slots[5].isConnection)
        assertEquals(RomParser.ELEVATOR_DOOR_LIST_PLACEHOLDER_PTR, slots[5].doorDefPtr)
        assertEquals(0xB167, slots[6].destRoomPtr, "BTS 6 must resolve to Frog Save")
        assertEquals(0xB0B4, slots[7].destRoomPtr, "BTS 7 must resolve to Norfair Map")
        assertTrue(slots[6].isConnection)
        assertTrue(slots[7].isConnection)

        assertFalse(RomParser.ELEVATOR_DOOR_LIST_PLACEHOLDER_PTR in nativeDoorConsumers(parser))
        val rooms = RoomRepository().getAllRooms().mapNotNull { parser.readRoomHeader(it.getRoomIdAsInt()) }
            .associateBy { it.roomId }
        assertTrue(RomValidator.checkDoorConsistency(parser, rooms).none {
            it.roomId == businessCenter && it.message.contains("destination room 0x0")
        })
    }

    @Test
    fun `editing a connection after the placeholder leaves elevator sentinel untouched`() {
        val original = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(original)
        val room = requireNotNull(parser.readRoomHeader(businessCenter))
        val slots = parser.parseDoorList(room.doorOut)
        val sentinelPc = parser.snesToPc(0x830000 or RomParser.ELEVATOR_DOOR_LIST_PLACEHOLDER_PTR)
        val sentinelBefore = original.copyOfRange(sentinelPc, sentinelPc + 12)
        val listBefore = original.copyOfRange(parser.snesToPc(0x8F0000 or room.doorOut),
            parser.snesToPc(0x8F0000 or room.doorOut) + 18)
        val target = slots[6]
        val project = SmEditProject("base.smc").also { owner ->
            owner.getOrCreateRoom(businessCenter).doorChanges += DoorChange(
                doorIndex = 6,
                destRoomPtr = target.destRoomPtr,
                bitflag = target.bitflag,
                doorCapCode = target.doorCapCode,
                screenX = target.screenX,
                screenY = target.screenY,
                distFromDoor = target.distFromDoor xor 1,
                entryCode = target.entryCode,
            )
        }
        val output = original.copyOf()

        ProjectRoomExporter(project, parser, output).exportRooms()

        val built = RomParser(output)
        assertEquals(target.distFromDoor xor 1, built.parseDoorList(room.doorOut)[6].distFromDoor)
        assertContentEquals(sentinelBefore, output.copyOfRange(sentinelPc, sentinelPc + 12))
        assertContentEquals(listBefore, output.copyOfRange(parser.snesToPc(0x8F0000 or room.doorOut),
            parser.snesToPc(0x8F0000 or room.doorOut) + 18))
    }

    @Test
    fun `export refuses to treat elevator placeholder as a DoorDef`() {
        val original = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(original)
        val project = SmEditProject("base.smc").also { owner ->
            owner.getOrCreateRoom(businessCenter).doorChanges += DoorChange(5, 0x91F8, 0, 0, 0, 0, 0x8000, 0)
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(project, parser, original.copyOf()).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("elevator placeholder"))
    }

    @Test
    fun `ASM ownership refuses to flatten a native special slot into DoorDef source`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val room = requireNotNull(parser.readRoomHeader(businessCenter))
        val entries = parser.parseDoorList(room.doorOut).take(5).mapIndexed { index, door ->
            SourceRoomDoor(
                doorDefPtr = door.doorDefPtr,
                change = DoorChange(index, door.destRoomPtr, door.bitflag, door.doorCapCode,
                    door.screenX, door.screenY, door.distFromDoor, door.entryCode),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            validateSourceRoomDoors(
                RoomEdits(businessCenter, asmDoorSourceVersion = 1),
                SourceRoomDoors(room.doorOut, entries),
                parser,
            )
        }

        assertTrue(failure.message.orEmpty().contains("special door-list slots"))
    }
}
