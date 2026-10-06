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

class MetroidSourceParityTest {

    @Tag("parity")
    @Test
    fun `Metroid insides shell electricity timing and renders match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val header = root.getValue("headers").jsonArray.single().jsonObject
        assertEquals(MetroidSpritemap.SPECIES_ID, header.int("speciesId"))
        assertEquals(MetroidSpritemap.TILE_DATA_SNES, header.int("tileDataSnesAddress"))
        assertEquals(MetroidSpritemap.TILE_DATA_SIZE, header.int("tileDataSize"))
        assertEquals(MetroidSpritemap.PALETTE_SNES, header.int("paletteSnesAddress"))

        val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, MetroidSpritemap.SPECIES_ID))
        val renderer = MetroidSpritemap(parser)
        assertTrue(renderer.load(tiles))
        assertEquals(root.getValue("assets").jsonArray.single().jsonObject.string("sha256"),
            TestRomHelper.sha256(tiles))

        val tracks = root.getValue("spriteObjectTracks").jsonArray.map { it.jsonObject }
            .associateBy { it.string("key") }
        compareTrack(tracks.getValue("electricity-intro"), renderer.electricityIntroFrames())
        compareTrack(tracks.getValue("electricity-steady"), renderer.electricitySteadyFrames())
        compareTrack(tracks.getValue("shell-intro"), renderer.shellIntroFrames())
        compareTrack(tracks.getValue("shell-steady"), renderer.shellSteadyFrames())
        assertEquals(2, tracks.values.count { it.getValue("entersByFallthrough").jsonPrimitive.content == "true" })

        assertEquals(
            root.getValue("insideSpritemaps").jsonArray.map { it.jsonObject.int("snesAddress") }.toSet(),
            MetroidSpritemap.COMPONENTS.filter { it.kind == MetroidSpritemap.ComponentKind.INSIDES }
                .map { it.snesAddress }.toSet(),
        )
        assertEquals(
            root.getValue("shellSpritemaps").jsonArray.map { it.jsonObject.int("snesAddress") }.toSet(),
            MetroidSpritemap.COMPONENTS.filter { it.kind == MetroidSpritemap.ComponentKind.SHELL }
                .map { it.snesAddress }.toSet(),
        )
        assertEquals(
            root.getValue("electricitySpritemaps").jsonArray.map { it.jsonObject.int("snesAddress") }.toSet(),
            MetroidSpritemap.COMPONENTS.filter { it.kind == MetroidSpritemap.ComponentKind.ELECTRICITY }
                .map { it.snesAddress }.toSet(),
        )

        val paletteRecord = root.getValue("palettes").jsonArray.single().jsonObject
        val palettePc = parser.snesToPc(paletteRecord.int("snesAddress"))
        val paletteRaw = parser.getRomData().copyOfRange(palettePc, palettePc + paletteRecord.int("size"))
        assertEquals(paletteRecord.string("sha256"), TestRomHelper.sha256(paletteRaw))

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        updatePixelDigest(paletteDigest, assertNotNull(renderer.readPalette()))
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        MetroidSpritemap.COMPOSITIONS.forEach { definition ->
            updatePixelDigest(compositionDigest, assertNotNull(renderer.renderComposition(definition), definition.name).pixels)
        }
        val componentDigest = MessageDigest.getInstance("SHA-256")
        MetroidSpritemap.COMPONENTS.forEach { definition ->
            updatePixelDigest(componentDigest, assertNotNull(renderer.renderComponent(definition), definition.name).pixels)
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        var animationFrames = 0
        MetroidSpritemap.ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderAnimation(definition), definition.name)
            animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
            animationFrames += animation.frames.size
        }

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val componentHash = componentDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("metroid.render.animation.frame.count=$animationFrames")
        println("metroid.render.palette.aggregate.sha256=$paletteHash")
        println("metroid.render.compositionPixel.aggregate.sha256=$compositionHash")
        println("metroid.render.componentPixel.aggregate.sha256=$componentHash")
        println("metroid.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(TestRomHelper.referenceInt("metroid.render.animation.frame.count"), animationFrames)
        assertEquals(TestRomHelper.referenceString("metroid.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("metroid.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("metroid.render.componentPixel.aggregate.sha256"), componentHash)
        assertEquals(TestRomHelper.referenceString("metroid.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun compareTrack(source: JsonObject, decoded: List<MetroidSpritemap.TimedMap>) {
        val expected = source.getValue("records").jsonArray.map { it.jsonObject }
            .filter { it.string("kind") == "frame" }
        assertEquals(expected.size, decoded.size, source.string("key"))
        expected.zip(decoded).forEach { (record, frame) ->
            assertEquals(record.int("duration"), frame.durationTicks, source.string("key"))
            val address = record.int("spritemapSnesAddress").takeUnless { it == MetroidSpritemap.EMPTY_DRAW_SNES }
            assertEquals(address, frame.snesAddress, source.string("key"))
        }
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "metroid.header.count",
            "assetCount" to "metroid.asset.count",
            "assetByteCount" to "metroid.asset.byte.count",
            "insideSpritemapCount" to "metroid.insideSpritemap.count",
            "insideOamEntryCount" to "metroid.insideOamEntry.count",
            "shellSpritemapCount" to "metroid.shellSpritemap.count",
            "shellOamEntryCount" to "metroid.shellOamEntry.count",
            "electricitySpritemapCount" to "metroid.electricitySpritemap.count",
            "electricityOamEntryCount" to "metroid.electricityOamEntry.count",
            "enemyInstructionListCount" to "metroid.enemyInstructionList.count",
            "spriteObjectTrackCount" to "metroid.spriteObjectTrack.count",
            "spriteObjectFrameOccurrenceCount" to "metroid.spriteObjectFrameOccurrence.count",
            "spriteObjectTickCount" to "metroid.spriteObjectTick.count",
            "fallthroughContinuationCount" to "metroid.fallthroughContinuation.count",
            "paletteCount" to "metroid.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "metroid.ownership.aggregate.sha256",
            "headers" to "metroid.header.aggregate.sha256",
            "assets" to "metroid.asset.aggregate.sha256",
            "insideSpritemaps" to "metroid.insideSpritemap.aggregate.sha256",
            "shellSpritemaps" to "metroid.shellSpritemap.aggregate.sha256",
            "electricitySpritemaps" to "metroid.electricitySpritemap.aggregate.sha256",
            "enemyInstructionLists" to "metroid.enemyInstructionList.aggregate.sha256",
            "spriteObjectTracks" to "metroid.spriteObjectTrack.aggregate.sha256",
            "palettes" to "metroid.palette.aggregate.sha256",
            "runtimeRanges" to "metroid.runtimeRange.aggregate.sha256",
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
        TestRomHelper.requireParityReport("metroid.json", "parityMetroid").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
