package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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

class KraidSourceParityTest {

    @Tag("parity")
    @Test
    fun `Kraid BG2 composition and ownership match the source manifest`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)

        val ownership = root.getValue("ownership").jsonObject
        val graphics = ownership.getValue("bgPixels").jsonObject
            .getValue("resource").jsonObject
        val kraid = KraidSpritemap(parser)
        assertTrue(kraid.load(), "Kraid renderer should load")
        assertEquals(0x1A, kraid.getTilesetId(), "room tileset")
        val rawTiles = assertNotNull(kraid.getTileData())
        assertEquals(0x8000, rawTiles.size, "complete no-CRE edit unit")
        assertEquals(graphics.string("decompressedSha256"), TestRomHelper.sha256(rawTiles))

        val sourceHeads = ownership.getValue("bgPlacement").jsonObject
            .getValue("headMaps").jsonArray.map { it.jsonObject }
        assertEquals(sourceHeads.map { it.int("snesAddress") }, KraidSpritemap.HEAD_TILEMAPS.map { it.snesAddr })

        val renderedHeads = KraidSpritemap.HEAD_TILEMAPS.mapIndexed { index, definition ->
            val source = sourceHeads[index]
            assertEquals(32, definition.cols)
            assertEquals(12, definition.storedRows)
            assertEquals(11, definition.visibleRows)
            assertEquals(0x300, source.int("storedBytes"))
            assertEquals(0x2C0, source.int("copiedBytes"))
            assertEquals(listOf(7), source.getValue("visiblePaletteRows").jsonArray.map { it.jsonPrimitive.int })

            val head = assertNotNull(kraid.renderHeadTilemap(definition), definition.name)
            assertEquals(256, head.width, definition.name)
            assertEquals(88, head.height, definition.name)
            assertEquals(32 * 11, head.entries.size, definition.name)
            assertTrue(head.pixels.any { (it ushr 24) != 0 }, definition.name)
            head
        }
        assertEquals(
            TestRomHelper.referenceString("kraid.render.headPixel.aggregate.sha256"),
            aggregatePixelHash(renderedHeads.map { it.pixels }),
            "four rendered head frames",
        )

        val sourceSequences = root.getValue("customHeadSequences").jsonArray.map { it.jsonObject }
        val animations = assertNotNull(kraid.loadHeadAnimations())
        assertEquals(sourceSequences.size, animations.size)
        sourceSequences.zip(animations).forEach { (source, animation) ->
            assertEquals(source.string("key"), animation.definition.key)
            val records = source.getValue("records").jsonArray.map { it.jsonObject }
            val sourceFrames = records.filter { it.string("kind") == "frame" }
            val sourceHandlers = records.filter { it.string("kind") == "handler" }
            assertEquals(sourceFrames.size, animation.frames.size, animation.definition.name)
            sourceFrames.zip(animation.frames).forEach { (frameSource, frame) ->
                assertEquals(frameSource.int("duration"), frame.duration)
                assertEquals(frameSource.int("tilemapSnesAddress"), frame.tilemap.snesAddr)
                assertEquals(frameSource.int("vulnerableHitboxSnesAddress"), frame.vulnerableHitboxSnes)
                assertEquals(
                    frameSource.getValue("invulnerableHitboxSnesAddress").jsonPrimitive.intOrNull,
                    frame.invulnerableHitboxSnes,
                )
            }
            assertEquals(
                sourceHandlers.map { it.int("handlerSnesAddress") },
                animation.handlerSnesAddresses,
                animation.definition.name,
            )
        }
        val sequenceDigest = MessageDigest.getInstance("SHA-256")
        animations.forEach { animation ->
            animation.frames.forEach { frame ->
                val rendered = assertNotNull(kraid.renderFullBody(frame.tilemap))
                updatePixelDigest(sequenceDigest, rendered.pixels)
            }
        }
        assertEquals(
            TestRomHelper.referenceString("kraid.render.sequenceCompositePixel.aggregate.sha256"),
            sequenceDigest.digest().toHex(),
            "21 source-ordered live-composite frame occurrences",
        )

        val sourceOamSequences = root.getValue("activeOamSequences").jsonArray.map { it.jsonObject }
        assertEquals(sourceOamSequences.map { it.string("key") }, KraidSpritemap.OAM_SEQUENCES.map { it.key })
        val oamPixelDigest = MessageDigest.getInstance("SHA-256")
        sourceOamSequences.zip(KraidSpritemap.OAM_SEQUENCES).forEach { (source, definition) ->
            assertEquals(source.int("snesAddress"), definition.snesAddr, definition.name)
            assertEquals(source.int("endSnesAddressExclusive"), definition.endSnesAddrExclusive, definition.name)
            assertEquals(source.getValue("extended").jsonPrimitive.content.toBoolean(), definition.extended, definition.name)
            assertEquals(source.getValue("loop").jsonPrimitive.content.toBoolean(), definition.loop, definition.name)
            val sourceRecords = source.getValue("records").jsonArray.map { it.jsonObject }
            val sourceFrames = sourceRecords.filter { it.string("kind") == "frame" }
            val sourceHandlers = sourceRecords.filter { it.string("kind") == "handler" }
            val parsed = assertNotNull(kraid.loadOamAnimation(definition), definition.name)
            assertEquals(sourceFrames.size, parsed.frames.size, definition.name)
            sourceFrames.zip(parsed.frames).forEach { (frameSource, frame) ->
                assertEquals(frameSource.int("duration"), frame.duration, definition.name)
                assertEquals(frameSource.int("spritemapSnesAddress"), frame.sourceSnes, definition.name)
            }
            assertEquals(
                sourceHandlers.map { it.int("handlerSnesAddress") },
                parsed.handlerSnesAddresses,
                definition.name,
            )
            val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, definition.speciesId))
            val rendered = assertNotNull(kraid.renderOamAnimation(definition, tiles))
            rendered.frames.forEach { updatePixelDigest(oamPixelDigest, it.pixels) }
        }
        val oamPixelHash = oamPixelDigest.digest().toHex()
        assertEquals(
            TestRomHelper.referenceString("kraid.render.activeOamPixel.aggregate.sha256"),
            oamPixelHash,
            "all 173 source-ordered linked OAM frame occurrences",
        )

        val miniSource = root.getValue("miniKraid").jsonObject
        assertEquals(MiniKraidSpritemap.SPECIES_ID, miniSource.int("speciesId"))
        val miniSequenceSources = miniSource.getValue("sequences").jsonArray.map { it.jsonObject }
        assertEquals(miniSequenceSources.map { it.string("key") }, MiniKraidSpritemap.SEQUENCES.map { it.key })
        val mini = MiniKraidSpritemap(parser)
        val miniTiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, MiniKraidSpritemap.SPECIES_ID))
        val miniPalette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, MiniKraidSpritemap.SPECIES_ID))
        val miniPixelDigest = MessageDigest.getInstance("SHA-256")
        miniSequenceSources.zip(MiniKraidSpritemap.SEQUENCES).forEach { (source, definition) ->
            assertEquals(source.int("snesAddress"), definition.snesAddr, definition.name)
            assertEquals(source.int("endSnesAddressExclusive"), definition.endSnesAddrExclusive, definition.name)
            val sourceFrames = source.getValue("records").jsonArray.map { it.jsonObject }
                .filter { it.string("kind") == "frame" }
            val parsed = assertNotNull(mini.loadAnimation(definition), definition.name)
            assertEquals(sourceFrames.size, parsed.frames.size, definition.name)
            sourceFrames.zip(parsed.frames).forEach { (frameSource, frame) ->
                assertEquals(frameSource.int("duration"), frame.duration, definition.name)
                assertEquals(frameSource.int("spritemapSnesAddress"), frame.sourceSnes, definition.name)
            }
            val rendered = assertNotNull(mini.renderAnimation(definition, miniTiles, miniPalette))
            rendered.frames.forEach { updatePixelDigest(miniPixelDigest, it.pixels) }
        }
        assertEquals(
            miniSource.getValue("uniquePoseSnesAddresses").jsonArray.map { it.jsonPrimitive.int },
            MiniKraidSpritemap.POSES.map { it.snesAddr },
        )
        val miniPixelHash = miniPixelDigest.digest().toHex()
        assertEquals(
            TestRomHelper.referenceString("kraid.render.miniKraidPixel.aggregate.sha256"),
            miniPixelHash,
            "all 24 source-ordered Mini Kraid frame occurrences",
        )

        val composites = KraidSpritemap.HEAD_TILEMAPS.mapIndexed { index, definition ->
            val composite = assertNotNull(kraid.renderFullBody(definition), definition.name)
            assertEquals(512, composite.width)
            assertEquals(512, composite.height)
            assertEquals(64 * 64, composite.entries.size)
            assertTrue(composite.pixels.count { (it ushr 24) != 0 } > 1000)
            assertEquals(
                renderedHeads[index].entries,
                composite.entries.take(32 * 11).mapIndexed { entryIndex, _ ->
                    // The first 32 words of every 64-word display row come from the
                    // upper-left screen block. Pull that shape back out for comparison.
                    composite.entries[(entryIndex / 32) * 64 + entryIndex % 32]
                },
                "${definition.name} overlay entries",
            )
            composite
        }
        assertEquals(
            TestRomHelper.referenceString("kraid.render.compositePixel.aggregate.sha256"),
            aggregatePixelHash(composites.map { it.pixels }),
            "four live 64x64 BG2 compositions",
        )

        val editable = renderedHeads.first()
        val editedPixels = editable.pixels.copyOf()
        val pixelIndex = editedPixels.indexOfFirst { (it ushr 24) != 0 && it != 0xFFFFFFFF.toInt() }
        assertTrue(pixelIndex >= 0)
        val before = rawTiles.copyOf()
        editedPixels[pixelIndex] = 0xFFFFFFFF.toInt()
        val modified = kraid.applyEdits(editable, editedPixels)
        assertTrue(modified.isNotEmpty(), "head edit should resolve to a tileset tile")
        val after = assertNotNull(kraid.getTileGraphics()?.getRawVarGfx())
        assertEquals(0x8000, after.size, "safe exported unit stays the complete tileset resource")
        assertNotEquals(TestRomHelper.sha256(before), TestRomHelper.sha256(after))
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "roomStateCount" to "kraid.roomState.count",
            "backgroundMapCount" to "kraid.backgroundMap.count",
            "activeBackgroundMapCount" to "kraid.backgroundMap.active.count",
            "headMapCount" to "kraid.headMap.count",
            "customSequenceCount" to "kraid.sequence.count",
            "customFrameOccurrenceCount" to "kraid.sequence.frame.count",
            "customHandlerOccurrenceCount" to "kraid.sequence.handler.count",
            "mouthHitboxCount" to "kraid.mouthHitbox.count",
            "paletteStateCount" to "kraid.paletteState.count",
            "linkedOamHeaderCount" to "kraid.oamHeader.count",
            "linkedOamInstructionListCount" to "kraid.oamInstructionList.count",
            "linkedOamFrameOccurrenceCount" to "kraid.oamFrame.count",
            "activeOamSequenceCount" to "kraid.activeOamSequence.count",
            "activeOamFrameOccurrenceCount" to "kraid.activeOamFrame.count",
            "miniKraidSequenceCount" to "kraid.miniKraid.sequence.count",
            "miniKraidFrameOccurrenceCount" to "kraid.miniKraid.frame.count",
            "miniKraidUniquePoseCount" to "kraid.miniKraid.pose.count",
            "backgroundMapConsumerAssociationCount" to "kraid.backgroundMap.consumerAssociation.count",
            "roomBackgroundTileConsumerCount" to "kraid.roomBackgroundTiles.consumer.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "kraid.ownership.aggregate.sha256",
            "headMaps" to "kraid.headMap.aggregate.sha256",
            "customSequences" to "kraid.sequence.aggregate.sha256",
            "palettes" to "kraid.palette.aggregate.sha256",
            "liveCompositeTilemap" to "kraid.compositeTilemap.sha256",
            "activeOamSequences" to "kraid.activeOamSequence.aggregate.sha256",
            "miniKraid" to "kraid.miniKraid.aggregate.sha256",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun aggregatePixelHash(frames: List<IntArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        frames.forEach { updatePixelDigest(digest, it) }
        return digest.digest().toHex()
    }

    private fun updatePixelDigest(digest: MessageDigest, pixels: IntArray) = pixels.forEach { pixel ->
        digest.update((pixel ushr 24).toByte())
        digest.update((pixel ushr 16).toByte())
        digest.update((pixel ushr 8).toByte())
        digest.update(pixel.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("kraid.json", "parityKraid").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
