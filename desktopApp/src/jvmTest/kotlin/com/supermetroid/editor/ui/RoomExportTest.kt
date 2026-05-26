package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomExportData
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
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
}
