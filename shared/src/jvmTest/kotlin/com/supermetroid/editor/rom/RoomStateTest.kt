package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomStateTest {

    @Test
    fun `Landing Site has 4 states`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0x91F8)
        assertEquals(4, states.size, "Landing Site should have 4 states")
        assertEquals("Standard (default)", states.last().stateInfo.conditionName)
        // All states share the same level data
        assertTrue(states.all { it.levelDataPtr == states[0].levelDataPtr },
            "All Landing Site states should share level data")
    }

    @Test
    fun `Parlor has 3 states`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0x92FD)
        assertEquals(3, states.size, "Parlor should have 3 states")
    }

    @Test
    fun `Wrecked Ship Main has 2 states`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0xA59F)
        assertEquals(2, states.size, "Wrecked Ship Main should have 2 states")
    }

    @Test
    fun `Kraid Room has 3 states`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0x9879)
        assertEquals(3, states.size, "Kraid Room should have 3 states")
    }

    @Test
    fun `all states have valid level data pointers`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        for (roomId in listOf(0x91F8, 0x92FD, 0xA59F, 0x9879)) {
            val states = rp.parseRoomStatesWithData(roomId)
            for (state in states) {
                assertTrue(state.levelDataPtr in 0xC00000..0xCFFFFF || state.levelDataPtr == 0,
                    "Room \$${roomId.toString(16)}, state '${state.stateInfo.conditionName}': level ptr should be in bank C0-CF")
            }
        }
    }

    @Test
    fun `Parlor states have different PLM sets`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0x92FD)
        val plmPtrs = states.map { it.plmSetPtr }.toSet()
        assertTrue(plmPtrs.size > 1, "Parlor should have different PLM sets across states")
    }

    @Test
    fun `default state matches readRoomHeader`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8)!!
        val states = rp.parseRoomStatesWithData(0x91F8)
        val defaultState = states.last()
        assertEquals(room.levelDataPtr, defaultState.levelDataPtr)
        assertEquals(room.plmSetPtr, defaultState.plmSetPtr)
        assertEquals(room.enemySetPtr, defaultState.enemySetPtr)
    }

    @Test
    fun `single-state room has exactly 1 state`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0xA322) // Phantoon's Room
        assertEquals(1, states.size)
    }

    @Test
    fun `state data has valid tileset and music`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val states = rp.parseRoomStatesWithData(0x91F8)
        for (state in states) {
            assertTrue(state.tileset in 0..29, "Tileset should be valid")
            assertTrue(state.musicTrack in 0..255, "Music track should be valid byte")
        }
    }
}
