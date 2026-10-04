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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemPlmGraphicsSourceParityTest {

    @Tag("parity")
    @Test
    fun `all item PLM payloads pixels variants and editor IDs match source`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        val totals = root.getValue("totals").jsonObject
        assertPinnedTotals(totals)
        assertPinnedHashes(root.getValue("aggregateHashes").jsonObject)

        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }
        val assetsByLabel = assets.associateBy { it.string("sourceLabel") }
        assertEquals(totals.int("assetCount"), assets.size)
        val decoder = TileDecoder()
        assets.forEach { asset ->
            val pc = parser.snesToPc(asset.int("snesAddress"))
            val raw = rom.copyOfRange(pc, pc + asset.int("size"))
            assertEquals(asset.string("sha256"), TestRomHelper.sha256(raw), asset.string("sourceLabel"))
            assertEquals(8, asset.int("tileCount"))
            assertEquals(2, asset.int("frameCount"))
            assertEquals(4, asset.int("tilesPerFrame"))
            val pixels = ByteArray(asset.int("tileCount") * 64)
            repeat(asset.int("tileCount")) { tile ->
                decoder.decode4bppTileIndices(raw, tile * 32).forEachIndexed { pixel, value ->
                    pixels[tile * 64 + pixel] = value.toByte()
                }
            }
            assertEquals(asset.string("pixelSha256"), TestRomHelper.sha256(pixels))
        }

        val records = root.getValue("loadRecords").jsonArray.map { it.jsonObject }
        assertEquals(totals.int("loadRecordCount"), records.size)
        val upgradeDefinitions = RomParser.ITEM_DEFS.drop(4)
        assertEquals(17, upgradeDefinitions.size)
        assertEquals(
            upgradeDefinitions.map { it.name }.toSet(),
            records.map { it.string("displayName") }.toSet(),
        )
        records.forEach { record ->
            val definition = upgradeDefinitions.single { it.name == record.string("displayName") }
            val expectedPlmId = when (record.string("variant")) {
                "visible" -> definition.visibleId
                "chozo" -> definition.chozoId
                "hidden" -> definition.hiddenId
                else -> error("Unknown item PLM variant ${record.string("variant")}")
            }
            assertEquals(expectedPlmId, record.int("plmId"), record.string("plmEntryLabel"))

            val loadPc = parser.snesToPc(record.int("loadInstructionSnesAddress"))
            assertEquals(0x8764, readU16(rom, loadPc), record.string("instructionListLabel"))
            val argumentPc = parser.snesToPc(record.int("argumentSnesAddress"))
            assertEquals(record.int("sourceSnesAddress") and 0xFFFF, readU16(rom, argumentPc))
            val expectedPalette = record.getValue("paletteIndices").jsonArray
                .map { it.jsonPrimitive.int }
                .toIntArray()
            val actualPalette = IntArray(8) { rom[argumentPc + 2 + it].toInt() and 0xFF }
            assertContentEquals(expectedPalette, actualPalette, record.string("instructionListLabel"))
            assertTrue(actualPalette.all { it in 0..7 })

            val plmPc = parser.snesToPc(record.int("plmSnesAddress"))
            assertEquals(record.int("setupSnesAddress") and 0xFFFF, readU16(rom, plmPc))
            assertEquals(record.int("instructionListSnesAddress") and 0xFFFF, readU16(rom, plmPc + 2))
            assertEquals(
                record.int("sourceSnesAddress"),
                assetsByLabel.getValue(record.string("sourceLabel")).int("snesAddress"),
            )
        }
        assets.forEach { asset ->
            val uses = records.filter { it.string("sourceLabel") == asset.string("sourceLabel") }
            assertEquals(setOf("visible", "chozo", "hidden"), uses.map { it.string("variant") }.toSet())
            assertEquals(1, uses.map { it.getValue("paletteIndices").toString() }.toSet().size)
        }
    }

    @Tag("parity")
    @Test
    fun `item PLM four slot allocator metatile tables and VRAM placement match source`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.getRomData()
        val root = loadManifest()
        val contract = root.getValue("runtimeContract").jsonObject
        val slots = contract.getValue("slots").jsonArray.map { it.jsonObject }

        assertEquals(0x89, contract.int("sourceBank"))
        assertEquals(0x100, contract.int("transferSize"))
        assertEquals(0x1C2D, contract.int("slotCounterWramAddress"))
        assertEquals(0x0006, contract.int("slotCounterMask"))
        assertEquals(4, contract.int("slotCount"))
        assertEquals(0x1C2F, contract.int("pointerArrayWramAddress"))
        assertEquals(0x7EDF0C, contract.int("plmAssignedSlotWramAddress"))
        assertEquals(0x7EA000, contract.int("tileTableWramAddress"))
        assertEquals(TestRomHelper.referenceInt("itemPlm.slot.count"), slots.size)

        contract.getValue("immediateChecks").jsonArray.map { it.jsonObject }.forEach { check ->
            val pc = parser.snesToPc(check.int("snesAddress"))
            val expected = check.getValue("bytes").jsonArray.map { it.jsonPrimitive.int }
            expected.forEachIndexed { index, byte ->
                assertEquals(byte, rom[pc + index].toInt() and 0xFF, check.string("description"))
            }
        }

        val vramTablePc = parser.snesToPc(contract.int("vramAddressTableSnesAddress"))
        val tileTablePc = parser.snesToPc(contract.int("tileTableIndexTableSnesAddress"))
        val startingTilesPc = parser.snesToPc(contract.int("startingTileTableSnesAddress"))
        slots.forEachIndexed { index, slot ->
            assertEquals(index, slot.int("slot"))
            assertEquals(index * 2, slot.int("counterValue"))
            assertEquals(slot.int("vramWordAddress") * 2, slot.int("vramByteAddress"))
            assertEquals(0x7EA000 + slot.int("tileTableByteOffset"), slot.int("tileTableWramAddress"))
            assertEquals(slot.int("vramWordAddress"), readU16(rom, vramTablePc + index * 2))
            assertEquals(slot.int("tileTableByteOffset"), readU16(rom, tileTablePc + index * 2))
            assertEquals(slot.int("firstTileNumber"), readU16(rom, startingTilesPc + index * 2))
            assertEquals(slot.int("firstTileNumber") + 7, slot.int("lastTileNumber"))
            assertEquals(0x8E + index * 2, slot.int("frame0Metatile"))
            assertEquals(0x8F + index * 2, slot.int("frame1Metatile"))

            val frames = slot.getValue("drawFrames").jsonArray.map { it.jsonObject }
            assertEquals(2, frames.size)
            frames.forEach { frame ->
                val pointerPc = parser.snesToPc(frame.int("pointerFieldSnesAddress"))
                assertEquals(
                    frame.int("drawInstructionSnesAddress") and 0xFFFF,
                    readU16(rom, pointerPc),
                )
                val drawPc = parser.snesToPc(frame.int("drawInstructionSnesAddress"))
                assertEquals(1, readU16(rom, drawPc))
                assertEquals(frame.int("drawWord"), readU16(rom, drawPc + 2))
                assertEquals(0, readU16(rom, drawPc + 4))
                assertEquals(frame.int("metatileIndex"), frame.int("drawWord") and 0x03FF)
            }
        }

        // Reproduce the routine's eight generated metatile words for every item/slot.
        root.getValue("loadRecords").jsonArray.map { it.jsonObject }.forEach { record ->
            val palettes = record.getValue("paletteIndices").jsonArray.map { it.jsonPrimitive.int }
            slots.forEach { slot ->
                repeat(8) { quadrant ->
                    val word = slot.int("firstTileNumber") + quadrant + (palettes[quadrant] shl 10)
                    val decoded = TileGraphics.decodeMetatileWord(word)
                    assertEquals(slot.int("firstTileNumber") + quadrant, decoded.tileNum)
                    assertEquals(palettes[quadrant], decoded.palette)
                    assertEquals(false, decoded.priority)
                    assertEquals(false, decoded.hFlip)
                    assertEquals(false, decoded.vFlip)
                }
            }
        }

        val dispatchCalls = root.getValue("dispatchCalls").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("itemPlm.dispatch.count"), dispatchCalls.size)
        dispatchCalls.forEach { call ->
            val pc = parser.snesToPc(call.int("snesAddress"))
            assertEquals(0x20, rom[pc].toInt() and 0xFF)
            assertEquals(call.int("targetSnesAddress") and 0xFFFF, readU16(rom, pc + 1))
        }
    }

    private fun assertPinnedTotals(totals: JsonObject) {
        val fields = mapOf(
            "assetCount" to "itemPlm.asset.count",
            "assetByteCount" to "itemPlm.asset.byte.count",
            "tileCount" to "itemPlm.tile.count",
            "frameCount" to "itemPlm.frame.count",
            "loadRecordCount" to "itemPlm.loadRecord.count",
            "plmIdCount" to "itemPlm.id.count",
            "visiblePlmCount" to "itemPlm.visible.count",
            "chozoPlmCount" to "itemPlm.chozo.count",
            "hiddenPlmCount" to "itemPlm.hidden.count",
            "paletteProfileCount" to "itemPlm.paletteProfile.count",
            "slotCount" to "itemPlm.slot.count",
            "drawPointerCount" to "itemPlm.drawPointer.count",
            "dispatchCallCount" to "itemPlm.dispatch.count",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), totals.int(field), field)
        }
    }

    private fun assertPinnedHashes(hashes: JsonObject) {
        val fields = mapOf(
            "assets" to "itemPlm.asset.aggregate.sha256",
            "pixels" to "itemPlm.pixel.aggregate.sha256",
            "loadRecords" to "itemPlm.loadRecord.aggregate.sha256",
            "slots" to "itemPlm.slot.aggregate.sha256",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceString(property), hashes.string(field), field)
        }
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport(
            "item-plm-graphics.json",
            "parityItemPlmGraphics",
        )
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
}
