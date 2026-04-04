package com.supermetroid.editor.rom

import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.RoomEdits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Round-trip tests for room property editing: scroll data, FX data, and state data fields.
 */
class RoomPropertyEditTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    // ─── Scroll data parsing ────────────────────────────────────────

    @Test
    fun `Landing Site has valid scroll data`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val scrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)

        assertEquals(room.width * room.height, scrolls.size,
            "Scroll data should have one byte per screen")
        for ((i, s) in scrolls.withIndex()) {
            assertTrue(s in 0x00..0x02,
                "Scroll value at index $i should be 0x00-0x02, got 0x${s.toString(16)}")
        }
        println("Landing Site scrolls (${room.width}x${room.height}): ${scrolls.map { 
            when (it) { 0 -> "R"; 1 -> "B"; 2 -> "G"; else -> "?" }
        }}")
    }

    @Test
    fun `special scroll pointers return correct defaults`() {
        val parser = loadTestRom() ?: return
        val allBlue = parser.parseScrollData(0x0000, 3, 2)
        assertEquals(6, allBlue.size)
        assertTrue(allBlue.all { it == 0x01 }, "scrollsPtr=0x0000 should give all Blue")

        val allGreen = parser.parseScrollData(0x0001, 4, 3)
        assertEquals(12, allGreen.size)
        assertTrue(allGreen.all { it == 0x02 }, "scrollsPtr=0x0001 should give all Green")
    }

    @Test
    fun `scroll edit round-trip - modify scroll, write, read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!

        // Skip if special pointer (can't write in-place)
        if (room.roomScrollsPtr <= 1) {
            println("Landing Site uses special scroll pointer, skipping in-place test")
            return
        }

        val originalScrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        val romData = parser.getRomData().copyOf()

        // Flip the first screen's scroll value
        val origVal = originalScrolls[0]
        val newVal = when (origVal) { 0x01 -> 0x00; 0x00 -> 0x02; else -> 0x01 }

        val scrollPc = parser.snesToPc(0x8F0000 or room.roomScrollsPtr)
        romData[scrollPc] = newVal.toByte()

        // Read back
        val patchedParser = RomParser(romData)
        val readBack = patchedParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        assertEquals(newVal, readBack[0],
            "Screen (0,0) scroll should be 0x${newVal.toString(16)}, got 0x${readBack[0].toString(16)}")

        // Other screens should be unchanged
        for (i in 1 until originalScrolls.size) {
            assertEquals(originalScrolls[i], readBack[i],
                "Screen $i should be unchanged: expected 0x${originalScrolls[i].toString(16)}")
        }
        println("Scroll edit round-trip: PASS (screen 0: 0x${origVal.toString(16)} → 0x${newVal.toString(16)})")
    }

    // ─── FX data parsing and editing ────────────────────────────────

    @Test
    fun `Landing Site has FX entries`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val fxEntries = parser.parseFxEntries(room.fxPtr)

        assertTrue(fxEntries.isNotEmpty(), "Landing Site should have at least one FX entry")
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        assertNotNull(defaultFx, "Should have a default (doorSelect=0) FX entry")
        println("Landing Site FX: ${fxEntries.size} entries, default type=${defaultFx!!.fxTypeName}")
    }

    @Test
    fun `FX edit round-trip - modify FX type and palette blend`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        if (room.fxPtr == 0 || room.fxPtr == 0xFFFF) {
            println("Landing Site has no FX data, skipping")
            return
        }

        val fxEntries = parser.parseFxEntries(room.fxPtr)
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 } ?: return
        val romData = parser.getRomData().copyOf()

        // Find the default FX entry's PC offset
        val fxSnesAddr = 0x830000 or room.fxPtr
        var fxPc = parser.snesToPc(fxSnesAddr)
        for (entry in fxEntries) {
            if (entry.doorSelect == 0) break
            fxPc += 16
        }

        // Modify FX type to Rain (0x0A) and palette blend to 0x42
        val origFxType = defaultFx.fxType
        val origPaletteBlend = defaultFx.paletteBlend
        romData[fxPc + 9] = 0x0A.toByte()    // fxType
        romData[fxPc + 15] = 0x42.toByte()   // paletteBlend

        // Read back
        val patchedParser = RomParser(romData)
        val readFx = patchedParser.parseFxEntries(room.fxPtr)
        val readDefault = readFx.lastOrNull { it.doorSelect == 0 }!!
        assertEquals(0x0A, readDefault.fxType, "FX type should be Rain (0x0A)")
        assertEquals("Rain", readDefault.fxTypeName)
        assertEquals(0x42, readDefault.paletteBlend, "Palette blend should be 0x42")

        println("FX edit round-trip: PASS (type: 0x${origFxType.toString(16)} → 0x0A, " +
                "blend: 0x${origPaletteBlend.toString(16)} → 0x42)")
    }

    @Test
    fun `FX liquid properties round-trip`() {
        val parser = loadTestRom() ?: return
        // Use a room with water/lava — Maridia room (aqueduct area)
        val room = parser.readRoomHeader(0x91F8)!!
        if (room.fxPtr == 0 || room.fxPtr == 0xFFFF) return

        val romData = parser.getRomData().copyOf()
        val fxEntries = parser.parseFxEntries(room.fxPtr)
        var fxPc = parser.snesToPc(0x830000 or room.fxPtr)
        for (entry in fxEntries) {
            if (entry.doorSelect == 0) break
            fxPc += 16
        }

        // Set liquid start=0x0100, target=0x0080, speed=0x0004
        romData[fxPc + 2] = 0x00; romData[fxPc + 3] = 0x01  // liquidStart = 0x0100
        romData[fxPc + 4] = 0x80.toByte(); romData[fxPc + 5] = 0x00  // liquidNew = 0x0080
        romData[fxPc + 6] = 0x04; romData[fxPc + 7] = 0x00  // liquidSpeed = 0x0004
        romData[fxPc + 8] = 0x10  // liquidDelay = 0x10
        romData[fxPc + 9] = 0x04  // fxType = Water

        val patchedParser = RomParser(romData)
        val readFx = patchedParser.parseFxEntries(room.fxPtr).lastOrNull { it.doorSelect == 0 }!!
        assertEquals(0x0100, readFx.liquidSurfaceStart)
        assertEquals(0x0080, readFx.liquidSurfaceNew)
        assertEquals(0x0004, readFx.liquidSpeed)
        assertEquals(0x10, readFx.liquidDelay)
        assertEquals(0x04, readFx.fxType)
        assertTrue(readFx.hasLiquid)
        println("FX liquid round-trip: PASS")
    }

    // ─── State data field editing ───────────────────────────────────

    @Test
    fun `state data tileset edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.isNotEmpty())

        val origTileset = room.tileset
        val newTileset = if (origTileset == 0) 1 else 0

        // Write to all state data blocks
        for (stateOff in allStates) {
            romData[stateOff + 3] = newTileset.toByte()
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(newTileset, readRoom.tileset,
            "Tileset should be $newTileset after edit")
        println("Tileset edit round-trip: PASS ($origTileset → $newTileset)")
    }

    @Test
    fun `state data music edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)

        val origMusicData = room.musicData
        val origMusicTrack = room.musicTrack

        for (stateOff in allStates) {
            romData[stateOff + 4] = 0x12.toByte()  // musicData
            romData[stateOff + 5] = 0x05.toByte()  // musicTrack
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(0x12, readRoom.musicData)
        assertEquals(0x05, readRoom.musicTrack)
        println("Music edit round-trip: PASS (data: 0x${origMusicData.toString(16)} → 0x12, " +
                "track: 0x${origMusicTrack.toString(16)} → 0x05)")
    }

    @Test
    fun `state data BG scrolling edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)

        val origBg = room.bgScrolling
        val newBg = 0x0001  // layer 2 follows layer 1

        for (stateOff in allStates) {
            romData[stateOff + 12] = (newBg and 0xFF).toByte()
            romData[stateOff + 13] = ((newBg shr 8) and 0xFF).toByte()
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(newBg, readRoom.bgScrolling,
            "BG scrolling should be 0x${newBg.toString(16)}")
        println("BG scrolling edit round-trip: PASS (0x${origBg.toString(16)} → 0x${newBg.toString(16)})")
    }

    // ─── Data model tests ───────────────────────────────────────────

    @Test
    fun `ScrollChange stores before and after values`() {
        val sc = ScrollChange(screenX = 2, screenY = 1, oldValue = 0x01, newValue = 0x00)
        assertEquals(2, sc.screenX)
        assertEquals(1, sc.screenY)
        assertEquals(0x01, sc.oldValue)
        assertEquals(0x00, sc.newValue)
    }

    @Test
    fun `FxChange only stores non-null diff fields`() {
        val fx = FxChange(fxType = 0x0A, paletteBlend = 0x42)
        assertEquals(0x0A, fx.fxType)
        assertEquals(0x42, fx.paletteBlend)
        assertNull(fx.liquidSurfaceStart)
        assertNull(fx.liquidSpeed)
        assertNull(fx.fxBitA)
    }

    @Test
    fun `StateDataChange only stores non-null diff fields`() {
        val sd = StateDataChange(tileset = 5, bgScrolling = 0x0001)
        assertEquals(5, sd.tileset)
        assertEquals(0x0001, sd.bgScrolling)
        assertNull(sd.musicData)
        assertNull(sd.musicTrack)
    }

    @Test
    fun `RoomEdits includes scroll, FX, and state data changes`() {
        val edits = RoomEdits(roomId = 0x91F8)
        assertTrue(edits.scrollChanges.isEmpty())
        assertNull(edits.fxChange)
        assertNull(edits.stateDataChange)

        edits.scrollChanges.add(ScrollChange(0, 0, 0x01, 0x00))
        edits.fxChange = FxChange(fxType = 0x06)
        edits.stateDataChange = StateDataChange(tileset = 3)

        assertEquals(1, edits.scrollChanges.size)
        assertEquals(0x06, edits.fxChange!!.fxType)
        assertEquals(3, edits.stateDataChange!!.tileset)
    }

    @Test
    fun `empty FxChange equals default`() {
        assertEquals(FxChange(), FxChange(),
            "Two empty FxChanges should be equal (no changes to apply)")
    }

    @Test
    fun `empty StateDataChange equals default`() {
        assertEquals(StateDataChange(), StateDataChange(),
            "Two empty StateDataChanges should be equal (no changes to apply)")
    }

    // ─── Multi-state per-state field variation (regression for remember-key bug) ──

    @Test
    fun `multi-state rooms have per-state music and tileset values`() {
        val parser = loadTestRom() ?: return
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.size >= 2,
            "Landing Site needs multiple states for this test, got ${allStates.size}")

        val stateFields = allStates.map { off ->
            val data = parser.readStateData(off)
            Triple(data["tileset"]!!, data["musicData"]!!, data["musicTrack"]!!)
        }

        println("Landing Site per-state fields:")
        for ((i, f) in stateFields.withIndex()) {
            println("  State #$i: tileset=${f.first}, musicData=0x${f.second.toString(16)}, musicTrack=0x${f.third.toString(16)}")
        }

        for ((i, f) in stateFields.withIndex()) {
            assertTrue(f.first in 0..29, "State #$i tileset ${f.first} out of range")
            assertTrue(f.second in 0..0xFF, "State #$i musicData out of byte range")
            assertTrue(f.third in 0..0xFF, "State #$i musicTrack out of byte range")
        }
    }

    @Test
    fun `StateDataChange is empty when edit matches orig - prevents false positives`() {
        val parser = loadTestRom() ?: return
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.size >= 2)

        for ((i, stateOff) in allStates.withIndex()) {
            val data = parser.readStateData(stateOff)
            val origTileset = data["tileset"]!!
            val origMusicData = data["musicData"]!!
            val origMusicTrack = data["musicTrack"]!!
            val origBg = data["bgScrolling"]!!

            val change = StateDataChange(
                tileset = origTileset.takeIf { it != origTileset },
                musicData = origMusicData.takeIf { it != origMusicData },
                musicTrack = origMusicTrack.takeIf { it != origMusicTrack },
                bgScrolling = origBg.takeIf { it != origBg }
            )
            assertEquals(StateDataChange(), change,
                "State #$i: editing to match orig values should produce empty change (no false positive)")
        }
    }

    @Test
    fun `stale edit from one state produces false positive against different state`() {
        val parser = loadTestRom() ?: return
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.size >= 2, "Need multi-state room")

        val state0Data = parser.readStateData(allStates[0])
        val state1Data = parser.readStateData(allStates.last())

        val music0 = state0Data["musicData"]!!
        val music1 = state1Data["musicData"]!!

        if (music0 == music1) {
            println("Both states have same musicData ($music0), checking tileset instead")
            val ts0 = state0Data["tileset"]!!
            val ts1 = state1Data["tileset"]!!
            if (ts0 != ts1) {
                val staleChange = StateDataChange(
                    tileset = ts0.takeIf { it != ts1 }
                )
                assertNotEquals(StateDataChange(), staleChange,
                    "Stale tileset from state 0 ($ts0) compared against state 1 ($ts1) should flag a false positive")
                println("Confirmed: stale tileset $ts0 vs orig $ts1 produces false StateDataChange(tileset=$ts0)")
            } else {
                println("Both states have identical tileset and music - cannot reproduce cross-state scenario with this room")
            }
            return
        }

        val staleChange = StateDataChange(
            musicData = music0.takeIf { it != music1 }
        )
        assertNotEquals(StateDataChange(), staleChange,
            "Stale musicData from state 0 (0x${music0.toString(16)}) compared against " +
            "state 1's orig (0x${music1.toString(16)}) should flag a false positive - " +
            "this is the bug that caused blank screen on new game")
        println("Confirmed: stale musicData 0x${music0.toString(16)} vs orig 0x${music1.toString(16)} " +
                "produces false StateDataChange - the remember-key bug would cause this")
    }

    // ─── Multi-room scroll analysis ─────────────────────────────────

    @Test
    fun `rooms with hidden screens have Red scroll values`() {
        val parser = loadTestRom() ?: return
        val repo = com.supermetroid.editor.data.RoomRepository()
        val allRooms = repo.getAllRooms()
        var roomsWithRedScrolls = 0

        for (roomInfo in allRooms) {
            val roomId = try { roomInfo.getRoomIdAsInt() } catch (_: Exception) { continue }
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.roomScrollsPtr <= 1) continue
            val scrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
            if (scrolls.any { it == 0x00 }) {
                roomsWithRedScrolls++
            }
        }
        assertTrue(roomsWithRedScrolls > 0,
            "Should find at least one room with hidden (Red) scroll screens")
        println("Rooms with Red (hidden) scroll screens: $roomsWithRedScrolls")
    }

    // ─── Room header change ──────────────────────────────────────────

    @Test
    fun `RoomHeaderChange with all fields null equals default`() {
        assertEquals(RoomHeaderChange(), RoomHeaderChange())
    }

    @Test
    fun `RoomHeaderChange with some fields set is not default`() {
        val change = RoomHeaderChange(area = 2, width = 3)
        assertNotEquals(RoomHeaderChange(), change)
        assertEquals(2, change.area)
        assertEquals(3, change.width)
        assertNull(change.index)
        assertNull(change.mapX)
        assertNull(change.height)
        assertNull(change.doorOut)
    }

    @Test
    fun `RoomHeaderChange covers all 11 header bytes`() {
        val change = RoomHeaderChange(
            index = 5,          // byte 0
            area = 1,           // byte 1
            mapX = 10,          // byte 2
            mapY = 20,          // byte 3
            width = 3,          // byte 4
            height = 4,         // byte 5
            upScroller = 0x70,  // byte 6
            downScroller = 0xA0,// byte 7
            creBitflag = 0x02,  // byte 8
            doorOut = 0x8F00,   // bytes 9-10
        )
        assertEquals(5, change.index)
        assertEquals(1, change.area)
        assertEquals(10, change.mapX)
        assertEquals(20, change.mapY)
        assertEquals(3, change.width)
        assertEquals(4, change.height)
        assertEquals(0x70, change.upScroller)
        assertEquals(0xA0, change.downScroller)
        assertEquals(0x02, change.creBitflag)
        assertEquals(0x8F00, change.doorOut)
    }

    @Test
    fun `room header round-trip preserves all fields`() {
        val parser = loadTestRom() ?: return
        // Landing Site
        val room = parser.readRoomHeader(0x91F8) ?: fail("Landing Site not found")
        assertEquals(0, room.area)
        assertEquals(23, room.mapX)  // known Landing Site mapX
        assertEquals(0, room.mapY)
        assertEquals(9, room.width)
        assertEquals(5, room.height)

        // Apply header change to ROM copy
        val romCopy = parser.getRomData().copyOf()
        val headerPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or 0x91F8)
        romCopy[headerPc] = 0x42.toByte()        // index
        romCopy[headerPc + 4] = 0x03.toByte()    // width = 3
        romCopy[headerPc + 5] = 0x02.toByte()    // height = 2
        romCopy[headerPc + 9] = 0xAA.toByte()    // doorOut low
        romCopy[headerPc + 10] = 0xBB.toByte()   // doorOut high

        val modified = RomParser(romCopy)
        val room2 = modified.readRoomHeader(0x91F8) ?: fail("Modified room not found")
        assertEquals(0x42, room2.index)
        assertEquals(3, room2.width)
        assertEquals(2, room2.height)
        assertEquals(0xBBAA, room2.doorOut)
    }

    // ─── FX data parsing ─────────────────────────────────────────────

    @Test
    fun `FxChange includes all 16-byte entry fields`() {
        val change = FxChange(
            fxType = 0x04,
            liquidSurfaceStart = 0x0100,
            liquidSurfaceNew = 0x0080,
            liquidSpeed = 0x0001,
            liquidDelay = 0x10,
            fxBitA = 0x02,
            fxBitB = 0x1C,
            fxBitC = 0x05,
            paletteFxBitflags = 0x0F,
            tileAnimBitflags = 0x03,
            paletteBlend = 0x08,
        )
        assertEquals(0x04, change.fxType)
        assertEquals(0x05, change.fxBitC)
        assertEquals(0x0F, change.paletteFxBitflags)
        assertEquals(0x03, change.tileAnimBitflags)
        assertNotEquals(FxChange(), change)
    }

    @Test
    fun `FxChange with only bitC set is not default`() {
        val change = FxChange(fxBitC = 0x01)
        assertNotEquals(FxChange(), change)
    }

    @Test
    fun `FX entries parse for rooms with liquid`() {
        val parser = loadTestRom() ?: return
        // Maridia Aqueduct room (0xD5A7) has water FX
        val room = parser.readRoomHeader(0xD5A7)
        if (room == null) {
            println("Room 0xD5A7 not found, skipping")
            return
        }
        val fxEntries = parser.parseFxEntries(room.fxPtr)
        assertTrue(fxEntries.isNotEmpty(), "Maridia room should have FX entries")
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        if (defaultFx != null) {
            println("FX type: 0x${defaultFx.fxType.toString(16)}, " +
                "bitC: 0x${defaultFx.fxBitC.toString(16)}, " +
                "palFx: 0x${defaultFx.paletteFxBitflags.toString(16)}, " +
                "tileAnim: 0x${defaultFx.tileAnimBitflags.toString(16)}")
        }
    }
}
