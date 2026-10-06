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
import kotlin.test.assertTrue

class TilesetSourceParityTest {

    @Tag("parity")
    @Test
    fun `all 29 tileset pointer triples match named source assets`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val tilesets = root.getValue("tilesets").jsonArray.map { it.jsonObject }
        val table = root.getValue("table").jsonObject

        assertEquals(TestRomHelper.referenceInt("tilesets.count"), tilesets.size)
        assertEquals(TileGraphics.NUM_TILESETS, tilesets.size)
        assertEquals(TileGraphics.TILESET_TABLE_SNES, table.int("snesAddress"))
        assertEquals(TestRomHelper.referenceInt("tilesets.pointerFields.count"), root.int("pointerFieldCount"))
        assertEquals(
            TestRomHelper.referenceInt("tilesets.resources.unique.count"),
            root.int("uniqueResourceCount"),
        )

        val rom = parser.getRomData()
        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val checkedPayloads = mutableSetOf<Int>()
        tilesets.forEachIndexed { tilesetId, record ->
            assertEquals(tilesetId, record.int("id"), "source tileset ID order")
            assertTrue(record.string("tableLabel").startsWith("Tileset_Table_"))
            assertEquals(
                TileGraphics.TILESET_TABLE_SNES + tilesetId * 9,
                record.int("tableSnesAddress"),
                "tileset $tilesetId source table address",
            )

            val catalogEntry = parser.graphicsCatalog.entry(tilesetId)
                ?: error("Vanilla graphics catalog omitted tileset ${tilesetId.toString(16)}")
            val resources = record.getValue("resources").jsonObject
            listOf(
                Triple("tileTable", 0, catalogEntry.tileTablePtr),
                Triple("graphics", 3, catalogEntry.gfxPtr),
                Triple("palette", 6, catalogEntry.palettePtr),
            ).forEach { (kind, entryOffset, catalogPointer) ->
                val resource = resources.getValue(kind).jsonObject
                val expectedPointer = resource.int("snesAddress")
                val romPointer = readU24(rom, tablePc + tilesetId * 9 + entryOffset)
                assertEquals(expectedPointer, romPointer, "tileset $tilesetId $kind ROM pointer")
                assertEquals(expectedPointer, catalogPointer, "tileset $tilesetId $kind catalog pointer")
                assertTrue(resource.string("sourceLabel").isNotBlank(), "tileset $tilesetId $kind label")
                assertTrue(resource.string("asset").endsWith(".bin"), "tileset $tilesetId $kind asset")

                if (checkedPayloads.add(expectedPointer)) {
                    val (decoded, consumed) = parser.decompressLZ2WithSize(expectedPointer)
                    assertEquals(resource.int("compressedSize"), consumed, "${resource.string("sourceLabel")} size")
                    assertEquals(resource.int("decompressedSize"), decoded.size, "${resource.string("sourceLabel")} decoded size")
                    assertEquals(
                        resource.string("decompressedSha256"),
                        TestRomHelper.sha256(decoded),
                        "${resource.string("sourceLabel")} decoded hash",
                    )
                }
            }
        }

