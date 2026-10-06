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

class MotherBrainSourceParityTest {

    @Tag("parity")
    @Test
    fun `Mother Brain ownership compositions palettes and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val motherBrain = MotherBrainSpritemap(parser)
        assertTrue(motherBrain.load(), "Mother Brain renderer should load")
        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }
            .associateBy { it.string("name") }
        assertEquals(
            assets.getValue("Tiles_MotherBrainHead").string("sha256"),
            TestRomHelper.sha256(assertNotNull(motherBrain.getHeadTileData())),
        )
        assertEquals(
            assets.getValue("Tiles_MotherBrainBody").string("sha256"),
            TestRomHelper.sha256(assertNotNull(motherBrain.getBodyRawTileData())),
        )

        val sourceHeadAddresses = root.getValue("headSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }
        // Source names 12..17 are limb/body OBJ pieces consumed inside the extended
        // body maps; the standard-component surface exposes 0..11 and 18..19.
        assertTrue(sourceHeadAddresses.containsAll(MotherBrainSpritemap.HEAD_COMPONENTS.map { it.snesAddress }))
        assertEquals(20, MotherBrainSpritemap.HEAD_COMPONENTS.size)
        assertEquals(
            (0x00..0x11).toList() + listOf(0x18, 0x19),
            MotherBrainSpritemap.HEAD_COMPONENTS.map { it.index },
        )
        val sourceBodyAddresses = root.getValue("bodyExtendedSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        assertEquals(sourceBodyAddresses, MotherBrainSpritemap.BODY_COMPONENTS.map { it.snesAddress }.toSet())

        val listsByName = root.getValue("instructionLists").jsonArray
            .map { it.jsonObject }
            .associateBy { it.string("sourceLabel") }
        MotherBrainSpritemap.ANIMATIONS.forEach { definition ->
            assertTrue(definition.sourceLabels.isNotEmpty(), "${definition.name} must identify its source list")
            val frames = definition.sourceLabels.flatMap { label ->
                listsByName.getValue(label).getValue("records").jsonArray
                    .map { it.jsonObject }
                    .filter { it.string("kind") == "frame" }
            }
            if (definition.bodyFrames.isNotEmpty()) {
                assertEquals(frames.map { it.int("duration") }, definition.bodyFrames.map { it.duration }, definition.name)
                assertEquals(
                    frames.map { it.int("spritemapSnesAddress") },
                    definition.bodyFrames.map { it.snesAddress },
                    definition.name,
                )
            } else {
                assertEquals(frames.map { it.int("duration") }, definition.headFrames.map { it.duration }, definition.name)
                assertEquals(
                    frames.map { it.int("spritemapSnesAddress") },
                    definition.headFrames.map { MotherBrainSpritemap.HEAD_COMPONENTS[it.headIndex].snesAddress },
                    definition.name,
                )
            }
        }

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        MotherBrainSpritemap.PALETTE_STAGES.forEach { stage ->
            updatePixelDigest(paletteDigest, assertNotNull(motherBrain.readPalette(stage), stage.name))
        }
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        MotherBrainSpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(motherBrain.renderComposition(definition), definition.name)
            assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, definition.name)
            updatePixelDigest(compositionDigest, rendered.pixels)
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        MotherBrainSpritemap.ANIMATIONS.forEach { definition ->
            val rendered = assertNotNull(motherBrain.renderAnimation(definition), definition.name)
            assertEquals(definition.headFrames.size, rendered.frames.size, definition.name)
            rendered.frames.forEach { frame -> updatePixelDigest(animationDigest, frame.pixels) }
        }

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        assertEquals(TestRomHelper.referenceString("motherBrain.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("motherBrain.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("motherBrain.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "roomStateCount" to "motherBrain.roomState.count",
            "activeRoomStateCount" to "motherBrain.activeRoomState.count",
            "headerCount" to "motherBrain.header.count",
            "assetCount" to "motherBrain.asset.count",
            "headSpritemapCount" to "motherBrain.headSpritemap.count",
            "headOamEntryCount" to "motherBrain.headOamEntry.count",
            "bodyExtendedSpritemapCount" to "motherBrain.bodyExtendedSpritemap.count",
            "bodyExtendedChildCount" to "motherBrain.bodyExtendedChild.count",
            "bodyTilemapCount" to "motherBrain.bodyTilemap.count",
            "bodyTilemapRunCount" to "motherBrain.bodyTilemap.run.count",
            "bodyTilemapWordCount" to "motherBrain.bodyTilemap.word.count",
            "instructionListCount" to "motherBrain.instructionList.count",
            "frameOccurrenceCount" to "motherBrain.frame.count",
            "handlerOccurrenceCount" to "motherBrain.handler.count",
            "unusedHeadSpritemapCount" to "motherBrain.unusedHeadSpritemap.count",
            "unusedExtendedSpritemapCount" to "motherBrain.unusedExtendedSpritemap.count",
            "unusedTilemapCount" to "motherBrain.unusedTilemap.count",
            "unusedInstructionListCount" to "motherBrain.unusedInstructionList.count",
            "basePaletteCount" to "motherBrain.basePalette.count",
            "healthPaletteRowCount" to "motherBrain.healthPaletteRow.count",
            "rainbowPaletteStageCount" to "motherBrain.rainbowPaletteStage.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "motherBrain.ownership.aggregate.sha256",
            "headers" to "motherBrain.header.aggregate.sha256",
            "assets" to "motherBrain.asset.aggregate.sha256",
            "headSpritemaps" to "motherBrain.headSpritemap.aggregate.sha256",
            "bodyExtendedSpritemaps" to "motherBrain.bodyExtendedSpritemap.aggregate.sha256",
            "bodyTilemaps" to "motherBrain.bodyTilemap.aggregate.sha256",
            "instructions" to "motherBrain.instructionList.aggregate.sha256",
            "unusedStructures" to "motherBrain.unusedStructure.aggregate.sha256",
            "palettes" to "motherBrain.palette.aggregate.sha256",
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
        TestRomHelper.requireParityReport("mother-brain.json", "parityMotherBrain").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
