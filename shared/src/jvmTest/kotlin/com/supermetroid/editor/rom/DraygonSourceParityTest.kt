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

class DraygonSourceParityTest {

    @Tag("parity")
    @Test
    fun `Draygon graphics ownership palettes compositions and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load(), "Draygon renderer should load")

        val ownership = root.getValue("ownership").jsonObject
        val objResource = ownership.getValue("objPixels").jsonObject.getValue("resource").jsonObject
        val rawEnemyTiles = assertNotNull(draygon.getRawEnemyTileData())
        assertEquals(objResource.int("size"), rawEnemyTiles.size)
        assertEquals(objResource.string("sha256"), TestRomHelper.sha256(rawEnemyTiles))

        val bgResource = ownership.getValue("bgPixels").jsonObject.getValue("resource").jsonObject
        val rawVarGfx = assertNotNull(draygon.getTileGraphics().getRawVarGfx())
        assertEquals(0x5000, rawVarGfx.size)
        assertEquals(
            bgResource.string("decompressedSha256"),
            TestRomHelper.sha256(rawVarGfx.copyOf(0x4800)),
            "room BG2 tiles remain owned by tileset \$1C rather than the enemy OBJ transfer",
        )

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        val renderedPalettes = DraygonSpritemap.PALETTE_STAGES.map { stage ->
            assertNotNull(draygon.readPalette(stage), stage.name).also { updatePixelDigest(paletteDigest, it) }
        } + assertNotNull(draygon.readPalette(DraygonSpritemap.WHITE_FLASH)).also {
            updatePixelDigest(paletteDigest, it)
        }
        assertEquals(9, renderedPalettes.map { it.contentHashCode() }.distinct().size)

        val compositionDigest = MessageDigest.getInstance("SHA-256")
        val compositions = DraygonSpritemap.COMPOSITIONS.map { definition ->
            assertNotNull(draygon.renderComposition(definition), definition.name).also { sprite ->
                updatePixelDigest(compositionDigest, sprite.pixels)
            }
        }
        assertEquals(10, compositions.map { it.pixels.contentHashCode() }.distinct().size)

        val sourceAnimations = root.getValue("productionAnimations").jsonArray
            .map { it.jsonObject }
            .associateBy { it.string("sourceLabel") }
        assertEquals(sourceAnimations.keys, DraygonSpritemap.ANIMATIONS.map { it.sourceLabel }.toSet())
        val animationDigest = MessageDigest.getInstance("SHA-256")
        var frameCount = 0
        DraygonSpritemap.ANIMATIONS.forEach { definition ->
            val source = sourceAnimations.getValue(definition.sourceLabel)
            assertEquals(source.int("snesAddress"), definition.snesAddr, definition.sourceLabel)
            assertEquals(source.int("endSnesAddressExclusive"), definition.endSnesAddrExclusive, definition.sourceLabel)
            val records = source.getValue("records").jsonArray.map { it.jsonObject }
            val sourceFrames = records.filter { it.string("kind") == "frame" }
            val sourceHandlers = records.filter { it.string("kind") == "handler" }
            val parsed = assertNotNull(draygon.loadAnimation(definition), definition.sourceLabel)
            assertEquals(sourceFrames.size, parsed.frames.size, definition.sourceLabel)
            sourceFrames.zip(parsed.frames).forEach { (frameSource, frame) ->
                assertEquals(frameSource.int("duration"), frame.duration, definition.sourceLabel)
                assertEquals(frameSource.int("spritemapSnesAddress"), frame.spritemapSnes, definition.sourceLabel)
            }
            assertEquals(
                sourceHandlers.map { it.int("handlerSnesAddress") },
                parsed.handlerSnesAddresses,
                definition.sourceLabel,
            )
            val rendered = assertNotNull(draygon.renderAnimation(definition), definition.sourceLabel)
            assertEquals(parsed.frames.size, rendered.frames.size, definition.sourceLabel)
            rendered.frames.forEach { frame -> updatePixelDigest(animationDigest, frame.pixels) }
            frameCount += rendered.frames.size
        }
        assertEquals(250, frameCount)

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        assertEquals(TestRomHelper.referenceString("draygon.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("draygon.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("draygon.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "roomStateCount" to "draygon.roomState.count",
            "headerCount" to "draygon.header.count",
            "standardSpritemapCount" to "draygon.standardSpritemap.count",
            "standardOamEntryCount" to "draygon.standardOamEntry.count",
            "extendedSpritemapCount" to "draygon.extendedSpritemap.count",
            "extendedChildCount" to "draygon.extendedChild.count",
            "tilemapCount" to "draygon.tilemap.count",
            "tilemapRunCount" to "draygon.tilemap.run.count",
            "tilemapWordCount" to "draygon.tilemap.word.count",
            "instructionListCount" to "draygon.instructionList.count",
            "frameOccurrenceCount" to "draygon.frame.count",
            "handlerOccurrenceCount" to "draygon.handler.count",
            "productionAnimationCount" to "draygon.productionAnimation.count",
            "productionAnimationFrameCount" to "draygon.productionAnimation.frame.count",
            "unusedInstructionListCount" to "draygon.unusedInstructionList.count",
            "sourcePaletteCount" to "draygon.sourcePalette.count",
            "healthPaletteStageCount" to "draygon.healthPaletteStage.count",
            "hitboxCount" to "draygon.hitbox.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "draygon.ownership.aggregate.sha256",
            "headers" to "draygon.header.aggregate.sha256",
            "standardSpritemaps" to "draygon.standardSpritemap.aggregate.sha256",
            "extendedSpritemaps" to "draygon.extendedSpritemap.aggregate.sha256",
            "tilemaps" to "draygon.tilemap.aggregate.sha256",
            "instructionLists" to "draygon.instructionList.aggregate.sha256",
            "productionAnimations" to "draygon.productionAnimation.aggregate.sha256",
            "unusedInstructionLists" to "draygon.unusedInstructionList.aggregate.sha256",
            "palettes" to "draygon.palette.aggregate.sha256",
            "hitboxes" to "draygon.hitbox.aggregate.sha256",
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
        TestRomHelper.requireParityReport("draygon.json", "parityDraygon").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
