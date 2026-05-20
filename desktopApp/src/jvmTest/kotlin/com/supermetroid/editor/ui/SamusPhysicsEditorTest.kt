package com.supermetroid.editor.ui
import com.supermetroid.editor.rom.TestRomHelper

import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies that Samus physics fields read correct vanilla ROM values.
 *
 * All addresses confirmed from hex_edits.txt (begrimed.com) and direct ROM inspection.
 * All values are single bytes — the physics table at these addresses in bank $91 uses
 * 1-byte entries, NOT 16-bit words.
 */
class SamusPhysicsEditorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readByte(parser: RomParser, pc: Int): Int {
        val rom = parser.getRomData()
        return rom[pc].toInt() and 0xFF
    }

    // ── Address correctness ──────────────────────────────────────────────────

    @Test
    fun `gravity address 0x081EA2 reads vanilla 0x1C`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x1C, readByte(parser, 0x081EA2))
    }

    @Test
    fun `max fall speed address 0x081110 reads vanilla 0x05`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x05, readByte(parser, 0x081110))
    }

    @Test
    fun `jump height address 0x081EB9 reads vanilla 0x04`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x04, readByte(parser, 0x081EB9))
    }

    @Test
    fun `hijump height address 0x081EC5 reads vanilla 0x06`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x06, readByte(parser, 0x081EC5))
    }

    @Test
    fun `walljump height address 0x081ED1 reads vanilla 0x04`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x04, readByte(parser, 0x081ED1))
    }

    @Test
    fun `walljump hijump height address 0x081EDD reads vanilla 0x05`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x05, readByte(parser, 0x081EDD))
    }

    @Test
    fun `run acceleration address 0x081F64 reads vanilla 0x30`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x30, readByte(parser, 0x081F64))
    }

    @Test
    fun `run max speed address 0x081F65 reads vanilla 0x02`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x02, readByte(parser, 0x081F65))
    }

    @Test
    fun `air physics mode address 0x081B2F reads vanilla 0x02`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x02, readByte(parser, 0x081B2F))
    }

    @Test
    fun `air spin jump speed address 0x081F7D reads vanilla 0x01`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x01, readByte(parser, 0x081F7D))
    }

    @Test
    fun `air normal jump speed address 0x081F71 reads vanilla 0x01`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x01, readByte(parser, 0x081F71))
    }

    // ── PhysicsField defaults match ROM ──────────────────────────────────────

    @Test
    fun `all PhysicsField defaultValues match vanilla ROM`() {
        val parser = loadTestRom() ?: return
        val mismatches = mutableListOf<String>()
        for (field in ALL_PHYSICS_FIELDS) {
            val actual = readByte(parser, field.pcOffset)
            if (actual != field.defaultValue) {
                mismatches += "${field.key}: ROM=0x${actual.toString(16).uppercase()} default=0x${field.defaultValue.toString(16).uppercase()} at PC 0x${field.pcOffset.toString(16).uppercase()}"
            }
        }
        if (mismatches.isNotEmpty()) {
            throw AssertionError("Physics field defaults don't match ROM:\n${mismatches.joinToString("\n")}")
        }
    }

    // ── readPhysicsValue uses correct single-byte reads ───────────────────────

    @Test
    fun `readPhysicsValue for gravity returns 0x1C`() {
        val parser = loadTestRom() ?: return
        val gravityField = ALL_PHYSICS_FIELDS.first { it.key == "gravity" }
        val value = readPhysicsValue(parser, gravityField)
        assertNotNull(value)
        assertEquals(0x1C, value)
    }

    @Test
    fun `readPhysicsValue for jump_height returns 0x04`() {
        val parser = loadTestRom() ?: return
        val field = ALL_PHYSICS_FIELDS.first { it.key == "jump_height" }
        val value = readPhysicsValue(parser, field)
        assertNotNull(value)
        assertEquals(0x04, value)
    }

    @Test
    fun `readPhysicsValue for run_accel returns 0x30`() {
        val parser = loadTestRom() ?: return
        val field = ALL_PHYSICS_FIELDS.first { it.key == "run_accel" }
        val value = readPhysicsValue(parser, field)
        assertNotNull(value)
        assertEquals(0x30, value)
    }
}
