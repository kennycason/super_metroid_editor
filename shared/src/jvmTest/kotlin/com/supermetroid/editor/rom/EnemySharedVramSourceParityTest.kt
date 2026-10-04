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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Source proof for active species whose headers transfer zero graphics bytes. */
class EnemySharedVramSourceParityTest {

    private data class Contract(
        val speciesId: Int,
        val tileAddress: Int,
        val tileSize: Int,
        val tileLabels: List<String>,
        val ownership: EnemyPreviewAssetOwnership,
        val ownerSpeciesId: Int?,
        val compressed: Boolean,
        val paletteAddress: Int,
        val paletteLabel: String,
        val paletteOwnership: EnemyPreviewAssetOwnership,
        val instructionListAddress: Int,
        val instructionListLabel: String,
        val expectedFrameCount: Int,
    )

    @Tag("parity")
    @Test
    fun `zero-transfer visual species use exact runtime assets without false tile ownership`() {
        val parser = TestRomHelper.requireRomParser()
        val assets = report("assets.json").getValue("assets").jsonArray.map { it.jsonObject }
        val lz5 = report("lz5.json").getValue("streams").jsonArray.map { it.jsonObject }
        val symbols = report("symbols.json").getValue("symbols").jsonArray.map { it.jsonObject }
        val headers = report("enemy-headers.json").getValue("headers").jsonArray.map { it.jsonObject }
        val lists = report("enemy-instructions.json").getValue("lists").jsonArray.map { it.jsonObject }
        val production = EnemySpritemap(parser)
        val rom = parser.getRomData()

        CONTRACTS.forEach { contract ->
            val context = "species %04X".format(contract.speciesId)
            val headerRecord = headers.single { it.int("speciesId") == contract.speciesId }
            val headerFields = headerRecord.getValue("fields").jsonObject
            assertEquals(0, headerFields.getValue("tileDataSize").jsonObject.int("value"), "$context size")
            assertNull(EnemySpriteGraphics.loadEnemyTileData(parser, contract.speciesId), context)

            val source = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewTileSource(parser, contract.speciesId),
                context,
            )
            assertFalse(source.editable, "$context shared bytes must remain read-only")
            assertEquals(contract.tileAddress, source.snesAddress, "$context tile address")
            assertEquals(contract.tileSize, source.byteCount, "$context tile size")
            assertEquals(contract.tileSize, source.bytes.size, "$context loaded tile bytes")
            assertEquals(contract.ownership, source.ownership, "$context ownership")
            assertEquals(contract.ownerSpeciesId, source.sourceSpeciesId, "$context owner species")
            assertEquals(contract.compressed, source.compressed, "$context compression")
            val staleProjectSource = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewTileSource(
                    parser,
                    contract.speciesId,
                    ByteArray(32) { 0x55 },
                ),
                "$context stale project block",
            )
            assertFalse(staleProjectSource.editable, "$context stale block ownership")
            assertTrue(
                staleProjectSource.bytes.contentEquals(source.bytes),
                "$context must ignore a project block for a zero-transfer header",
            )

            if (contract.compressed) {
                val oracle = lz5.single { it.int("snesAddress") == contract.tileAddress }
                assertEquals(contract.tileSize, oracle.int("decompressedSize"), "$context decompressed size")
                assertEquals(
                    oracle.string("decompressedSha256"),
                    TestRomHelper.sha256(source.bytes),
                    "$context decompressed bytes",
                )
            } else {
                val sourceAssets = contract.tileLabels.map { label ->
                    assets.single { it.string("name") == label }
                }
                assertEquals(contract.tileAddress, sourceAssets.first().int("snesAddress"), "$context asset start")
                assertEquals(contract.tileSize, sourceAssets.sumOf { it.int("size") }, "$context asset span")
                val expected = sourceAssets.flatMap { asset ->
                    val pc = parser.snesToPc(asset.int("snesAddress"))
                    rom.copyOfRange(pc, pc + asset.int("size")).asIterable()
                }.toByteArray()
                assertTrue(expected.contentEquals(source.bytes), "$context source asset bytes")
            }

