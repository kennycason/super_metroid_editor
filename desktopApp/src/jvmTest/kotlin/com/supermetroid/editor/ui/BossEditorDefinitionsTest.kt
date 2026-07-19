package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    @Test
    fun `ridley fields use disassembly immediate operand addresses`() {
        val fields = ALL_RIDLEY_FIELDS.associateBy { it.key }

        assertEquals(0xA6A372, fields.getValue("norfair_intro_delay").snesAddress)
        assertEquals(0x00AA, fields.getValue("norfair_intro_delay").defaultValue)
        assertEquals(0xA6A1C1, fields.getValue("norfair_tail_damage").snesAddress)
        assertEquals(0x0078, fields.getValue("norfair_tail_damage").defaultValue)
        assertEquals(0xA6B4EE, fields.getValue("norfair_swoop_horizontal_speed").snesAddress)
        assertEquals(0x0500, fields.getValue("norfair_swoop_horizontal_speed").defaultValue)
    }

    @Test
    fun `draygon mirrored fields write every matching phase operand`() {
        val fields = ALL_DRAYGON_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xA58B6F, 0xA58CF1),
            fields.getValue("goop_count").writeSnesAddresses,
        )
        assertEquals(
            listOf(0xA588BE, 0xA5895B, 0xA58A0D, 0xA58A9D),
            fields.getValue("arm_apex_index").writeSnesAddresses,
        )
        assertTrue(fields.getValue("goop_left_boundary").signed)
    }

    @Test
    fun `spore spawn mirrored death tolerance patches both axes`() {
        val fields = ALL_SPORE_SPAWN_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xA5EC27, 0xA5EC37),
            fields.getValue("death_arrival_tolerance").writeSnesAddresses,
        )
        assertTrue(fields.getValue("fight_angle_delta").signed)
    }

    @Test
    fun `crocomire mirrored scroll fields cover both offscreen branches`() {
        val fields = ALL_CROCOMIRE_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xA48BC1, 0xA48BFC),
            fields.getValue("offscreen_padding").writeSnesAddresses,
        )
        assertEquals(
            listOf(0xA48BE1, 0xA48BE9),
            fields.getValue("offscreen_bg_scroll").writeSnesAddresses,
        )
    }

    @Test
    fun `botwoon mirrored fields cover spit pattern and fall clamp operands`() {
        val fields = ALL_BOTWOON_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xB39F19, 0xB39F66),
            fields.getValue("spit_angle_step").writeSnesAddresses,
        )
        assertEquals(
            listOf(0xB39A87, 0xB39A8C),
            fields.getValue("fall_ground_y").writeSnesAddresses,
        )
        assertTrue(fields.getValue("initial_target_history_index").signed)
    }

    @Test
    fun `torizo mirrored fields cover shared low-health and fall reset operands`() {
        val fields = ALL_TORIZO_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xAAC35F, 0xAAC636, 0xAAC70B),
            fields.getValue("low_health_drool_threshold").writeSnesAddresses,
        )
        assertEquals(
            listOf(0xAAC7B8, 0xAAC81F, 0xAAC86D, 0xAAD64F),
            fields.getValue("fall_reset_y_speed").writeSnesAddresses,
        )
        assertTrue(fields.getValue("forward_jump_left_x_speed").signed)
        assertTrue(fields.getValue("back_jump_right_x_speed").signed)
    }

    @Test
    fun `mother brain mirrored fields cover bomb gates and rainbow width operands`() {
        val fields = ALL_MOTHER_BRAIN_FIELDS.associateBy { it.key }

        assertEquals(
            listOf(0xA9B6BE, 0xA9B71D),
            fields.getValue("max_active_bombs").writeSnesAddresses,
        )
        assertEquals(
            listOf(0xA9B995, 0xA9BA77),
            fields.getValue("rainbow_initial_width").writeSnesAddresses,
        )
        assertTrue(fields.getValue("fake_death_smoke_threshold").hex)
        assertTrue(fields.getValue("escape_earthquake_timer").hex)
    }

    @Test
    fun `boss behavior guardrails clamp common unsafe values without clamping raw hex fields`() {
        val motherBrainFields = ALL_MOTHER_BRAIN_FIELDS.associateBy { it.key }
        val torizoFields = ALL_TORIZO_FIELDS.associateBy { it.key }

        assertEquals(
            0x7FFF,
            coerceBossBehaviorValue(motherBrainFields.getValue("second_phase_low_health_threshold"), 0xFFFF),
            "HP thresholds should not accept 0xFFFF as a practical value",
        )
        assertEquals(
            0x0FFF,
            coerceBossBehaviorValue(motherBrainFields.getValue("phase1_samus_x_gate"), 0xFFFF),
            "pixel-position fields should be clamped to a sane room-coordinate ceiling",
        )
        assertEquals(
            0xFFFF,
            coerceBossBehaviorValue(motherBrainFields.getValue("escape_earthquake_timer"), 0xFFFF),
            "explicit hex fields should still allow full 16-bit constants",
        )
        assertEquals(
            0xFE00,
            coerceBossBehaviorValue(torizoFields.getValue("forward_jump_left_x_speed"), 0xFE00),
            "signed speed fields should preserve vanilla negative velocities",
        )
    }

    @Test
    fun `boss behavior field defaults match vanilla rom words`() {
        val parser = TestRomHelper.loadRomParser()
        assumeTrue(parser != null, "Test ROM not found")
        parser!!

        val fields = BOSS_BEHAVIOR_DEFINITIONS.flatMap { definition ->
            definition.sections.flatMap { section -> section.fields }
        }
        for (field in fields) {
            for (snesAddress in field.writeSnesAddresses) {
                assertEquals(
                    field.defaultValue,
                    readBossBehaviorFromRom(parser, field.copy(snesAddress = snesAddress)),
                    "${field.key} should point at vanilla default word at ${snesAddress.toString(16)}",
                )
            }
        }
    }
}
