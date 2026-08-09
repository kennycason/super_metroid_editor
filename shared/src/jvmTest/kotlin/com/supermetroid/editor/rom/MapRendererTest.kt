package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapRendererTest {

    private var romParser: RomParser? = null
    private var renderer: MapRenderer? = null

    @BeforeAll
    fun setUp() {
        romParser = TestRomHelper.loadRomParser()
        romParser?.let { renderer = MapRenderer(it) }
    }

    private fun loadRoom(roomId: Int): Room? {
        val rp = romParser ?: return null
        return rp.readRoomHeader(roomId)
    }

    private fun oneScreenLevelData(firstBlockWord: Int): ByteArray {
        val totalBlocks = 16 * 16
        val layer1Size = totalBlocks * 2
        val data = ByteArray(2 + layer1Size + totalBlocks)
        data[0] = (layer1Size and 0xFF).toByte()
        data[1] = ((layer1Size shr 8) and 0xFF).toByte()
        data[2] = (firstBlockWord and 0xFF).toByte()
        data[3] = ((firstBlockWord shr 8) and 0xFF).toByte()
        return data
    }

    private fun syntheticCrateriaRoom(tileset: Int = 0): Room =
        Room(
            roomId = 0x91F8,
            name = "Synthetic Crateria",
            handle = "syntheticCrateria",
            index = 0,
            area = 0,
            mapX = 0,
            mapY = 0,
            width = 1,
            height = 1,
            upScroller = 0,
            downScroller = 0,
            creBitflag = 0,
            doorOut = 0,
            tileset = tileset,
        )

    // ── Basic room rendering ─────────────────────────────────────

    @Nested
    inner class BasicRendering {
        @Test
        fun `renderRoom returns valid data for Landing Site (0x91F8)`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)
            assertNotNull(result)
            assertEquals(room.width * 16, result!!.blocksWide)
            assertEquals(room.height * 16, result.blocksTall)
            assertEquals(result.blocksWide * 16, result.width)
            assertEquals(result.blocksTall * 16, result.height)
            assertEquals(result.width * result.height, result.pixels.size)
        }

        @Test
        fun `renderRoom produces non-uniform pixels`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            val uniqueColors = result.pixels.toSet()
            assertTrue(uniqueColors.size > 10, "Should have variety in pixels, got ${uniqueColors.size}")
        }

        @Test
        fun `renderRoom returns block types array with correct size`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            val expectedBlocks = room.width * 16 * room.height * 16
            assertEquals(expectedBlocks, result.blockTypes.size)
            assertEquals(expectedBlocks, result.btsData.size)
        }

        @Test
        fun `block types contain valid values (0-15)`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            for (bt in result.blockTypes) {
                assertTrue(bt in 0..15, "Block type $bt out of range")
            }
        }

        @Test
        fun `Landing Site has solid blocks (type 8)`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            assertTrue(result.blockTypes.any { it == 0x8 }, "Should have solid blocks")
        }

        @Test
        fun `Landing Site has air blocks (type 0)`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            assertTrue(result.blockTypes.any { it == 0x0 }, "Should have air blocks")
        }
    }

    // ── PLM and enemy parsing in render ──────────────────────────

    @Nested
    inner class PlmAndEnemyData {
        @Test
        fun `Landing Site render includes PLM entries`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            assertTrue(result.plmEntries.isNotEmpty(), "Landing Site has PLMs (door caps, etc.)")
        }

        @Test
        fun `room with items has item blocks (Morph Ball room 0x9E9F)`() {
            val mr = renderer ?: return
            val room = loadRoom(0x9E9F) ?: return
            val result = mr.renderRoom(room)!!
            assertTrue(result.itemBlocks.isNotEmpty(), "Morph Ball room should have item PLMs")
        }

        @Test
        fun `renderRoom includes enemy entries`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoom(room)!!
            assertNotNull(result.enemyEntries)
        }
    }

    // ── renderRoomFromLevelData ──────────────────────────────────

    @Nested
    inner class FromLevelData {
        @Test
        fun `renderRoomFromLevelData works with decompressed data`() {
            val rp = romParser ?: return
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val levelData = rp.decompressLZ2(room.levelDataPtr)
            val result = mr.renderRoomFromLevelData(room, levelData)
            assertNotNull(result)
            assertTrue(result!!.pixels.isNotEmpty())
        }

        @Test
        fun `renderRoomFromLevelData with empty data returns grid fallback`() {
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val result = mr.renderRoomFromLevelData(room, ByteArray(0))
            assertNotNull(result)
            assertEquals(room.width * 16 * 16, result!!.width)
        }

        @Test
        fun `renderRoomFromLevelData accepts PLM overrides`() {
            val rp = romParser ?: return
            val mr = renderer ?: return
            val room = loadRoom(0x91F8)!!
            val levelData = rp.decompressLZ2(room.levelDataPtr)
            val result = mr.renderRoomFromLevelData(room, levelData, plmOverrides = emptyList())
            assertNotNull(result)
            assertTrue(result!!.plmEntries.isEmpty(), "Should use the overrides")
        }

        @Test
        fun `renderRoomFromLevelData draws Crateria pipe metatiles instead of collision fallback`() {
            val rp = romParser ?: return
            val mr = renderer ?: return
            val tileGraphics = TileGraphics(rp)
            assertTrue(tileGraphics.loadTileset(0))
            val expected = tileGraphics.renderMetatile(157)
            assertNotNull(expected)
            val levelData = oneScreenLevelData(0x809D)

            val result = mr.renderRoomFromLevelData(
                syntheticCrateriaRoom(),
                levelData,
                plmOverrides = emptyList(),
                enemyOverrides = emptyList(),
            )
            assertNotNull(result)

            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    assertEquals(expected!![y * 16 + x], result!!.pixels[y * result.width + x])
                }
            }
        }
    }

    // ── Multiple rooms across areas ──────────────────────────────

    @Nested
    inner class MultipleRooms {
        @Test
        fun `render rooms from different areas`() {
            val mr = renderer ?: return
            val roomIds = listOf(
                0x91F8,  // Landing Site (Crateria)
                0x9AD9,  // Green Brinstar main shaft
                0xA6A1,  // Red Tower (Norfair)
                0xCC6F,  // Wrecked Ship entrance
                0xCFC9,  // Maridia (aqueduct)
            )
            for (id in roomIds) {
                val room = loadRoom(id) ?: continue
                val result = mr.renderRoom(room)
                assertNotNull(result, "Room 0x${id.toString(16)} should render")
                assertTrue(result!!.pixels.isNotEmpty(), "Room 0x${id.toString(16)} should have pixels")
                assertTrue(result.blocksWide > 0)
                assertTrue(result.blocksTall > 0)
            }
        }
    }
}
