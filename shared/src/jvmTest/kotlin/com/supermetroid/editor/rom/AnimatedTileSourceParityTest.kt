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

class AnimatedTileSourceParityTest {

    @Tag("parity")
    @Test
    fun `all animated tile objects frames activations and DMA consumers match source`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        val totals = root.getValue("totals").jsonObject

        assertPinnedTotals(totals)
        assertPinnedHashes(root.getValue("aggregateHashes").jsonObject)

        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }
        val assetsByAddress = assets.associateBy { it.int("snesAddress") }
        assertEquals(totals.int("assetCount"), assets.size)
        assets.forEach { asset ->
            val pc = parser.snesToPc(asset.int("snesAddress"))
            val data = rom.copyOfRange(pc, pc + asset.int("size"))
            assertEquals(asset.string("sha256"), TestRomHelper.sha256(data), asset.string("sourceLabel"))
        }
        assertEquals(
            totals.int("referencedAssetCount"),
            assets.count { it.getValue("referenced").jsonPrimitive.boolean },
        )
        assertEquals(
            totals.int("orphanAssetCount"),
            assets.count { !it.getValue("referenced").jsonPrimitive.boolean },
        )

        val objects = root.getValue("objects").jsonArray.map { it.jsonObject }
        val objectsByLabel = objects.associateBy { it.string("label") }
        objects.forEach { obj ->
            val pc = parser.snesToPc(obj.int("snesAddress"))
            assertEquals(
                obj.int("instructionListSnesAddress") and 0xFFFF,
                readU16(rom, pc),
                "${obj.string("label")} instruction list",
            )
            assertEquals(obj.int("transferSize"), readU16(rom, pc + 2), "${obj.string("label")} size")
            assertEquals(
                obj.int("vramWordAddress"),
                readU16(rom, pc + 4),
                "${obj.string("label")} VRAM destination",
            )
            assertEquals(obj.int("vramWordAddress") * 2, obj.int("vramByteAddress"))
        }

        val frames = root.getValue("frames").jsonArray.map { it.jsonObject }
        assertEquals(totals.int("uniqueFrameInstructionCount"), frames.size)
        frames.forEach { frame ->
            val pc = parser.snesToPc(frame.int("snesAddress"))
            assertEquals(frame.int("duration"), readU16(rom, pc), "${frame.string("address")} duration")
            assertEquals(
                frame.int("sourceSnesAddress") and 0xFFFF,
                readU16(rom, pc + 2),
                "${frame.string("address")} source",
            )
            val asset = assetsByAddress.getValue(frame.int("sourceSnesAddress"))
            assertEquals(asset.string("sourceLabel"), frame.string("sourceLabel"))
            frame.getValue("objectLabels").jsonArray.forEach { label ->
                val obj = objectsByLabel.getValue(label.jsonPrimitive.content)
                assertEquals(obj.int("transferSize"), asset.int("size"), label.jsonPrimitive.content)
            }
        }
        assertEquals(
            totals.int("objectFrameAssociationCount"),
            objects.sumOf { it.int("frameCount") },
        )

        assertAreaActivation(parser, rom, root.getValue("areaActivation").jsonObject, objectsByLabel)
        assertConsumers(parser, rom, root.getValue("consumers").jsonArray.map { it.jsonObject })
        assertTransferContract(
            parser,
            rom,
            root.getValue("transferContract").jsonObject,
        )
    }

    private fun assertAreaActivation(
        parser: RomParser,
        rom: ByteArray,
        activation: JsonObject,
        objectsByLabel: Map<String, JsonObject>,
    ) {
        val lists = activation.getValue("lists").jsonArray.map { it.jsonObject }
        val entries = activation.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("animatedTiles.areaList.count"), lists.size)
        assertEquals(TestRomHelper.referenceInt("animatedTiles.areaBitMapping.count"), entries.size)
        lists.forEach { list ->
            val fieldPc = parser.snesToPc(list.int("pointerFieldSnesAddress"))
            assertEquals(list.int("listSnesAddress") and 0xFFFF, readU16(rom, fieldPc))
        }
        entries.forEach { entry ->
            assertEquals(1 shl entry.int("bit"), entry.int("mask"))
            val fieldPc = parser.snesToPc(entry.int("pointerFieldSnesAddress"))
            val obj = objectsByLabel.getValue(entry.string("objectLabel"))
            assertEquals(obj.int("snesAddress") and 0xFFFF, readU16(rom, fieldPc))
        }
    }

    private fun assertConsumers(
        parser: RomParser,
        rom: ByteArray,
        consumers: List<JsonObject>,
    ) {
        assertEquals(TestRomHelper.referenceInt("animatedTiles.consumer.count"), consumers.size)
        assertEquals(
            TestRomHelper.referenceInt("animatedTiles.consumer.spawn.count"),
            consumers.count { it.string("kind") == "spawn" },
        )
        assertEquals(
            TestRomHelper.referenceInt("animatedTiles.consumer.handler.count"),
            consumers.count { it.string("kind") == "handler" },
        )
        assertEquals(
            TestRomHelper.referenceInt("animatedTiles.consumer.dma.count"),
            consumers.count { it.string("kind") == "dma" },
        )
        consumers.forEach { consumer ->
            val address = consumer.int("callSiteSnesAddress")
            val pc = parser.snesToPc(address)
            when (consumer.string("instruction")) {
                "JSL.L" -> {
                    assertEquals(0x22, rom[pc].toInt() and 0xFF)
                    assertEquals(consumer.int("targetSnesAddress"), readU24(rom, pc + 1))
                }
                "JSR.W" -> {
                    assertEquals(0x20, rom[pc].toInt() and 0xFF)
                    val target = (address and 0xFF0000) or readU16(rom, pc + 1)
                    assertEquals(consumer.int("targetSnesAddress"), target)
                }
                else -> error("Unknown consumer instruction ${consumer.string("instruction")}")
            }
        }
    }

    private fun assertTransferContract(
        parser: RomParser,
        rom: ByteArray,
        contract: JsonObject,
    ) {
        assertEquals(0x87, contract.int("sourceBank"))
        assertEquals(0x1801, contract.int("dmaControl"))
        assertBytes(parser, rom, contract.int("sourceBankInstructionSnesAddress"), 0xA0, 0x87)
        assertBytes(parser, rom, contract.int("dmaControlInstructionSnesAddress"), 0xA9, 0x01, 0x18)
        assertEquals("87:8027", contract.string("spawnRoutineAddress"))
        assertEquals("80:9416", contract.string("dmaRoutineAddress"))
        val header = contract.getValue("objectHeader").jsonObject
        assertEquals(0, header.int("instructionListOffset"))
        assertEquals(2, header.int("transferSizeOffset"))
        assertEquals(4, header.int("vramWordAddressOffset"))
        assertEquals(6, header.int("size"))
    }

    private fun assertBytes(
        parser: RomParser,
        rom: ByteArray,
        snesAddress: Int,
        vararg expected: Int,
    ) {
        val pc = parser.snesToPc(snesAddress)
        expected.forEachIndexed { index, byte ->
            assertEquals(byte, rom[pc + index].toInt() and 0xFF, "${snesAddress.toString(16)}+$index")
        }
    }

    private fun assertPinnedTotals(totals: JsonObject) {
        val fields = mapOf(
            "assetCount" to "animatedTiles.asset.count",
            "assetByteCount" to "animatedTiles.asset.byte.count",
            "referencedAssetCount" to "animatedTiles.asset.referenced.count",
            "orphanAssetCount" to "animatedTiles.asset.orphan.count",
            "sourceDeclaredUnusedAssetCount" to "animatedTiles.asset.sourceDeclaredUnused.count",
            "objectCount" to "animatedTiles.object.count",
            "nonEmptyObjectCount" to "animatedTiles.object.nonEmpty.count",
            "uniqueFrameInstructionCount" to "animatedTiles.frame.unique.count",
            "objectFrameAssociationCount" to "animatedTiles.frame.objectAssociation.count",
            "areaListCount" to "animatedTiles.areaList.count",
            "areaBitMappingCount" to "animatedTiles.areaBitMapping.count",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
    }

    private fun assertPinnedHashes(hashes: JsonObject) {
        val fields = mapOf(
            "assets" to "animatedTiles.asset.aggregate.sha256",
            "objects" to "animatedTiles.object.aggregate.sha256",
            "frames" to "animatedTiles.frame.aggregate.sha256",
            "areaActivation" to "animatedTiles.areaActivation.aggregate.sha256",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport("animated-tiles.json", "parityAnimatedTiles")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
