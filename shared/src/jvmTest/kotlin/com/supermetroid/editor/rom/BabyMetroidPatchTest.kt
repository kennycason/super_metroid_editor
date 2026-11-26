package com.supermetroid.editor.rom

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BabyMetroidPatchTest {
    @Tag("parity")
    @Test
    fun `movement fields point at immediate operands and decode vanilla indexes`() {
        val rom = TestRomHelper.requireRomBytes()
        val parser = RomParser(rom)

        for (field in BabyMetroidPatch.MOVEMENT_FIELDS) {
            for (address in field.operandSnesAddresses) {
                val pc = parser.snesToPc(address)
                assertEquals(0xA0, rom[pc - 1].toInt() and 0xFF, "${field.label} LDY opcode")
                val value = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
                assertEquals(field.defaultValue, value, "${field.label} operand")
            }
        }
    }

    @Test
    fun `private vulnerability table freezes ice and damages only with missiles`() {
        val table = BabyMetroidPatch.buildVulnerabilityTable(
            freezable = true,
            missileVulnerability = 2,
            superVulnerability = 4,
        )

        assertEquals(22, table.size)
        assertEquals(setOf(2, 3, 6, 7, 10, 11), table.indices.filter { (table[it].toInt() and 0xFF) == 0xFF }.toSet())
        assertEquals(2, table[12].toInt() and 0xFF)
        assertEquals(4, table[13].toInt() and 0xFF)
        assertTrue(table.indices.filterNot { it in setOf(2, 3, 6, 7, 10, 11, 12, 13) }
            .all { table[it].toInt() == 0 })

        val noIce = BabyMetroidPatch.buildVulnerabilityTable(
            freezable = false,
            missileVulnerability = 2,
            superVulnerability = 2,
        )
        assertTrue(noIce.indices.filterNot { it == 12 || it == 13 }.all { noIce[it].toInt() == 0 })
    }

    @Tag("parity")
    @Test
    fun `invincible mode can tune movement without installing combat hooks`() {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        val parser = RomParser(rom)
        val plan = RomWritePlan(rom)
        val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes)

        val result = BabyMetroidPatch.install(
            config = mapOf(
                BabyMetroidPatch.MORTAL_KEY to 0,
                BabyMetroidPatch.DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY to 1,
                "samus_chase_agility" to 4,
            ),
            parser = parser,
            writePlan = plan,
            freeSpaceAllocator = allocator,
        )

        assertTrue(!result.mortalInstalled)
        assertEquals(null, result.shotShimSnes)
        assertEquals(null, result.vulnerabilityTableSnes)
        assertTrue(!result.noRescueIfDeadInstalled)
        assertEquals(null, result.rescueCheckSnes)
        assertEquals(null, result.finalBeamDispatchSnes)
        assertContentEquals(
            byteArrayOf(0xA9.toByte(), 0xFF.toByte(), 0x7F, 0x9D.toByte(), 0x8C.toByte(), 0x0F),
            plan.romData.copyOfRange(parser.snesToPc(0xA9EFC8), parser.snesToPc(0xA9EFC8) + 6),
        )
        fun word(snes: Int): Int {
            val pc = parser.snesToPc(snes)
            return (plan.romData[pc].toInt() and 0xFF) or ((plan.romData[pc + 1].toInt() and 0xFF) shl 8)
        }
        assertEquals(4, word(0xA9F1EB))
        assertEquals(0xF842, word(0xA0EEF1))
        assertEquals(0xF12E, word(0xA0EEFB))
        assertEquals(0xBDED, word(0xA9BDE1))
        assertContentEquals(
            byteArrayOf(0x20, 0x1B, 0xBE.toByte()),
            plan.romData.copyOfRange(parser.snesToPc(0xA9BDDD), parser.snesToPc(0xA9BDDD) + 3),
        )
    }

    @Tag("parity")
    @Test
    fun `mortal install preserves combat guard and relocates private data`() {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        val parser = RomParser(rom)
        val plan = RomWritePlan(rom)
        val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes, guardBytes = 2)

        val result = BabyMetroidPatch.install(
            config = mapOf(
                BabyMetroidPatch.MORTAL_KEY to 1,
                BabyMetroidPatch.FREEZABLE_KEY to 1,
                BabyMetroidPatch.MISSILE_VULNERABILITY_KEY to 2,
                BabyMetroidPatch.SUPER_VULNERABILITY_KEY to 2,
                "samus_chase_agility" to 12,
            ),
            parser = parser,
            writePlan = plan,
            freeSpaceAllocator = allocator,
        )

        assertTrue(result.mortalInstalled)
        assertEquals(1, result.movementWordsWritten)
        val shotShimSnes = requireNotNull(result.shotShimSnes)
        val vulnerabilityTableSnes = requireNotNull(result.vulnerabilityTableSnes)
        assertEquals(0xA9, shotShimSnes ushr 16)
        assertEquals(0xB4, vulnerabilityTableSnes ushr 16)
        assertTrue(!result.noRescueIfDeadInstalled)
        assertEquals(null, result.rescueCheckSnes)
        assertEquals(null, result.finalBeamDispatchSnes)

        val output = plan.romData
        val healthRestore = parser.snesToPc(0xA9EFC8)
        assertContentEquals(ByteArray(6) { 0xEA.toByte() }, output.copyOfRange(healthRestore, healthRestore + 6))

        fun word(snes: Int): Int {
            val pc = parser.snesToPc(snes)
            return (output[pc].toInt() and 0xFF) or ((output[pc + 1].toInt() and 0xFF) shl 8)
        }
        assertEquals(shotShimSnes and 0xFFFF, word(0xA0EEF1))
        assertEquals(vulnerabilityTableSnes and 0xFFFF, word(0xA0EEFB))
        assertEquals(0xA0, output[parser.snesToPc(0xA9F1EA)].toInt() and 0xFF)
        assertEquals(12, word(0xA9F1EB))

        val shimPc = parser.snesToPc(shotShimSnes)
        assertContentEquals(
            byteArrayOf(
                0xAE.toByte(), 0x54, 0x0E,
                0xBD.toByte(), 0xB6.toByte(), 0x0F,
                0xF0.toByte(), 0x08,
                0x22, 0x42, 0xF8.toByte(), 0xA9.toByte(),
                0x22, 0x3D, 0xA6.toByte(), 0xA0.toByte(),
                0x6B,
            ),
            output.copyOfRange(shimPc, shimPc + 17),
        )
        val tablePc = parser.snesToPc(vulnerabilityTableSnes)
        assertContentEquals(
            BabyMetroidPatch.buildVulnerabilityTable(true, 2, 2),
            output.copyOfRange(tablePc, tablePc + 22),
        )
        assertContentEquals(
            byteArrayOf(0x20, 0x1B, 0xBE.toByte()),
            output.copyOfRange(parser.snesToPc(0xA9BDDD), parser.snesToPc(0xA9BDDD) + 3),
            "The default-disabled option must preserve the native rescue spawn",
        )
    }

    @Tag("parity")
    @Test
    fun `dead Baby option persists death and skips the Mother Brain rescue spawn`() {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        val parser = RomParser(rom)
        val plan = RomWritePlan(rom)
        val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes, guardBytes = 2)

        val result = BabyMetroidPatch.install(
            config = mapOf(
                BabyMetroidPatch.MORTAL_KEY to 1,
                BabyMetroidPatch.DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY to 1,
            ),
            parser = parser,
            writePlan = plan,
            freeSpaceAllocator = allocator,
        )

        assertTrue(result.mortalInstalled)
        assertTrue(result.noRescueIfDeadInstalled)
        val shotShimSnes = requireNotNull(result.shotShimSnes)
        val rescueCheckSnes = requireNotNull(result.rescueCheckSnes)
        val finalBeamDispatchSnes = requireNotNull(result.finalBeamDispatchSnes)
        assertEquals(0xA9, shotShimSnes ushr 16)
        assertEquals(0xA9, rescueCheckSnes ushr 16)
        assertEquals(0xA9, finalBeamDispatchSnes ushr 16)

        val output = plan.romData
        val shimPc = parser.snesToPc(shotShimSnes)
        assertContentEquals(
            byteArrayOf(
                0xAE.toByte(), 0x54, 0x0E,
                0xBD.toByte(), 0xB6.toByte(), 0x0F,
                0xF0.toByte(), 0x14,
                0x22, 0x42, 0xF8.toByte(), 0xA9.toByte(),
                0x22, 0x3D, 0xA6.toByte(), 0xA0.toByte(),
                0xBD.toByte(), 0x8C.toByte(), 0x0F,
                0xD0.toByte(), 0x07,
                0xA9.toByte(), 0x14, 0x00,
                0x22, 0xFA.toByte(), 0x81.toByte(), 0x80.toByte(),
                0x6B,
            ),
            output.copyOfRange(shimPc, shimPc + 29),
        )

        val rescueCheckPc = parser.snesToPc(rescueCheckSnes)
        assertContentEquals(
            byteArrayOf(
                0xA9.toByte(), 0x14, 0x00,
                0x22, 0x33, 0x82.toByte(), 0x80.toByte(),
                0xB0.toByte(), 0x04,
                0x20, 0x1B, 0xBE.toByte(),
                0x60,
                0x60,
            ),
            output.copyOfRange(rescueCheckPc, rescueCheckPc + 14),
        )

        val finalBeamDispatchPc = parser.snesToPc(finalBeamDispatchSnes)
        assertContentEquals(
            byteArrayOf(
                0xA9.toByte(), 0x14, 0x00,
                0x22, 0x33, 0x82.toByte(), 0x80.toByte(),
                0xB0.toByte(), 0x03,
                0x4C, 0xED.toByte(), 0xBD.toByte(),
                0x4C, 0x51, 0xB9.toByte(),
            ),
            output.copyOfRange(finalBeamDispatchPc, finalBeamDispatchPc + 15),
        )

        val spawnCallPc = parser.snesToPc(0xA9BDDD)
        assertContentEquals(
            byteArrayOf(
                0x20,
                (rescueCheckSnes and 0xFF).toByte(),
                ((rescueCheckSnes ushr 8) and 0xFF).toByte(),
            ),
            output.copyOfRange(spawnCallPc, spawnCallPc + 3),
        )
        val finalBeamFunctionPc = parser.snesToPc(0xA9BDE1)
        assertContentEquals(
            byteArrayOf(
                (finalBeamDispatchSnes and 0xFF).toByte(),
                ((finalBeamDispatchSnes ushr 8) and 0xFF).toByte(),
            ),
            output.copyOfRange(finalBeamFunctionPc, finalBeamFunctionPc + 2),
        )
    }

    @Tag("parity")
    @Test
    fun `mortal install fails closed when native health hook changed`() {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        val parser = RomParser(rom)
        rom[parser.snesToPc(0xA9EFC8)] = 0xEA.toByte()
        val before = rom.copyOf()
        val plan = RomWritePlan(rom)
        val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes)

        val failure = assertFailsWith<RomWritePlanException> {
            BabyMetroidPatch.install(
                config = mapOf(
                    BabyMetroidPatch.MORTAL_KEY to 1,
                    "samus_chase_agility" to 4,
                ),
                parser = parser,
                writePlan = plan,
                freeSpaceAllocator = allocator,
            )
        }

        assertTrue(failure.message.orEmpty().contains("unchecked hook"))
        assertContentEquals(before, plan.romData, "Signature validation must happen before movement writes")
    }

    @Tag("parity")
    @Test
    fun `dead Baby option fails closed when either cutscene hook changed`() {
        for (hookSnes in listOf(0xA9BDDD, 0xA9BDE1)) {
            val rom = TestRomHelper.requireRomBytes().copyOf()
            val parser = RomParser(rom)
            rom[parser.snesToPc(hookSnes)] = 0xEA.toByte()
            val before = rom.copyOf()
            val plan = RomWritePlan(rom)
            val allocator = RomFreeSpaceAllocator(plan.romData, parser::snesToPc, parser::pcToSnes)

            val failure = assertFailsWith<RomWritePlanException> {
                BabyMetroidPatch.install(
                    config = mapOf(
                        BabyMetroidPatch.MORTAL_KEY to 1,
                        BabyMetroidPatch.DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY to 1,
                        "samus_chase_agility" to 4,
                    ),
                    parser = parser,
                    writePlan = plan,
                    freeSpaceAllocator = allocator,
                )
            }

            assertTrue(failure.message.orEmpty().contains("changed"))
            assertContentEquals(before, plan.romData, "Signature validation must happen before any writes")
        }
    }
}
