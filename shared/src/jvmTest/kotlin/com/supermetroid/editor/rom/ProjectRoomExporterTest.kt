package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.ScrollCommand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectRoomExporterTest {
    @Test
    fun `custom scroll behavior rejects a target outside the room`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms().first().getRoomIdAsInt()
        val room = parser.readRoomHeader(roomId)!!
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).customScrollCommands["cmd_0"] = mutableListOf(
                ScrollCommand(room.width * room.height, 1)
            )
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(project, parser, rom).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("invalid entry"))
        assertTrue(failure.message.orEmpty().contains("screen indices"))
    }

    @Test
    fun `custom scroll behavior rejects empty and duplicate screen writes`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms().first().getRoomIdAsInt()

        val emptyProject = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).customScrollCommands["cmd_0"] = mutableListOf()
        }
        val emptyFailure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(emptyProject, parser, rom.copyOf()).exportRooms()
        }
        assertTrue(emptyFailure.message.orEmpty().contains("is empty"))

        val duplicateProject = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).customScrollCommands["cmd_0"] = mutableListOf(
                ScrollCommand(0, 1),
                ScrollCommand(0, 0),
            )
        }
        val duplicateFailure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(duplicateProject, parser, rom.copyOf()).exportRooms()
        }
        assertTrue(duplicateFailure.message.orEmpty().contains("more than once"))
    }

    @Test
    fun `expanded scroll data aborts when bank 8F has no free space`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms()
            .map { it.getRoomIdAsInt() }
            .first { id ->
                val room = parser.readRoomHeader(id)
                room != null && room.roomScrollsPtr > 1 && room.width > 0 && room.height > 0
            }
        val room = parser.readRoomHeader(roomId)!!

        fillTrailingBank8FFreeSpace(rom, parser)

        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).roomHeaderChange = RoomHeaderChange(
                width = room.width + 1,
                height = room.height,
            )
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(
                project = project,
                romParser = parser,
                romData = rom,
            ).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("avoid corrupting adjacent data"))
    }

    @Test
    fun `save station index eight aborts because the game cannot address it`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms().first().getRoomIdAsInt()
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).saveStationSpawns.add(
                SaveStationSpawnChange(
                    area = 0,
                    saveIndex = RomParser.SAVE_STATION_SLOT_COUNT,
                    roomId = roomId,
                    doorPtr = 0,
                    scrollX = 0,
                    scrollY = 0,
                    samusY = 0,
                    samusX = 0,
                )
            )
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(project, parser, rom).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("unreachable"))
        assertTrue(failure.message.orEmpty().contains("0-7"))
    }

    @Test
    fun `save station export cannot overwrite the occupied gunship slot`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = 0x93D5
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).saveStationSpawns.add(
                SaveStationSpawnChange(
                    area = 0,
                    saveIndex = 0,
                    roomId = roomId,
                    doorPtr = 0,
                    scrollX = 0,
                    scrollY = 0,
                    samusY = 0,
                    samusX = 0,
                )
            )
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(project, parser, rom).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("cannot overwrite occupied load-station 0:0"))
        assertTrue(failure.message.orEmpty().contains("0x91F8"))

        val clearProject = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).saveStationSpawns.add(
                SaveStationSpawnChange(
                    area = 0,
                    saveIndex = 0,
                    roomId = 0,
                    doorPtr = 0,
                    scrollX = 0,
                    scrollY = 0,
                    samusY = 0,
                    samusX = 0,
                    clearSlot = true,
                )
            )
        }
        val clearFailure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(clearProject, parser, rom.copyOf()).exportRooms()
        }
        assertTrue(clearFailure.message.orEmpty().contains("cannot clear load-station 0:0"))
        assertTrue(clearFailure.message.orEmpty().contains("0x91F8"))
    }

    @Test
    fun `shared scroll pointer uses copy on write and preserves the other room`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomIds = RoomRepository().getAllRooms().map { it.getRoomIdAsInt() }
        val sourceRoomId = roomIds.first { id ->
            val room = parser.readRoomHeader(id)
            room != null && room.roomScrollsPtr > 1 && room.width > 0 && room.height > 0 &&
                parser.findAllStateDataOffsets(id).isNotEmpty()
        }
        val sourceRoom = parser.readRoomHeader(sourceRoomId)!!
        val otherRoomId = roomIds.first { it != sourceRoomId && parser.findAllStateDataOffsets(it).isNotEmpty() }
        val otherStateOffset = parser.findAllStateDataOffsets(otherRoomId).first()
        writeU16(rom, otherStateOffset + 14, sourceRoom.roomScrollsPtr)
        val aliasedParser = RomParser(rom)
        val originalScrolls = aliasedParser.parseScrollData(
            sourceRoom.roomScrollsPtr,
            sourceRoom.width,
            sourceRoom.height,
        )
        val newValue = if (originalScrolls[0] == 0) 1 else 0
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(sourceRoomId).scrollChanges.add(
                ScrollChange(0, 0, originalScrolls[0], newValue)
            )
        }

        ProjectRoomExporter(project, aliasedParser, rom).exportRooms()

        val exported = RomParser(rom)
        val sourcePointer = exported.readRoomHeader(sourceRoomId)!!.roomScrollsPtr
        assertNotEquals(sourceRoom.roomScrollsPtr, sourcePointer)
        assertEquals(sourceRoom.roomScrollsPtr, exported.readUInt16At(otherStateOffset + 14))
        assertContentEquals(
            originalScrolls,
            exported.parseScrollData(sourceRoom.roomScrollsPtr, sourceRoom.width, sourceRoom.height),
        )
        assertEquals(newValue, exported.parseScrollData(sourcePointer, sourceRoom.width, sourceRoom.height)[0])
    }

    @Test
    fun `editing a special uniform scroll value materializes a private table`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms()
            .map { it.getRoomIdAsInt() }
            .first { id ->
                val room = parser.readRoomHeader(id)
                room != null && room.roomScrollsPtr <= 1 && room.width > 0 && room.height > 0 &&
                    parser.findAllStateDataOffsets(id).isNotEmpty()
            }
        val room = parser.readRoomHeader(roomId)!!
        val originalScrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        val replacement = if (originalScrolls[0] == 0) 1 else 0
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).scrollChanges.add(
                ScrollChange(0, 0, originalScrolls[0], replacement)
            )
        }

        ProjectRoomExporter(project, parser, rom).exportRooms()

        val exported = RomParser(rom)
        val pointer = exported.readRoomHeader(roomId)!!.roomScrollsPtr
        assertTrue(pointer > 1)
        val actual = exported.parseScrollData(pointer, room.width, room.height)
        assertEquals(replacement, actual[0])
        assertContentEquals(originalScrolls.drop(1), actual.drop(1))
    }

    private fun fillTrailingBank8FFreeSpace(rom: ByteArray, parser: RomParser) {
        val bankStart = parser.snesToPc(0x8F8000)
        val bankEndExclusive = parser.snesToPc(0x8FFFFF) + 1
        var firstTrailingFree = bankEndExclusive
        while (firstTrailingFree > bankStart && (rom[firstTrailingFree - 1].toInt() and 0xFF) == 0xFF) {
            firstTrailingFree--
        }
        for (offset in firstTrailingFree until bankEndExclusive) {
            rom[offset] = 0
        }
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
