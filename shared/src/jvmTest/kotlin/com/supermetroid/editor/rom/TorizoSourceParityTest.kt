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

class TorizoSourceParityTest {

    @Tag("parity")
    @Test
    fun `Torizo shared body runtime overlays projectiles palettes and animations match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, TorizoSpritemap.BOMB_SPECIES_ID))
        val renderer = TorizoSpritemap(parser)
        assertTrue(renderer.load(tiles))

        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }.associateBy { it.string("sourceLabel") }
        TorizoSpritemap.PIXEL_SOURCES.forEach { source ->
            val asset = assertNotNull(assets[source.sourceLabel], source.sourceLabel)
            assertEquals(source.snesAddress, asset.int("snesAddress"), source.key)
            assertEquals(source.byteCount, asset.int("size"), source.key)
            assertEquals(asset.string("sha256"), TestRomHelper.sha256(assertNotNull(renderer.readPixelSource(source))))
        }

        val activeExtended = root.getValue("activeExtendedSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        val activeProjectiles = root.getValue("activeProjectileSpritemaps").jsonArray
            .map { it.jsonObject.int("snesAddress") }.toSet()
        TorizoSpritemap.COMPOSITIONS.forEach { assertTrue(it.snesAddress in activeExtended, it.name) }
        TorizoSpritemap.COMPONENTS.forEach {
            assertTrue(it.snesAddress in if (it.projectile) activeProjectiles else activeExtended, it.name)
        }
        TorizoSpritemap.ANIMATIONS.flatMap { it.frames }.forEach {
            assertTrue(it.snesAddress in activeExtended, "Missing guided body frame ${it.snesAddress.toString(16)}")
        }
        TorizoSpritemap.PROJECTILE_ANIMATIONS.flatMap { it.addresses.toList() }.forEach {
            assertTrue(it in activeProjectiles, "Missing guided projectile frame ${it.toString(16)}")
        }

        val paletteDigest = MessageDigest.getInstance("SHA-256")
        TorizoSpritemap.PALETTE_STAGES.forEach { stage ->
            updatePixelDigest(paletteDigest, assertNotNull(renderer.readPalette(stage), stage.name))
        }
        val compositionDigest = MessageDigest.getInstance("SHA-256")
        TorizoSpritemap.COMPOSITIONS.forEach { definition ->
            updatePixelDigest(compositionDigest, assertNotNull(renderer.renderComposition(definition, paletteFor(definition.encounter))).pixels)
        }
        val componentDigest = MessageDigest.getInstance("SHA-256")
        TorizoSpritemap.COMPONENTS.forEach { definition ->
            updatePixelDigest(componentDigest, assertNotNull(renderer.renderComponent(definition, paletteFor(TorizoSpritemap.Encounter.GOLDEN))).pixels)
        }
        val bodyAnimationDigest = MessageDigest.getInstance("SHA-256")
        var bodyFrameCount = 0
        TorizoSpritemap.ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderAnimation(definition, paletteFor(definition.encounter)), definition.key)
            animation.frames.forEach { updatePixelDigest(bodyAnimationDigest, it.pixels) }
            bodyFrameCount += animation.frames.size
        }
        val projectileAnimationDigest = MessageDigest.getInstance("SHA-256")
        var projectileFrameCount = 0
        TorizoSpritemap.PROJECTILE_ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderProjectileAnimation(definition, paletteFor(TorizoSpritemap.Encounter.GOLDEN)), definition.key)
            animation.frames.forEach { updatePixelDigest(projectileAnimationDigest, it.pixels) }
            projectileFrameCount += animation.frames.size
        }

        val values = mapOf(
            "torizo.render.palette.aggregate.sha256" to paletteDigest.digest().toHex(),
            "torizo.render.compositionPixel.aggregate.sha256" to compositionDigest.digest().toHex(),
            "torizo.render.componentPixel.aggregate.sha256" to componentDigest.digest().toHex(),
            "torizo.render.bodyAnimationPixel.aggregate.sha256" to bodyAnimationDigest.digest().toHex(),
            "torizo.render.projectileAnimationPixel.aggregate.sha256" to projectileAnimationDigest.digest().toHex(),
        )
        println("torizo.render.bodyAnimation.frame.count=$bodyFrameCount")
        println("torizo.render.projectileAnimation.frame.count=$projectileFrameCount")
        values.forEach { (key, value) -> println("$key=$value") }
        values.forEach { (key, value) -> assertEquals(TestRomHelper.referenceString(key), value, key) }
        assertEquals(TestRomHelper.referenceInt("torizo.render.bodyAnimation.frame.count"), bodyFrameCount)
        assertEquals(TestRomHelper.referenceInt("torizo.render.projectileAnimation.frame.count"), projectileFrameCount)
    }

    private fun paletteFor(encounter: TorizoSpritemap.Encounter): TorizoSpritemap.PaletteStageDef =
        TorizoSpritemap.PALETTE_STAGES.first {
            if (encounter == TorizoSpritemap.Encounter.BOMB) it.key == "bomb-normal" else it.key == "gold-active"
        }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "headerCount" to "torizo.header.count",
            "assetCount" to "torizo.asset.count",
            "assetByteCount" to "torizo.asset.byte.count",
            "activeStandardSpritemapCount" to "torizo.activeStandardSpritemap.count",
            "unusedStandardSpritemapCount" to "torizo.unusedStandardSpritemap.count",
            "standardOamEntryCount" to "torizo.standardOamEntry.count",
            "activeExtendedSpritemapCount" to "torizo.activeExtendedSpritemap.count",
            "unusedExtendedSpritemapCount" to "torizo.unusedExtendedSpritemap.count",
            "extendedChildCount" to "torizo.extendedChild.count",
            "activeBodyInstructionListCount" to "torizo.activeBodyInstructionList.count",
            "unusedBodyInstructionListCount" to "torizo.unusedBodyInstructionList.count",
            "bodyFrameOccurrenceCount" to "torizo.bodyFrame.count",
            "projectileInstructionSymbolCount" to "torizo.projectileInstructionSymbol.count",
            "activeProjectileSpritemapCount" to "torizo.activeProjectileSpritemap.count",
            "unusedProjectileSpritemapCount" to "torizo.unusedProjectileSpritemap.count",
            "projectileOamEntryCount" to "torizo.projectileOamEntry.count",
            "runtimeTransferCount" to "torizo.runtimeTransfer.count",
            "paletteRowCount" to "torizo.paletteRow.count",
        ).forEach { (field, property) -> assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field) }

        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "torizo.ownership.aggregate.sha256",
            "headers" to "torizo.header.aggregate.sha256",
            "assets" to "torizo.asset.aggregate.sha256",
            "activeStandardSpritemaps" to "torizo.activeStandardSpritemap.aggregate.sha256",
            "unusedStandardSpritemaps" to "torizo.unusedStandardSpritemap.aggregate.sha256",
            "activeExtendedSpritemaps" to "torizo.activeExtendedSpritemap.aggregate.sha256",
            "unusedExtendedSpritemaps" to "torizo.unusedExtendedSpritemap.aggregate.sha256",
            "bodyInstructionLists" to "torizo.bodyInstructionList.aggregate.sha256",
            "projectileInstructionSymbols" to "torizo.projectileInstructionSymbol.aggregate.sha256",
            "activeProjectileSpritemaps" to "torizo.activeProjectileSpritemap.aggregate.sha256",
            "unusedProjectileSpritemaps" to "torizo.unusedProjectileSpritemap.aggregate.sha256",
            "runtimeTransfers" to "torizo.runtimeTransfer.aggregate.sha256",
            "palettes" to "torizo.palette.aggregate.sha256",
        ).forEach { (field, property) -> assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field) }
    }

    private fun updatePixelDigest(digest: MessageDigest, pixels: IntArray) = pixels.forEach { pixel ->
        digest.update((pixel ushr 24).toByte())
        digest.update((pixel ushr 16).toByte())
        digest.update((pixel ushr 8).toByte())
        digest.update(pixel.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("torizo.json", "parityTorizo").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
