package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.BabyMetroidPatch
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BabyMetroidRomExporterTest {
    @Test
    fun `generic vulnerability export refuses to mutate the shared Baby corpse table`() {
        val parser = RomParser(TestRomHelper.requireRomBytes())
        val statuses = mutableListOf<String>()
        val project = SmEditProject("fixture.sfc").apply {
            patches += SmPatch(
                id = "config_enemy_vuln",
                name = "Enemy Vulnerabilities",
                enabled = true,
                configType = "enemy_vuln",
                configData = mutableMapOf("metroid_vuln12" to 2),
            )
        }

        assertNull(RomExporter(project, parser, onStatus = statuses::add).build())
        assertTrue(statuses.any { it.contains("dedicated Baby Metroid editor") && it.contains("corpse enemies") })
    }

    @Test
    fun `Baby patch baseline treats vanilla settings as unchanged`() {
        val parser = RomParser(TestRomHelper.requireRomBytes())
        val patch = SmPatch(
            id = BabyMetroidPatch.PATCH_ID,
            name = "Baby Metroid",
            configType = BabyMetroidPatch.CONFIG_TYPE,
            configData = mutableMapOf(
                BabyMetroidPatch.MORTAL_KEY to 0,
                BabyMetroidPatch.FREEZABLE_KEY to 1,
                BabyMetroidPatch.MISSILE_VULNERABILITY_KEY to 2,
                BabyMetroidPatch.SUPER_VULNERABILITY_KEY to 2,
            ),
        )

        assertTrue(!patchSettingsDifferFromBaseline(patch, parser))
        patch.configData!![BabyMetroidPatch.MORTAL_KEY] = 1
        assertTrue(patchSettingsDifferFromBaseline(patch, parser))
    }

    @Test
    fun `desktop export combines Baby stats with guarded private combat data`() {
        val original = TestRomHelper.requireRomBytes()
        val parser = RomParser(original)
        val sharedVulnerabilityPc = parser.snesToPc(0xB4F12E)
        val sharedVulnerabilityBefore = original.copyOfRange(
            sharedVulnerabilityPc,
            sharedVulnerabilityPc + 22,
        )
        val project = SmEditProject("fixture.sfc").apply {
            patches += SmPatch(
                id = "config_enemy_stats",
                name = "Enemy Stats",
                enabled = true,
                configType = "enemy_stats",
                configData = mutableMapOf("metroid_hp" to BabyMetroidPatch.FIFTY_MISSILE_HP),
            )
            patches += SmPatch(
                id = BabyMetroidPatch.PATCH_ID,
                name = "Baby Metroid",
                enabled = true,
                configType = BabyMetroidPatch.CONFIG_TYPE,
                configData = mutableMapOf(
                    BabyMetroidPatch.MORTAL_KEY to 1,
                    BabyMetroidPatch.FREEZABLE_KEY to 1,
                    BabyMetroidPatch.MISSILE_VULNERABILITY_KEY to 2,
                    BabyMetroidPatch.SUPER_VULNERABILITY_KEY to 2,
                ),
            )
        }

        val built = assertNotNull(RomExporter(project, parser).build())
        val result = built.resultRom
        fun word(snes: Int): Int {
            val pc = parser.snesToPc(snes)
            return (result[pc].toInt() and 0xFF) or ((result[pc + 1].toInt() and 0xFF) shl 8)
        }

        assertEquals(BabyMetroidPatch.FIFTY_MISSILE_HP, word(0xA0EEC3))
        assertContentEquals(ByteArray(6) { 0xEA.toByte() }, result.copyOfRange(
            parser.snesToPc(0xA9EFC8),
            parser.snesToPc(0xA9EFC8) + 6,
        ))
        val privateTableSnes = 0xB40000 or word(0xA0EEFB)
        assertNotEquals(0xB4F12E, privateTableSnes)
        assertContentEquals(
            BabyMetroidPatch.buildVulnerabilityTable(true, 2, 2),
            result.copyOfRange(parser.snesToPc(privateTableSnes), parser.snesToPc(privateTableSnes) + 22),
        )
        assertContentEquals(
            sharedVulnerabilityBefore,
            result.copyOfRange(sharedVulnerabilityPc, sharedVulnerabilityPc + 22),
            "The corpse/Baby vanilla table is shared and must remain untouched",
        )
        assertTrue(built.writeReport.writes.any { it.label.contains("guarded shot AI") })
        assertTrue(built.writeReport.writes.any { it.label.contains("private vulnerability") })
    }
}
