package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnvironmentalDamageEditorTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fixed point conversion preserves vanilla rates`() {
        assertEquals(0x00004000, EnvironmentalDamagePatch.encodeEnergyPerSecond(15).raw)
        assertEquals(0x00008000, EnvironmentalDamagePatch.encodeEnergyPerSecond(30).raw)
        assertEquals(0x00018000, EnvironmentalDamagePatch.encodeEnergyPerSecond(90).raw)
        assertEquals(15, EnvironmentalDamagePatch.decodeEnergyPerSecond(0x4000, 0x0000))
        assertEquals(30, EnvironmentalDamagePatch.decodeEnergyPerSecond(0x8000, 0x0000))
        assertEquals(90, EnvironmentalDamagePatch.decodeEnergyPerSecond(0x8000, 0x0001))
    }

    @Test
    fun `per-area payload installs three checked hooks and encodes area tables`() {
        val config = mutableMapOf(
            EnvironmentalDamagePatch.PER_AREA_RATES_KEY to 1,
            EnvironmentalDamagePatch.areaRateKey("norfair", EnvironmentalDamagePatch.HEAT_KEY) to 60,
            EnvironmentalDamagePatch.areaRateKey("lower_norfair", EnvironmentalDamagePatch.HEAT_KEY) to 120,
            EnvironmentalDamagePatch.areaRateKey("maridia", EnvironmentalDamagePatch.LAVA_KEY) to 12,
            EnvironmentalDamagePatch.areaRateKey("tourian", EnvironmentalDamagePatch.ACID_KEY) to 0,
        )

        val writes = EnvironmentalDamagePatch.perAreaCheckedWrites(config)

        assertEquals(4, writes.size)
        assertEquals(listOf(0x22, 0x00, 0xFE, 0xDF), writes[0].bytes.take(4))
        assertEquals(listOf(0x22, 0x1E, 0xFE, 0xDF), writes[1].bytes.take(4))
        assertEquals(listOf(0x22, 0x3C, 0xFE, 0xDF), writes[2].bytes.take(4))
        assertTrue(writes.take(3).all { it.bytes.size == 19 && it.bytes.drop(4).all { byte -> byte == 0xEA } })
        val payload = writes.last()
        assertEquals(EnvironmentalDamagePatch.PER_AREA_CODE_PC, payload.pcOffset)
        assertEquals(236, payload.bytes.size)
        assertEquals(List(236) { 0xFF }, payload.expectedBytes)

        val tableStart = 30 * 3 + 38
        val norfairHeat = tableStart + 2 * 4
        assertEquals(listOf(0x00, 0x00, 0x01, 0x00), payload.bytes.subList(norfairHeat, norfairHeat + 4))
        val lowerNorfairHeat = tableStart + EnvironmentalDamagePatch.LOWER_NORFAIR_REGION_INDEX * 4
        assertEquals(listOf(0x00, 0x00, 0x02, 0x00),
            payload.bytes.subList(lowerNorfairHeat, lowerNorfairHeat + 4))
        val tableSize = EnvironmentalDamagePatch.RATE_REGIONS.size * 4
        val maridiaLava = tableStart + tableSize + 4 * 4
        assertEquals(listOf(0x33, 0x33, 0x00, 0x00), payload.bytes.subList(maridiaLava, maridiaLava + 4))
        val tourianAcid = tableStart + tableSize * 2 + 5 * 4
        assertEquals(listOf(0, 0, 0, 0), payload.bytes.subList(tourianAcid, tourianAcid + 4))
        assertEquals(27, EnvironmentalDamagePatch.AREA_RATE_FIELDS.map { it.key }.distinct().size)
        assertEquals(
            listOf("crateria", "brinstar", "norfair", "lower_norfair", "wrecked_ship", "maridia", "tourian", "ceres", "debug"),
            EnvironmentalDamagePatch.DISPLAY_RATE_REGIONS.map { it.key },
        )

        val exactFallback = EnvironmentalDamagePatch.perAreaCheckedWrites(
            config = mapOf(EnvironmentalDamagePatch.PER_AREA_RATES_KEY to 1),
            fallbackFixedRates = mapOf(
                EnvironmentalDamagePatch.HEAT_KEY to EnvironmentalDamagePatch.FixedRate(0x1234, 0x0002),
            ),
        ).last().bytes
        EnvironmentalDamagePatch.RATE_REGIONS.indices.forEach { areaIndex ->
            val at = tableStart + areaIndex * 4
            assertEquals(listOf(0x34, 0x12, 0x02, 0x00), exactFallback.subList(at, at + 4))
        }

        val independentLowerNorfair = EnvironmentalDamagePatch.perAreaCheckedWrites(
            config = mapOf(
                EnvironmentalDamagePatch.PER_AREA_RATES_KEY to 1,
                EnvironmentalDamagePatch.HEAT_KEY to 30,
                EnvironmentalDamagePatch.areaRateKey("norfair", EnvironmentalDamagePatch.HEAT_KEY) to 60,
            ),
        ).last().bytes
        assertEquals(listOf(0x00, 0x80, 0x00, 0x00),
            independentLowerNorfair.subList(lowerNorfairHeat, lowerNorfairHeat + 4),
            "Lower Norfair inherits only the global heat rate, not Upper Norfair")
        assertEquals(30, EnvironmentalDamagePatch.resolvedAreaRate(
            config = mapOf(
                EnvironmentalDamagePatch.HEAT_KEY to 30,
                EnvironmentalDamagePatch.areaRateKey("norfair", EnvironmentalDamagePatch.HEAT_KEY) to 60,
            ),
            area = EnvironmentalDamagePatch.RATE_REGIONS.single { it.key == "lower_norfair" },
            hazard = EnvironmentalDamagePatch.FIELDS.single { it.key == EnvironmentalDamagePatch.HEAT_KEY },
        ))
        assertEquals(
            setOf(0, 1, 2, 4, 8),
            EnvironmentalDamagePatch.heatedRateRegionIndexes(mapOf(
                EnvironmentalDamagePatch.HEAT_KEY to 0,
                EnvironmentalDamagePatch.areaRateKey("crateria", EnvironmentalDamagePatch.HEAT_KEY) to 1,
                EnvironmentalDamagePatch.areaRateKey("brinstar", EnvironmentalDamagePatch.HEAT_KEY) to 2,
                EnvironmentalDamagePatch.areaRateKey("norfair", EnvironmentalDamagePatch.HEAT_KEY) to 3,
                EnvironmentalDamagePatch.areaRateKey("lower_norfair", EnvironmentalDamagePatch.HEAT_KEY) to 6,
                EnvironmentalDamagePatch.areaRateKey("maridia", EnvironmentalDamagePatch.HEAT_KEY) to 5,
                EnvironmentalDamagePatch.areaRateKey("tourian", EnvironmentalDamagePatch.HEAT_KEY) to 0,
            )),
            "Every positive regional heat rate must install room-wide heat independently",
        )
    }

    @Test
    fun `per-area region lookup separates Upper and Lower Norfair after Lava Dive`() {
        assertEquals(EnvironmentalDamagePatch.NORFAIR_AREA_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x25), "Lava Dive remains Upper Norfair")
        assertEquals(EnvironmentalDamagePatch.LOWER_NORFAIR_REGION_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x26), "Lower Norfair elevator starts the split")
        assertEquals(EnvironmentalDamagePatch.NORFAIR_AREA_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x27))
        assertEquals(EnvironmentalDamagePatch.NORFAIR_AREA_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x33))
        assertEquals(EnvironmentalDamagePatch.LOWER_NORFAIR_REGION_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x34))
        assertEquals(EnvironmentalDamagePatch.LOWER_NORFAIR_REGION_INDEX,
            EnvironmentalDamagePatch.rateRegionIndex(2, 0x4C))
        assertEquals(4, EnvironmentalDamagePatch.rateRegionIndex(4, 0x40))
        assertEquals(0, EnvironmentalDamagePatch.rateRegionIndex(8, 0x40))
    }

    @Test
    fun `per-area heat helper selects configured regions and skips Tourian safety ranges`() {
        val writes = EnvironmentalDamagePatch.perAreaHeatCheckedWrites(setOf(5))
        val helper = writes.last()

        assertEquals(EnvironmentalDamagePatch.TOURIAN_AREA_INDEX,
            EnvironmentalDamagePatch.AREAS.single { it.key == "tourian" }.index)
        assertEquals(EnvironmentalDamagePatch.NATIVE_ROOM_LOAD_HOOKS.map { it.pcOffset },
            writes.take(2).map { it.pcOffset })
        assertEquals(60, helper.bytes.size)
        assertEquals(listOf(0x22, 0x82, 0xAB, 0x89), helper.bytes.take(4),
            "The native FX loader must run before any filtering")
        assertEquals(
            listOf(
                0x20, 0x5A, 0xFE, 0xC9, 0x05, 0x00, 0xF0, 0x02, 0x80, 0x23,
                0xAD, 0x9D, 0x07, 0xC9, 0x07, 0x00, 0x90, 0x05,
                0xC9, 0x0D, 0x00, 0x90, 0x16,
            ),
            helper.bytes.subList(10, 33),
            "The shared region classifier must select Tourian and bypass heat only in rooms 07-0C",
        )
        assertEquals(20, 18 + helper.bytes[17].toByte(), "Tourian reaches its safety checks")
        assertEquals(55, 20 + helper.bytes[19].toByte(), "Unconfigured regions return without heat")
        assertEquals(33, 28 + helper.bytes[27].toByte(), "Early Tourian reaches the scan")
        val unsafeTourianBranchTarget = 33 + helper.bytes[32].toByte()
        assertEquals(55, unsafeTourianBranchTarget)
        assertEquals(listOf(0x7A, 0xFA, 0x68, 0x28, 0x6B), helper.bytes.drop(unsafeTourianBranchTarget),
            "Unsafe Tourian rooms must only restore native state and return")
        assertEquals(listOf(0x22, 0xE9, 0xC4, 0x8D), helper.bytes.subList(51, 55),
            "Safe Tourian and escape rooms may reach the native heat-object spawn")
        assertEquals(List(helper.bytes.size) { 0xFF }, helper.expectedBytes)

        (0x07..0x0C).forEach { room ->
            assertTrue(EnvironmentalDamagePatch.isTourianHeatExcluded(5, room))
        }
        assertTrue(!EnvironmentalDamagePatch.isTourianHeatExcluded(5, 0x06), "Dust Torizo remains heated")
        assertTrue(!EnvironmentalDamagePatch.isTourianHeatExcluded(5, 0x0D), "Lower Tourian save remains heated")
        (0x0E..0x11).forEach { room ->
            assertTrue(!EnvironmentalDamagePatch.isTourianHeatExcluded(5, room), "Escape room $room remains heated")
            assertTrue(EnvironmentalDamagePatch.isTourianHeatExcluded(
                areaIndex = 5,
                roomIndex = room,
                noHeatDuringEscape = true,
            ), "Escape room $room obeys the optional escape exclusion")
        }
        assertTrue(!EnvironmentalDamagePatch.isTourianHeatExcluded(4, 0x08), "The exclusions are Tourian-only")

        val fullyHeated = EnvironmentalDamagePatch.perAreaHeatCheckedWrites(
            heatedRegionIndexes = setOf(5),
            noHeatBabyToMotherBrain = false,
            noHeatDuringEscape = false,
        ).last().bytes
        assertTrue(fullyHeated.size < helper.bytes.size)
        assertEquals(listOf(0x20, 0x5A, 0xFE, 0xC9, 0x05, 0x00), fullyHeated.subList(10, 16))

        val regional = EnvironmentalDamagePatch.perAreaHeatCheckedWrites(setOf(0, 1, 2, 4, 5, 8)).last().bytes
        listOf(0, 1, 2, 4, 5, 8).forEachIndexed { selection, regionIndex ->
            val compareAt = 13 + selection * 5
            assertEquals(listOf(0xC9, regionIndex, 0x00), regional.subList(compareAt, compareAt + 3))
        }
    }

    @Test
    fun `editor reads all three vanilla rates from ROM`() {
        val parser = TestRomHelper.loadRomParser() ?: return

        val rates = readEnvironmentalDamageRomRates(parser)

        assertEquals(15, rates[EnvironmentalDamagePatch.HEAT_KEY])
        assertEquals(30, rates[EnvironmentalDamagePatch.LAVA_KEY])
        assertEquals(90, rates[EnvironmentalDamagePatch.ACID_KEY])
        assertEquals(rates, readEnvironmentalDamageRomRates(RomParser(ByteArray(512) { 0x5A } + parser.getRomData())))
    }

    @Test
    fun `ROM environmental export preserves copier header and targets header adjusted pairs`() {
        val original = TestRomHelper.requireRomBytes()
        val header = ByteArray(512) { 0x5A }
        val input = File(tempDir, "headered-hazards.smc").apply { writeBytes(header + original) }
        val project = SmEditProject(input.path).apply { patches.add(ENVIRONMENTAL_DAMAGE_PATCH.copy(enabled = true,
            configData = mutableMapOf(EnvironmentalDamagePatch.LAVA_KEY to 40))) }
        val result = kotlin.test.assertNotNull(RomExporter(project, RomParser(header + original)).build())
        kotlin.test.assertContentEquals(header, result.resultRom.copyOfRange(0, 512))
        val fixed = EnvironmentalDamagePatch.encodeEnergyPerSecond(40)
        assertEquals(fixed.lowWord, result.resultRom.readWord(512 + 0x81E8B))
        assertEquals(fixed.highWord, result.resultRom.readWord(512 + 0x81E8D))
        kotlin.test.assertContentEquals(original, input.readBytes().copyOfRange(512, input.length().toInt()))
    }

    @Test
    fun `desktop export writes configured environmental rates`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "environmental-damage.smc")
        input.writeBytes(original!!)
        val project = SmEditProject(romPath = input.absolutePath).also { project ->
            project.patches.add(
                ENVIRONMENTAL_DAMAGE_PATCH.copy(
                    enabled = true,
                    configData = mutableMapOf(
                        EnvironmentalDamagePatch.HEAT_KEY to 0,
                        EnvironmentalDamagePatch.LAVA_KEY to 45,
                        EnvironmentalDamagePatch.ACID_KEY to 120,
                        EnvironmentalDamagePatch.LEGACY_ALL_ROOMS_HEATED_KEY to 1,
                    ),
                )
            )
        }

        val outputPath = RomExporter(project, RomParser(original)).export()

        assertNotNull(outputPath)
        val exported = File(outputPath!!).readBytes()
        val heat = EnvironmentalDamagePatch.FIELDS.first { it.key == EnvironmentalDamagePatch.HEAT_KEY }
        val lava = EnvironmentalDamagePatch.FIELDS.first { it.key == EnvironmentalDamagePatch.LAVA_KEY }
        val acid = EnvironmentalDamagePatch.FIELDS.first { it.key == EnvironmentalDamagePatch.ACID_KEY }
        assertEquals(0x0000, exported.readWord(heat.lowWordPc))
        assertEquals(0x0000, exported.readWord(heat.highWordPc))
        assertEquals(0xC000, exported.readWord(lava.lowWordPc))
        assertEquals(0x0000, exported.readWord(lava.highWordPc))
        assertEquals(0x0000, exported.readWord(acid.lowWordPc))
        assertEquals(0x0002, exported.readWord(acid.highWordPc))
        EnvironmentalDamagePatch.NATIVE_ROOM_LOAD_HOOKS.forEach { hook ->
            assertEquals(hook.expectedBytes,
                exported.copyOfRange(hook.pcOffset, hook.pcOffset + hook.expectedBytes.size)
                    .map { it.toInt() and 0xFF }, "Global scalar rates must not create room-wide heat")
        }
    }

    @Test
    fun `desktop export installs per-area damage tables without replacing suit checks`() {
        val original = TestRomHelper.loadRomBytes()
        assumeTrue(original != null, "Test ROM not found")
        val input = File(tempDir, "environmental-damage-areas.smc").apply { writeBytes(original!!) }
        val config = mutableMapOf(
            EnvironmentalDamagePatch.PER_AREA_RATES_KEY to 1,
            EnvironmentalDamagePatch.areaRateKey("norfair", EnvironmentalDamagePatch.HEAT_KEY) to 60,
        )
        val project = SmEditProject(input.absolutePath).apply {
            patches += ENVIRONMENTAL_DAMAGE_PATCH.copy(enabled = true, configData = config)
        }

        val built = kotlin.test.assertNotNull(RomExporter(project, RomParser(original!!)).build()).resultRom
        val expected = EnvironmentalDamagePatch.perAreaCheckedWrites(config)
        val expectedHeat = EnvironmentalDamagePatch.perAreaHeatCheckedWrites(
            EnvironmentalDamagePatch.heatedRateRegionIndexes(config),
        )

        (expected + expectedHeat).forEach { write ->
            assertEquals(
                write.bytes,
                built.copyOfRange(write.pcOffset, write.pcOffset + write.bytes.size).map { it.toInt() and 0xFF },
            )
        }
        assertEquals(0x21, built[0x06E37D].toInt() and 0xFF, "The native Varia/Gravity heat check remains outside the hook")
    }

    private fun ByteArray.readWord(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