        assertEquals(TestRomHelper.referenceInt("tilesets.resources.unique.count"), checkedPayloads.size)
        assertCountsMatchReference(root.getValue("uniqueResourceCounts").jsonObject, "unique")
        assertCountsMatchReference(root.getValue("aliasGroupCounts").jsonObject, "aliasGroups")
        assertIndirectPointerTable(parser, root, tilesets)
        assertAliasGroups(root, tilesets)
    }

    @Tag("parity")
    @Test
    fun `CRE ownership boundaries and every engine consumer match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val cre = root.getValue("cre").jsonObject
        val graphics = cre.getValue("graphics").jsonObject
        val tileTable = cre.getValue("tileTable").jsonObject

        assertTrue(cre.getValue("compressedRangesContiguous").jsonPrimitive.boolean)
        assertEquals(TileGraphics.CRE_GFX_SNES, graphics.int("snesAddress"))
        assertEquals(TileGraphics.CRE_TILE_TABLE_SNES, tileTable.int("snesAddress"))
        assertEquals(
            graphics.int("snesAddress") + graphics.int("compressedSize"),
            tileTable.int("snesAddress"),
            "compressed CRE graphics end exactly where the CRE tile table begins",
        )
        assertEquals(TileGraphics.CRE_GFX_SNES, parser.graphicsCatalog.creGfxPtr)
        assertEquals(TileGraphics.CRE_TILE_TABLE_SNES, parser.graphicsCatalog.creTileTablePtr)

        assertCrePayload(parser, graphics, "cre.graphics")
        assertCrePayload(parser, tileTable, "cre.tileTable")

        val graphicsRuntime = graphics.getValue("runtimeOwnership").jsonObject
        assertEquals(TileGraphics.CRE_TILE_START, graphicsRuntime.int("tileStart"))
        assertEquals(TileGraphics.TOTAL_TILES - 1, graphicsRuntime.int("tileEndInclusive"))
        assertEquals(TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START, graphicsRuntime.int("tileCount"))
        assertEquals(TileGraphics.CRE_GFX_MAX_BYTES, graphics.int("decompressedSize"))
        assertEquals("7E:7000", graphicsRuntime.string("wramStagingStart"))
        assertEquals("7E:9FFF", graphicsRuntime.string("wramStagingEndInclusive"))
        assertEquals("5000", graphicsRuntime.string("vramByteStart"))
        assertEquals("7FFF", graphicsRuntime.string("vramByteEndInclusive"))

        val tableRuntime = tileTable.getValue("runtimeOwnership").jsonObject
        assertEquals(0, tableRuntime.int("metatileStart"))
        assertEquals(TileGraphics.CRE_METATILE_COUNT - 1, tableRuntime.int("metatileEndInclusive"))
        assertEquals(TileGraphics.CRE_METATILE_COUNT, tableRuntime.int("metatileCount"))
        assertEquals(TileGraphics.CRE_TILE_TABLE_MAX_BYTES, tileTable.int("decompressedSize"))
        assertEquals("7E:A000", tableRuntime.string("wramStart"))
        assertEquals("7E:A7FF", tableRuntime.string("wramEndInclusive"))
        assertEquals("7E:A800", tableRuntime.string("tilesetSpecificTableStart"))
        assertEquals(TileGraphics.CRE_TILE_START, tableRuntime.int("minimumReferencedTile"))
        assertEquals(TileGraphics.TOTAL_TILES - 1, tableRuntime.int("maximumReferencedTile"))
        assertEquals(TileGraphics.CRE_METATILE_COUNT * 4, tableRuntime.int("creTileReferenceCount"))

        assertConsumerInventory(
            graphics,
            "cre.graphics",
            listOf(
                "DoorTransitionFunction_PlaceSamus_LoadTiles" to 0x82E418,
                "Load_CRETiles_TilesetTiles_and_TilesetPalette" to 0x82E79A,
            ),
        )
        assertRomPointerLoads(parser, TileGraphics.CRE_GFX_SNES, listOf(0x82E418, 0x82E79A))
        assertConsumerInventory(
            tileTable,
            "cre.tileTable",
            listOf(
                "LoadLevelData_CRE_TileTable_ScrollData_PLMs_DoorASM_RoomASM" to 0x82E840,
                "Load_Level_Scroll_and_CRE_Data" to 0x82EAF0,
            ),
        )
        assertRomPointerLoads(parser, TileGraphics.CRE_TILE_TABLE_SNES, listOf(0x82E840, 0x82EAF0))
    }

    private fun assertCrePayload(parser: RomParser, resource: JsonObject, propertyPrefix: String) {
        val (decoded, consumed) = parser.decompressLZ2WithSize(resource.int("snesAddress"))
        assertEquals(TestRomHelper.referenceInt("$propertyPrefix.compressedBytes"), consumed)
        assertEquals(TestRomHelper.referenceInt("$propertyPrefix.decompressedBytes"), decoded.size)
        assertEquals(resource.int("compressedSize"), consumed)
        assertEquals(resource.int("decompressedSize"), decoded.size)
        assertEquals(resource.string("decompressedSha256"), TestRomHelper.sha256(decoded))
    }

    private fun assertConsumerInventory(
        resource: JsonObject,
        propertyPrefix: String,
        expected: List<Pair<String, Int>>,
    ) {
        val inventory = resource.getValue("consumers").jsonObject
        assertEquals(TestRomHelper.referenceInt("$propertyPrefix.reference.count"), inventory.int("referenceCount"))
        assertEquals(TestRomHelper.referenceInt("$propertyPrefix.consumer.count"), inventory.int("consumerCount"))
        val actual = inventory.getValue("consumers").jsonArray.map { consumerElement ->
            val consumer = consumerElement.jsonObject
            consumer.string("routine") to consumer.int("pointerLoadSnesAddress")
        }
        assertEquals(expected, actual, "$propertyPrefix direct engine consumers")
    }

    /**
     * Independently scan the assembled ROM for the engine's paired immediate
     * `DP_DecompSrc+1` / `DP_DecompSrc` load idiom. This catches a numeric source
     * reference even if it bypasses the symbolic assembly-reference inventory.
     */
    private fun assertRomPointerLoads(parser: RomParser, pointer: Int, expectedLowWordLoads: List<Int>) {
        val rom = parser.getRomData()
        val actual = buildList {
            for (pc in 0..rom.size - 10) {
                if ((rom[pc].toInt() and 0xFF) != 0xA9) continue
                if ((rom[pc + 3].toInt() and 0xFF) != 0x85 || (rom[pc + 4].toInt() and 0xFF) != 0x48) continue
                if ((rom[pc + 5].toInt() and 0xFF) != 0xA9) continue
                if ((rom[pc + 8].toInt() and 0xFF) != 0x85 || (rom[pc + 9].toInt() and 0xFF) != 0x47) continue
                val loadedPointer = ((readU16(rom, pc + 1) ushr 8) shl 16) or readU16(rom, pc + 6)
                if (loadedPointer == pointer) add(parser.pcToSnes(pc + 5))
            }
        }
        assertEquals(expectedLowWordLoads, actual, "all assembled pointer-load consumers for ${pointer.toSnes()}")
    }

    private fun assertCountsMatchReference(counts: JsonObject, suffix: String) {
        listOf("tileTable", "graphics", "palette").forEach { kind ->
            assertEquals(
                TestRomHelper.referenceInt("tilesets.$kind.$suffix.count"),
                counts.int(kind),
                "$kind $suffix count",
            )
        }
    }

    private fun assertIndirectPointerTable(
        parser: RomParser,
        root: JsonObject,
        tilesets: List<JsonObject>,
    ) {
        val indirect = root.getValue("indirectPointerTable").jsonObject
        assertEquals(TileGraphics.TILESET_TABLE_SNES + TileGraphics.NUM_TILESETS * 9, indirect.int("snesAddress"))
        assertEquals(TileGraphics.NUM_TILESETS, indirect.int("entryCount"))
        val pc = parser.snesToPc(indirect.int("snesAddress"))
        indirect.getValue("entries").jsonArray.forEachIndexed { index, pointerElement ->
            val pointer = pointerElement.jsonObject
            assertEquals(tilesets[index].string("tableLabel"), pointer.string("tableLabel"))
            assertEquals(tilesets[index].int("tableSnesAddress") and 0xFFFF, readU16(parser.getRomData(), pc + index * 2))
        }
    }

    private fun assertAliasGroups(root: JsonObject, tilesets: List<JsonObject>) {
        val aliases = root.getValue("aliasGroups").jsonObject
        listOf("tileTable", "graphics", "palette").forEach { kind ->
            aliases.getValue(kind).jsonArray.forEach { groupElement ->
                val group = groupElement.jsonObject
                val ids = group.getValue("tilesetIds").jsonArray.map { it.jsonPrimitive.int }
                assertTrue(ids.size > 1, "$kind aliases must contain at least two tilesets")
                val pointers = ids.map { tilesets[it].getValue("resources").jsonObject.getValue(kind).jsonObject.int("snesAddress") }
                assertEquals(setOf(group.int("snesAddress")), pointers.toSet(), "$kind alias group ${ids.joinToString()}")
            }
        }
    }

    private fun loadManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport("tilesets.json", "parityTilesets")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun Int.toSnes(): String = "%02X:%04X".format((this ushr 16) and 0xFF, this and 0xFFFF)
}
