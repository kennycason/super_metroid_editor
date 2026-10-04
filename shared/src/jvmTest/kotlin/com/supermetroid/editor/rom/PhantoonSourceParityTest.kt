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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhantoonSourceParityTest {

    @Tag("parity")
    @Test
    fun `Phantoon BG2 composition palettes and animations match the source manifest`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val phantoon = PhantoonSpritemap(parser)
        assertTrue(phantoon.load(), "Phantoon renderer should load")
        assertEquals(PhantoonSpritemap.PHANTOON_TILESET_ID, phantoon.getTilesetId())

        val bgResource = root.getValue("ownership").jsonObject
            .getValue("bgPixels").jsonObject
            .getValue("resource").jsonObject
        val rawVarGfx = assertNotNull(phantoon.getTileGraphics().getRawVarGfx())
        assertEquals(0x5000, rawVarGfx.size, "production varGfx buffer includes the reserved 0x800-byte gap")
        assertEquals(
            bgResource.string("decompressedSha256"),
            TestRomHelper.sha256(rawVarGfx.copyOf(0x4800)),
            "the source-owned prefix is the exact 0x4800-byte Wrecked Ship resource",
        )

        val sourceTilemaps = root.getValue("tilemaps").jsonArray.map { it.jsonObject }
        assertEquals(
            sourceTilemaps.map { it.int("snesAddress") }.toSet(),
            PhantoonSpritemap.COMPONENT_TILEMAPS.map { it.tilemapSnes }.toSet(),
        )
        assertEquals(22, PhantoonSpritemap.COMPONENT_TILEMAPS.size)

        val componentDigest = MessageDigest.getInstance("SHA-256")
        PhantoonSpritemap.COMPONENT_TILEMAPS.forEach { definition ->
            val rendered = assertNotNull(phantoon.renderComponent(definition), definition.name)
            assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, definition.name)
            updatePixelDigest(componentDigest, rendered.pixels)
        }
        assertEquals(
            TestRomHelper.referenceString("phantoon.render.componentPixel.aggregate.sha256"),
            componentDigest.digest().toHex(),
            "all 22 source tilemaps",
        )

        val sourcePalettes = root.getValue("palettes").jsonArray.map { it.jsonObject }
            .associateBy { it.string("sourceLabel") }
        val healthPalettes = PhantoonSpritemap.PALETTE_STAGES.mapIndexed { index, stage ->
            val source = sourcePalettes.getValue("Palette_Phantoon_HealthBased_$index")
            assertEquals(source.int("snesAddress"), stage.snesAddr, stage.name)
            assertNotNull(phantoon.readPalette(stage), stage.name)
        }
        assertEquals(8, healthPalettes.map { it.contentHashCode() }.distinct().size)
        assertEquals(
            sourcePalettes.getValue("Palette_Phantoon_HealthBased_7").int("snesAddress"),
            PhantoonSpritemap.PALETTE_SNES,
        )
        val unusedClone = root.getValue("unusedFullHealthPaletteClone").jsonObject
        assertTrue(unusedClone.getValue("bytesIdentical").jsonPrimitive.content.toBoolean())
        assertNotEquals(unusedClone.int("snesAddress"), PhantoonSpritemap.PALETTE_SNES)

        val sourceAnimations = root.getValue("productionAnimations").jsonArray.map { it.jsonObject }
        assertEquals(sourceAnimations.map { it.string("key") }, PhantoonSpritemap.ANIMATIONS.map { it.key })
        val animationDigest = MessageDigest.getInstance("SHA-256")
        sourceAnimations.zip(PhantoonSpritemap.ANIMATIONS).forEach { (source, definition) ->
            assertEquals(source.int("snesAddress"), definition.snesAddr, definition.name)
            assertEquals(source.int("endSnesAddressExclusive"), definition.endSnesAddrExclusive, definition.name)
            val sourceRecords = source.getValue("records").jsonArray.map { it.jsonObject }
            val sourceFrames = sourceRecords.filter { it.string("kind") == "frame" }
            val sourceHandlers = sourceRecords.filter { it.string("kind") == "handler" }
            val parsed = assertNotNull(phantoon.loadAnimation(definition), definition.name)
            assertEquals(sourceFrames.size, parsed.frames.size, definition.name)
            sourceFrames.zip(parsed.frames).forEach { (frameSource, frame) ->
                assertEquals(frameSource.int("duration"), frame.duration, definition.name)
                assertEquals(frameSource.int("spritemapSnesAddress"), frame.extendedSpritemapSnes, definition.name)
            }
            assertEquals(
                sourceHandlers.map { it.int("handlerSnesAddress") },
                parsed.handlerSnesAddresses,
                definition.name,
            )
            val rendered = assertNotNull(phantoon.renderAnimation(definition), definition.name)
            assertEquals(parsed.frames.size, rendered.frames.size, definition.name)
            rendered.frames.forEach { frame -> updatePixelDigest(animationDigest, frame.pixels) }
        }
        assertEquals(
            TestRomHelper.referenceString("phantoon.render.animationPixel.aggregate.sha256"),
            animationDigest.digest().toHex(),
            "all 13 production animation frame occurrences",
        )

        val fullBodyDigest = MessageDigest.getInstance("SHA-256")
        val fullBodies = listOf(assertNotNull(phantoon.renderFullBody())) +
            PhantoonSpritemap.EYEBALL_TILEMAPS.map { eyeball ->
                assertNotNull(phantoon.renderFullBody(eyeball = eyeball), eyeball.name)
            }
        fullBodies.forEach { body ->
            assertEquals(80, body.width)
            assertEquals(112, body.height)
            updatePixelDigest(fullBodyDigest, body.pixels)
        }
        assertEquals(10, fullBodies.map { it.pixels.contentHashCode() }.distinct().size)
        assertEquals(
            TestRomHelper.referenceString("phantoon.render.fullBodyPixel.aggregate.sha256"),
            fullBodyDigest.digest().toHex(),
            "closed eye plus all nine gaze compositions",
        )

        val editable = assertNotNull(phantoon.renderComponent(PhantoonSpritemap.BODY_TILEMAPS.single()))
        val editedPixels = editable.pixels.copyOf()
        val pixelIndex = editedPixels.indexOfFirst { (it ushr 24) != 0 && it != 0xFFFFFFFF.toInt() }
        assertTrue(pixelIndex >= 0)
        val before = rawVarGfx.copyOf()
        editedPixels[pixelIndex] = 0xFFFFFFFF.toInt()
        val changedTiles = phantoon.applyEdits(editable, editedPixels, phantoon.getTileGraphics())
        assertTrue(changedTiles.isNotEmpty())
        val after = assertNotNull(phantoon.getTileGraphics().getRawVarGfx())
        assertEquals(before.size, after.size)
        assertNotEquals(TestRomHelper.sha256(before), TestRomHelper.sha256(after))
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "roomStateCount" to "phantoon.roomState.count",
            "headerCount" to "phantoon.header.count",
            "tilemapCount" to "phantoon.tilemap.count",
            "tilemapRunCount" to "phantoon.tilemap.run.count",
            "tilemapWordCount" to "phantoon.tilemap.word.count",
            "extendedSpritemapCount" to "phantoon.extendedSpritemap.count",
            "extendedChildCount" to "phantoon.extendedChild.count",
            "instructionListCount" to "phantoon.instructionList.count",
            "frameOccurrenceCount" to "phantoon.frame.count",
            "handlerOccurrenceCount" to "phantoon.handler.count",
            "productionAnimationCount" to "phantoon.productionAnimation.count",
            "productionAnimationFrameCount" to "phantoon.productionAnimation.frame.count",
            "paletteStateCount" to "phantoon.paletteState.count",
            "healthPaletteCount" to "phantoon.healthPalette.count",
            "hitboxCount" to "phantoon.hitbox.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "phantoon.ownership.aggregate.sha256",
            "headers" to "phantoon.header.aggregate.sha256",
            "tilemaps" to "phantoon.tilemap.aggregate.sha256",
            "extendedSpritemaps" to "phantoon.extendedSpritemap.aggregate.sha256",
            "instructionLists" to "phantoon.instructionList.aggregate.sha256",
            "productionAnimations" to "phantoon.productionAnimation.aggregate.sha256",
            "palettes" to "phantoon.palette.aggregate.sha256",
            "hitboxes" to "phantoon.hitbox.aggregate.sha256",
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
        TestRomHelper.requireParityReport("phantoon.json", "parityPhantoon").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
