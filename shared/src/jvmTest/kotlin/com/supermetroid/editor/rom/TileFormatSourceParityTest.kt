package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TileFormatSourceParityTest {

    @Tag("parity")
    @Test
    fun `all source tiles match independent 2bpp and 4bpp pixel oracles`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadOracle()
        val totals = root.getValue("totals").jsonObject
        val decoder = TileDecoder()
        val graphics4bpp = root.getValue("graphics4bpp").jsonArray.map { it.jsonObject }

        assertEquals(
            TestRomHelper.referenceInt("tileFormats.graphics4bpp.resource.count"),
            graphics4bpp.size,
        )
        assertEquals(
            TestRomHelper.referenceInt("tileFormats.graphics4bpp.tile.count"),
            totals.int("graphics4bppTileCount"),
        )
        graphics4bpp.forEach { record ->
            val decoded = parser.decompressLZ2(record.int("snesAddress"))
            assertEquals(record.int("decompressedSize"), decoded.size, record.string("sourceLabel"))
            val tileCount = record.int("tileCount")
            val decoderPixels = flattenTiles(tileCount) { tile ->
                when (record.string("layout")) {
                    "standard-4bpp" -> decoder.decode4bppTileIndices(
                        decoded,
                        tile * RomConstants.BYTES_PER_4BPP_TILE,
                    )
                    "global-split-plane-4bpp" -> decoder.decodeSplitPlane4bppTileIndices(
                        decoded,
                        tile,
                        tileCount,
                    )
                    else -> error("Unknown 4bpp layout ${record.string("layout")}")
                }
            }
            assertEquals(
                record.string("pixelSha256"),
                TestRomHelper.sha256(decoderPixels),
                "${record.string("sourceLabel")} TileDecoder pixels",
            )

            val tileGraphics = TileGraphics(parser)
            val runtimeStart = record.int("runtimeTileStart")
            val representativeTileset = if (record.string("role") == "cre") {
                0
            } else {
                record.getValue("tilesetIds").jsonArray.first().jsonPrimitive.int
            }
            assertTrue(tileGraphics.loadTileset(representativeTileset))
            val runtimePixels = flattenTiles(tileCount) { tile ->
                assertNotNull(
                    tileGraphics.readTileIndices(runtimeStart + tile),
                    "${record.string("sourceLabel")} runtime tile $tile",
                )
            }
            assertEquals(
                record.string("pixelSha256"),
                TestRomHelper.sha256(runtimePixels),
                "${record.string("sourceLabel")} TileGraphics pixels",
            )

            val undefinedCount = record.int("undefinedReservedTileCount")
            if (undefinedCount > 0) {
                val undefinedStart = runtimeStart + tileCount
                repeat(undefinedCount) { index ->
                    val pixels = assertNotNull(tileGraphics.readTileIndices(undefinedStart + index))
                    assertTrue(
                        pixels.all { it == 0 },
                        "${record.string("sourceLabel")} reserved tile ${undefinedStart + index} must be blank",
                    )
                }
            }
        }

        val graphics2bpp = root.getValue("graphics2bpp").jsonArray.map { it.jsonObject }
        assertEquals(
            TestRomHelper.referenceInt("tileFormats.graphics2bpp.resource.count"),
            graphics2bpp.size,
        )
        graphics2bpp.forEach { record ->
            val pc = parser.snesToPc(record.int("snesAddress"))
            val raw = parser.getRomData().copyOfRange(pc, pc + record.int("size"))
            val pixels = flattenTiles(record.int("tileCount")) { tile ->
                decoder.decode2bppTileIndices(raw, tile * 16)
            }
            assertEquals(record.string("pixelSha256"), TestRomHelper.sha256(pixels))
        }

        assertMetatileFlipRendering(parser)
    }

    @Tag("parity")
    @Test
    fun `every source metatile word and combined table placement matches oracle`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadOracle()
        val totals = root.getValue("totals").jsonObject
        val tables = root.getValue("metatileTables").jsonArray.map { it.jsonObject }

        assertEquals(
            TestRomHelper.referenceInt("tileFormats.metatileTable.resource.count"),
            tables.size,
        )
        assertEquals(
            TestRomHelper.referenceInt("tileFormats.metatile.count"),
            totals.int("metatileCount"),
        )
        assertEquals(
            TestRomHelper.referenceInt("tileFormats.metatileWord.count"),
            totals.int("metatileWordCount"),
        )

        for (word in 0..0xFFFF) {
            val decoded = TileGraphics.decodeMetatileWord(word)
            assertEquals(word and 0x03FF, decoded.tileNum)
            assertEquals((word ushr 10) and 7, decoded.palette)
            assertEquals(word and 0x2000 != 0, decoded.priority)
            assertEquals(word and 0x4000 != 0, decoded.hFlip)
            assertEquals(word and 0x8000 != 0, decoded.vFlip)
            assertEquals(
                word,
                TileGraphics.encodeMetatileWord(
                    tileNum = decoded.tileNum,
                    palette = decoded.palette,
                    priority = decoded.priority,
                    hFlip = decoded.hFlip,
                    vFlip = decoded.vFlip,
                ),
            )
        }

        tables.forEach { record ->
            val raw = parser.decompressLZ2(record.int("snesAddress"))
            assertEquals(record.int("decompressedSize"), raw.size)
            assertEquals(record.string("semanticSha256"), semanticHash(raw), record.string("sourceLabel"))

            val tileGraphics = TileGraphics(parser)
            val representativeTileset = if (record.string("role") == "cre") {
                0
            } else {
                record.getValue("tilesetIds").jsonArray.first().jsonPrimitive.int
            }
            assertTrue(tileGraphics.loadTileset(representativeTileset))
            val runtimeStart = record.int("runtimeMetatileStart")
            val exported = if (record.string("role") == "cre") {
                tileGraphics.getRawCreTileTable()
            } else {
                tileGraphics.getRawVarTileTable()
            }
            assertContentEquals(raw, assertNotNull(exported), "${record.string("sourceLabel")} raw ownership")
            repeat(record.int("metatileCount")) { metatile ->
                val sourceOffset = metatile * 8
                val expectedWords = IntArray(4) { quadrant ->
                    readU16(raw, sourceOffset + quadrant * 2)
                }
                assertContentEquals(
                    expectedWords,
                    assertNotNull(tileGraphics.getMetatileWords(runtimeStart + metatile)),
                    "${record.string("sourceLabel")} metatile $metatile",
                )
            }

            if (record.string("role") == "tileset" && runtimeStart == TileGraphics.CRE_METATILE_COUNT) {
                assertEquals(TileGraphics.MetatileTableSource.CRE, tileGraphics.metatileTableSource(0))
                assertEquals(
                    TileGraphics.MetatileTableSource.VARIABLE,
                    tileGraphics.metatileTableSource(runtimeStart),
                )
            } else if (record.string("role") == "tileset" && runtimeStart == 0) {
                assertEquals(0, tileGraphics.creMetatileCount())
                assertEquals(TileGraphics.MetatileTableSource.VARIABLE, tileGraphics.metatileTableSource(0))
            }
        }
    }

    private fun assertMetatileFlipRendering(parser: RomParser) {
        val graphics = TileGraphics(parser)
        assertTrue(graphics.loadTileset(0))
        val tile = 575
        val sourceIndices = IntArray(64) { index -> (index % 15) + 1 }
        for (y in 0 until 8) for (x in 0 until 8) {
            graphics.writePixelIndex(tile, x, y, sourceIndices[y * 8 + x])
        }
        val words = intArrayOf(
            TileGraphics.encodeMetatileWord(tile, 0, priority = false, hFlip = false, vFlip = false),
            TileGraphics.encodeMetatileWord(tile, 0, priority = true, hFlip = true, vFlip = false),
            TileGraphics.encodeMetatileWord(tile, 0, priority = false, hFlip = false, vFlip = true),
            TileGraphics.encodeMetatileWord(tile, 0, priority = true, hFlip = true, vFlip = true),
        )
        val rendered = assertNotNull(graphics.renderMetatileWords(words))
        val palette = assertNotNull(graphics.getPalettes())[0]
        repeat(4) { quadrant ->
            val decoded = TileGraphics.decodeMetatileWord(words[quadrant])
            val baseX = (quadrant and 1) * 8
            val baseY = (quadrant ushr 1) * 8
            for (y in 0 until 8) for (x in 0 until 8) {
                val sourceX = if (decoded.hFlip) 7 - x else x
                val sourceY = if (decoded.vFlip) 7 - y else y
                val colorIndex = sourceIndices[sourceY * 8 + sourceX]
                assertEquals(
                    palette[colorIndex],
                    rendered[(baseY + y) * 16 + baseX + x],
                    "quadrant $quadrant pixel ($x,$y)",
                )
            }
        }
    }

    private fun flattenTiles(tileCount: Int, decode: (Int) -> IntArray): ByteArray {
        val result = ByteArray(tileCount * 64)
        repeat(tileCount) { tile ->
            val pixels = decode(tile)
            assertEquals(64, pixels.size, "tile $tile pixel count")
            pixels.forEachIndexed { index, value ->
                result[tile * 64 + index] = value.toByte()
            }
        }
        return result
    }

    private fun semanticHash(raw: ByteArray): String {
        val canonical = ByteArray(raw.size / 2 * 4)
        repeat(raw.size / 2) { index ->
            val decoded = TileGraphics.decodeMetatileWord(readU16(raw, index * 2))
            val offset = index * 4
            canonical[offset] = (decoded.tileNum and 0xFF).toByte()
            canonical[offset + 1] = (decoded.tileNum ushr 8).toByte()
            canonical[offset + 2] = decoded.palette.toByte()
            canonical[offset + 3] = (
                (if (decoded.priority) 1 else 0) or
                    (if (decoded.hFlip) 2 else 0) or
                    (if (decoded.vFlip) 4 else 0)
                ).toByte()
        }
        return TestRomHelper.sha256(canonical)
    }

    private fun loadOracle(): JsonObject {
        val report = TestRomHelper.requireParityReport("tile-formats.json", "parityTileFormats")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
