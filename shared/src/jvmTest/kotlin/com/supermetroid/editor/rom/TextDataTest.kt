package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class TextDataTest {

    @Test
    fun `green text decodes intro story from ROM`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val sections = TextData.decodeGreenText(rom!!)
        assertTrue(sections.isNotEmpty(), "Should have at least one green text section")

        // First section starts with "I FIRST BATTLED THE METROIDS"
        val part1 = sections[0].text
        assertTrue(part1.startsWith("I FIRST BATTLED"), "Part 1 should start with 'I FIRST BATTLED', got: ${part1.take(30)}")
        assertTrue(part1.contains("PLANET ZEBES"), "Part 1 should mention PLANET ZEBES")
        assertTrue(part1.contains("MOTHER BRAIN"), "Part 1 should mention MOTHER BRAIN")
        assertTrue(part1.contains("GALACTIC CIVILIZATION"), "Part 1 should mention GALACTIC CIVILIZATION")
    }

    @Test
    fun `green text has 6 sections separated by movie markers`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val sections = TextData.decodeGreenText(rom!!)
        assertEquals(6, sections.size, "Should have 6 story sections")

        // Verify section names
        assertEquals("Intro Part 1", sections[0].name)
        assertEquals("Intro Part 2", sections[1].name)

        // Section 2: SR388
        assertTrue(sections[1].text.contains("SR388"), "Part 2 should mention SR388")

        // Section 3: Ceres station
        assertTrue(sections[2].text.contains("CERES"), "Part 3 should mention CERES")

        // Section 6: CERES STATION WAS UNDER ATTACK
        assertTrue(sections[5].text.contains("ATTACK"), "Part 6 should mention ATTACK")
    }

    @Test
    fun `area names decode correctly from ROM`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val areaNames = entries.filter { it.category == TextCategory.AREA_NAME }
        assertEquals(7, areaNames.size, "Should have 7 area names")

        assertEquals("CRATERIA", areaNames[0].text)
        assertEquals("BRINSTAR", areaNames[1].text)
        assertEquals("NORFAIR", areaNames[2].text)
        assertEquals("WRECKED SHIP", areaNames[3].text)
        assertEquals("MARIDIA", areaNames[4].text)
        assertEquals("TOURIAN", areaNames[5].text)
        assertEquals("COLONY", areaNames[6].text) // Ceres
    }

    @Test
    fun `area name roundtrip encode-decode preserves text`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val crateria = entries.first { it.id == "area_0" }

        // Encode a new name and decode it back
        val encoded = TextData.encodeAreaName("HELLO WORLD", crateria.rawBytes)
        val decoded = StringBuilder()
        for (i in 0 until 12) {
            val tile = encoded[i * 2].toInt() and 0xFF
            val ch = when {
                tile in 0x30..0x49 -> ('A' + (tile - 0x30))
                else -> ' '
            }
            decoded.append(ch)
        }
        assertEquals("HELLO WORLD ", decoded.toString())
    }

    @Test
    fun `escape text decodes correctly from ROM`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val escapes = entries.filter { it.category == TextCategory.ESCAPE_TEXT }
        assertEquals(2, escapes.size, "Should have 2 escape messages")

        // Ceres escape
        assertEquals(
            "SELF DESTRUCT SEQUENCE\nACTIVATED EVACUATE\nCOLONY IMMEDIATELY",
            escapes[0].text,
        )

        // Tourian escape
        assertEquals(
            "TIME BOMB SET!\nESCAPE IMMEDIATELY!",
            escapes[1].text,
        )
    }

    @Test
    fun `readAllText includes intro story sections`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val introEntries = entries.filter { it.category == TextCategory.INTRO_STORY }
        assertEquals(6, introEntries.size, "Should have 6 intro story entries")

        for (entry in introEntries) {
            assertTrue(entry.snesAddress > 0x8C0000, "SNES address should be in bank \$8C")
        }
    }

    @Test
    fun `UI messages decode correctly from ROM`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val uiMsgs = entries.filter { it.category == TextCategory.UI_MESSAGE }
        assertEquals(9, uiMsgs.size, "Should have 9 UI messages")

        val energyRecharge = uiMsgs.first { it.id == "energy_recharge" }
        assertEquals("ENERGY RECHARGE", energyRecharge.text)

        val missileReload = uiMsgs.first { it.id == "missile_reload" }
        assertEquals("MISSILE RELOAD", missileReload.text)

        val savePrompt1 = uiMsgs.first { it.id == "save_prompt_1" }
        assertEquals("WOULD YOU LIKE", savePrompt1.text)

        val savePrompt2 = uiMsgs.first { it.id == "save_prompt_2" }
        assertEquals("TO SAVE.", savePrompt2.text)

        val saveDone = uiMsgs.first { it.id == "save_done" }
        assertEquals("SAVE COMPLETED.", saveDone.text)

        val reserve = uiMsgs.first { it.id == "reserve_label" }
        assertEquals("RESERVE TANK", reserve.text)
    }

    @Test
    fun `UI message roundtrip encode-decode preserves text`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val energy = entries.first { it.id == "energy_recharge" }

        val encoded = TextData.encodeUiMessage("PLASMA CHARGE", energy.rawBytes)
        val decoded = TextData.decodeUiMessage(encoded, 0)
        assertTrue(decoded.contains("PLASMA CHARGE"), "Roundtrip should preserve text, got: $decoded")
    }

    @Test
    fun `UI message encode handles shorter text with padding`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val missile = entries.first { it.id == "missile_reload" }

        val encoded = TextData.encodeUiMessage("HI", missile.rawBytes)
        val decoded = TextData.decodeUiMessage(encoded, 0)
        assertTrue(decoded.contains("HI"), "Should contain 'HI', got: $decoded")
    }

    @Test
    fun `item pickup names decode correctly from ROM`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val itemNames = entries.filter { it.category == TextCategory.ITEM_NAME }
        assertEquals(19, itemNames.size, "Should have 19 item names")

        assertEquals("ENERGY TANK", itemNames.first { it.id == "item_energy_tank" }.text)
        assertEquals("MISSILE", itemNames.first { it.id == "item_missile" }.text)
        assertEquals("SUPER MISSILE", itemNames.first { it.id == "item_super_missile" }.text)
        assertEquals("POWER BOMB", itemNames.first { it.id == "item_power_bomb" }.text)
        assertEquals("VARIA SUIT", itemNames.first { it.id == "item_varia_suit" }.text)
        assertEquals("CHARGE BEAM", itemNames.first { it.id == "item_charge_beam" }.text)
        assertEquals("SPAZER", itemNames.first { it.id == "item_spazer" }.text)
        assertEquals("PLASMA BEAM", itemNames.first { it.id == "item_plasma_beam" }.text)
        assertEquals("BOMB", itemNames.first { it.id == "item_bomb" }.text)
    }

    @Test
    fun `item pickup name roundtrip encode-decode preserves text`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val missile = entries.first { it.id == "item_missile" }

        val encoded = TextData.encodeUiMessage("HYPER BEAM", missile.rawBytes)
        val decoded = TextData.decodeUiMessage(encoded, 0, missile.maxLength)
        assertTrue(decoded.contains("HYPER BEAM"), "Roundtrip should preserve text, got: $decoded")
    }

    @Test
    fun `UI message encode handles period character`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val saveDone = entries.first { it.id == "save_done" }

        val encoded = TextData.encodeUiMessage("DONE.", saveDone.rawBytes)
        val decoded = TextData.decodeUiMessage(encoded, 0)
        assertTrue(decoded.contains("DONE."), "Should handle period, got: $decoded")
    }
}
