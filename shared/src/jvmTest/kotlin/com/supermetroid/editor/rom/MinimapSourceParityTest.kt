package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimapSourceParityTest {

    @Tag("parity")
    @Test
    fun `all area tilemaps and map data masks match source and round trip exactly`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedCounts(root)
        assertPinnedHash(root, "tilemaps")
        assertPinnedHash(root, "mapData")

        root.getValue("areas").jsonArray.map { it.jsonObject }.forEach { expected ->
            val area = expected.int("area")
            val tilemapAddress = expected.int("tilemapAddress")
            val mapDataAddress = expected.int("mapDataAddress")
            assertEquals(tilemapAddress, parser.readMinimapTilemapAddress(area), expected.string("name"))
            assertEquals(mapDataAddress, parser.readMapStationDataAddress(area), expected.string("name"))

            val tilemap = parser.readMinimapTiles(area)
            val tileBytes = ByteArray(MinimapData.TILE_COUNT * 2)
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                val value = tilemap.getTile(x, y)
                val logicalIndex = y * MinimapData.MAP_WIDTH + x
                tileBytes[logicalIndex * 2] = value.toByte()
                tileBytes[logicalIndex * 2 + 1] = (value ushr 8).toByte()

                val rawPc = parser.snesToPc(tilemapAddress) + MinimapData.storageWordIndex(x, y) * 2
                assertEquals(parser.readUInt16At(rawPc), value, "area=$area ($x,$y)")
            }
            assertEquals(expected.string("tilemapLogicalSha256"), TestRomHelper.sha256(tileBytes))

            val tilePatches = parser.writeMinimapTiles(tilemap)
            assertEquals(0x1000, tilePatches.size)
            assertEquals(0x1000, tilePatches.map { it.first }.toSet().size)
            val tileBasePc = parser.snesToPc(tilemapAddress)
            assertTrue(tilePatches.all { it.first in tileBasePc until tileBasePc + 0x1000 })
            tilePatches.forEach { (offset, byte) -> assertEquals(parser.readByteAt(offset).toByte(), byte) }

            val mapData = parser.readMapStationData(area)
            var revealedCount = 0
            for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
                val (byteOffset, mask) = MinimapData.mapDataByteAndMask(x, y)
                val raw = parser.readByteAt(parser.snesToPc(mapDataAddress) + byteOffset)
                val expectedValue = (raw and mask) != 0
                assertEquals(expectedValue, mapData.isRevealed(x, y), "area=$area ($x,$y)")
                if (expectedValue) revealedCount++
            }
            assertEquals(expected.int("revealedTileCount"), revealedCount)

            val mapDataPatches = parser.writeMapStationData(mapData)
            assertEquals(0x100, mapDataPatches.size)
            assertEquals(0x100, mapDataPatches.map { it.first }.toSet().size)
            val mapDataBasePc = parser.snesToPc(mapDataAddress)
            assertTrue(mapDataPatches.all { it.first in mapDataBasePc until mapDataBasePc + 0x100 })
            mapDataPatches.forEach { (offset, byte) -> assertEquals(parser.readByteAt(offset).toByte(), byte) }
        }
    }

    @Tag("parity")
    @Test
    fun `coordinate transforms preserve both pages MSB bit order and padding row`() {
        val indices = mutableSetOf<Int>()
        val masks = mutableSetOf<Pair<Int, Int>>()
        for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
            indices += MinimapData.storageWordIndex(x, y)
            masks += MinimapData.mapDataByteAndMask(x, y)
        }
        assertEquals(MinimapData.TILE_COUNT, indices.size)
        assertEquals(MinimapData.TILE_COUNT, masks.size)
        assertEquals(31, MinimapData.ROOM_MAP_HEIGHT)

        val root = loadManifest()
        assertEquals(MinimapData.ROOM_MAP_HEIGHT, root.int("roomCoordinateHeight"))
        assertEquals(TestRomHelper.referenceInt("minimap.room.maxRight"), root.int("maxRoomRight"))
        assertEquals(TestRomHelper.referenceInt("minimap.room.maxBottom"), root.int("maxRoomBottom"))

        assertEquals(32, MinimapData.storageWordIndex(0, 0))
        assertEquals(0, MinimapData.storageWordIndex(0, 31))
        assertEquals(1056, MinimapData.storageWordIndex(32, 0))
        assertEquals(1024, MinimapData.storageWordIndex(32, 31))
        assertEquals(4 to 0x80, MinimapData.mapDataByteAndMask(0, 0))
        assertEquals(7 to 0x01, MinimapData.mapDataByteAndMask(31, 0))
        assertEquals(0x84 to 0x80, MinimapData.mapDataByteAndMask(32, 0))
        assertEquals(0 to 0x80, MinimapData.mapDataByteAndMask(0, 31))

        val transform = root.getValue("coordinateTransform").jsonObject
        assertEquals(
            TestRomHelper.referenceString("minimap.tilemapTransform.sha256"),
            transform.string("tilemapTransformSha256"),
        )
        assertEquals(
            TestRomHelper.referenceString("minimap.mapDataTransform.sha256"),
            transform.string("mapDataTransformSha256"),
        )
    }

    @Tag("parity")
    @Test
    fun `pause map graphics and all vanilla station placements match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val graphics = root.getValue("graphics").jsonObject
        assertEquals(TestRomHelper.referenceInt("minimap.graphics.tile.count"), graphics.int("tileCount"))
        assertEquals(TestRomHelper.referenceInt("minimap.graphics.usedByte.count"), graphics.int("usedByteCount"))
        val graphicsPc = parser.snesToPc(graphics.int("snesAddress"))
        val raw = parser.getRomData().copyOfRange(graphicsPc, graphicsPc + graphics.int("usedByteCount"))
        assertEquals(TestRomHelper.referenceString("minimap.graphics.raw.sha256"), TestRomHelper.sha256(raw))

        val decoded = parser.readMinimapTileGraphics()
        val pixels = ByteArray(decoded.size * 64)
        decoded.forEachIndexed { tile, values ->
            values.forEachIndexed { pixel, value -> pixels[tile * 64 + pixel] = value.toByte() }
        }
        assertEquals(
            TestRomHelper.referenceString("minimap.graphics.decoded.sha256"),
            TestRomHelper.sha256(pixels),
        )

        val placements = root.getValue("mapStations").jsonObject
            .getValue("placements").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("minimap.mapStationPlacement.count"), placements.size)
        assertEquals((0..4).toList(), placements.map { it.int("area") })
        placements.forEach { placement ->
            val entries = parser.parsePlmSet(placement.int("populationPointer"))
            val entry = entries[placement.int("entryIndex")]
            assertEquals(0xB6D3, entry.id)
            assertEquals(placement.int("x"), entry.x)
            assertEquals(placement.int("y"), entry.y)
            assertEquals(placement.int("param"), entry.param)
        }
        assertPinnedHash(root, "mapStations")
        assertPinnedHash(root, "consumers")
    }

    private fun assertPinnedCounts(root: JsonObject) {
        val fields = mapOf(
            "areaCount" to "minimap.area.count",
            "areaMapPointerCount" to "minimap.areaMapPointer.count",
            "mapDataPointerCount" to "minimap.mapDataPointer.count",
            "mapDataUniquePointerCount" to "minimap.mapDataUniquePointer.count",
            "tilemapWordCount" to "minimap.tilemapWord.count",
            "mapDataByteCount" to "minimap.mapDataByte.count",
            "revealedTileCount" to "minimap.revealedTile.count",
            "mapStationPlacementCount" to "minimap.mapStationPlacement.count",
            "mapStationAreaCount" to "minimap.mapStationArea.count",
            "roomCount" to "minimap.room.count",
            "roomScreenCount" to "minimap.roomScreen.count",
            "roomCoordinateHeight" to "minimap.roomCoordinate.height",
            "maxRoomRight" to "minimap.room.maxRight",
            "maxRoomBottom" to "minimap.room.maxBottom",
            "engineConsumerCount" to "minimap.engineConsumer.count",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), root.int(field), field)
        }
    }

    private fun assertPinnedHash(root: JsonObject, name: String) {
        assertEquals(
            TestRomHelper.referenceString("minimap.$name.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string(name),
            name,
        )
    }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("minimap.json", "parityMinimap").readText(),
    ).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}
