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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnemyHeaderSourceParityTest {

    @Tag("parity")
    @Test
    fun `all bank A0 enemy header macro fields match the production parser`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        val totals = root.getValue("totals").jsonObject
        assertPinnedTotals(totals)
        assertPinnedHashes(root.getValue("aggregateHashes").jsonObject)

        val headers = root.getValue("headers").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("enemyHeaders.header.count"), headers.size)
        val sourceSpeciesIds = headers.map { it.int("speciesId") }.toSet()
        assertEquals(headers.size, sourceSpeciesIds.size)

        headers.forEach { record ->
            val speciesId = record.int("speciesId")
            val pc = parser.snesToPc(record.int("snesAddress"))
            val raw = rom.copyOfRange(pc, pc + record.int("size"))
            assertEquals(record.string("rawHeaderSha256"), TestRomHelper.sha256(raw))

            val header = assertNotNull(
                EnemySpriteGraphics.readSpeciesHeader(parser, speciesId),
                record.string("sourceLabel"),
            )
            assertEquals(speciesId, header.speciesId)
            val fields = record.getValue("fields").jsonObject
            val productionValues = mapOf(
                "tileDataSize" to header.rawTileDataSize,
                "palette" to header.palettePointer,
                "health" to header.health,
                "damage" to header.damage,
                "width" to header.width,
                "height" to header.height,
                "bank" to header.aiBank,
                "hurtAITime" to header.hurtAiTime,
                "cry" to header.cry,
                "bossID" to header.bossId,
                "initAI" to header.initAi,
                "parts" to header.parts,
                "unused" to header.unused,
                "mainAI" to header.mainAi,
                "grappleAI" to header.grappleAi,
                "hurtAI" to header.hurtAi,
                "frozenAI" to header.frozenAi,
                "timeIsFrozen" to header.timeIsFrozen,
                "deathAnimation" to header.deathAnimation,
                "powerBombReaction" to header.powerBombReaction,
                "variantIndex" to header.variantIndex,
                "enemyTouch" to header.enemyTouch,
                "enemyShot" to header.enemyShot,
                "spritemap" to header.spritemap,
                "tileData" to header.tileDataAddress,
                "layer" to header.layer,
                "drops" to header.dropsPointer,
                "vulnerabilities" to header.vulnerabilitiesPointer,
                "name" to header.namePointer,
            )
            assertEquals(29, productionValues.size)
            productionValues.forEach { (field, actual) ->
                assertEquals(
                    fields.getValue(field).jsonObject.int("value"),
                    actual,
                    "${record.string("sourceLabel")}.$field",
                )
            }
            assertEquals(0L, header.deathAnimationUnused)
            assertEquals(0L, header.variantUnused)
            assertEquals(header.rawTileDataSize and 0x7FFF, header.tileDataSize)
            assertEquals(header.rawTileDataSize and 0x8000 != 0, header.alternateVramLayout)
        }

        // User-facing catalogs may intentionally omit internal species, but must
        // never offer pointers that land in the middle of a 64-byte header.
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.smedit.enemyCatalog.count"),
            RomParser.ENEMY_CATALOG.size,
        )
        assertTrue(RomParser.ENEMY_CATALOG.all { it.first in sourceSpeciesIds })
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.smedit.spriteCatalog.count"),
            EnemySpriteGraphics.EDITOR_ENEMIES.size,
        )
        assertTrue(EnemySpriteGraphics.EDITOR_ENEMIES.all { it.speciesId in sourceSpeciesIds })
    }

    @Tag("parity")
    @Test
    fun `every GRAPHADR range alias and extracted asset segment matches source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val decoder = TileDecoder()
        val headers = root.getValue("headers").jsonArray.map { it.jsonObject }

        headers.forEach { record ->
            val speciesId = record.int("speciesId")
            val graphics = record.getValue("graphics").jsonObject
            val transferSize = graphics.int("transferSize")
            val productionHeader = assertNotNull(EnemySpriteGraphics.readSpeciesHeader(parser, speciesId))
            assertEquals(transferSize, productionHeader.tileDataSize)
            assertEquals(
                graphics.getValue("alternateVramLayout").jsonPrimitive.boolean,
                productionHeader.alternateVramLayout,
            )

            if (transferSize == 0) {
                assertNull(EnemySpriteGraphics.loadEnemyTileData(parser, speciesId))
                assertTrue(graphics.getValue("segments").jsonArray.isEmpty())
                return@forEach
            }

            val block = assertNotNull(EnemySpriteGraphics.readGraphicsBlock(parser, speciesId))
            assertEquals(graphics.int("snesAddress"), block.snesAddress)
            val raw = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, speciesId))
            assertEquals(transferSize, raw.size)
            assertEquals(graphics.string("sha256"), TestRomHelper.sha256(raw))
            assertEquals(transferSize / 32, graphics.int("tileCount"))

            val pixels = ByteArray(graphics.int("tileCount") * 64)
            repeat(graphics.int("tileCount")) { tile ->
                decoder.decode4bppTileIndices(raw, tile * 32).forEachIndexed { pixel, value ->
                    pixels[tile * 64 + pixel] = value.toByte()
                }
            }
            assertEquals(graphics.string("pixelSha256"), TestRomHelper.sha256(pixels))

            val segments = graphics.getValue("segments").jsonArray.map { it.jsonObject }
            assertTrue(segments.isNotEmpty())
            assertEquals(transferSize, segments.sumOf { it.int("size") })
            var expectedAddress = graphics.int("snesAddress")
            segments.forEach { segment ->
                assertEquals(expectedAddress, segment.int("rangeSnesAddress"))
                assertTrue(segment.int("assetOffset") >= 0)
                assertTrue(segment.int("assetOffset") + segment.int("size") <= segment.int("assetSize"))
                expectedAddress += segment.int("size")
            }

            val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, raw)
            assertTrue(validation.isExportable, record.string("sourceLabel"))
            assertEquals(transferSize, validation.expectedSize)
            assertEquals(block.snesAddress, validation.snesAddress)
        }

        val aliases = root.getValue("graphicsAliases").jsonObject
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.graphics.startAliasGroup.count"),
            aliases.getValue("startAliases").jsonArray.size,
        )
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.graphics.exactRangeAliasGroup.count"),
            aliases.getValue("exactRangeAliases").jsonArray.size,
        )
        val overlaps = aliases.getValue("overlaps").jsonArray.map { it.jsonObject }
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.graphics.overlapPair.count"),
            overlaps.size,
        )
        assertEquals(
            TestRomHelper.referenceInt("enemyHeaders.graphics.crossStartOverlapPair.count"),
            overlaps.count { it.int("leftSnesAddress") != it.int("rightSnesAddress") },
        )
        overlaps.forEach { overlap ->
            val leftEnd = overlap.int("leftSnesAddress") + overlap.int("leftTransferSize")
            val rightEnd = overlap.int("rightSnesAddress") + overlap.int("rightTransferSize")
            val actual = minOf(leftEnd, rightEnd) - maxOf(
                overlap.int("leftSnesAddress"),
                overlap.int("rightSnesAddress"),
            )
            assertEquals(overlap.int("overlapSize"), actual)
            assertTrue(actual > 0)
        }
    }

    private fun assertPinnedTotals(totals: JsonObject) {
        val fields = mapOf(
            "headerCount" to "enemyHeaders.header.count",
            "headerByteCount" to "enemyHeaders.header.byte.count",
            "macroFieldCount" to "enemyHeaders.macroField.count",
            "sourceDeclaredUnusedHeaderCount" to "enemyHeaders.header.sourceDeclaredUnused.count",
            "nonEmptyGraphicsHeaderCount" to "enemyHeaders.graphics.nonEmptyHeader.count",
            "zeroSizeGraphicsHeaderCount" to "enemyHeaders.graphics.zeroSizeHeader.count",
            "alternateVramLayoutHeaderCount" to "enemyHeaders.graphics.alternateVramLayoutHeader.count",
            "uniqueGraphicsStartCount" to "enemyHeaders.graphics.uniqueStart.count",
            "uniqueGraphicsRangeCount" to "enemyHeaders.graphics.uniqueRange.count",
            "graphicsAssociationByteCount" to "enemyHeaders.graphics.associationByte.count",
            "graphicsTileAssociationCount" to "enemyHeaders.graphics.tileAssociation.count",
            "sourceAssetCount" to "enemyHeaders.graphics.sourceAsset.count",
            "sourceAssetSegmentAssociationCount" to "enemyHeaders.graphics.segmentAssociation.count",
            "multiAssetGraphicsHeaderCount" to "enemyHeaders.graphics.multiAssetHeader.count",
            "startAliasGroupCount" to "enemyHeaders.graphics.startAliasGroup.count",
            "exactRangeAliasGroupCount" to "enemyHeaders.graphics.exactRangeAliasGroup.count",
            "overlappingUniqueRangePairCount" to "enemyHeaders.graphics.overlapPair.count",
            "crossStartOverlapPairCount" to "enemyHeaders.graphics.crossStartOverlapPair.count",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
    }

    private fun assertPinnedHashes(hashes: JsonObject) {
        val fields = mapOf(
            "headers" to "enemyHeaders.header.aggregate.sha256",
            "sourceFields" to "enemyHeaders.sourceField.aggregate.sha256",
            "graphics" to "enemyHeaders.graphics.aggregate.sha256",
            "overlaps" to "enemyHeaders.overlap.aggregate.sha256",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport("enemy-headers.json", "parityEnemyHeaders")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
