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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SamusSourceParityTest {

    @Tag("parity")
    @Test
    fun `all Samus poses DMA assets spritemaps and base palettes match source`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        assertPinnedManifest(root)
        assertAnchors(root.getValue("anchors").jsonObject)

        val decoder = SamusSpriteDecoder(parser)
        val dmaTables = root.getValue("dmaTables").jsonArray.map { it.jsonObject }
        val allDmaEntries = dmaTables.flatMap { table ->
            table.getValue("entries").jsonArray.map { it.jsonObject }
        }
        assertEquals(root.getValue("totals").jsonObject.int("dmaEntryCount"), allDmaEntries.size)
        assertEquals(allDmaEntries.size, allDmaEntries.map { it.string("assetName") }.distinct().size)
        allDmaEntries.forEach { entry ->
            val definitionPc = parser.snesToPc(entry.int("snesAddress"))
            assertEquals(entry.int("sourceSnesAddress"), readU24(rom, definitionPc), entry.string("sourceLabel"))
            assertEquals(entry.int("row1Size"), readU16(rom, definitionPc + 3), entry.string("sourceLabel"))
            assertEquals(entry.int("row2Size"), readU16(rom, definitionPc + 5), entry.string("sourceLabel"))
            val sourcePc = parser.snesToPc(entry.int("sourceSnesAddress"))
            val raw = rom.copyOfRange(sourcePc, sourcePc + entry.int("size"))
            assertEquals(entry.string("assetSha256"), TestRomHelper.sha256(raw), entry.string("assetName"))
        }

        val animations = root.getValue("animations").jsonArray.map { it.jsonObject }
        assertEquals(decoder.animationCount, animations.size)
        animations.forEach { animation ->
            val poseId = animation.int("poseId")
            val frames = animation.getValue("frames").jsonArray.map { it.jsonObject }
            assertEquals(animation.int("frameCount"), decoder.getFrameCount(poseId), "pose ${poseId.hex()}")
            assertEquals(animation.int("frameCount"), frames.size, "pose ${poseId.hex()}")
            frames.forEach { expected ->
                val frameIndex = expected.int("index")
                val pose = assertNotNull(decoder.getPose(poseId, frameIndex), "pose ${poseId.hex()} frame $frameIndex")
                assertEquals(expected.int("combinedEntryCount"), pose.tilemaps.size, "pose ${poseId.hex()} frame $frameIndex")
                assertEquals(
                    expected.string("combinedTilemapSha256"),
                    tilemapHash(pose.tilemaps),
                    "pose ${poseId.hex()} frame $frameIndex tilemap",
                )
                assertEquals(
                    expected.string("vramSha256"),
                    TestRomHelper.sha256(pose.vram),
                    "pose ${poseId.hex()} frame $frameIndex VRAM",
                )
            }
        }

        val groups = SamusSpriteDecoder.ANIMATION_GROUPS
        assertTrue(groups.isNotEmpty())
        assertFalse(groups.any { it.name == "Death" }, "Death uses a separate graphics path, not E7/E8")
        groups.flatMap { it.animationIds }.forEach { poseId ->
            assertTrue(poseId in 0 until decoder.animationCount, "out-of-range grouped pose ${poseId.hex()}")
        }

        val palettes = root.getValue("palettes").jsonArray.map { it.jsonObject }
        assertEquals(3, palettes.size)
        palettes.forEach { palette ->
            val pc = parser.snesToPc(palette.int("snesAddress"))
            val raw = rom.copyOfRange(pc, pc + palette.int("size"))
            assertEquals(palette.string("rawSha256"), TestRomHelper.sha256(raw), palette.string("sourceLabel"))
        }
        SamusSpriteDecoder.SuitType.entries.forEach { suit ->
            val palette = decoder.readPalette(suit)
            assertEquals(16, palette.size)
            assertEquals(0, palette[0])
            assertTrue(palette.drop(1).any { (it ushr 24) != 0 }, suit.name)
        }
    }

    private fun assertAnchors(anchors: JsonObject) {
        assertEquals(SamusSpriteDecoder.ANIMATION_DELAY_PTRS, anchors.address("animationDelayPointers"))
        assertEquals(SamusSpriteDecoder.TILEMAP_PTRS, anchors.address("spritemapPointers"))
        assertEquals(SamusSpriteDecoder.UPPER_TILEMAP_INDEX, anchors.address("topSpritemapIndices"))
        assertEquals(SamusSpriteDecoder.LOWER_TILEMAP_INDEX, anchors.address("bottomSpritemapIndices"))
        assertEquals(SamusSpriteDecoder.TOP_DMA_PTRS, anchors.address("topDmaPointers"))
        assertEquals(SamusSpriteDecoder.BOT_DMA_PTRS, anchors.address("bottomDmaPointers"))
        assertEquals(SamusSpriteDecoder.FRAME_PROG_PTRS, anchors.address("animationDefinitionPointers"))
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "poseCount" to "samus.pose.count",
            "uniqueAnimationDefinitionCount" to "samus.animationDefinition.unique.count",
            "uniqueAnimationDefinitionFrameCount" to "samus.animationDefinition.uniqueFrame.count",
            "animationDefinitionFrameOccurrenceCount" to "samus.animationDefinition.frameOccurrence.count",
            "uniqueAnimationDelayCount" to "samus.animationDelay.unique.count",
            "dmaTableCount" to "samus.dmaTable.count",
            "dmaEntryCount" to "samus.dmaEntry.count",
            "tileAssetCount" to "samus.tileAsset.count",
            "tileAssetByteCount" to "samus.tileAsset.byte.count",
            "spritemapCount" to "samus.spritemap.count",
            "spritemapEntryCount" to "samus.spritemapEntry.count",
            "nullSpritemapLookupCount" to "samus.spritemapLookup.null.count",
            "paletteCount" to "samus.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "dma" to "samus.dma.aggregate.sha256",
            "animationDelays" to "samus.animationDelay.aggregate.sha256",
            "animations" to "samus.animation.aggregate.sha256",
            "spritemaps" to "samus.spritemap.aggregate.sha256",
            "palettes" to "samus.palette.aggregate.sha256",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun tilemapHash(entries: List<SamusSpriteDecoder.TilemapEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.updateLittleEndianWord(entry.xOffset)
            digest.updateLittleEndianWord(entry.yOffset)
            digest.updateLittleEndianWord(entry.tileNum)
            digest.update(entry.palette.toByte())
            val flags =
                (if (entry.xFlip) 1 else 0) or
                    (if (entry.yFlip) 2 else 0) or
                    (if (entry.is16x16) 4 else 0)
            digest.update(flags.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun MessageDigest.updateLittleEndianWord(value: Int) {
        update(value.toByte())
        update((value ushr 8).toByte())
    }

    private fun JsonObject.address(name: String): Int =
        getValue(name).jsonObject.int("snesAddress")

    private fun Int.hex(): String = "0x${toString(16).uppercase().padStart(2, '0')}"

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("samus.json", "paritySamus").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