            val paletteSymbol = symbols.single { it.string("name") == contract.paletteLabel }
            assertEquals(contract.paletteAddress, paletteSymbol.int("snesAddress"), "$context palette symbol")
            val palette = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, contract.speciesId),
                context,
            )
            assertEquals(contract.paletteAddress, palette.snesAddress, "$context palette address")
            assertEquals(contract.paletteOwnership, palette.ownership, "$context palette ownership")
            assertTrue(
                expectedPalette(rom, parser.snesToPc(contract.paletteAddress)).contentEquals(palette.colors),
                "$context palette bytes",
            )
            assertEquals(
                contract.paletteOwnership == EnemyPreviewAssetOwnership.SPECIES_HEADER,
                palette.editable,
                "$context palette editability",
            )
            if (palette.editable) {
                val headerPaletteAddress =
                    (headerFields.getValue("bank").jsonObject.int("value") shl 16) or
                        headerFields.getValue("palette").jsonObject.int("value")
                assertEquals(contract.paletteAddress, headerPaletteAddress, "$context header palette")
            }

            val list = lists.single { it.int("snesAddress") == contract.instructionListAddress }
            assertEquals(contract.instructionListLabel, list.string("sourceLabel"), "$context list")
            val frames = production.findAnimationFrames(contract.speciesId)
            assertEquals(contract.expectedFrameCount, frames.size, "$context frames")
            assertEquals(contract.instructionListAddress, list.int("snesAddress"), "$context list address")
            assertNotNull(production.findDefaultSpritemap(contract.speciesId), "$context default frame")
            frames.forEachIndexed { index, frame ->
                val rendered = assertNotNull(
                    production.renderRenderableFrame(frame.renderableFrame, source.bytes, palette.colors),
                    "$context frame $index",
                )
                assertTrue(
                    rendered.pixels.any { (it ushr 24) != 0 },
                    "$context frame $index must contain visible pixels",
                )
            }
        }
    }

    private fun report(name: String): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport(name, "parityReport").readText(),
    ).jsonObject

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun expectedPalette(rom: ByteArray, pc: Int): IntArray = IntArray(16) { index ->
        if (index == 0) {
            0
        } else {
            val bgr = (rom[pc + index * 2].toInt() and 0xFF) or
                ((rom[pc + index * 2 + 1].toInt() and 0xFF) shl 8)
            val r = ((bgr and 0x1F) * 255 + 15) / 31
            val g = (((bgr shr 5) and 0x1F) * 255 + 15) / 31
            val b = (((bgr shr 10) and 0x1F) * 255 + 15) / 31
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private companion object {
        val CONTRACTS = listOf(
            Contract(
                0xD73F, 0x9AD200, 0x2000,
                listOf("Tiles_Standard_Sprite_0", "Tiles_Standard_Sprite_1"),
                EnemyPreviewAssetOwnership.GLOBAL_RUNTIME, null, false,
                0x9A81A0, "Initial_Palette_spritePalette5",
                EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                0xA394D6, "InstList_Elevator", 2,
            ),
            Contract(
                0xE1FF, 0x9AD200, 0x2000,
                listOf("Tiles_Standard_Sprite_0", "Tiles_Standard_Sprite_1"),
                EnemyPreviewAssetOwnership.GLOBAL_RUNTIME, null, false,
                0x9A81A0, "Initial_Palette_spritePalette5",
                EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                0xA6F061, "InstList_CeresSteam_Up_2", 8,
            ),
            Contract(
                0xE27F, 0xB78000, 0x1000, listOf("Tiles_MotherBrainHead"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xEC3F, false,
                0x9A8140, "Initial_Palette_spritePalette2",
                EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                0xA6FDCC, "InstList_Big_HealthGreaterThanEqualTo800", 1,
            ),
            Contract(
                0xECBF, 0xB18400, 0x0C00, listOf("Tiles_BabyMetroid"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xEEBF, false,
                0xA9F8E6, "Palette_BabyMetroid",
                EnemyPreviewAssetOwnership.SPECIES_HEADER,
                0xA9CFA2, "InstList_BabyMetroid_Initial", 4,
            ),
            Contract(
                0xECFF, 0xB78000, 0x1000, listOf("Tiles_MotherBrainHead"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xEC3F, false,
                0xA99472, "Palette_MotherBrain",
                EnemyPreviewAssetOwnership.SPECIES_HEADER,
                0xA98C69, "InstList_MotherBrainTubes_0", 1,
            ),
            Contract(
                0xEDFF, 0xB7C000, 0x0E00,
                listOf("Tiles_Corpse_Sidehopper_Zoomer_Ripper_Skree"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xED7F, false,
                0xA9F8A6, "Palette_CorpseCommon",
                EnemyPreviewAssetOwnership.SPECIES_HEADER,
                0xA9ECF5, "InstList_CorpseZoomer_Param1_0", 1,
            ),
            Contract(
                0xEE3F, 0xB7C000, 0x0E00,
                listOf("Tiles_Corpse_Sidehopper_Zoomer_Ripper_Skree"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xED7F, false,
                0xA9F8A6, "Palette_CorpseCommon",
                EnemyPreviewAssetOwnership.SPECIES_HEADER,
                0xA9ED07, "InstList_CorpseRipper_Param1_0", 1,
            ),
            Contract(
                0xEE7F, 0xB7C000, 0x0E00,
                listOf("Tiles_Corpse_Sidehopper_Zoomer_Ripper_Skree"),
                EnemyPreviewAssetOwnership.SHARED_SPECIES, 0xED7F, false,
                0xA9F8A6, "Palette_CorpseCommon",
                EnemyPreviewAssetOwnership.SPECIES_HEADER,
                0xA9ED13, "InstList_CorpseSkree_Param1_0", 1,
            ),
        )
    }
}
