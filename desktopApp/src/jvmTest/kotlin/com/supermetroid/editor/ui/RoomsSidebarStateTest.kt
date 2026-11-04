package com.supermetroid.editor.ui

import com.supermetroid.editor.data.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class RoomsSidebarStateTest {
    @Test fun `selected panel persists by name without modifying other preferences or saving on open`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        var settings = json.decodeFromString<AppSettings>("{}").copy(fontSize = "LARGER", theme = "LIGHT")
        var writes = 0
        val state = RoomsSidebarState(settings.roomSidebarTab) { writes++; settings = settings.copy(roomSidebarTab = it) }
        assertEquals(RoomSidebarTab.TILESET, state.selectedTab)
        assertEquals(0, writes)
        state.select(RoomSidebarTab.ROOM_INFO)
        state.select(RoomSidebarTab.ROOM_INFO)
        assertEquals(1, writes)
        val saved = json.decodeFromString<AppSettings>(json.encodeToString(settings))
        val restored = RoomsSidebarState(saved.roomSidebarTab) { fail("Restoration must not write config") }
        assertEquals(RoomSidebarTab.ROOM_INFO, restored.selectedTab)
        assertEquals("LARGER", saved.fontSize)
        assertEquals("LIGHT", saved.theme)
        assertEquals(RoomSidebarTab.TILESET, RoomsSidebarState("REMOVED_PANEL") {}.selectedTab)
    }

    @Test fun `each sidebar viewport survives pane changes and has independent scroll state`() = runBlocking {
        val state = RoomsSidebarState("ROOM_INFO") {}
        val info = state.verticalScroll(RoomSidebarTab.ROOM_INFO)
        val tiles = state.verticalScroll(RoomSidebarTab.TILESET)
        info.scrollTo(480)
        tiles.scrollTo(200)
        state.tilesetHorizontalScroll.scrollTo(64)
        state.select(RoomSidebarTab.TILESET)
        state.select(RoomSidebarTab.PATTERNS)
        state.select(RoomSidebarTab.ROOM_INFO)
        assertSame(info, state.verticalScroll(RoomSidebarTab.ROOM_INFO))
        assertEquals(480, info.value)
        assertEquals(200, tiles.value)
        assertEquals(64, state.tilesetHorizontalScroll.value)
        assertNotSame(info, state.verticalScroll(RoomSidebarTab.GENERATE))
        assertEquals(0, state.verticalScroll(RoomSidebarTab.PATTERNS).value)
    }
}
