package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

class OrdinaryEnemyAnimationSourceParityTest {

    @Tag("parity")
    @Test
    fun `helper selected ordinary enemy routes timing and renders match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedManifest(root)
        val renderer = EnemySpritemap(parser)

        val sourceAnimations = root.getValue("animations").jsonArray
            .map { it.jsonObject }
            .associateBy { it.string("key") }
        assertEquals(sourceAnimations.keys, SourceEnemyAnimations.DEFINITIONS.map { it.key }.toSet())

        val sourceLists = root.getValue("instructionLists").jsonArray
            .map { it.jsonObject }
            .associateBy { it.int("snesAddress") }
        SourceEnemyAnimations.DEFINITIONS.forEach { definition ->
            val source = sourceAnimations.getValue(definition.key)
            assertEquals(source.int("speciesId"), definition.speciesId, definition.key)
            assertEquals(
                source.intList("speciesIds").toSet(),
                setOf(definition.speciesId) + definition.aliasSpeciesIds,
                definition.key,
            )
            assertEquals(source.string("name"), definition.name, definition.key)
            assertEquals(source.intList("instructionLists"), definition.instructionLists, definition.key)
            assertEquals(source.intList("expectedFramesPerList"), definition.expectedFramesPerList, definition.key)
            assertEquals(source.boolean("loop"), definition.loop, definition.key)
            assertEquals(
                source.optionalInt("contextInstructionList"),
                definition.contextInstructionList,
                definition.key,
            )
            assertEquals(
                source.optionalInt("contextExpectedFrames") ?: 0,
                definition.contextExpectedFrames,
                definition.key,
            )
            assertEquals(
                source.optionalBoolean("contextOnTop") ?: false,
                definition.contextOnTop,
                definition.key,
            )

            definition.instructionLists.zip(definition.expectedFramesPerList).forEach { (address, expectedCount) ->
                val expectedFrames = sourceLists.getValue(address).getValue("records").jsonArray
                    .map { it.jsonObject }
                    .filter { it.string("kind") == "frame" }
                val trace = renderer.traceInstructionListAt(address, expectedCount)
                assertEquals(expectedCount, trace.frames.size, sourceLists.getValue(address).string("sourceLabel"))
                expectedFrames.zip(trace.frames).forEachIndexed { index, (expected, actual) ->
                    val context = "${definition.key} frame $index"
                    assertEquals(expected.int("duration"), actual.duration, context)
                    assertEquals(expected.int("spritemapSnesAddress"), actual.spritemap.snesAddress, context)
                }
            }
        }

        val componentDigest = MessageDigest.getInstance("SHA-256")
        root.getValue("spritemaps").jsonArray.map { it.jsonObject }.forEach { source ->
            val address = source.int("snesAddress")
            val map = assertNotNull(renderer.parseSpritemap(address), source.string("sourceLabel"))
            assertEquals(source.int("entryCount"), map.entries.size, source.string("sourceLabel"))
            val pc = parser.snesToPc(address)
            assertEquals(
                source.string("sha256"),
                TestRomHelper.sha256(parser.getRomData().copyOfRange(pc, pc + source.int("size"))),
                source.string("sourceLabel"),
            )
            val speciesId = speciesIdForKey(root, source.string("speciesKey"))
            val tiles = assertNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, speciesId))
            val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, speciesId))
            val rendered = assertNotNull(renderer.renderSpritemap(map, tiles, palette), source.string("sourceLabel"))
            updatePixelDigest(componentDigest, rendered.pixels)
        }

        val animationDigest = MessageDigest.getInstance("SHA-256")
        var animationFrames = 0
        SourceEnemyAnimations.DEFINITIONS.forEach { definition ->
            val tiles = assertNotNull(
                EnemySpriteGraphics.loadStandardOamRenderTileData(parser, definition.speciesId),
                definition.name,
            )
            val palette = assertNotNull(
                EnemySpriteGraphics.readEnemyPalette(parser, definition.speciesId),
                definition.name,
            )
            val animation = assertNotNull(
                renderer.buildSourceAnimation(definition, tiles, palette),
                definition.name,
            )
            assertEquals(definition.expectedFramesPerList.sum(), animation.frames.size, definition.name)
            val expectedDurations = definition.instructionLists.flatMap { address ->
                sourceLists.getValue(address).getValue("records").jsonArray
                    .map { it.jsonObject }
                    .filter { it.string("kind") == "frame" }
                    .map { it.int("duration") }
            }
            assertEquals(expectedDurations, animation.frames.map { it.durationTicks }, definition.name)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.name)
            animation.frames.forEach { updatePixelDigest(animationDigest, it.pixels) }
            animationFrames += animation.frames.size
        }

        root.getValue("species").jsonArray.map { it.jsonObject }.forEach { species ->
            val speciesId = species.int("speciesId")
            val expectedList = species.int("defaultInstructionList")
            val expected = sourceLists.getValue(expectedList).getValue("records").jsonArray
                .map { it.jsonObject }
                .filter { it.string("kind") == "frame" }
            val actual = renderer.findAnimationFrames(speciesId)
            assertEquals(expected.size, actual.size, species.string("displayName"))
            expected.zip(actual).forEach { (frame, decoded) ->
                assertEquals(frame.int("duration"), decoded.duration, species.string("displayName"))
                assertEquals(frame.int("spritemapSnesAddress"), decoded.spritemap.snesAddress, species.string("displayName"))
            }
        }

        val componentHash = componentDigest.digest().toHex()
        val animationHash = animationDigest.digest().toHex()
        println("ordinaryEnemyAnimations.render.componentPixel.aggregate.sha256=$componentHash")
        println("ordinaryEnemyAnimations.render.animationPixel.aggregate.sha256=$animationHash")
        assertEquals(
            TestRomHelper.referenceString("ordinaryEnemyAnimations.render.componentPixel.aggregate.sha256"),
            componentHash,
        )
        assertEquals(
            TestRomHelper.referenceString("ordinaryEnemyAnimations.render.animationPixel.aggregate.sha256"),
            animationHash,
        )
        assertEquals(TestRomHelper.referenceInt("ordinaryEnemyAnimations.animationFrame.count"), animationFrames)
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        mapOf(
            "speciesCount" to "ordinaryEnemyAnimations.species.count",
            "assetCount" to "ordinaryEnemyAnimations.asset.count",
            "assetByteCount" to "ordinaryEnemyAnimations.asset.byte.count",
            "spritemapCount" to "ordinaryEnemyAnimations.spritemap.count",
            "oamEntryCount" to "ordinaryEnemyAnimations.oamEntry.count",
            "instructionListCount" to "ordinaryEnemyAnimations.instructionList.count",
            "sourceFrameOccurrenceCount" to "ordinaryEnemyAnimations.sourceFrameOccurrence.count",
            "animationCount" to "ordinaryEnemyAnimations.animation.count",
            "animationFrameCount" to "ordinaryEnemyAnimations.animationFrame.count",
            "paletteCount" to "ordinaryEnemyAnimations.palette.count",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "ownership" to "ordinaryEnemyAnimations.ownership.aggregate.sha256",
            "species" to "ordinaryEnemyAnimations.species.aggregate.sha256",
            "spritemaps" to "ordinaryEnemyAnimations.spritemap.aggregate.sha256",
            "instructionLists" to "ordinaryEnemyAnimations.instructionList.aggregate.sha256",
            "animations" to "ordinaryEnemyAnimations.animation.aggregate.sha256",
            "palettes" to "ordinaryEnemyAnimations.palette.aggregate.sha256",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun speciesIdForKey(root: JsonObject, key: String): Int = root.getValue("species").jsonArray
        .map { it.jsonObject }
        .first { it.string("familyKey") == key }
        .int("speciesId")

    private fun updatePixelDigest(digest: MessageDigest, pixels: IntArray) = pixels.forEach { pixel ->
        digest.update((pixel ushr 24).toByte())
        digest.update((pixel ushr 16).toByte())
        digest.update((pixel ushr 8).toByte())
        digest.update(pixel.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport(
            "ordinary-enemy-animations.json",
            "parityOrdinaryEnemyAnimations",
        ).readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.optionalInt(name: String): Int? = get(name)?.jsonPrimitive?.int

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    private fun JsonObject.optionalBoolean(name: String): Boolean? = get(name)?.jsonPrimitive?.boolean

    private fun JsonObject.intList(name: String): List<Int> =
        getValue(name).jsonArray.map { it.jsonPrimitive.int }
}
