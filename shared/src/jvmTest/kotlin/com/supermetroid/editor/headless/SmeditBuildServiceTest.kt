package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmeditBuildServiceTest {
    @Test
    fun `build applies catalog config and raw writes`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf(
                "hex_higher_jump" to SmeditPatchRequest(),
                "bombs" to SmeditPatchRequest(
                    config = mapOf(
                        BOMB_MAX_ACTIVE_KEY to 5,
                        BOMB_FUSE_FRAMES_KEY to 10,
                        BOMB_COOLDOWN_FRAMES_KEY to 1,
                        BOMB_EXPLOSION_FRAME_DELAY_KEY to 1,
                    )
                ),
                "fanfares" to SmeditPatchRequest(
                    config = mapOf(FANFARE_FRAMES_KEY to 16)
                ),
                CERES_ESCAPE_CONFIG_TYPE to SmeditPatchRequest(configValue = 90),
            ),
            rawWrites = listOf(
                SmeditRawWriteRequest(address = "pc:0x1234", bytes = listOf(1, 2), label = "pc test"),
                SmeditRawWriteRequest(address = "80:8000", bytes = listOf(0xAA), label = "snes test"),
                SmeditRawWriteRequest(address = "pc:0x2000", bytes = listOf(0), label = "zero write"),
            ),
        )

        val service = SmeditBuildService()
        val result = service.build(original, request)
        val rom = result.romBytes

        assertEquals(0x05, rom[0x81EB9].toInt() and 0xFF)
        assertEquals(5, rom.readWord(BOMB_ACTIVE_HARD_CAP_OPERAND_PC))
        assertEquals(10, rom.readWord(BOMB_FUSE_TIMER_PC))
        assertEquals(1, rom[BOMB_COOLDOWN_PC].toInt() and 0xFF)
        assertEquals(1, rom.readWord(BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC))
        assertEquals(16, rom.readWord(FANFARE_MESSAGE_BOX_WAIT_PC))
        for (offset in FANFARE_MUSIC_RESUME_DELAY_PCS) {
            assertEquals(16, rom.readWord(offset))
        }
        assertEquals(0x0130, rom.readWord(RomParser(original).snesToPc(CERES_TIMER_OPERAND_SNES)))
        assertEquals(1, rom[0x1234].toInt() and 0xFF)
        assertEquals(2, rom[0x1235].toInt() and 0xFF)
        assertEquals(0xAA, rom[0].toInt() and 0xFF)

        assertTrue(result.report.changedBytes > 0)
        assertTrue(result.report.applied.any { it.identifier == "hex_higher_jump" })
        assertTrue(result.report.applied.any { it.configType == BOMB_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == FANFARE_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == CERES_ESCAPE_CONFIG_TYPE })

        val reconstructed = original.copyOf()
        for (write in PatchRepository.parseIps(result.ipsPatchBytes)) {
            for (i in write.bytes.indices) {
                reconstructed[write.offset.toInt() + i] = write.bytes[i].toByte()
            }
        }
        assertContentEquals(rom, reconstructed)

        val patchOnly = service.buildPatch(request)
        assertEquals("patch", patchOnly.report.mode)
        val directPatchWrites = PatchRepository.parseIps(patchOnly.ipsPatchBytes)
        assertTrue(directPatchWrites.any { it.offset == 0x2000L && it.bytes == listOf(0) })

        val reconstructedFromDirectPatch = original.copyOf()
        for (write in directPatchWrites) {
            for (i in write.bytes.indices) {
                reconstructedFromDirectPatch[write.offset.toInt() + i] = write.bytes[i].toByte()
            }
        }
        assertContentEquals(rom, reconstructedFromDirectPatch)
    }

    @Test
    fun `explicit unsupported config patch fails`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf("hex_hyper_beam" to SmeditPatchRequest())
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().build(original, request)
        }
        assertTrue(error.message.orEmpty().contains("not supported by headless build v1"))
    }

    @Test
    fun `known desktop only config patch fails explicitly`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().buildPatch(
                SmeditBuildRequest(
                    patches = mapOf("boss_stats" to SmeditPatchRequest())
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("not supported by headless build v1"))
    }

    @Test
    fun `patch only build applies beam damage and enemy stats`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf(
                BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf("power" to 123)
                ),
                ENEMY_STATS_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf(
                        "zoomer_hp" to 456,
                        "zoomer_dmg" to 78,
                        "zoomer_touchAi" to 0x8023,
                    )
                ),
            )
        )

        val result = SmeditBuildService().buildPatch(request)
        val reconstructed = original.copyOf()
        for (write in PatchRepository.parseIps(result.ipsPatchBytes)) {
            for (i in write.bytes.indices) {
                reconstructed[write.offset.toInt() + i] = write.bytes[i].toByte()
            }
        }

        val parser = RomParser(original)
        val beamTablePc = parser.snesToPc(0x938431)
        val zoomerHeaderPc = parser.snesToPc(0xA0DCFF)

        assertEquals(123, reconstructed.readWord(beamTablePc))
        assertEquals(369, reconstructed.readWord(beamTablePc + 12 * 22))
        assertEquals(456, reconstructed.readWord(zoomerHeaderPc + 0x04))
        assertEquals(78, reconstructed.readWord(zoomerHeaderPc + 0x06))
        assertEquals(0x8023, reconstructed.readWord(zoomerHeaderPc + 0x30))
        assertTrue(result.report.applied.any { it.configType == BEAM_DAMAGE_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == ENEMY_STATS_CONFIG_TYPE })
    }

    @Test
    fun `rom build applies enemy drops and vulnerabilities from rom pointers`() {
        val original = ByteArray(0x300000)
        val parser = RomParser(original)
        val zoomerHeaderPc = parser.snesToPc(0xA0DCFF)
        val dropTablePc = parser.snesToPc(0xB49000)
        val vulnerabilityTablePc = parser.snesToPc(0xB49020)
        writeWord(original, zoomerHeaderPc + 0x3A, 0x9000)
        writeWord(original, zoomerHeaderPc + 0x3C, 0x9020)

        val result = SmeditBuildService().build(
            inputRom = original,
            request = SmeditBuildRequest(
                patches = mapOf(
                    ENEMY_DROPS_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("zoomer_drop2" to 77)
                    ),
                    ENEMY_VULN_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("zoomer_vuln9" to 4)
                    ),
                )
            )
        )

        assertEquals(77, result.romBytes[dropTablePc + 2].toInt() and 0xFF)
        assertEquals(4, result.romBytes[vulnerabilityTablePc + 9].toInt() and 0xFF)
        assertTrue(result.report.applied.any { it.configType == ENEMY_DROPS_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == ENEMY_VULN_CONFIG_TYPE })
    }

    @Test
    fun `patch only enemy pointer tables warn without rom`() {
        val result = SmeditBuildService().buildPatch(
            SmeditBuildRequest(
                patches = mapOf(
                    ENEMY_DROPS_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("zoomer_drop0" to 10)
                    )
                )
            )
        )

        assertEquals(0, PatchRepository.parseIps(result.ipsPatchBytes).size)
        assertTrue(result.report.warnings.any { it.contains("requires --rom") })
    }

    @Test
    fun `project non patch edits warn because headless v1 ignores them`() {
        val project = SmEditProject(
            romPath = "base.smc",
            roomNameOverrides = mutableMapOf("91F8" to "Landing Test"),
        )

        val result = SmeditBuildService().buildPatch(SmeditBuildRequest(), project)

        assertTrue(
            result.report.warnings.any {
                it.contains("ignored unsupported project data") && it.contains("room name overrides")
            }
        )
    }

    @Test
    fun `patch only build applies fixed sprite palette overrides`() {
        val rawPalette = ByteArray(SpritePalettes.BEAM_STANDARD.byteSize) { index -> index.toByte() }
        val project = SmEditProject(
            romPath = "base.smc",
        ).also {
            it.customGfx.spritePalettes[SpritePalettes.BEAM_STANDARD.id] =
                Base64.getEncoder().encodeToString(rawPalette)
        }

        val result = SmeditBuildService().buildPatch(SmeditBuildRequest(), project)
        val writes = PatchRepository.parseIps(result.ipsPatchBytes)

        assertTrue(result.report.warnings.none { it.contains("graphics") || it.contains("palette") })
        assertTrue(result.report.applied.any { it.identifier == "project_sprite_palettes" })
        assertTrue(
            writes.any { write ->
                write.offset == SpritePalettes.BEAM_STANDARD.offset.toLong() && write.bytes == rawPalette.toIntList()
            }
        )
    }

    @Test
    fun `rom build applies tileset palette override`() {
        val original = ByteArray(0x300000) { 0xFF.toByte() }
        val parser = RomParser(original)
        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val paletteSnes = 0xC08000
        val palettePc = parser.snesToPc(paletteSnes)
        writeU24(original, tablePc + 6, paletteSnes)
        val vanillaPalette = ByteArray(256) { 0x11 }
        val compressedVanilla = LZ5Compressor.compress(vanillaPalette)
        compressedVanilla.copyInto(original, palettePc)

        val editedPalette = ByteArray(256) { 0x22 }
        val project = SmEditProject(
            romPath = "base.smc",
        ).also {
            it.customGfx.palettes["0"] = Base64.getEncoder().encodeToString(editedPalette)
        }

        val result = SmeditBuildService().build(original, SmeditBuildRequest(), project)
        val resultParser = RomParser(result.romBytes)
        val patchedPaletteSnes = result.romBytes.readU24(tablePc + 6)
        val patchedPalette = resultParser.decompressLZ2(patchedPaletteSnes)

        assertContentEquals(editedPalette, patchedPalette)
        assertTrue(result.report.applied.any { it.identifier == "project_tileset_palettes" })
        assertTrue(result.report.warnings.none { it.contains("tileset palettes require --rom") })
    }

    private fun ByteArray.readWord(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.readU24(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16)

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun writeWord(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.toIntList(): List<Int> =
        map { it.toInt() and 0xFF }
}
