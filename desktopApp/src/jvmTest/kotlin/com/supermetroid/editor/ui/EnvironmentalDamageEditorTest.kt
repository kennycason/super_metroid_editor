package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `editor reads all three vanilla rates from ROM`() {
        val parser = TestRomHelper.loadRomParser() ?: return

        val rates = readEnvironmentalDamageRomRates(parser)

        assertEquals(15, rates[EnvironmentalDamagePatch.HEAT_KEY])
        assertEquals(30, rates[EnvironmentalDamagePatch.LAVA_KEY])
        assertEquals(90, rates[EnvironmentalDamagePatch.ACID_KEY])
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
    }

    private fun ByteArray.readWord(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
