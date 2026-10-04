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
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class EnemyOamSourceParityTest {

    @Tag("parity")
    @Test
    fun `all named standard enemy OAM structures match the production parser`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        assertPinnedManifest(root)
        val production = EnemySpritemap(parser)

        val records = root.getValue("standardSpritemaps").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("enemyOam.standard.label.count"), records.size)
        records.forEach { record ->
            val label = record.string("sourceLabel")
            assertRawRange(parser, rom, record, label)
            val spritemap = assertNotNull(
                production.parseSpritemap(record.int("snesAddress")),
                label,
            )
            val expectedEntries = record.getValue("entries").jsonArray.map { it.jsonObject }
            assertEquals(expectedEntries.size, spritemap.entries.size, label)
            expectedEntries.zip(spritemap.entries).forEachIndexed { index, (expected, actual) ->
                val context = "$label entry $index"
                assertEquals(expected.int("xOffset"), actual.xOffset, context)
                assertEquals(expected.int("yOffset"), actual.yOffset, context)
                assertEquals(expected.int("tileNumber"), actual.tileNum, context)
                assertEquals(expected.int("nameTable"), (actual.tileNum shr 8) and 1, context)
                assertEquals(expected.int("paletteRow"), actual.palRow, context)
                assertEquals(expected.int("priority"), actual.priority, context)
                assertEquals(expected.boolean("hFlip"), actual.hFlip, context)
                assertEquals(expected.boolean("vFlip"), actual.vFlip, context)
                assertEquals(expected.boolean("is16x16"), actual.is16x16, context)
            }
        }
    }

    @Tag("parity")
    @Test
    fun `all named extended enemy spritemaps and tilemaps match the production parser`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        val production = EnemySpritemap(parser)

        val tilemaps = root.getValue("extendedTilemaps").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("enemyOam.tilemap.label.count"), tilemaps.size)
        tilemaps.forEach { record ->
            val label = record.string("sourceLabel")
            assertRawRange(parser, rom, record, label)
            val tilemap = assertNotNull(
                production.parseExtendedTilemap(record.int("snesAddress")),
                label,
            )
            val expectedRuns = record.getValue("runs").jsonArray.map { it.jsonObject }
            assertEquals(expectedRuns.size, tilemap.runs.size, label)
            expectedRuns.zip(tilemap.runs).forEachIndexed { index, (expected, actual) ->
                assertEquals(expected.int("destination"), actual.dest, "$label run $index destination")
                assertEquals(
                    expected.getValue("words").jsonArray.map { it.jsonPrimitive.int },
                    actual.tiles,
                    "$label run $index words",
                )
            }
        }

        val extended = root.getValue("extendedSpritemaps").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("enemyOam.extended.label.count"), extended.size)
        extended.forEach { record ->
            val label = record.string("sourceLabel")
            assertRawRange(parser, rom, record, label)
            val spritemap = assertNotNull(
                production.parseExtendedSpritemap(record.int("snesAddress")),
                label,
            )
            val expectedChildren = record.getValue("children").jsonArray.map { it.jsonObject }
            assertEquals(expectedChildren.size, spritemap.children.size, label)
            expectedChildren.zip(spritemap.children).forEachIndexed { index, (expected, actual) ->
                val context = "$label child $index"
                assertEquals(expected.int("xOffset"), actual.xOffset, context)
                assertEquals(expected.int("yOffset"), actual.yOffset, context)
                assertEquals(expected.int("hitboxPointer"), actual.hitboxPtr, context)
                assertEquals(expected.int("childSnesAddress"), actual.snesAddress, context)
                when (expected.string("childType")) {
                    "standard-oam" -> assertIs<EnemySpritemap.ExtendedChild.Oam>(actual, context)
                    "extended-tilemap" -> assertIs<EnemySpritemap.ExtendedChild.Tilemap>(actual, context)
                    else -> error("Unknown child type in $context")
                }
            }
        }
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        val totalFields = mapOf(
            "standardLabelCount" to "enemyOam.standard.label.count",
            "standardUniqueAddressCount" to "enemyOam.standard.uniqueAddress.count",
            "standardUnusedLabelCount" to "enemyOam.standard.unusedLabel.count",
            "standardZeroEntryCount" to "enemyOam.standard.zeroEntry.count",
            "standardEntryCount" to "enemyOam.standard.entry.count",
            "standard8x8EntryCount" to "enemyOam.standard.entry8x8.count",
            "standard16x16EntryCount" to "enemyOam.standard.entry16x16.count",
            "extendedLabelCount" to "enemyOam.extended.label.count",
            "extendedUniqueAddressCount" to "enemyOam.extended.uniqueAddress.count",
            "extendedUnusedLabelCount" to "enemyOam.extended.unusedLabel.count",
            "extendedChildAssociationCount" to "enemyOam.extended.child.count",
            "extendedOamChildAssociationCount" to "enemyOam.extended.oamChild.count",
            "extendedTilemapChildAssociationCount" to "enemyOam.extended.tilemapChild.count",
            "uniqueHitboxAddressCount" to "enemyOam.extended.hitboxAddress.count",
            "tilemapLabelCount" to "enemyOam.tilemap.label.count",
            "tilemapUniqueAddressCount" to "enemyOam.tilemap.uniqueAddress.count",
            "tilemapUnusedLabelCount" to "enemyOam.tilemap.unusedLabel.count",
            "tilemapRunCount" to "enemyOam.tilemap.run.count",
            "tilemapWordCount" to "enemyOam.tilemap.word.count",
        )
        totalFields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "standard" to "enemyOam.standard.aggregate.sha256",
            "extended" to "enemyOam.extended.aggregate.sha256",
            "tilemaps" to "enemyOam.tilemap.aggregate.sha256",
        ).forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun assertRawRange(
        parser: RomParser,
        rom: ByteArray,
        record: JsonObject,
        context: String,
    ) {
        val pc = parser.snesToPc(record.int("snesAddress"))
        val raw = rom.copyOfRange(pc, pc + record.int("size"))
        assertEquals(record.string("rawSha256"), TestRomHelper.sha256(raw), context)
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport("enemy-oam.json", "parityEnemyOam")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
}
