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

class SporeSpawnSourceParityTest {

    @Tag("parity")
    @Test
    fun `Spore Spawn body stalk projectiles palettes and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val tiles = assertNotNull(
            EnemySpriteGraphics.loadEnemyTileData(parser, SporeSpawnSpritemap.SPECIES_ID),
        )
        val renderer = SporeSpawnSpritemap(parser)
        assertTrue(renderer.load(tiles))
        assertEquals(SporeSpawnSpritemap.TILE_DATA_SIZE, tiles.size)
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

        val sourceLists = root.getValue("instructionLists").jsonArray.map { it.jsonObject }
            .associateBy { it.int("snesAddress") }
        assertEquals(sourceLists.keys, SporeSpawnSpritemap.INSTRUCTION_LISTS.map { it.snesAddress }.toSet())
        SporeSpawnSpritemap.INSTRUCTION_LISTS.forEach { definition ->
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
        updatePixelDigest(paletteDigest, assertNotNull(renderer.readBaseSporePalette()))
        SporeSpawnSpritemap.PALETTE_STAGES.forEach { stage ->
            updatePixelDigest(paletteDigest, assertNotNull(renderer.readPalette(stage), stage.name))
        }
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        SporeSpawnSpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComposition(definition), definition.name)
            assertEquals(4, rendered.spritemap.entries.size - bodyEntryCount(renderer, definition.snesAddress))
            updatePixelDigest(compositionDigest, rendered.pixels)
        }
        val componentDigest = MessageDigest.getInstance("SHA-256")
        SporeSpawnSpritemap.COMPONENTS.forEach { definition ->
            updatePixelDigest(
                componentDigest,
                assertNotNull(renderer.renderComponent(definition), definition.name).pixels,
            )
        }
        val animationDigest = MessageDigest.getInstance("SHA-256")
        var animationFrames = 0
        SporeSpawnSpritemap.ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderAnimation(definition), definition.name)
            animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
            animationFrames += animation.frames.size
        }
        listOf(
            assertNotNull(renderer.renderSpawnerAnimation()),
            assertNotNull(renderer.renderSporeAnimation()),
        ).forEach { animation ->
            animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
            animationFrames += animation.frames.size
        }
        assertEquals(TestRomHelper.referenceInt("sporeSpawn.render.animation.frame.count"), animationFrames)

        val paletteHash = paletteDigest.digest().toHex()
        val compositionHash = compositionDigest.digest().toHex()
        val componentHash = componentDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("sporeSpawn.render.palette.aggregate.sha256=$paletteHash")
        println("sporeSpawn.render.compositionPixel.aggregate.sha256=$compositionHash")
        println("sporeSpawn.render.componentPixel.aggregate.sha256=$componentHash")
        println("sporeSpawn.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(TestRomHelper.referenceString("sporeSpawn.render.palette.aggregate.sha256"), paletteHash)
        assertEquals(TestRomHelper.referenceString("sporeSpawn.render.compositionPixel.aggregate.sha256"), compositionHash)
        assertEquals(TestRomHelper.referenceString("sporeSpawn.render.componentPixel.aggregate.sha256"), componentHash)
        assertEquals(TestRomHelper.referenceString("sporeSpawn.render.animationPixel.aggregate.sha256"), animationHash)
    }

    private fun bodyEntryCount(renderer: SporeSpawnSpritemap, address: Int): Int {
        val component = SporeSpawnSpritemap.ComponentDef(
            key = "parity",
            name = "parity",
            kind = SporeSpawnSpritemap.ComponentKind.BODY,
            snesAddress = address,
        )
        return assertNotNull(renderer.renderComponent(component)).spritemap.entries.size
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "sporeSpawn.header.count",
            "assetCount" to "sporeSpawn.asset.count",
            "assetByteCount" to "sporeSpawn.asset.byte.count",
            "standardSpritemapCount" to "sporeSpawn.standardSpritemap.count",
            "standardOamEntryCount" to "sporeSpawn.standardOamEntry.count",
            "activeExtendedSpritemapCount" to "sporeSpawn.activeExtendedSpritemap.count",
            "activeExtendedChildCount" to "sporeSpawn.activeExtendedChild.count",
            "unusedExtendedSpritemapCount" to "sporeSpawn.unusedExtendedSpritemap.count",
            "projectileSpritemapCount" to "sporeSpawn.projectileSpritemap.count",
            "projectileOamEntryCount" to "sporeSpawn.projectileOamEntry.count",
            "instructionListCount" to "sporeSpawn.instructionList.count",
            "frameOccurrenceCount" to "sporeSpawn.frame.count",
            "uniqueFrameCount" to "sporeSpawn.frame.unique.count",
            "handlerOccurrenceCount" to "sporeSpawn.handler.count",
            "guidedAnimationCount" to "sporeSpawn.guidedAnimation.count",
            "guidedAnimationFrameCount" to "sporeSpawn.guidedAnimation.frame.count",
            "paletteCount" to "sporeSpawn.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "sporeSpawn.ownership.aggregate.sha256",
            "headers" to "sporeSpawn.header.aggregate.sha256",
            "assets" to "sporeSpawn.asset.aggregate.sha256",
            "standardSpritemaps" to "sporeSpawn.standardSpritemap.aggregate.sha256",
            "activeExtendedSpritemaps" to "sporeSpawn.activeExtendedSpritemap.aggregate.sha256",
            "unusedExtendedSpritemaps" to "sporeSpawn.unusedExtendedSpritemap.aggregate.sha256",
            "projectileSpritemaps" to "sporeSpawn.projectileSpritemap.aggregate.sha256",
            "instructionLists" to "sporeSpawn.instructionList.aggregate.sha256",
            "guidedAnimations" to "sporeSpawn.guidedAnimation.aggregate.sha256",
            "palettes" to "sporeSpawn.palette.aggregate.sha256",
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
        TestRomHelper.requireParityReport("spore-spawn.json", "paritySporeSpawn").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
