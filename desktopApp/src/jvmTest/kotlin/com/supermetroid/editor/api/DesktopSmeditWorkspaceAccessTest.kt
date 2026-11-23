package com.supermetroid.editor.api

import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.objectSourceTestRom
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SourceFxResource
import com.supermetroid.editor.rom.emptyDefaultFxEntry
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.ui.EditorState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DesktopSmeditWorkspaceAccessTest {
    @Test
    fun `room details expose every state and ordered effective FX records`() {
        val rom = objectSourceTestRom()
        fun word(pc: Int, value: Int) {
            rom[pc] = value.toByte()
            rom[pc + 1] = (value ushr 8).toByte()
        }
        word(0x7920A + 6, 0x8800)
        word(0x79300 + 6, 0x8800)
        SourceFxResource(
            "fixture",
            listOf(
                emptyDefaultFxEntry().copy(doorSelect = 0x9000, fxType = 4, liquidSurfaceStart = 0x120),
                emptyDefaultFxEntry().copy(fxType = 2, fxBitC = 2),
            ),
        ).bytes().copyInto(rom, 0x18800)
        val parser = RomParser(rom)
        val editor = EditorState()
        val edits = editor.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        edits.states.last().fxChange = FxChange(paletteBlend = 0x20)
        val access = DesktopSmeditWorkspaceAccess(
            editorState = editor,
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )

        val room = access.room(0x91F8)!!

        assertEquals(2, room.states.size)
        assertTrue(room.states.first().conditionKind != "DEFAULT")
        assertTrue(room.states.last().defaultState)
        assertEquals(listOf("door_specific", "default"), room.states.last().fxEntries.map { it.selector })
        assertEquals(listOf("bg_animate"), room.states.last().fxEntries.last().liquidOptions)
        assertEquals(0x20, room.states.last().fxEntries.last().paletteBlend)
    }

    @Test
    fun `documentation retrieval is useful and cannot escape the docs root`() {
        val access = DesktopSmeditWorkspaceAccess(
            editorState = EditorState(),
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { null },
            roomInfos = { emptyList() },
            selectedRoom = { null },
        )

        val matches = access.searchDocs("state-sensitive room music", limit = 10)
        assertTrue(matches.any { it.path == "ai/semantic_safety.md" })

        val excerpt = access.readDoc("ai/semantic_safety.md", startLine = 1, maxLines = 40)
        assertTrue(excerpt?.text?.contains("State-sensitive room music") == true)
        assertNull(access.readDoc("../README.md", startLine = 1, maxLines = 20))
        assertNull(access.readDoc("/etc/passwd", startLine = 1, maxLines = 20))
    }

    @Test
    fun `palette and item catalogs expose boss colors and state aware room items`() {
        val parser = RomParser(objectSourceTestRom())
        val access = DesktopSmeditWorkspaceAccess(
            editorState = EditorState(),
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )

        val kraid = access.palette("sprite:boss_kraid_health_bg")
        assertEquals(160, kraid?.colorCount)
        assertEquals(160, kraid?.colorsBgr555?.size)
        assertTrue(kraid?.usage?.contains("full-health assembled preview") == true)
        assertTrue("sprite:boss_kraid_health_oam" in kraid?.relatedPaletteIds.orEmpty())
        val phantoon = access.palette("sprite:boss_phantoon_health")
        assertEquals(128, phantoon?.colorCount)
        assertEquals(128, phantoon?.colorsBgr555?.size)
        assertTrue(phantoon?.usage?.contains("indices 112–127") == true)
        assertEquals(listOf("sprite:boss_phantoon"), phantoon?.relatedPaletteIds)
        assertTrue(access.itemTypes().any { it.name == "Missile" && it.variant == "Visible" })
        val roomItems = access.roomItems(0x91F8)
        assertEquals(2, roomItems?.states?.size)
        assertEquals(32, roomItems?.widthBlocks)
    }
}
