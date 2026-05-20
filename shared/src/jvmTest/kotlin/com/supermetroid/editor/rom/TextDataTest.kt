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
        assertTrue(escapes[0].text.contains("SELF DESTRUCT"), "Ceres should mention SELF DESTRUCT")

        // Tourian escape
        assertTrue(escapes[1].text.contains("TIME BOMB"), "Tourian should mention TIME BOMB")
    }

    @Test
    fun `readAllText includes intro story sections`() {
        val rom = TestRomHelper.loadRomBytes()
        assumeTrue(rom != null, "ROM not available")

        val entries = TextData.readAllText(rom!!)
        val introEntries = entries.filter { it.category == TextCategory.INTRO_STORY }
        assertEquals(6, introEntries.size, "Should have 6 intro story entries")

        // All should have valid SNES addresses
        for (entry in introEntries) {
            assertTrue(entry.snesAddress > 0x8C0000, "SNES address should be in bank \$8C")
        }
    }
}
