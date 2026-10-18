package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.DATA_PATCH_DEFINITIONS
import com.supermetroid.editor.asm.DATA_PATCH_TYPES
import com.supermetroid.editor.asm.nativeDataPatchRuntimeAddresses
import com.supermetroid.editor.asm.AsmEditorTarget
import com.supermetroid.editor.asm.AsmSemanticBridge
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class NativeDataPatchRomTest {
    @TempDir lateinit var directory: File

    @Test fun `ROM controls share no op range validation bomb coupling and occupied button swaps`() {
        val editor = EditorState().apply { testMode = true }
        val bombs = editor.findOrCreateConfigPatch(BOMB_CONFIG_TYPE)
        val dirty = editor.dirty; val version = editor.patchVersion
        editor.setPatchConfigData(bombs.id, BOMB_MAX_ACTIVE_KEY, 5)
        assertEquals(dirty, editor.dirty); assertFalse(bombs.enabled); assertEquals(version, editor.patchVersion)
        editor.setPatchConfigData(bombs.id, BOMB_MAX_ACTIVE_KEY, 4)
        assertEquals(4, bombs.configData?.get(BOMB_MAX_ACTIVE_KEY)); assertEquals(14, bombs.configData?.get(BOMB_COOLDOWN_FRAMES_KEY))
        assertTrue(bombs.enabled); assertTrue(editor.dirty)
        editor.setPatchConfigData(bombs.id, BOMB_FUSE_FRAMES_KEY, 70)
        assertEquals(14, bombs.configData?.get(BOMB_COOLDOWN_FRAMES_KEY), "Changing only fuse does not change cooldown")
        val controller = editor.findOrCreateConfigPatch(ControllerBindings.CONFIG_TYPE)
        editor.setPatchConfigData(controller.id, "jump", 0x4000)
        assertEquals(0x4000, controller.configData?.get("jump")); assertEquals(0x80, controller.configData?.get("item_cancel"))
        ControllerBindings.requireMapping(assertNotNull(controller.configData))
        val before = controller.configData?.toMap()
        editor.setPatchConfigData(controller.id, "jump", 0x1000)
        assertEquals(before, controller.configData); assertContains(editor.statusMessage, "blocked")
    }

    @Test fun `headered ROM readers writers and source reference links agree on all native fields`() {
        val original = TestRomHelper.requireRomBytes(); val header = ByteArray(512) { 0x5A }
        val parser = RomParser(header + original)
        assertEquals(readBombsRomDefaults(RomParser(original)), readBombsRomDefaults(parser))
        assertEquals(readFanfareRomDefaults(RomParser(original)), readFanfareRomDefaults(parser))
        val file = File(directory, "Native.smc").apply { writeBytes(header + original) }
        val project = SmEditProject(file.path)
        val unmodified = DATA_PATCH_TYPES.mapIndexed { index, type -> SmPatch("native-$index", type, enabled = true, configType = type) }
        project.patches.addAll(unmodified)
        assertContentEquals(header + original, assertNotNull(RomExporter(project, parser).build()).resultRom, "Empty enabled panels cannot invent overrides")
        val edits = mapOf("ceres_escape_seconds" to mapOf("seconds" to 95), ZEBES_ESCAPE_CONFIG_TYPE to mapOf("seconds" to 305),
            BOMB_CONFIG_TYPE to mapOf(BOMB_FUSE_FRAMES_KEY to 70), FANFARE_CONFIG_TYPE to mapOf(FANFARE_FRAMES_KEY to 240),
            ControllerBindings.CONFIG_TYPE to mapOf("jump" to 0x4000))
        unmodified.forEach { it.configData = edits.getValue(it.configType!!).toMutableMap() }
        val result = assertNotNull(RomExporter(project, parser).build()).resultRom; val output = RomParser(result)
        assertContentEquals(header, result.copyOfRange(0, 512))
        assertEquals(0x0135, output.readUInt16At(512 + 0x1E0E)); assertEquals(0x0505, output.readUInt16At(512 + 0x1E21))
        assertEquals(70, output.readUInt16At(512 + BOMB_FUSE_TIMER_PC))
        assertEquals(5, output.readUInt16At(512 + BOMB_ACTIVE_HARD_CAP_OPERAND_PC)); assertEquals(16, result[512 + BOMB_COOLDOWN_PC].toInt() and 255)
        for (pc in FANFARE_MUSIC_RESUME_DELAY_PCS + FANFARE_MESSAGE_BOX_WAIT_PC) assertEquals(240, output.readUInt16At(512 + pc))
        for (reset in listOf(false, true)) { val mapping = ControllerBindings.read(result, reset); assertEquals(0x4000, mapping["jump"]); assertEquals(0x80, mapping["item_cancel"]) }
        assertContentEquals(original.copyOfRange(0x17575, 0x17587), result.copyOfRange(512 + 0x17575, 512 + 0x17587))
        val index = AsmSemanticBridge.buildIndex(parser)
        for (definition in DATA_PATCH_DEFINITIONS) {
            val addresses = nativeDataPatchRuntimeAddresses(definition, parser)
            assertEquals(definition.sites.size * if (definition.sites.first().op in setOf("dw", "db")) 1 else 2, addresses.size, definition.id)
            addresses.forEach { assertTrue(index.linksFor(it).any { link -> link.target == AsmEditorTarget.PatchData(definition.type, definition.key) }, definition.id) }
        }
    }
}
