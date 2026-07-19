package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BossEditorDefinitionsTest {
    @Test
    fun `phantoon stat labels describe body parts from enemy headers`() {
        val phantoon = BOSS_DEFS.first { it.name == "Phantoon" }
        val labelsByKey = phantoon.fields.associate { it.key to it.label }

        assertEquals("Eye Contact", labelsByKey["phantoon_flame1"])
        assertEquals("Tentacles Contact", labelsByKey["phantoon_flame2"])
        assertEquals("Mouth Contact", labelsByKey["phantoon_flame3"])
    }

    @Test
    fun `phantoon flame rain position defaults use x y words not movement index words`() {
        val fields = ALL_PHANTOON_FIELDS.associateBy { it.key }

        assertEquals(168, fields.getValue("pos1_x").defaultValue)
        assertEquals(64, fields.getValue("pos1_y").defaultValue)
        assertEquals(208, fields.getValue("pos2_x").defaultValue)
        assertEquals(96, fields.getValue("pos2_y").defaultValue)
        assertEquals(168, fields.getValue("pos3_x").defaultValue)
        assertEquals(128, fields.getValue("pos3_y").defaultValue)
    }

    @Test
    fun `kraid mirrored fingernail velocity fields write every disassembly table copy`() {
        val field = ALL_KRAID_FIELDS.first { it.key == "diagonal_up_y_speed" }

        assertEquals(0xA7BE54, field.snesAddress)
        assertEquals(listOf(0xA7BE54, 0xA7BE64, 0xA7BE74, 0xA7BE84), field.writeSnesAddresses)
        assertTrue(field.signed)
    }
}
