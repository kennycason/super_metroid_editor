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

class RidleySourceParityTest {

    @Tag("parity")
    @Test
    fun `Ridley shared encounters DMA assembly palettes and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val ridley = RidleySpritemap(parser)
        assertTrue(ridley.load(), "Ridley renderer should load")

        val ownership = root.getValue("ownership").jsonObject
            .getValue("sharedPixels").jsonObject
        val rawTiles = assertNotNull(ridley.getRawTileData())
        assertEquals(ownership.int("size"), rawTiles.size)
        assertEquals(ownership.string("sha256"), TestRomHelper.sha256(rawTiles))

        val lowObjOwnership = root.getValue("ownership").jsonObject
            .getValue("sharedLowObjPage").jsonObject
        val auxiliaryAssets = root.getValue("auxiliaryAssets").jsonArray.map { it.jsonObject }
        val sharedVramSources = RidleySpritemap.SHARED_VRAM_SOURCES
        assertEquals(1, auxiliaryAssets.size)
        assertEquals(1, sharedVramSources.size)
        assertEquals(RidleySpritemap.FORWARD_TILES_SNES, lowObjOwnership.int("snesAddress"))
        assertEquals(RidleySpritemap.FORWARD_TILES_SIZE, lowObjOwnership.int("size"))
        assertEquals(RidleySpritemap.FORWARD_TILES_PHYSICAL_BASE, lowObjOwnership.int("physicalTileStart"))
        assertEquals(auxiliaryAssets.single().int("snesAddress"), sharedVramSources.single().snesAddress)
        assertEquals(auxiliaryAssets.single().int("size"), sharedVramSources.single().byteCount)
        assertEquals(
            auxiliaryAssets.single().string("sha256"),
            TestRomHelper.sha256(assertNotNull(ridley.readRuntimeSource(sharedVramSources.single()))),
        )

        val headers = root.getValue("headers").jsonArray.map { it.jsonObject }
        assertEquals(setOf(0xE13F, 0xE17F), headers.map { it.int("speciesId") }.toSet())
        assertEquals(1, headers.map { it.string("graphicsSha256") }.distinct().size)
        assertEquals(setOf("MainAI_RidleyCeres", "MainAI_Ridley"), headers.map { it.string("mainAiLabel") }.toSet())

        val sourceBodies = root.getValue("bodyExtendedSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        assertEquals(sourceBodies, RidleySpritemap.BODY_COMPONENTS.map { it.snesAddress }.toSet())

        val sourceWings = root.getValue("wingSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        assertEquals(sourceWings, RidleySpritemap.WING_COMPONENTS.map { it.snesAddress }.toSet())

        val sourceTails = root.getValue("tailSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        assertTrue(sourceTails.containsAll(RidleySpritemap.TAIL_COMPONENTS.map { it.snesAddress }))

        val runtimeAssets = root.getValue("runtimeAssets").jsonArray.map { it.jsonObject }
            .sortedBy { it.int("snesAddress") }
        val runtimeSources = RidleySpritemap.RUNTIME_SOURCES.sortedBy { it.snesAddress }
        assertEquals(runtimeAssets.size, runtimeSources.size)
        runtimeAssets.zip(runtimeSources).forEach { (source, definition) ->
            assertEquals(source.int("snesAddress"), definition.snesAddress, definition.name)
            assertEquals(source.int("size"), definition.byteCount, definition.name)
            assertEquals(
                source.string("sha256"),
                TestRomHelper.sha256(assertNotNull(ridley.readRuntimeSource(definition))),
                definition.name,
            )
        }

        val sourceLists = root.getValue("animationLists").jsonArray.map { it.jsonObject }
            .associateBy { it.string("sourceLabel") }
        val proceduralLabels = setOf("UpdateRidleyWingsAnimation", "RidleyRibsAnimationTable")
        val instructionAnimations = RidleySpritemap.ANIMATIONS.filter { it.sourceLabel !in proceduralLabels }
        assertEquals(
            instructionAnimations.map { it.sourceLabel }.toSet(),
            sourceLists.keys - "UNUSED_InstList_RidleyCeres_FacingRight_RetrieveBabyMetroid_A6E676",
        )
        instructionAnimations.forEach { definition ->
            val sourceFrames = sourceLists.getValue(definition.sourceLabel).getValue("records").jsonArray
                .map { it.jsonObject }
                .filter { it.string("kind") == "frame" }
            assertEquals(sourceFrames.map { it.int("duration") }, definition.frames.map { it.duration }, definition.name)
            assertEquals(sourceFrames.map { it.int("spritemapSnesAddress") }, definition.frames.map { it.bodySnes }, definition.name)
        }

        val runtimeTables = root.getValue("runtimeTables").jsonObject
        val leftWingPointers = runtimeTables.getValue("wingPointersLeft").jsonObject
            .getValue("words").jsonArray.map { it.jsonPrimitive.int }
        val rightWingPointers = runtimeTables.getValue("wingPointersRight").jsonObject
            .getValue("words").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(
            leftWingPointers,
            wingAddresses(RidleySpritemap.ANIMATIONS.single { it.key == "wing-left" }),
        )
        assertEquals(
            rightWingPointers,
            wingAddresses(RidleySpritemap.ANIMATIONS.single { it.key == "wing-right" }),
        )

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        RidleySpritemap.PALETTE_STAGES.forEach { stage ->
            updatePixelDigest(paletteDigest, assertNotNull(ridley.readPalette(stage), stage.name))
        }
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        RidleySpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(ridley.renderComposition(definition), definition.name)
            assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, definition.name)
            updatePixelDigest(compositionDigest, rendered.pixels)
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        RidleySpritemap.ANIMATIONS.forEach { definition ->
            val rendered = assertNotNull(ridley.renderAnimation(definition), definition.name)
            assertEquals(definition.frames.size, rendered.frames.size, definition.name)
            rendered.frames.forEach { frame -> updatePixelDigest(animationDigest, frame.pixels) }
        }

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("ridley.render.palette.aggregate.sha256=$paletteHash")
        println("ridley.render.compositionPixel.aggregate.sha256=$compositionHash")
        println("ridley.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(TestRomHelper.referenceString("ridley.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("ridley.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("ridley.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun wingAddresses(definition: RidleySpritemap.AnimationDef): List<Int> = definition.frames.map { frame ->
        val index = assertNotNull(frame.wingIndex)
        RidleySpritemap.WING_COMPONENTS.single { it.side == frame.side && it.index == index }.snesAddress and 0xFFFF
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "ridley.header.count",
            "baseAssetCount" to "ridley.baseAsset.count",
            "baseAssetByteCount" to "ridley.baseAsset.byte.count",
            "auxiliaryAssetCount" to "ridley.auxiliaryAsset.count",
            "auxiliaryAssetByteCount" to "ridley.auxiliaryAsset.byte.count",
            "runtimeDmaAssetCount" to "ridley.runtimeDmaAsset.count",
            "runtimeDmaByteCount" to "ridley.runtimeDmaAsset.byte.count",
            "bodyExtendedSpritemapCount" to "ridley.bodyExtendedSpritemap.count",
            "bodyExtendedChildCount" to "ridley.bodyExtendedChild.count",
            "bodyChildSpritemapCount" to "ridley.bodyChildSpritemap.count",
            "bodyChildOamEntryCount" to "ridley.bodyChildOamEntry.count",
            "wingSpritemapCount" to "ridley.wingSpritemap.count",
            "wingOamEntryCount" to "ridley.wingOamEntry.count",
            "tailSpritemapCount" to "ridley.tailSpritemap.count",
            "tailOamEntryCount" to "ridley.tailOamEntry.count",
            "animationListCount" to "ridley.animationList.count",
            "activeAnimationListCount" to "ridley.animationList.active.count",
            "unusedAnimationListCount" to "ridley.animationList.unused.count",
            "animationFrameCount" to "ridley.animation.frame.count",
            "animationHandlerCount" to "ridley.animation.handler.count",
            "paletteStageCount" to "ridley.paletteStage.count",
            "runtimeTableCount" to "ridley.runtimeTable.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "ridley.ownership.aggregate.sha256",
            "headers" to "ridley.header.aggregate.sha256",
            "baseAssets" to "ridley.baseAsset.aggregate.sha256",
            "auxiliaryAssets" to "ridley.auxiliaryAsset.aggregate.sha256",
            "runtimeAssets" to "ridley.runtimeAsset.aggregate.sha256",
            "bodyExtendedSpritemaps" to "ridley.bodyExtendedSpritemap.aggregate.sha256",
            "bodyChildSpritemaps" to "ridley.bodyChildSpritemap.aggregate.sha256",
            "wingSpritemaps" to "ridley.wingSpritemap.aggregate.sha256",
            "tailSpritemaps" to "ridley.tailSpritemap.aggregate.sha256",
            "animationLists" to "ridley.animationList.aggregate.sha256",
            "runtimeTables" to "ridley.runtimeTable.aggregate.sha256",
            "palettes" to "ridley.palette.aggregate.sha256",
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
        TestRomHelper.requireParityReport("ridley.json", "parityRidley").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
