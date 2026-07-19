package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomExportData
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.procgen.BiomeRules
import com.supermetroid.editor.procgen.BiomeStyle
import com.supermetroid.editor.procgen.BiomeTheme
import com.supermetroid.editor.procgen.TilesetProfile
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RoomExportTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `export Landing Site produces valid JSON`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        assertTrue(json.contains("91F8"), "JSON should contain room ID")
        assertTrue(json.contains("Landing Site"), "JSON should contain room name")
        assertTrue(json.contains("levelDataBase64"), "JSON should contain level data")
        assertTrue(json.contains("enemies"), "JSON should contain enemies")
        assertTrue(json.contains("plms"), "JSON should contain PLMs")
        assertTrue(json.contains("doors"), "JSON should contain doors")
    }

    @Test
    fun `exported JSON deserializes back correctly`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        val parsed = Json.decodeFromString(RoomExportData.serializer(), json)

        assertEquals("91F8", parsed.roomId)
        assertEquals(9, parsed.width)
        assertEquals(5, parsed.height)
        assertTrue(parsed.enemies.isNotEmpty(), "Should have enemies")
        assertTrue(parsed.plms.isNotEmpty(), "Should have PLMs")
        assertTrue(parsed.doors.isNotEmpty(), "Should have doors")
        assertEquals(45, parsed.scrollData.size, "Should have 9×5=45 scroll entries")
        assertTrue(parsed.levelDataBase64.isNotEmpty(), "Level data should be non-empty")
    }

    @Test
    fun `exported level data roundtrips through base64`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val json = es.exportRoomToJson(0x91F8, rp)
        val parsed = Json.decodeFromString(RoomExportData.serializer(), json)

        val decodedData = java.util.Base64.getDecoder().decode(parsed.levelDataBase64)
        assertTrue(decodedData.size > 1000, "Decompressed level data should be substantial")
        // First 2 bytes are the L1 size header
        val l1Size = (decodedData[0].toInt() and 0xFF) or ((decodedData[1].toInt() and 0xFF) shl 8)
        assertEquals(9 * 16 * 5 * 16 * 2, l1Size, "L1 size should match 9×5 screens × 256 tiles × 2 bytes")
    }

    @Test
    fun `reset current room restores ROM data and removes room edits`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)
        val originalData = es.workingLevelData!!.copyOf()
        val originalScrolls = es.workingScrolls.copyOf()
        val oldWord = es.readBlockWord(4, 4)
        val oldBts = es.readBts(4, 4)
        val newScroll = if (originalScrolls[0] == 0x02) 0x01 else 0x02

        es.applyBulkEdits("test edit", listOf(TileEdit(4, 4, oldWord, oldWord xor 0x001, oldBts, oldBts xor 0x01)))
        es.setScroll(0, 0, newScroll, room.width)

        assertTrue(es.project.rooms.containsKey("91F8"), "test setup should create room edits")
        assertTrue(es.resetCurrentRoomToOriginal(rp))

        assertFalse(es.project.rooms.containsKey("91F8"), "reset should remove the room edit record")
        assertTrue(originalData.contentEquals(es.workingLevelData!!), "level data should match ROM data after reset")
        assertTrue(originalScrolls.contentEquals(es.workingScrolls), "scroll data should match ROM data after reset")
        assertTrue(es.undoStack.isEmpty(), "reset should clear undo history for the room")
        assertTrue(es.redoStack.isEmpty(), "reset should clear redo history for the room")
    }

    @Test
    fun `apply all generates normal rooms skips excluded rooms and resets generated edits`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val result = es.generateBiomeForAllRooms(
            BiomeRules.roll(BiomeStyle.PIPE_MAZE, 12345L),
            BiomeTheme.KEEP,
            12345L,
            rp,
        )

        assertTrue(result.generatedRooms > 20, "bulk generation should touch regular rooms")
        assertTrue(result.skippedRooms > 0, "bulk generation should skip excluded rooms")
        assertTrue(es.project.rooms.containsKey("91F8"), "Landing Site should be generated")
        assertTrue(es.project.rooms.containsKey("92FD"), "Parlor and Alcatraz should be generated")
        assertFalse(es.project.rooms.containsKey("93D5"), "save rooms should be skipped")
        assertFalse(es.project.rooms.containsKey("A59F"), "boss rooms should be skipped")

        val reset = es.resetGeneratedBiomeRooms(rp)

        assertTrue(reset.generatedRooms > 20, "reset should remove generated room records")
        assertTrue(es.project.rooms.isEmpty(), "generated edits should reset back to a clean project")
    }

    @Test
    fun `apply all skips rooms with manual edits`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val roomId = 0x92FD
        val room = rp.readRoomHeader(roomId) ?: return
        val es = EditorState()
        es.loadRoom(roomId, rp, room)

        val oldWord = es.readBlockWord(8, 8)
        val oldBts = es.readBts(8, 8)
        es.applyBulkEdits(
            "Manual test edit",
            listOf(TileEdit(8, 8, oldWord, oldWord xor 0x0001, oldBts, oldBts)),
        )

        val result = es.generateBiomeForAllRooms(
            BiomeRules.roll(BiomeStyle.PIPE_MAZE, 98765L),
            BiomeTheme.KEEP,
            98765L,
            rp,
            omitSpecialRooms = false,
        )

        assertTrue(result.manualSkippedRooms >= 1, "bulk generation should report manual-edited skips")
        val roomEdits = es.project.rooms[es.project.roomKey(roomId)]
        assertTrue(roomEdits != null, "manual-edited room should remain in project")
        val ops = roomEdits!!.operations
        assertEquals(1, ops.size, "manual-edited room should not receive a generated biome operation")
        assertEquals("Manual test edit", ops.single().description)
        assertFalse(
            ops.any { it.description.startsWith("Generated biome:") || it.description.startsWith("Generate biome (") },
            "manual-edited room should be skipped by Generate All"
        )
    }

    @Test
    fun `generate room keeps bottom elevator standing space open`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val roomId = 0x9938 // Elevator To Green Brinstar
        val room = rp.readRoomHeader(roomId) ?: return
        val es = EditorState()
        es.loadRoom(roomId, rp, room)

        val elevatorDoor = rp.findDoorsLeadingTo(roomId).single { it.isElevator }
        val clearanceBefore = elevatorClearanceSnapshot(es, elevatorDoor, room.width * 16, room.height * 16)
        val topDoorLeft = es.readBlockWord(7, 10)
        val topDoorRight = es.readBlockWord(8, 10)
        val bottomDoorLeft = es.readBlockWord(7, 15)
        val bottomDoorRight = es.readBlockWord(8, 15)
        val changed = es.generateBiome(
            BiomeRules.roll(BiomeStyle.PIPE_MAZE, 424242L),
            TilesetProfile.synthetic(),
            424242L,
            romParser = rp,
        )

        assertTrue(changed > 0, "room generation should change some non-protected tiles")
        assertEquals(topDoorLeft, es.readBlockWord(7, 10), "top elevator left trigger tile must be preserved")
        assertEquals(topDoorRight, es.readBlockWord(8, 10), "top elevator right trigger tile must be preserved")
        assertEquals(bottomDoorLeft, es.readBlockWord(7, 15), "bottom elevator left door tile must be preserved")
        assertEquals(bottomDoorRight, es.readBlockWord(8, 15), "bottom elevator right door tile must be preserved")
        assertGreenBrinstarElevatorShaftClear(es)
        assertElevatorClearance(
            es,
            clearanceBefore,
            "bottom elevator",
        )
    }

    @Test
    fun `generate room keeps every elevator endpoint clear`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val roomInfos = RoomRepository().getAllRooms()
        var checked = 0

        for (info in roomInfos) {
            val roomId = info.getRoomIdAsInt()
            val incomingElevators = rp.findDoorsLeadingTo(roomId).filter { it.isElevator }
            if (incomingElevators.isEmpty()) continue
            val room = rp.readRoomHeader(roomId) ?: continue
            val es = EditorState()
            es.loadRoom(roomId, rp, room)
            val clearancesBefore = incomingElevators.associateWith { door ->
                elevatorClearanceSnapshot(es, door, room.width * 16, room.height * 16)
            }
            es.generateBiome(
                BiomeRules.roll(BiomeStyle.PIPE_MAZE, 424242L + roomId),
                TilesetProfile.synthetic(),
                424242L + roomId,
                romParser = rp,
            )

            for (door in incomingElevators) {
                checked++
                assertElevatorClearance(
                    es,
                    clearancesBefore.getValue(door),
                    "room 0x${roomId.toString(16).uppercase()} ${info.name}",
                )
            }
        }

        assertTrue(checked >= 14, "test ROM should expose every vanilla elevator endpoint")
    }

    @Test
    fun `export relocates oversized randomized tileset palette and updates tileset pointer`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidPalette.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())

        var targetTilesetId = -1
        var originalPaletteSnes = 0
        var originalCompressedSize = 0
        var targetColors = IntArray(0)
        var targetRawPalette = ByteArray(0)
        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            val paletteSnes = readTilesetPalettePointer(parser, tilesetId)
            val (_, vanillaCompressedSize) = parser.decompressLZ2WithSize(paletteSnes)
            val colors = highEntropyPaletteColors(tilesetId)
            val rawPalette = paletteBytes(colors)
            val compressedSize = LZ5Compressor.compress(rawPalette).size
            if (compressedSize > vanillaCompressedSize) {
                targetTilesetId = tilesetId
                originalPaletteSnes = paletteSnes
                originalCompressedSize = vanillaCompressedSize
                targetColors = colors
                targetRawPalette = rawPalette
                break
            }
        }
        assumeTrue(targetTilesetId >= 0, "No tileset palette needed relocation in the test ROM")

        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)
        state.saveTilesetPaletteFromColors(targetTilesetId, targetColors)

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)
        val exportedPaletteSnes = readTilesetPalettePointer(exportedParser, targetTilesetId)
        val exportedRawPalette = exportedParser.decompressLZ2(exportedPaletteSnes).copyOf(256)

        assertTrue(
            LZ5Compressor.compress(targetRawPalette).size > originalCompressedSize,
            "test setup should exercise an oversized palette",
        )
        assertTrue(
            exportedPaletteSnes != originalPaletteSnes,
            "oversized palette should be written to free space and the tileset pointer should change",
        )
        assertTrue(
            targetRawPalette.contentEquals(exportedRawPalette),
            "exported ROM should decompress to the saved randomized palette",
        )
    }

    @Test
    fun `boss stats export enables patch and writes kraid hp to both body stat blocks`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidBossStats.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val patch = state.findOrCreateConfigPatch("boss_stats")
        assertFalse(patch.enabled, "boss stats starts disabled in a new project")
        state.setPatchConfigData(patch.id, "kraid_hp", 10_000)
        assertTrue(patch.enabled, "editing boss stats should enable the patch for export")

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun hp(speciesId: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId) + 4)

        assertEquals(10_000, hp(0xE2BF), "Kraid main stat block HP should be patched")
        assertEquals(10_000, hp(0xE2FF), "Kraid upper-body stat block HP should be patched")
    }

    @Test
    fun `saved disabled boss stats data is auto-enabled on export`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidSavedBossStats.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val patch = state.findOrCreateConfigPatch("boss_stats")
        patch.configData = mutableMapOf("kraid_hp" to 10_000)
        patch.enabled = false

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun hp(speciesId: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId) + 4)

        assertTrue(patch.enabled, "saved boss stats config data should be enabled for export")
        assertEquals(10_000, hp(0xE2BF), "Kraid main stat block HP should be patched from saved data")
        assertEquals(10_000, hp(0xE2FF), "Kraid upper-body stat block HP should be patched from saved data")
    }

    @Test
    fun `kraid behavior export writes direct and mirrored fields`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidKraidBehavior.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val patch = state.findOrCreateConfigPatch(KRAID_CONFIG_TYPE)
        assertFalse(patch.enabled, "kraid behavior starts disabled in a new project")
        state.setPatchConfigData(patch.id, "intro_delay", 42)
        state.setPatchConfigData(patch.id, "diagonal_up_y_speed", 2)
        assertTrue(patch.enabled, "editing kraid behavior should enable the patch for export")

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun word(snesAddress: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(snesAddress))

        assertEquals(42, word(0xA7AA6A), "Kraid intro delay immediate operand should be patched")
        for (addr in listOf(0xA7BE54, 0xA7BE64, 0xA7BE74, 0xA7BE84)) {
            assertEquals(2, word(addr), "Mirrored diagonal-up fingernail Y speed should be patched at ${addr.toString(16)}")
        }
    }

    @Test
    fun `ridley and draygon behavior export writes direct and mirrored fields`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidBossBehavior.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val ridleyPatch = state.findOrCreateConfigPatch(RIDLEY_CONFIG_TYPE)
        assertFalse(ridleyPatch.enabled, "ridley behavior starts disabled in a new project")
        state.setPatchConfigData(ridleyPatch.id, "norfair_tail_damage", 0x0042)
        state.setPatchConfigData(ridleyPatch.id, "norfair_swoop_horizontal_speed", 0x0600)
        assertTrue(ridleyPatch.enabled, "editing ridley behavior should enable the patch for export")

        val draygonPatch = state.findOrCreateConfigPatch(DRAYGON_CONFIG_TYPE)
        assertFalse(draygonPatch.enabled, "draygon behavior starts disabled in a new project")
        state.setPatchConfigData(draygonPatch.id, "goop_count", 0x0005)
        state.setPatchConfigData(draygonPatch.id, "arm_apex_index", 0x0070)
        assertTrue(draygonPatch.enabled, "editing draygon behavior should enable the patch for export")

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun word(snesAddress: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(snesAddress))

        assertEquals(0x0042, word(0xA6A1C1), "Ridley Norfair tail damage immediate operand should be patched")
        assertEquals(0x0600, word(0xA6B4EE), "Ridley Norfair swoop horizontal speed immediate operand should be patched")
        for (addr in listOf(0xA58B6F, 0xA58CF1)) {
            assertEquals(0x0005, word(addr), "Mirrored Draygon goop count should be patched at ${addr.toString(16)}")
        }
        for (addr in listOf(0xA588BE, 0xA5895B, 0xA58A0D, 0xA58A9D)) {
            assertEquals(0x0070, word(addr), "Mirrored Draygon arm-apex index should be patched at ${addr.toString(16)}")
        }
    }

    @Test
    fun `spore crocomire and botwoon behavior export writes direct and mirrored fields`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidMoreBossBehavior.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val sporePatch = state.findOrCreateConfigPatch(SPORE_SPAWN_CONFIG_TYPE)
        assertFalse(sporePatch.enabled, "spore spawn behavior starts disabled in a new project")
        state.setPatchConfigData(sporePatch.id, "fight_max_x_radius", 0x0055)
        state.setPatchConfigData(sporePatch.id, "death_arrival_tolerance", 0x000C)
        assertTrue(sporePatch.enabled, "editing spore spawn behavior should enable the patch for export")

        val crocomirePatch = state.findOrCreateConfigPatch(CROCOMIRE_CONFIG_TYPE)
        assertFalse(crocomirePatch.enabled, "crocomire behavior starts disabled in a new project")
        state.setPatchConfigData(crocomirePatch.id, "charged_steps_back", 0x0004)
        state.setPatchConfigData(crocomirePatch.id, "offscreen_bg_scroll", 0x0120)
        assertTrue(crocomirePatch.enabled, "editing crocomire behavior should enable the patch for export")

        val botwoonPatch = state.findOrCreateConfigPatch(BOTWOON_CONFIG_TYPE)
        assertFalse(botwoonPatch.enabled, "botwoon behavior starts disabled in a new project")
        state.setPatchConfigData(botwoonPatch.id, "spit_timer", 0x0040)
        state.setPatchConfigData(botwoonPatch.id, "fall_ground_y", 0x00D0)
        assertTrue(botwoonPatch.enabled, "editing botwoon behavior should enable the patch for export")

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun word(snesAddress: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(snesAddress))

        assertEquals(0x0055, word(0xA5E6D7), "Spore Spawn fight max X radius should be patched")
        for (addr in listOf(0xA5EC27, 0xA5EC37)) {
            assertEquals(0x000C, word(addr), "Mirrored Spore Spawn death tolerance should be patched at ${addr.toString(16)}")
        }
        assertEquals(0x0004, word(0xA48698), "Crocomire charged steps back should be patched")
        for (addr in listOf(0xA48BE1, 0xA48BE9)) {
            assertEquals(0x0120, word(addr), "Mirrored Crocomire offscreen BG scroll should be patched at ${addr.toString(16)}")
        }
        assertEquals(0x0040, word(0xB39923), "Botwoon spit timer should be patched")
        for (addr in listOf(0xB39A87, 0xB39A8C)) {
            assertEquals(0x00D0, word(addr), "Mirrored Botwoon fall ground Y should be patched at ${addr.toString(16)}")
        }
    }

    @Test
    fun `torizo and mother brain behavior export writes direct and mirrored fields`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidFinalBossBehavior.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val torizoPatch = state.findOrCreateConfigPatch(TORIZO_CONFIG_TYPE)
        assertFalse(torizoPatch.enabled, "torizo behavior starts disabled in a new project")
        state.setPatchConfigData(torizoPatch.id, "low_health_drool_threshold", 0x0120)
        state.setPatchConfigData(torizoPatch.id, "fall_reset_y_speed", 0x0110)
        state.setPatchConfigData(torizoPatch.id, "golden_forward_jump_distance", 0x0080)
        assertTrue(torizoPatch.enabled, "editing torizo behavior should enable the patch for export")

        val motherBrainPatch = state.findOrCreateConfigPatch(MOTHER_BRAIN_CONFIG_TYPE)
        assertFalse(motherBrainPatch.enabled, "mother brain behavior starts disabled in a new project")
        state.setPatchConfigData(motherBrainPatch.id, "attack_cooldown", 0x0050)
        state.setPatchConfigData(motherBrainPatch.id, "max_active_bombs", 0x0002)
        state.setPatchConfigData(motherBrainPatch.id, "phase1_samus_x_gate", 0xFFFF)
        state.setPatchConfigData(motherBrainPatch.id, "rainbow_initial_width", 0x0300)
        state.setPatchConfigData(motherBrainPatch.id, "escape_door_explosion_interval", 0x0006)
        assertTrue(motherBrainPatch.enabled, "editing mother brain behavior should enable the patch for export")

        val exportedPath = state.exportToRom(parser) ?: error("Expected export path")
        val exportedRomBytes = File(exportedPath).readBytes()
        val exportedParser = RomParser(exportedRomBytes)

        fun word(snesAddress: Int): Int =
            readU16(exportedRomBytes, exportedParser.snesToPc(snesAddress))

        for (addr in listOf(0xAAC35F, 0xAAC636, 0xAAC70B)) {
            assertEquals(0x0120, word(addr), "Mirrored Torizo low-health threshold should be patched at ${addr.toString(16)}")
        }
        for (addr in listOf(0xAAC7B8, 0xAAC81F, 0xAAC86D, 0xAAD64F)) {
            assertEquals(0x0110, word(addr), "Mirrored Torizo fall reset Y speed should be patched at ${addr.toString(16)}")
        }
        assertEquals(0x0080, word(0xAAD4BB), "Golden Torizo forward jump distance should be patched")
        assertEquals(0x0FFF, word(0xA987F5), "Mother Brain Samus X gate should be clamped during export")
        assertEquals(0x0050, word(0xA9B65B), "Mother Brain attack cooldown should be patched")
        for (addr in listOf(0xA9B6BE, 0xA9B71D)) {
            assertEquals(0x0002, word(addr), "Mirrored Mother Brain active bomb gate should be patched at ${addr.toString(16)}")
        }
        for (addr in listOf(0xA9B995, 0xA9BA77)) {
            assertEquals(0x0300, word(addr), "Mirrored Mother Brain rainbow width should be patched at ${addr.toString(16)}")
        }
        assertEquals(0x0006, word(0xA9B350), "Mother Brain escape door explosion interval should be patched")
    }

    @Test
    fun `boss tab config edits enable all boss config patches`() {
        val state = EditorState()
        val keysByConfig = mapOf(
            "boss_stats" to "kraid_hp",
            "boss_defeated" to "kraid",
            "phantoon" to "vuln_0",
            KRAID_CONFIG_TYPE to "intro_delay",
            RIDLEY_CONFIG_TYPE to "norfair_tail_damage",
            DRAYGON_CONFIG_TYPE to "goop_count",
            SPORE_SPAWN_CONFIG_TYPE to "fight_max_x_radius",
            CROCOMIRE_CONFIG_TYPE to "charged_steps_back",
            BOTWOON_CONFIG_TYPE to "spit_timer",
            TORIZO_CONFIG_TYPE to "low_health_drool_threshold",
            MOTHER_BRAIN_CONFIG_TYPE to "attack_cooldown",
        )

        for ((configType, key) in keysByConfig) {
            val patch = state.findOrCreateConfigPatch(configType)
            assertFalse(patch.enabled, "$configType starts disabled in a new project")
            state.setPatchConfigData(patch.id, key, 1)
            assertTrue(patch.enabled, "editing $configType should enable the patch")
        }
    }

    private data class ElevatorCellBefore(
        val word: Int,
        val bts: Int,
    )

    private fun assertGreenBrinstarElevatorShaftClear(es: EditorState) {
        for (y in 5..14) {
            for (x in 6..9) {
                val originalType9Trigger = x in 7..8 && y == 10
                if (originalType9Trigger) continue
                assertEquals(0x00FF, es.readBlockWord(x, y), "Green Brinstar elevator shaft cell ($x,$y) must be blank air")
                assertEquals(0, es.readBts(x, y), "Green Brinstar elevator shaft cell ($x,$y) must clear BTS")
            }
        }
    }

    private fun assertElevatorClearance(
        es: EditorState,
        cellsBefore: Map<Pair<Int, Int>, ElevatorCellBefore>,
        label: String,
    ) {
        for ((cell, before) in cellsBefore) {
            val (x, y) = cell
            val word = es.readBlockWord(x, y)
            val bts = es.readBts(x, y)
            val originalType = (before.word shr 12) and 0xF
            if (originalType == 0x9) {
                assertEquals(before.word, word, "$label elevator tile ($x,$y) must be preserved")
                assertEquals(before.bts, bts, "$label elevator tile ($x,$y) BTS must be preserved")
            } else {
                assertEquals(0x00FF, word, "$label elevator clearance cell ($x,$y) must be blank air")
                assertEquals(0, bts, "$label elevator clearance cell ($x,$y) must clear BTS")
            }
        }
    }

    private fun elevatorClearanceSnapshot(
        es: EditorState,
        door: RomParser.DoorEntry,
        width: Int,
        height: Int,
    ): Map<Pair<Int, Int>, ElevatorCellBefore> {
        return elevatorClearanceCells(door, width, height).associateWith { (x, y) ->
            ElevatorCellBefore(
                word = es.readBlockWord(x, y),
                bts = es.readBts(x, y),
            )
        }
    }

    private fun elevatorClearanceCells(
        door: RomParser.DoorEntry,
        width: Int,
        height: Int,
    ): List<Pair<Int, Int>> {
        val screenX0 = door.screenX * 16
        val screenY0 = door.screenY * 16
        val centerLeftX = screenX0 + 7
        val centerRightX = screenX0 + 8
        val centerTopY = screenY0 + 7
        val centerBottomY = screenY0 + 8
        val verticalClearLeftX = centerLeftX - 1
        val verticalClearRightX = centerRightX + 1
        val horizontalClearTopY = centerTopY - 1
        val horizontalClearBottomY = centerBottomY + 1
        val elevatorClearanceDepth = 5

        fun cells(xRange: IntRange, yRange: IntRange): List<Pair<Int, Int>> =
            yRange.flatMap { y -> xRange.map { x -> x to y } }
                .filter { (x, y) -> x in 0 until width && y in 0 until height }

        return when (door.direction and 0x03) {
            2 -> cells(
                verticalClearLeftX..verticalClearRightX,
                (screenY0 + 1)..(screenY0 + elevatorClearanceDepth),
            )
            3 -> {
                val doorY = minOf(screenY0 + 15, height - 1)
                cells(verticalClearLeftX..verticalClearRightX, (doorY - elevatorClearanceDepth) until doorY)
            }
            0 -> cells(
                (screenX0 + 1)..(screenX0 + elevatorClearanceDepth),
                horizontalClearTopY..horizontalClearBottomY,
            )
            1 -> {
                val doorX = minOf(screenX0 + 15, width - 1)
                cells((doorX - elevatorClearanceDepth) until doorX, horizontalClearTopY..horizontalClearBottomY)
            }
            else -> emptyList()
        }
    }

    private fun readTilesetPalettePointer(parser: RomParser, tilesetId: Int): Int {
        val romData = parser.getRomData()
        val tablePc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val offset = tablePc + tilesetId * 9 + 6
        return (romData[offset].toInt() and 0xFF) or
                ((romData[offset + 1].toInt() and 0xFF) shl 8) or
                ((romData[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readU16(bytes: ByteArray, pc: Int): Int =
        (bytes[pc].toInt() and 0xFF) or ((bytes[pc + 1].toInt() and 0xFF) shl 8)

    private fun highEntropyPaletteColors(tilesetId: Int): IntArray {
        var x = 0x13579BDF xor (tilesetId * 0x10203)
        return IntArray(128) {
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            x and 0x7FFF
        }
    }

    private fun paletteBytes(colors: IntArray): ByteArray {
        val raw = ByteArray(colors.size * 2)
        for (i in colors.indices) {
            raw[i * 2] = (colors[i] and 0xFF).toByte()
            raw[i * 2 + 1] = ((colors[i] shr 8) and 0xFF).toByte()
        }
        return raw
    }
}
