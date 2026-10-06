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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotwoonSourceParityTest {

    @Tag("parity")
    @Test
    fun `Botwoon head body history palettes projectiles and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, BotwoonSpritemap.SPECIES_ID))
        val renderer = BotwoonSpritemap(parser)
        assertTrue(renderer.load(tiles))
        assertEquals(BotwoonSpritemap.TILE_DATA_SIZE, tiles.size)
        assertEquals(
            root.getValue("assets").jsonArray.single().jsonObject.string("sha256"),
            TestRomHelper.sha256(tiles),
        )

        val rom = parser.getRomData()
        root.getValue("palettes").jsonArray.map { it.jsonObject }.forEach { palette ->
            val pc = parser.snesToPc(palette.int("snesAddress"))
            val raw = rom.copyOfRange(pc, pc + palette.int("size"))
            assertEquals(palette.string("sha256"), TestRomHelper.sha256(raw), palette.string("sourceLabel"))
        }
        assertContentEquals(renderer.readHeaderPalette(), renderer.readPalette())

        val animations = root.getValue("guidedAnimations").jsonArray.map { it.jsonObject }
        assertEquals(17, animations.size)
        BotwoonSpritemap.DIRECTIONS.forEach { direction ->
            val swimSource = animations.single { it.string("key") == "swim-${direction.key}" }
            val spitSource = animations.single { it.string("key") == "spit-${direction.key}" }
            assertEquals(direction.closedHeadMap, swimSource.int("closedHeadSpritemap"), direction.name)
            assertEquals(direction.closedInstructionList, swimSource.int("headInstructionList"), direction.name)
            assertEquals(direction.openHeadMap, spitSource.int("openHeadSpritemap"), direction.name)
            assertEquals(direction.spitInstructionList, spitSource.int("headInstructionList"), direction.name)
            assertEquals(direction.spitOpenTicks, spitSource.int("openTicks"), direction.name)
            assertEquals(direction.bodyMaps.toList(), swimSource.getValue("bodySpritemaps").jsonArray.map { it.jsonPrimitive.int })
            assertEquals(direction.tailMap, swimSource.int("tailSpritemap"), direction.name)
        }

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        BotwoonSpritemap.PALETTE_STAGES.forEach { stage ->
            updatePixelDigest(paletteDigest, assertNotNull(renderer.readPalette(stage), stage.name))
        }
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        BotwoonSpritemap.COMPOSITIONS.forEach { definition ->
            updatePixelDigest(compositionDigest, assertNotNull(renderer.renderComposition(definition), definition.name).pixels)
            updatePixelDigest(compositionDigest, assertNotNull(renderer.renderComposition(definition, mouthOpen = true), definition.name).pixels)
        }
        val componentDigest = MessageDigest.getInstance("SHA-256")
        BotwoonSpritemap.COMPONENTS.forEach { definition ->
            updatePixelDigest(componentDigest, assertNotNull(renderer.renderComponent(definition), definition.name).pixels)
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        var animationFrames = 0
        BotwoonSpritemap.DIRECTIONS.forEach { direction ->
            listOf(
                assertNotNull(renderer.renderSwimAnimation(direction)),
                assertNotNull(renderer.renderSpitAnimation(direction)),
            ).forEach { animation ->
                animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
                animationFrames += animation.frames.size
            }
        }
        assertNotNull(renderer.renderSpitProjectileAnimation()).also { animation ->
            animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
            animationFrames += animation.frames.size
        }
        assertEquals(TestRomHelper.referenceInt("botwoon.render.animation.frame.count"), animationFrames)

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val componentHash = componentDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("botwoon.render.palette.aggregate.sha256=$paletteHash")
        println("botwoon.render.compositionPixel.aggregate.sha256=$compositionHash")
        println("botwoon.render.componentPixel.aggregate.sha256=$componentHash")
        println("botwoon.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(TestRomHelper.referenceString("botwoon.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("botwoon.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("botwoon.render.componentPixel.aggregate.sha256"), componentHash)
        assertEquals(TestRomHelper.referenceString("botwoon.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "botwoon.header.count",
            "assetCount" to "botwoon.asset.count",
            "assetByteCount" to "botwoon.asset.byte.count",
            "activeHeadSpritemapCount" to "botwoon.activeHeadSpritemap.count",
            "unusedHeadSpritemapCount" to "botwoon.unusedHeadSpritemap.count",
            "headOamEntryCount" to "botwoon.headOamEntry.count",
            "activeProjectileSpritemapCount" to "botwoon.activeProjectileSpritemap.count",
            "unusedProjectileSpritemapCount" to "botwoon.unusedProjectileSpritemap.count",
            "projectileOamEntryCount" to "botwoon.projectileOamEntry.count",
            "activeHeadInstructionListCount" to "botwoon.activeHeadInstructionList.count",
            "unusedHeadInstructionListCount" to "botwoon.unusedHeadInstructionList.count",
            "headFrameOccurrenceCount" to "botwoon.headFrame.count",
            "activeProjectileInstructionListCount" to "botwoon.activeProjectileInstructionList.count",
            "runtimeRangeCount" to "botwoon.runtimeRange.count",
            "guidedAnimationCount" to "botwoon.guidedAnimation.count",
            "guidedAnimationFrameCount" to "botwoon.guidedAnimation.frame.count",
            "paletteCount" to "botwoon.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "botwoon.ownership.aggregate.sha256",
            "headers" to "botwoon.header.aggregate.sha256",
            "assets" to "botwoon.asset.aggregate.sha256",
            "activeHeadSpritemaps" to "botwoon.activeHeadSpritemap.aggregate.sha256",
            "unusedHeadSpritemaps" to "botwoon.unusedHeadSpritemap.aggregate.sha256",
            "activeProjectileSpritemaps" to "botwoon.activeProjectileSpritemap.aggregate.sha256",
            "unusedProjectileSpritemaps" to "botwoon.unusedProjectileSpritemap.aggregate.sha256",
            "headInstructionLists" to "botwoon.headInstructionList.aggregate.sha256",
            "projectileInstructionLists" to "botwoon.projectileInstructionList.aggregate.sha256",
            "runtimeRanges" to "botwoon.runtimeRange.aggregate.sha256",
            "guidedAnimations" to "botwoon.guidedAnimation.aggregate.sha256",
            "palettes" to "botwoon.palette.aggregate.sha256",
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
        TestRomHelper.requireParityReport("botwoon.json", "parityBotwoon").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
