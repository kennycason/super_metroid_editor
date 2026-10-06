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
import kotlin.test.assertTrue

class EnemyInstructionSourceParityTest {

    @Tag("parity")
    @Test
    fun `all named enemy instruction lists match source records and measured preview coverage`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        assertPinnedManifest(root)
        val production = EnemySpritemap(parser)

        val lists = root.getValue("lists").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("enemyInstructions.list.count"), lists.size)
        lists.forEach { list ->
            val label = list.string("sourceLabel")
            assertRawRange(parser, rom, list, label)
            val records = list.getValue("records").jsonArray.map { it.jsonObject }
            assertEquals(list.int("recordCount"), records.size, label)
            assertEquals(list.int("frameCount"), records.count { it.string("kind") == "frame" }, label)
            assertEquals(list.int("handlerCount"), records.count { it.string("kind") == "handler" }, label)

            records.forEachIndexed { index, record ->
                val context = "$label record $index"
                val pc = parser.snesToPc(record.int("snesAddress"))
                when (record.string("kind")) {
                    "frame" -> {
                        assertEquals(record.int("duration"), readU16(rom, pc), "$context duration")
                        assertEquals(record.int("spritemapPointer"), readU16(rom, pc + 2), "$context pointer")
                        val renderable = production.parseRenderableFrame(record.int("spritemapSnesAddress"))
                        assertNotNull(renderable, "$context source frame structure")
                        val hasVisibleOam = production.flattenRenderableFrame(renderable).entries.isNotEmpty()
                        assertEquals(record.boolean("productionRenderable"), hasVisibleOam, "$context renderability")
                    }
                    "handler" -> {
                        val pointer = readU16(rom, pc)
                        assertTrue(pointer >= 0x8000, "$context handler pointer")
                        assertEquals(record.int("pointer"), pointer, "$context handler pointer")
                        assertEquals(
                            (list.int("bank") shl 16) or pointer,
                            record.int("handlerSnesAddress"),
                            "$context handler address",
                        )
                        assertEquals(record.int("size") - 2, record.int("operandByteCount"), "$context operands")
                    }
                    else -> error("Unknown record kind in $context")
                }
            }

            val expected = list.getValue("productionPreview").jsonObject
                .getValue("detectedFrames").jsonArray
                .map { frame ->
                    val value = frame.jsonObject
                    PreviewFrame(value.int("duration"), value.int("spritemapSnesAddress"))
                }
            val actual = production.parseAnimationFramesAt(list.int("snesAddress"))
                .map { PreviewFrame(it.duration, it.renderableFrame.snesAddress) }
            assertEquals(expected, actual, "$label production preview")
        }

        assertTrue(root.getValue("inconsistentWidthHandlers").jsonArray.isEmpty())
    }

    private fun assertPinnedManifest(root: JsonObject) {
        val totals = root.getValue("totals").jsonObject
        val totalFields = mapOf(
            "enemyListCount" to "enemyInstructions.list.count",
            "sourceDeclaredUnusedListCount" to "enemyInstructions.list.sourceDeclaredUnused.count",
            "excludedNonEnemyListCount" to "enemyInstructions.list.excludedNonEnemy.count",
            "recordCount" to "enemyInstructions.record.count",
            "frameRecordCount" to "enemyInstructions.frame.count",
            "uniqueFrameSpritemapCount" to "enemyInstructions.frame.uniqueSpritemap.count",
            "handlerOccurrenceCount" to "enemyInstructions.handler.occurrence.count",
            "uniqueHandlerCount" to "enemyInstructions.handler.unique.count",
            "commonWidthKnownHandlerCount" to "enemyInstructions.handler.commonWidthKnown.count",
            "unsupportedHandlerCount" to "enemyInstructions.handler.unsupported.count",
            "inconsistentHandlerWidthCount" to "enemyInstructions.handler.inconsistentWidth.count",
            "productionRenderableFrameCount" to "enemyInstructions.preview.renderableFrame.count",
            "productionUnrenderableFrameCount" to "enemyInstructions.preview.unrenderableFrame.count",
            "productionRecoveredSourceFrameCount" to "enemyInstructions.preview.recoveredSourceFrame.count",
            "productionMissedSourceFrameCount" to "enemyInstructions.preview.missedSourceFrame.count",
            "productionOutOfBlockFrameCount" to "enemyInstructions.preview.outOfBlockFrame.count",
            "listsWithMissedSourceFrames" to "enemyInstructions.preview.listWithMiss.count",
            "listsWithUnsupportedHandlers" to "enemyInstructions.handler.listWithUnsupported.count",
            "frameOnlyListCount" to "enemyInstructions.list.frameOnly.count",
            "handlerOnlyListCount" to "enemyInstructions.list.handlerOnly.count",
            "sourceCommentAddressMismatchCount" to "enemyInstructions.sourceCommentAddressMismatch.count",
        )
        totalFields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
        val hashes = root.getValue("aggregateHashes").jsonObject
        mapOf(
            "lists" to "enemyInstructions.list.aggregate.sha256",
            "handlers" to "enemyInstructions.handler.aggregate.sha256",
            "misses" to "enemyInstructions.miss.aggregate.sha256",
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
        val report = TestRomHelper.requireParityReport("enemy-instructions.json", "parityEnemyInstructions")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    private data class PreviewFrame(val duration: Int, val spritemapSnesAddress: Int)
}
