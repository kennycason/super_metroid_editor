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
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrocomireSourceParityTest {

    @Tag("parity")
    @Test
    fun `Crocomire split graphics death phases and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val tiles = assertNotNull(
            EnemySpriteGraphics.loadEnemyTileData(parser, CrocomireSpritemap.SPECIES_ID),
        )
        val renderer = CrocomireSpritemap(parser)
        assertTrue(renderer.load(tiles), "Crocomire renderer should load")

        val livingOwnership = root.getValue("ownership").jsonObject
            .getValue("livingObjPixels").jsonObject
        val rawTiles = assertNotNull(renderer.getRawEnemyTileData())
        assertEquals(livingOwnership.int("size"), rawTiles.size)
        assertEquals(
            root.getValue("assets").jsonArray.map { it.jsonObject }
                .single { it.string("sourceLabel") == "Tiles_Crocomire" }.string("sha256"),
            TestRomHelper.sha256(rawTiles),
        )

        val rom = parser.getRomData()
        root.getValue("assets").jsonArray.map { it.jsonObject }.forEach { asset ->
            val pc = parser.snesToPc(asset.int("snesAddress"))
            val bytes = rom.copyOfRange(pc, pc + asset.int("size"))
            assertEquals(asset.string("sha256"), TestRomHelper.sha256(bytes), asset.string("sourceLabel"))
        }

        val sourceLists = root.getValue("instructionLists").jsonArray.map { it.jsonObject }
            .associateBy { it.int("snesAddress") }
        assertEquals(sourceLists.keys, CrocomireSpritemap.INSTRUCTION_LISTS.map { it.snesAddress }.toSet())
        CrocomireSpritemap.INSTRUCTION_LISTS.forEach { definition ->
            val source = sourceLists.getValue(definition.snesAddress)
            assertEquals(definition.byteCount, source.int("size"), definition.name)
            val expected = source.getValue("records").jsonArray.map { it.jsonObject }
                .filter { it.string("kind") == "frame" }
            val decoded = renderer.sourceFrames(definition)
            assertEquals(expected.size, decoded.size, definition.name)
            expected.zip(decoded).forEach { (record, frame) ->
                assertEquals(record.int("duration"), frame.durationTicks, definition.name)
                assertEquals(record.int("spritemapSnesAddress"), frame.snesAddress, definition.name)
            }
        }

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        updatePixelDigest(paletteDigest, assertNotNull(renderer.readPalette()))
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        CrocomireSpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComposition(definition), definition.name)
            assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, definition.name)
            updatePixelDigest(compositionDigest, rendered.pixels)
        }
        val componentDigest = MessageDigest.getInstance("SHA-256")
        CrocomireSpritemap.COMPONENTS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComponent(definition), definition.name)
            updatePixelDigest(componentDigest, rendered.pixels)
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        var animationFrames = 0
        CrocomireSpritemap.ANIMATIONS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderAnimation(definition), definition.name)
            assertEquals(definition.expectedFrameCount, rendered.frames.size, definition.name)
            rendered.frames.forEach { frame -> updatePixelDigest(animationDigest, frame.pixels) }
            animationFrames += rendered.frames.size
        }
        assertEquals(TestRomHelper.referenceInt("crocomire.guidedAnimation.frame.count"), animationFrames)

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val componentHash = componentDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("crocomire.render.palette.sha256=$paletteHash")
        println("crocomire.render.compositionPixel.aggregate.sha256=$compositionHash")
        println("crocomire.render.componentPixel.aggregate.sha256=$componentHash")
        println("crocomire.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(TestRomHelper.referenceString("crocomire.render.palette.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("crocomire.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("crocomire.render.componentPixel.aggregate.sha256"), componentHash)
        assertEquals(TestRomHelper.referenceString("crocomire.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "crocomire.header.count",
            "assetCount" to "crocomire.asset.count",
            "assetByteCount" to "crocomire.asset.byte.count",
            "standardSpritemapCount" to "crocomire.standardSpritemap.count",
            "standardOamEntryCount" to "crocomire.standardOamEntry.count",
            "extendedSpritemapCount" to "crocomire.extendedSpritemap.count",
            "extendedChildCount" to "crocomire.extendedChild.count",
            "tilemapCount" to "crocomire.tilemap.count",
            "tilemapRunCount" to "crocomire.tilemap.run.count",
            "tilemapWordCount" to "crocomire.tilemap.word.count",
            "instructionListCount" to "crocomire.instructionList.count",
            "frameOccurrenceCount" to "crocomire.frame.count",
            "uniqueFrameCount" to "crocomire.frame.unique.count",
            "handlerOccurrenceCount" to "crocomire.handler.count",
            "guidedAnimationCount" to "crocomire.guidedAnimation.count",
            "guidedAnimationFrameCount" to "crocomire.guidedAnimation.frame.count",
            "unusedInstructionListCount" to "crocomire.unusedInstructionList.count",
            "paletteCount" to "crocomire.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "crocomire.ownership.aggregate.sha256",
            "headers" to "crocomire.header.aggregate.sha256",
            "assets" to "crocomire.asset.aggregate.sha256",
            "standardSpritemaps" to "crocomire.standardSpritemap.aggregate.sha256",
            "extendedSpritemaps" to "crocomire.extendedSpritemap.aggregate.sha256",
            "tilemaps" to "crocomire.tilemap.aggregate.sha256",
            "instructionLists" to "crocomire.instructionList.aggregate.sha256",
            "guidedAnimations" to "crocomire.guidedAnimation.aggregate.sha256",
            "unusedInstructionLists" to "crocomire.unusedInstructionList.aggregate.sha256",
            "palettes" to "crocomire.palette.aggregate.sha256",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun updatePixelDigest(digest: MessageDigest, pixels: IntArray) = pixels.forEach { pixel ->
        digest.update((pixel ushr 24).toByte())
        digest.update((pixel ushr 16).toByte())
        digest.update((pixel ushr 8).toByte())
        digest.update(pixel.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("crocomire.json", "parityCrocomire").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
