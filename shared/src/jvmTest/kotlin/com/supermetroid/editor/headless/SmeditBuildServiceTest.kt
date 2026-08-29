package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomNamePauseMapPatch
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TestRomHelper
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
        original[0x81EB9] = 0x04
        val request = SmeditBuildRequest(
            patches = mapOf(
                "higher_jump" to SmeditPatchRequest(),
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
        assertTrue(result.report.applied.any { it.identifier == "higher_jump" })
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
    fun `build applies writes to headered rom body and emits headerless ips`() {
        val original = ByteArray(RomConstants.ROM_SIZE_WITH_HEADER)
        original.fill(0xA5.toByte(), 0, RomConstants.SMC_HEADER_SIZE)
        original[RomConstants.SMC_HEADER_SIZE + 0x81EB9] = 0x04
        writeWord(original, RomConstants.SMC_HEADER_SIZE + FANFARE_MESSAGE_BOX_WAIT_PC, 0x1234)

        val request = SmeditBuildRequest(
            patches = mapOf(
                "higher_jump" to SmeditPatchRequest(),
                "fanfares" to SmeditPatchRequest(),
            ),
            rawWrites = listOf(
                SmeditRawWriteRequest(address = "pc:0x1234", bytes = listOf(1, 2), label = "pc test"),
                SmeditRawWriteRequest(address = "80:8000", bytes = listOf(0xAA), label = "snes test"),
            ),
        )

        val result = SmeditBuildService().build(original, request)
        val rom = result.romBytes
        val headerSize = RomConstants.SMC_HEADER_SIZE

        assertContentEquals(original.copyOfRange(0, headerSize), rom.copyOfRange(0, headerSize))
        assertEquals(0, rom[0x81EB9].toInt() and 0xFF)
        assertEquals(0x05, rom[headerSize + 0x81EB9].toInt() and 0xFF)
        assertEquals(1, rom[headerSize + 0x1234].toInt() and 0xFF)
        assertEquals(2, rom[headerSize + 0x1235].toInt() and 0xFF)
        assertEquals(0xAA, rom[headerSize].toInt() and 0xFF)
        assertEquals(0x1234, rom.readWord(headerSize + FANFARE_MUSIC_RESUME_DELAY_PCS.first()))

        val writes = PatchRepository.parseIps(result.ipsPatchBytes)
        assertTrue(writes.any { it.offset == 0L && it.bytes == listOf(0xAA) })
        assertTrue(writes.any { it.offset == 0x1234L && it.bytes == listOf(1, 2) })
        assertTrue(writes.any { it.offset == 0x81EB9L && it.bytes == listOf(0x05) })
        assertTrue(writes.none { it.offset >= RomConstants.SMC_HEADER_SIZE && it.offset - RomConstants.SMC_HEADER_SIZE == 0x81EB9L })

        val reconstructedBody = original.copyOfRange(headerSize, original.size)
        for (write in writes) {
            for (i in write.bytes.indices) {
                reconstructedBody[write.offset.toInt() + i] = write.bytes[i].toByte()
            }
        }
        assertContentEquals(rom.copyOfRange(headerSize, rom.size), reconstructedBody)
    }

    @Test
    fun `build accepts clean public aliases for prefixed patch ids`() {
        val service = SmeditBuildService()
        val clean = service.buildPatch(
            SmeditBuildRequest(
                patches = mapOf(
                    "higher_jump" to SmeditPatchRequest(),
                    "skip_intro" to SmeditPatchRequest(),
                    "fast_elevators" to SmeditPatchRequest(),
                    "energy_free_shinesparks" to SmeditPatchRequest(),
                    "infinite_power_bombs" to SmeditPatchRequest(),
                )
            )
        )
        val internal = service.buildPatch(
            SmeditBuildRequest(
                patches = mapOf(
                    "hex_higher_jump" to SmeditPatchRequest(),
                    "bundled_skip_intro_ceres" to SmeditPatchRequest(),
                    "bundled_elevators_speed" to SmeditPatchRequest(),
                    "bundled_energy_free_shinesparks" to SmeditPatchRequest(),
                    "hex_infinite_pbs" to SmeditPatchRequest(),
                )
            )
        )

        assertContentEquals(internal.ipsPatchBytes, clean.ipsPatchBytes)
        assertTrue(clean.report.applied.any { it.identifier == "higher_jump" })
        assertTrue(clean.report.applied.any { it.identifier == "skip_intro" })
        assertTrue(clean.report.applied.any { it.identifier == "fast_elevators" })
        assertTrue(clean.report.applied.any { it.identifier == "energy_free_shinesparks" })
        assertTrue(clean.report.applied.any { it.identifier == "infinite_power_bombs" })

        val cleanSkipCeres = service.buildPatch(
            SmeditBuildRequest(patches = mapOf("skip_intro_and_ceres" to SmeditPatchRequest()))
        )
        val internalSkipCeres = service.buildPatch(
            SmeditBuildRequest(patches = mapOf("bundled_skip_intro" to SmeditPatchRequest()))
        )
        assertContentEquals(internalSkipCeres.ipsPatchBytes, cleanSkipCeres.ipsPatchBytes)
        assertTrue(cleanSkipCeres.report.applied.any { it.identifier == "skip_intro_and_ceres" })
    }

    @Test
    fun `patch only build rejects overlapping bundled patches`() {
        val error = assertFailsWith<com.supermetroid.editor.rom.RomWriteConflictException> {
            SmeditBuildService().buildPatch(
                SmeditBuildRequest(
                    patches = mapOf(
                        "skip_intro" to SmeditPatchRequest(),
                        "skip_intro_and_ceres" to SmeditPatchRequest(),
                    )
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("bundled_skip_intro"))
        assertTrue(error.message.orEmpty().contains("bundled_skip_intro_ceres"))
    }

    @Test
    fun `rom build rejects a fixed patch authored for a different base rom`() {
        val patch = SmPatch(
            id = "base-specific",
            name = "Base-specific patch",
            writes = mutableListOf(PatchWrite(0x100, listOf(0xEA))),
            compatibleRomHashes = mutableListOf("not-the-input-hash"),
        )
        val service = SmeditBuildService(listOf(patch))

        val error = assertFailsWith<IllegalArgumentException> {
            service.build(
                ByteArray(0x300000),
                SmeditBuildRequest(patches = mapOf("base-specific" to SmeditPatchRequest())),
            )
        }

        assertTrue(error.message.orEmpty().contains("not compatible with input ROM SHA-256"))
    }

    @Test
    fun `rom build rejects a raw write with stale expected bytes`() {
        val error = assertFailsWith<com.supermetroid.editor.rom.RomWritePreconditionException> {
            SmeditBuildService().build(
                ByteArray(0x300000),
                SmeditBuildRequest(
                    rawWrites = listOf(
                        SmeditRawWriteRequest(
                            pcOffset = 0x1234,
                            bytes = listOf(0xEA),
                            expectedBytes = listOf(0x60),
                            label = "safe hook",
                        )
                    )
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("expected 0x60, found 0x00"))
    }

    @Test
    fun `explicit unsupported config patch fails`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf(
                "unknown_config_patch" to SmeditPatchRequest(configType = "unknown_config")
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().build(original, request)
        }
        assertTrue(error.message.orEmpty().contains("not supported by headless build v1"))
    }

    @Test
    fun `unknown generated-looking config patch fails explicitly`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().buildPatch(
                SmeditBuildRequest(
                    patches = mapOf(
                        "fake_generated" to SmeditPatchRequest(configType = "fake_generated")
                    )
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
    fun `patch only build applies boss stats physics and controller config`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf(
                BOSS_STATS_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf(
                        "kraid_hp" to 10_000,
                        "kraid_belly_spike" to 33,
                    )
                ),
                SAMUS_PHYSICS_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf("gravity" to 0x2C)
                ),
                CONTROLLER_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf("jump" to 0x4000)
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
        assertEquals(10_000, reconstructed.readWord(parser.snesToPc(0xA0E2BF) + 0x04))
        assertEquals(10_000, reconstructed.readWord(parser.snesToPc(0xA0E2FF) + 0x04))
        assertEquals(33, reconstructed.readWord(parser.snesToPc(0xA0E33F) + 0x06))
        assertEquals(33, reconstructed.readWord(parser.snesToPc(0xA0E37F) + 0x06))
        assertEquals(33, reconstructed.readWord(parser.snesToPc(0xA0E3BF) + 0x06))
        assertEquals(0x2C, reconstructed[0x081EA2].toInt() and 0xFF)
        assertEquals(0x4000, reconstructed.readWord(0x017575 + 2))
        assertTrue(result.report.applied.any { it.configType == BOSS_STATS_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == SAMUS_PHYSICS_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == CONTROLLER_CONFIG_TYPE })
    }

    @Test
    fun `patch only build applies boss behavior configs`() {
        val original = ByteArray(0x300000)
        val request = SmeditBuildRequest(
            patches = mapOf(
                PHANTOON_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf(
                        "vuln_0" to 45,
                        "rev_cap_0" to -20,
                    )
                ),
                KRAID_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf(
                        "diagonal_up_x_speed" to -3,
                        "earthquake_ceiling_mask" to 0x00FF,
                    )
                ),
                RIDLEY_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf("norfair_hover_wall_timer" to 5)
                ),
                TORIZO_CONFIG_TYPE to SmeditPatchRequest(
                    config = mapOf("fall_reset_y_speed" to 0x0111)
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
        assertEquals(45, reconstructed.readWord(parser.snesToPc(0xA7CD41)))
        assertEquals(0xFFEC, reconstructed.readWord(parser.snesToPc(0xA7CD89)))
        assertEquals(0xFFFD, reconstructed.readWord(parser.snesToPc(0xA7BE50)))
        assertEquals(0xFFFD, reconstructed.readWord(parser.snesToPc(0xA7BE60)))
        assertEquals(0xFFFD, reconstructed.readWord(parser.snesToPc(0xA7BE70)))
        assertEquals(0xFFFD, reconstructed.readWord(parser.snesToPc(0xA7BE80)))
        assertEquals(0x00FF, reconstructed.readWord(parser.snesToPc(0xA7AC51)))
        assertEquals(5, reconstructed.readWord(parser.snesToPc(0xA6B604)))
        assertEquals(5, reconstructed.readWord(parser.snesToPc(0xA6B632)))
        assertEquals(0x0111, reconstructed.readWord(parser.snesToPc(0xAAC7B8)))
        assertEquals(0x0111, reconstructed.readWord(parser.snesToPc(0xAAC81F)))
        assertEquals(0x0111, reconstructed.readWord(parser.snesToPc(0xAAC86D)))
        assertEquals(0x0111, reconstructed.readWord(parser.snesToPc(0xAAD64F)))
        assertTrue(result.report.applied.any { it.configType == PHANTOON_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == KRAID_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == RIDLEY_CONFIG_TYPE })
        assertTrue(result.report.applied.any { it.configType == TORIZO_CONFIG_TYPE })
    }

    @Test
    fun `patch only build applies combined per frame hook configs`() {
        val result = SmeditBuildService().buildPatch(
            SmeditBuildRequest(
                patches = mapOf(
                    BOSS_DEFEATED_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf(
                            "kraid" to 1,
                            "phantoon" to 1,
                            "ridley" to 1,
                            "draygon" to 1,
                        )
                    ),
                    HYPER_BEAM_CONFIG_TYPE to SmeditPatchRequest(),
                    "infinite_blue_suit" to SmeditPatchRequest(),
                )
            )
        )

        val writes = PatchRepository.parseIps(result.ipsPatchBytes)
        val hookWrite = writes.first { it.offset == PER_FRAME_HOOK_PATCH_PC.toLong() }
        val payloadWrite = writes.first { it.offset == PER_FRAME_HOOK_PAYLOAD_PC.toLong() }

        assertEquals(PER_FRAME_HOOK_JSL, hookWrite.bytes)
        assertEquals(listOf(0x22, 0xEF, 0x89, 0x82, 0x08, 0xC2, 0x20), payloadWrite.bytes.take(7))
        assertTrue(payloadWrite.bytes.containsSubsequence(listOf(0xAD, 0x9B, 0x07, 0xC9, 0x58, 0xDD)))
        assertTrue(payloadWrite.bytes.containsSubsequence(listOf(0xAF, 0x29, 0xD8, 0x7E, 0x09, 0x01, 0x00)))
        assertTrue(payloadWrite.bytes.containsSubsequence(listOf(0xAF, 0x21, 0xD8, 0x7E, 0x09, 0x07, 0x00)))
        assertTrue(payloadWrite.bytes.containsSubsequence(listOf(0xA9, 0x00, 0x80, 0x8F, 0x76, 0x0A, 0x7E)))
        assertTrue(payloadWrite.bytes.containsSubsequence(listOf(0xA9, 0x00, 0x04, 0x8F, 0x3E, 0x0B, 0x7E)))
        assertTrue(result.report.applied.any { it.identifier == "combined_per_frame_hook" })
    }

    @Test
    fun `rom build applies room name pause map patch`() {
        val original = ByteArray(0x300000) { 0xFF.toByte() }
        val parser = RomParser(original)
        installOriginalPauseMapHook(original, parser.snesToPc(0x828D25))
        installOriginalPauseMapHook(original, parser.snesToPc(0x8291DD))
        val project = SmEditProject(
            romPath = "base.smc",
            roomNameOverrides = mutableMapOf("91F8" to "LANDING TEST"),
        )

        val result = SmeditBuildService().build(
            inputRom = original,
            request = SmeditBuildRequest(
                patches = mapOf(
                    ROOM_NAME_PAUSE_MAP_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf(RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY to 0)
                    )
                )
            ),
            project = project,
        )

        assertTrue(result.report.applied.any { it.configType == ROOM_NAME_PAUSE_MAP_CONFIG_TYPE })
        assertTrue(result.report.warnings.none { it.contains("room name overrides") })
        val firstHook = result.romBytes.readBytes(parser.snesToPc(0x828D25), 4)
        val secondHook = result.romBytes.readBytes(parser.snesToPc(0x8291DD), 4)
        assertEquals(0x22, firstHook[0])
        assertContentEquals(firstHook, secondHook)
        val wrapperSnes = firstHook[1] or (firstHook[2] shl 8) or (firstHook[3] shl 16)
        assertEquals(0x22, result.romBytes[parser.snesToPc(wrapperSnes)].toInt() and 0xFF)
        assertTrue(result.report.applied.first { it.configType == ROOM_NAME_PAUSE_MAP_CONFIG_TYPE }.bytes > 100)
    }

    @Test
    fun `spider ball and room names produce a conflict-free owned write plan`() {
        val original = TestRomHelper.loadRomBytes() ?: return
        val parser = RomParser(original)
        val project = SmEditProject(
            romPath = "base.smc",
            roomNameOverrides = mutableMapOf("91F8" to "LANDING TEST"),
        )

        val result = SmeditBuildService().build(
            inputRom = original,
            request = SmeditBuildRequest(
                patches = mapOf(
                    "spider_ball" to SmeditPatchRequest(),
                    ROOM_NAME_PAUSE_MAP_CONFIG_TYPE to SmeditPatchRequest(),
                )
            ),
            project = project,
        )

        assertTrue(result.report.applied.any { it.identifier == "spider_ball" })
        assertTrue(result.report.applied.any { it.configType == ROOM_NAME_PAUSE_MAP_CONFIG_TYPE })
        val owners = result.report.writePlan?.owners.orEmpty().map { it.owner }.toSet()
        assertTrue("patch:bundled_spider_ball" in owners)
        assertTrue("patch:config_room_name_pause_map" in owners)
        assertTrue((result.report.writePlan?.resourceClaims ?: 0) >= 9)

        val hook = result.romBytes.readBytes(parser.snesToPc(0x828D25), 4)
        assertEquals(0x22, hook[0])
        assertTrue(hook[3] in 0x82..0xBE)
    }

    @Test
    fun `rom build applies project room edits through shared exporter`() {
        val original = TestRomHelper.loadRomBytes() ?: return
        val parser = RomParser(original)
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId) ?: return
        val newMapX = (room.mapX + 1).coerceAtMost(63)
        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).roomHeaderChange = RoomHeaderChange(mapX = newMapX)
        }

        val result = SmeditBuildService().build(original, SmeditBuildRequest(), project)
        val headerPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)

        assertEquals(newMapX, result.romBytes[headerPc + 2].toInt() and 0xFF)
        assertTrue(result.report.applied.any { it.identifier == "project_room_edits" })
        assertTrue(result.report.warnings.none { it.contains("unsupported room edits") })
    }

    @Test
    fun `patch only room name pause map warns without rom`() {
        val result = SmeditBuildService().buildPatch(
            SmeditBuildRequest(
                patches = mapOf(
                    ROOM_NAME_PAUSE_MAP_CONFIG_TYPE to SmeditPatchRequest()
                )
            )
        )

        assertEquals(0, PatchRepository.parseIps(result.ipsPatchBytes).size)
        assertTrue(result.report.warnings.any { it.contains("requires --rom") })
    }

    @Test
    fun `config schema exposes supported fields`() {
        val enemyStats = SmeditPatchCatalog.configSchema(ENEMY_STATS_CONFIG_TYPE)!!
        val controller = SmeditPatchCatalog.configSchema("config_controller")!!
        val phantoon = SmeditPatchCatalog.configSchema(PHANTOON_CONFIG_TYPE)!!
        val bossDefeated = SmeditPatchCatalog.configSchema(BOSS_DEFEATED_CONFIG_TYPE)!!
        val roomName = SmeditPatchCatalog.configSchema(ROOM_NAME_PAUSE_MAP_CONFIG_TYPE)!!
        val hyperBeam = SmeditPatchCatalog.configSchema(HYPER_BEAM_CONFIG_TYPE)!!

        assertTrue(enemyStats.supportsPatchOnly)
        assertTrue(enemyStats.fields.any { it.key == "zoomer_hp" && it.defaultValue == 15 })
        assertTrue(enemyStats.fields.any { it.key == "zoomer_touchAi" })
        assertEquals(CONTROLLER_CONFIG_TYPE, controller.configType)
        assertTrue(controller.fields.first { it.key == "jump" }.choices.any { it.label == "Y" && it.value == 0x4000 })
        assertTrue(phantoon.supportsPatchOnly)
        assertTrue(
            phantoon.fields.any {
                it.key == "rev_cap_0" && it.signed && it.logicalMin == -255 && it.logicalMax == 255
            }
        )
        assertTrue(bossDefeated.fields.any { it.key == "kraid" && it.max == 1 })
        assertTrue(roomName.requiresRom)
        assertTrue(roomName.fields.first { it.key == RoomNamePauseMapPatch.CONFIG_ALIGNMENT_KEY }.choices.any { it.label == "Left" })
        assertTrue(hyperBeam.supportsPatchOnly)
    }

    @Test
    fun `config validation fails by default and warns in lenient mode`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().buildPatch(
                SmeditBuildRequest(
                    patches = mapOf(
                        BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                            config = mapOf("power_typo" to 123)
                        )
                    )
                )
            )
        }
        assertTrue(error.message.orEmpty().contains("power_typo"))

        val warningResult = SmeditBuildService().buildPatch(
            SmeditBuildRequest(
                strictConfigValidation = false,
                patches = mapOf(
                    BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("power_typo" to 123)
                    )
                )
            )
        )
        assertTrue(warningResult.report.warnings.any { it.contains("unknown config key") && it.contains("power_typo") })
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

    @Test
    fun `rom build applies colorize to selected tilesets and sprite palettes`() {
        val original = ByteArray(0x300000) { 0xFF.toByte() }
        val parser = RomParser(original)
        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val paletteSnes = 0xC08000
        val palettePc = parser.snesToPc(paletteSnes)
        writeU24(original, tablePc + 6, paletteSnes)

        val tilesetColors = IntArray(128) { index -> if (index == 0) 0 else 0x03E0 }
        val tilesetPalette = SpritePalettes.colorsToBytes(tilesetColors)
        LZ5Compressor.compress(tilesetPalette).copyInto(original, palettePc)

        val spriteColors = IntArray(SpritePalettes.BEAM_STANDARD.colorCount) { index -> if (index == 0) 0 else 0x03E0 }
        val spritePalette = SpritePalettes.colorsToBytes(spriteColors)
        spritePalette.copyInto(original, SpritePalettes.BEAM_STANDARD.offset)

        val result = SmeditBuildService().build(
            inputRom = original,
            request = SmeditBuildRequest(
                colorize = SmeditColorizeRequest(
                    effect = "psychedelic",
                    tilesets = listOf(0),
                    spriteRegions = listOf(SpritePalettes.BEAM_STANDARD.id),
                )
            )
        )

        val expectedTilesetColors = tilesetColors.copyOf()
        PaletteEffects.psychedelic(expectedTilesetColors)
        val expectedTilesetPalette = SpritePalettes.colorsToBytes(expectedTilesetColors)
        val patchedPaletteSnes = result.romBytes.readU24(tablePc + 6)
        val patchedTilesetPalette = RomParser(result.romBytes).decompressLZ2(patchedPaletteSnes).copyOf(256)
        assertContentEquals(expectedTilesetPalette, patchedTilesetPalette)

        val expectedSpriteColors = spriteColors.copyOf()
        PaletteEffects.psychedelic(expectedSpriteColors)
        val expectedSpritePalette = SpritePalettes.colorsToBytes(expectedSpriteColors)
        assertContentEquals(
            expectedSpritePalette.toIntList(),
            result.romBytes.readBytes(SpritePalettes.BEAM_STANDARD.offset, expectedSpritePalette.size),
        )
        assertTrue(result.report.applied.any { it.identifier == "colorize" })
    }

    @Test
    fun `colorize rejects unknown effects and patch-only builds`() {
        val unknownEffect = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().build(
                ByteArray(0x300000),
                SmeditBuildRequest(colorize = SmeditColorizeRequest(effect = "not-a-real-effect")),
            )
        }
        assertTrue(unknownEffect.message.orEmpty().contains("Unknown colorize effect"))

        val patchOnly = assertFailsWith<IllegalArgumentException> {
            SmeditBuildService().buildPatch(
                SmeditBuildRequest(colorize = SmeditColorizeRequest(effect = "psychedelic"))
            )
        }
        assertTrue(patchOnly.message.orEmpty().contains("requires --rom"))
    }

    private fun ByteArray.readWord(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.readU24(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16)

    private fun ByteArray.readBytes(offset: Int, count: Int): List<Int> =
        (0 until count).map { this[offset + it].toInt() and 0xFF }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun writeWord(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun installOriginalPauseMapHook(data: ByteArray, offset: Int) {
        val bytes = listOf(0x22, 0xC3, 0x93, 0x82)
        for ((index, byte) in bytes.withIndex()) {
            data[offset + index] = byte.toByte()
        }
    }

    private fun List<Int>.containsSubsequence(needle: List<Int>): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        return windowed(needle.size).any { it == needle }
    }

    private fun ByteArray.toIntList(): List<Int> =
        map { it.toInt() and 0xFF }
}
