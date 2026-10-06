package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnemyVerticalSliceSourceParityTest {

    @Tag("parity")
    @Test
    fun `Zoomer Sidehopper and walking Space Pirate match complete source slices`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        assertPinnedManifest(root)
        val production = EnemySpritemap(parser)

        root.getValue("slices").jsonArray.map { it.jsonObject }.forEach { slice ->
            val context = slice.string("displayName")
            val speciesId = slice.int("speciesId")
            val header = assertNotNull(EnemySpriteGraphics.readSpeciesHeader(parser, speciesId), context)
            assertEquals(slice.int("aiBank"), header.aiBank, "$context AI bank")
            assertEquals(slice.int("tileDataSnesAddress"), header.tileDataAddress, "$context GRAPHADR")
            assertEquals(slice.int("tileDataSize"), header.tileDataSize, "$context tile size")
            assertEquals(
                slice.int("paletteSnesAddress"),
                (header.aiBank shl 16) or header.palettePointer,
                "$context palette address",
            )

            val tileData = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, speciesId), context)
            assertEquals(slice.string("tileDataSha256"), TestRomHelper.sha256(tileData), "$context tile bytes")
            val palettePc = parser.snesToPc(slice.int("paletteSnesAddress"))
            assertEquals(
                slice.string("paletteSha256"),
                TestRomHelper.sha256(rom.copyOfRange(palettePc, palettePc + 32)),
                "$context palette bytes",
            )
            val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, speciesId), context)

            val trace = production.traceInstructionListAt(slice.int("entryListSnesAddress"))
            assertEquals(slice.string("expectedTermination"), trace.termination.name, "$context termination")
            assertEquals(
                slice.int("expectedTerminalSnesAddress"),
                trace.terminalAddress,
                "$context terminal address",
            )
            val expectedHandlers = slice.getValue("handlers").jsonArray.map {
                it.jsonObject.int("handlerSnesAddress")
            }
            assertEquals(expectedHandlers, trace.handlerAddresses, "$context handler path")

            val expectedFrames = slice.getValue("frames").jsonArray.map { it.jsonObject }
            assertEquals(expectedFrames.size, trace.frames.size, "$context traced frames")
            expectedFrames.zip(trace.frames).forEachIndexed { index, (expected, actual) ->
                val frameContext = "$context frame $index"
                assertEquals(expected.int("duration"), actual.duration, "$frameContext duration")
                assertEquals(
                    expected.int("spritemapSnesAddress"),
                    actual.renderableFrame.snesAddress,
                    "$frameContext spritemap",
                )
                when (expected.string("structureType")) {
                    "standard-oam" -> assertIs<EnemySpritemap.RenderableFrame.Oam>(
                        actual.renderableFrame,
                        frameContext,
                    )
                    "extended-spritemap" -> assertIs<EnemySpritemap.RenderableFrame.Extended>(
                        actual.renderableFrame,
                        frameContext,
                    )
                    else -> error("Unknown frame type in $frameContext")
                }

                val flattened = production.flattenRenderableFrame(actual.renderableFrame)
                val expectedEntries = expected.getValue("flattenedOamEntries").jsonArray
                    .map { it.jsonObject }
                assertEquals(expected.int("flattenedOamEntryCount"), flattened.entries.size, frameContext)
                assertEquals(expectedEntries.size, flattened.entries.size, frameContext)
                expectedEntries.zip(flattened.entries).forEachIndexed { entryIndex, (source, entry) ->
                    val entryContext = "$frameContext OAM $entryIndex"
                    assertEquals(source.int("xOffset"), entry.xOffset, entryContext)
                    assertEquals(source.int("yOffset"), entry.yOffset, entryContext)
                    assertEquals(source.int("tileNumber"), entry.tileNum, entryContext)
                    assertEquals(source.int("paletteRow"), entry.palRow, entryContext)
                    assertEquals(source.int("priority"), entry.priority, entryContext)
                    assertEquals(source.boolean("hFlip"), entry.hFlip, entryContext)
                    assertEquals(source.boolean("vFlip"), entry.vFlip, entryContext)
                    assertEquals(source.boolean("is16x16"), entry.is16x16, entryContext)
                }

                val rendered = assertNotNull(
                    production.renderRenderableFrame(actual.renderableFrame, tileData, palette),
                    frameContext,
                )
                assertEquals(expected.int("width"), rendered.width, "$frameContext width")
                assertEquals(expected.int("height"), rendered.height, "$frameContext height")
                assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, "$frameContext visible pixels")
            }

            val speciesFrames = production.findAnimationFrames(speciesId)
            assertEquals(
                trace.frames.map { it.duration to it.renderableFrame.snesAddress },
                speciesFrames.map { it.duration to it.renderableFrame.snesAddress },
                "$context species entry path",
            )
            val animation = assertNotNull(
                production.buildAnimation(speciesId, tileData, palette, context),
                context,
            )
            assertEquals(expectedFrames.size, animation.frames.size, "$context rendered animation")
        }
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "sliceCount" to "enemyVerticalSlices.slice.count",
            "frameOccurrenceCount" to "enemyVerticalSlices.frame.count",
            "uniqueFrameSpritemapCount" to "enemyVerticalSlices.frame.uniqueSpritemap.count",
            "handlerOccurrenceCount" to "enemyVerticalSlices.handler.count",
            "standardFrameOccurrenceCount" to "enemyVerticalSlices.frame.standard.count",
            "extendedFrameOccurrenceCount" to "enemyVerticalSlices.frame.extended.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        assertEquals(
            TestRomHelper.referenceString("enemyVerticalSlices.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string("slices"),
            "vertical-slice aggregate",
        )
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport(
            "enemy-vertical-slices.json",
            "parityEnemyVerticalSlices",
        )
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
}
