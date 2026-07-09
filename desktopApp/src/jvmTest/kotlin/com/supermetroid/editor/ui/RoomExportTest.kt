package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomExportData
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomExportTest {

    @Test
    fun `export Landing Site produces valid JSON`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        assertTrue(json.contains("91F8"), "JSON should contain room ID")
        assertTrue(json.contains("Landing Site"), "JSON should contain room name")
        assertTrue(json.contains("levelDataBase64"), "JSON should contain level data")
        assertTrue(json.contains("enemies"), "JSON should contain enemies")
        assertTrue(json.contains("plms"), "JSON should contain PLMs")
        assertTrue(json.contains("doors"), "JSON should contain doors")
    }

    @Test
    fun `exported JSON deserializes back correctly`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        val parsed = Json.decodeFromString(RoomExportData.serializer(), json)

        assertEquals("91F8", parsed.roomId)
        assertEquals(9, parsed.width)
        assertEquals(5, parsed.height)
        assertTrue(parsed.enemies.isNotEmpty(), "Should have enemies")
        assertTrue(parsed.plms.isNotEmpty(), "Should have PLMs")
        assertTrue(parsed.doors.isNotEmpty(), "Should have doors")
        assertEquals(45, parsed.scrollData.size, "Should have 9×5=45 scroll entries")
        assertTrue(parsed.levelDataBase64.isNotEmpty(), "Level data should be non-empty")
    }

    @Test
    fun `exported level data roundtrips through base64`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        val parsed = Json.decodeFromString(RoomExportData.serializer(), json)

        val decodedData = java.util.Base64.getDecoder().decode(parsed.levelDataBase64)
        assertTrue(decodedData.size > 1000, "Decompressed level data should be substantial")
        // First 2 bytes are the L1 size header
        val l1Size = (decodedData[0].toInt() and 0xFF) or ((decodedData[1].toInt() and 0xFF) shl 8)
        assertEquals(9 * 16 * 5 * 16 * 2, l1Size, "L1 size should match 9×5 screens × 256 tiles × 2 bytes")
    }

    @Test
    fun `reset current room restores ROM data and removes room edits`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)
        val originalData = es.workingLevelData!!.copyOf()
        val originalScrolls = es.workingScrolls.copyOf()
        val oldWord = es.readBlockWord(4, 4)
        val oldBts = es.readBts(4, 4)
        val newScroll = if (originalScrolls[0] == 0x02) 0x01 else 0x02

        es.applyBulkEdits("test edit", listOf(TileEdit(4, 4, oldWord, oldWord xor 0x001, oldBts, oldBts xor 0x01)))
        es.setScroll(0, 0, newScroll, room.width)

        assertTrue(es.project.rooms.containsKey("91F8"), "test setup should create room edits")
        assertTrue(es.resetCurrentRoomToOriginal(rp))

        assertFalse(es.project.rooms.containsKey("91F8"), "reset should remove the room edit record")
        assertTrue(originalData.contentEquals(es.workingLevelData!!), "level data should match ROM data after reset")
        assertTrue(originalScrolls.contentEquals(es.workingScrolls), "scroll data should match ROM data after reset")
        assertTrue(es.undoStack.isEmpty(), "reset should clear undo history for the room")
        assertTrue(es.redoStack.isEmpty(), "reset should clear redo history for the room")
    }

    @Test
    fun `apply all generates normal rooms skips excluded rooms and resets generated edits`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val result = es.generateBiomeForAllRooms(
            BiomeRules.roll(BiomeStyle.PIPE_MAZE, 12345L),
            BiomeTheme.KEEP,
            12345L,
            rp,
        )

        assertTrue(result.generatedRooms > 20, "bulk generation should touch regular rooms")
        assertTrue(result.skippedRooms > 0, "bulk generation should skip excluded rooms")
        assertTrue(es.project.rooms.containsKey("91F8"), "Landing Site should be generated")
        assertTrue(es.project.rooms.containsKey("92FD"), "Parlor and Alcatraz should be generated")
        assertFalse(es.project.rooms.containsKey("93D5"), "save rooms should be skipped")
        assertFalse(es.project.rooms.containsKey("A59F"), "boss rooms should be skipped")

        val reset = es.resetGeneratedBiomeRooms(rp)

        assertTrue(reset.generatedRooms > 20, "reset should remove generated room records")
        assertTrue(es.project.rooms.isEmpty(), "generated edits should reset back to a clean project")
    }
}
