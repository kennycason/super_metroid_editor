package com.supermetroid.editor.asm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.jetbrains.skia.Surface
import kotlin.test.*

class AsmDataPatchSourceTest {
    @TempDir lateinit var directory: File
    private val originals: Map<String, String> get() = buildMap {
        for ((file, entries) in DATA_PATCH_DEFINITIONS.flatMap { def -> def.sites.map { def to it } }.groupBy { it.second.file }) {
            put(file, buildString {
                for ((symbol, sites) in entries.groupBy { it.second.symbol }) {
                    appendLine("$symbol:")
                    for ((def, site) in sites) {
                        val value = when (def.type) {
                            "ceres_escape_seconds" -> 0x0100
                            ZEBES_ESCAPE_CONFIG_TYPE -> 0x0300
                            FANFARE_CONFIG_TYPE -> 360
                            ControllerBindings.CONFIG_TYPE -> ControllerBindings.defaults.getValue(def.key)
                            else -> when (def.key) { BOMB_MAX_ACTIVE_KEY -> 5; BOMB_FUSE_FRAMES_KEY -> 60; BOMB_COOLDOWN_FRAMES_KEY -> 16; else -> 1 }
                        }
                        if (site.op == "db") {
                            repeat(5) { appendLine(" db 0") }
                            appendLine(" db regional(${ '$' }10, ${ '$' }0C)")
                            repeat(3) { appendLine(" db 0") }
                        } else if (site.op == "dw") appendLine(" dw regional(${ '$' }003C, ${ '$' }0034)")
                        else {
                            site.before.forEach { appendLine(" $it") }
                            appendLine(" ${site.op} #${'$'}${value.toString(16).padStart(4, '0')}")
                            site.consumer?.let { appendLine(" $it") }
                        }
                    }
                    if (sites.none { it.second.op in setOf("db", "dw") }) appendLine(" RTS")
                }
                if (file == "bank_90.asm") {
                    appendLine("HUDSelectionHandler_MorphBall:")
                    repeat(2) {
                        appendLine(" LDA.W BombTimerResetValue\n STA.W SamusProjectile_BombTimers-${'$'}A,X")
                        appendLine(" LDA.W ProjectileCooldowns_NonBeamProjectiles,Y\n AND.W #${'$'}00FF\n STA.W SamusProjectile_CooldownTimer")
                    }
                    appendLine(" RTS")
                }
                if (file == "bank_82.asm") {
                    appendLine("GameOptionsMenu_ControllerBindings:\n RTS\nSave_GameOptionsMenu_ControllerBindings:\n RTS")
                    appendLine("Configurable_Controller_Binding_RAM_Addresses:")
                    ControllerBindings.slots.forEach { appendLine(" dw ${it.ramSymbol}") }
                    appendLine("Controller_Input_Bitmasks:")
                    ControllerBindings.menuMasks.forEach { appendLine(" dw ${'$'}${it.toString(16)}") }
                }
            })
        }
        put("main.asm", "!PAL ?= 0\n" + keys.joinToString("\n") { "incsrc $it" } + "\nincsrc bank_8F.asm\n")
        put("macros.asm", "function regional(value_ntsc, value_pal) = select(!PAL, value_pal, value_ntsc)\n")
    }
    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState) {
        val bridge get() = assertNotNull(editor.dataPatchBridge)
        val project get() = editor.project
        fun patch(type: String) = editor.findOrCreateConfigPatch(type)
    }
    private suspend fun fixture(): Fixture {
        val root = File(directory, "reference"); val src = File(root, "src").apply { mkdirs() }; val data = File(root, "data").apply { mkdirs() }
        originals.forEach { (file, text) -> File(src, file).writeText(text) }; File(src, PLM_SOURCE_FILE).writeText("; Unrelated\n")
        val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0), AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), RomParser(ByteArray(0x300000)), emptyMap()), workspace)
        return Fixture(editor, workspace)
    }

    @Test fun `all finite codecs are readable and inspection and unchanged edits do not dirty the project`() = runBlocking {
        val f = fixture(); val before = Json.encodeToString(SmEditProject.serializer(), f.project); val revision = f.workspace.sourceBufferRevision
        assertEquals(14, f.bridge.catalog.definitions.size)
        for (def in DATA_PATCH_DEFINITIONS) {
            val view = f.bridge.view(def.type, def.key); assertNull(view.problem, "${def.id}: ${view.problem}")
            assertTrue(f.editor.openDataPatchSource(def.type, def.key))
            f.bridge.editValues(def.type, mapOf(def.key to assertNotNull(view.value)))
        }
        DATA_PATCH_TYPES.forEach { f.bridge.reset(it) }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertEquals(originals, f.bridge.texts()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
    }

    @Test fun `BCD uses decimal seconds rejects invalid digits and retains valid out of UI range`() {
        val def = DATA_PATCH_DEFINITIONS.first()
        assertEquals(0x0135, def.encode(95)); assertEquals(95, def.decode(0x0135)); assertEquals(0x1000, def.encode(600))
        assertNull(def.decode(0x0060)); assertNull(def.decode(0x01AF)); assertEquals(5999, def.decode(0x9959))
        assertFailsWith<IllegalArgumentException> { def.encode(601) }
        val catalog = AsmDataPatchSource(originals)
        assertNull(catalog.field(def.id, originals + ("bank_80.asm" to originals.getValue("bank_80.asm").replace("#${'$'}0100", "#${'$'}9959"))).value)
    }

    @Test fun `CRLF tabs and instruction case retain correct lenses and precise source context links`() {
        val catalog = AsmDataPatchSource(originals)
        val texts = originals.mapValues { (_, text) -> text.replace("LDA.W #", "lda.w\t#").replace("STA.W ", "sta.w\t")
            .replace(" db ", " DB\t").replace(" dw ", " DW\t").replace("\n", "\r\n") }
        for (definition in DATA_PATCH_DEFINITIONS) {
            assertEquals(catalog.original(definition.id).values, catalog.field(definition.id, texts).values)
            assertFalse(catalog.changed(definition.id, texts))
        }
        catalog.controllerInterface(texts)
        val id = dataPatchKey(ControllerBindings.CONFIG_TYPE, "jump")
        val (file, line) = catalog.line(id, texts)
        assertEquals(listOf(AsmEditorTarget.PatchData(ControllerBindings.CONFIG_TYPE, "jump")), catalog.editorLinks(file, line, texts).map { it.target })
    }

    @Test fun `timer edit retires scalar and source paste handles both timers with atomic undo`() = runBlocking {
        val f = fixture(); val ceres = f.patch("ceres_escape_seconds"); val zebes = f.patch(ZEBES_ESCAPE_CONFIG_TYPE)
        f.editor.setPatchConfigValue(ceres.id, 95)
        assertEquals(95, f.bridge.view(ceres.configType!!, "seconds").value); assertNull(ceres.configValue); assertTrue(ceres.enabled)
        assertTrue(f.workspace.undoSourceEdit("bank_80.asm"), f.workspace.error)
        assertEquals(60, ceres.configValue); assertFalse(ceres.enabled)
        val source = originals.getValue("bank_80.asm").replace("#${'$'}0100", "#${'$'}0200").replace("#${'$'}0300", "#${'$'}0400")
        assertTrue(f.workspace.updateSourceEditText("bank_80.asm", source), f.workspace.error)
        assertTrue(ceres.enabled); assertTrue(zebes.enabled); assertNull(ceres.configValue); assertNull(zebes.configValue)
        assertTrue(f.workspace.undoSourceEdit("bank_80.asm"), f.workspace.error)
        assertFalse(ceres.enabled); assertFalse(zebes.enabled); assertEquals(originals, f.bridge.texts())
    }

    @Test fun `bomb count and cooldown edit atomically without altering fuse or explosion`() = runBlocking {
        val f = fixture(); val patch = f.patch(BOMB_CONFIG_TYPE)
        f.editor.setPatchConfigData(patch.id, BOMB_MAX_ACTIVE_KEY, 4)
        assertEquals(4, f.bridge.view(BOMB_CONFIG_TYPE, BOMB_MAX_ACTIVE_KEY).value)
        assertEquals(14, f.bridge.view(BOMB_CONFIG_TYPE, BOMB_COOLDOWN_FRAMES_KEY).value)
        assertEquals(60, f.bridge.view(BOMB_CONFIG_TYPE, BOMB_FUSE_FRAMES_KEY).value)
        assertTrue(patch.enabled); assertNull(patch.configData)
        assertTrue(f.workspace.undoSourceEdit("bank_90.asm"), f.workspace.error); assertEquals(originals, f.bridge.texts()); assertFalse(patch.enabled)
        f.editor.setPatchConfigData(patch.id, BOMB_FUSE_FRAMES_KEY, 70)
        assertEquals(5, f.bridge.view(BOMB_CONFIG_TYPE, BOMB_MAX_ACTIVE_KEY).value)
        assertEquals(16, f.bridge.view(BOMB_CONFIG_TYPE, BOMB_COOLDOWN_FRAMES_KEY).value)
    }

    @Test fun `fanfare edits all ten sites and independent values remain exact and read only`() = runBlocking {
        val f = fixture(); val patch = f.patch(FANFARE_CONFIG_TYPE); val id = dataPatchKey(FANFARE_CONFIG_TYPE, FANFARE_FRAMES_KEY)
        f.editor.setPatchConfigData(patch.id, FANFARE_FRAMES_KEY, 240)
        assertEquals(List(10) { 240 }, f.bridge.catalog.field(id, f.bridge.texts()).values)
        assertTrue(f.workspace.undoSourceEdit("bank_84.asm"), f.workspace.error); assertEquals(originals, f.bridge.texts())
        val file = "bank_84.asm"; val current = f.workspace.projectSourceText(file)
        assertTrue(f.workspace.updateSourceEditText(file, current.replaceFirst("#${'$'}0168", "#${'$'}000A")))
        assertNotNull(f.bridge.view(FANFARE_CONFIG_TYPE, FANFARE_FRAMES_KEY).problem)
        assertEquals(listOf(360, 10) + List(8) { 360 }, f.bridge.catalog.field(id, f.bridge.texts()).values)
        assertFails { f.bridge.editValues(FANFARE_CONFIG_TYPE, mapOf(FANFARE_FRAMES_KEY to 120)) }
        f.bridge.reset(FANFARE_CONFIG_TYPE); assertEquals(List(10) { 360 }, f.bridge.catalog.field(id, f.bridge.texts()).values)
    }

    @Test fun `controller swaps both default paths and disable reenable and undo preserve exact values`() = runBlocking {
        val f = fixture(); val type = ControllerBindings.CONFIG_TYPE; val patch = f.patch(type)
        f.editor.setPatchConfigData(patch.id, "jump", 0x4000)
        for ((key, value) in mapOf("jump" to 0x4000, "item_cancel" to 0x80)) {
            assertEquals(listOf(value, value), f.bridge.catalog.field(dataPatchKey(type, key), f.bridge.texts()).values)
        }
        assertTrue(patch.enabled); val custom = f.bridge.texts()
        assertTrue(f.workspace.undoSourceEdit("bank_82.asm"), f.workspace.error); assertEquals(originals, f.bridge.texts()); assertFalse(patch.enabled)
        assertTrue(f.workspace.redoSourceEdit("bank_81.asm"), f.workspace.error); assertEquals(custom, f.bridge.texts())
        f.editor.requestTogglePatch(patch.id); assertNotNull(f.editor.pendingPatchDisable); f.editor.dismissPatchDisable()
        assertEquals(custom, f.bridge.texts()); assertTrue(patch.enabled)
        f.editor.requestTogglePatch(patch.id); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled, f.editor.statusMessage)
        ControllerBindings.slots.forEach { assertEquals(it.defaultMask, f.bridge.view(type, it.key).value) }
        assertEquals(setOf("jump", "item_cancel"), f.project.asmWorkspace.disabledPatchValues[type]?.keys)
        f.editor.requestTogglePatch(patch.id); assertTrue(patch.enabled, f.editor.statusMessage)
        assertEquals(custom, f.bridge.texts()); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
    }

    @Test fun `unsupported expression and changed bomb consumer lock only affected fields`() = runBlocking {
        val f = fixture(); val source = f.workspace.projectSourceText("bank_90.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", source.replace("regional(${ '$' }003C, ${ '$' }0034)", "60+1")))
        assertNotNull(f.bridge.view(BOMB_CONFIG_TYPE, BOMB_FUSE_FRAMES_KEY).problem)
        assertNull(f.bridge.view(BOMB_CONFIG_TYPE, BOMB_MAX_ACTIVE_KEY).problem)
        assertTrue(f.workspace.undoSourceEdit("bank_90.asm"), f.workspace.error)
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", source.replaceFirst("LDA.W BombTimerResetValue", "LDA.W OtherTimer")))
        assertNotNull(f.bridge.view(BOMB_CONFIG_TYPE, BOMB_FUSE_FRAMES_KEY).problem)
        assertNull(f.bridge.view(BOMB_CONFIG_TYPE, BOMB_COOLDOWN_FRAMES_KEY).problem)
    }

    @Test fun `controller context links identify the operand not all seven actions and menu changes lock controls`() = runBlocking {
        val f = fixture(); val type = ControllerBindings.CONFIG_TYPE
        for (slot in ControllerBindings.slots) {
            val word = f.bridge.catalog.original(dataPatchKey(type, slot.key)).words.first()
            val line = f.bridge.texts().getValue(word.site.file).take(word.span.first).count { it == '\n' }
            assertEquals(listOf(AsmEditorTarget.PatchData(type, slot.key)), f.bridge.editorLinks(word.site.file, line).map { it.target })
        }
        val text = f.workspace.projectSourceText("bank_82.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_82.asm", text.replace("GameOptionsMenu_ControllerBindings:\n RTS", "GameOptionsMenu_ControllerBindings:\n NOP\n RTS")))
        ControllerBindings.slots.forEach { assertNotNull(f.bridge.view(type, it.key).problem) }
        assertNull(f.bridge.view("ceres_escape_seconds", "seconds").problem)
    }

    @Test fun `independent valid controller defaults lock the whole coupled panel and reset remains undoable`() = runBlocking {
        val f = fixture(); val type = ControllerBindings.CONFIG_TYPE
        val text = f.workspace.projectSourceText("bank_81.asm")
        val next = text.replace("LDA.W #${'$'}0080\n STA.W JumpBinding", "LDA.W #${'$'}4000\n STA.W JumpBinding")
            .replace("LDA.W #${'$'}4000\n STA.W ItemCancelBinding", "LDA.W #${'$'}0080\n STA.W ItemCancelBinding")
        assertTrue(f.workspace.updateSourceEditText("bank_81.asm", next), f.workspace.error)
        ControllerBindings.slots.forEach { assertNotNull(f.bridge.view(type, it.key).problem) }
        assertFails { f.bridge.editValues(type, mapOf("dash" to 0x40)) }
        val exact = f.bridge.texts(); f.bridge.reset(type)
        ControllerBindings.slots.forEach { assertNull(f.bridge.view(type, it.key).problem) }
        assertTrue(f.workspace.undoSourceEdit("bank_81.asm"), f.workspace.error); assertEquals(exact, f.bridge.texts())
    }

    @Test fun `unrelated comments stay unowned and cold snapshots reject values wider than their byte`() = runBlocking {
        val f = fixture(); val timer = f.patch("ceres_escape_seconds")
        val text = f.workspace.projectSourceText("bank_80.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_80.asm", text + "; documentation\n"))
        assertFalse(timer.enabled); assertTrue(f.project.asmWorkspace.patchSourceKeys.isEmpty())
        f.editor.setPatchConfigData(f.patch(BOMB_CONFIG_TYPE).id, BOMB_COOLDOWN_FRAMES_KEY, 0)
        val patch = f.patch(BOMB_CONFIG_TYPE); f.editor.requestTogglePatch(patch.id); f.editor.confirmPatchDisable()
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        val families = patchToggleFamilies(null, null, null, f.bridge.catalog)
        validatePatchToggleSources(cold, families, f.bridge.texts())
        cold.asmWorkspace.disabledPatchValues[BOMB_CONFIG_TYPE] = mapOf(BOMB_COOLDOWN_FRAMES_KEY to listOf(256))
        assertFailsWith<IllegalArgumentException> { validatePatchToggleSources(cold, families, f.bridge.texts()) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `rendered timer fanfare controller controls source links and disable confirmation stay reciprocal`() = runBlocking {
        val f = fixture(); val timer = f.patch("ceres_escape_seconds"); f.editor.selectPatch(timer.id)
        val scene = ComposeScene(); val surface = Surface.makeRasterN32Premul(1000, 1000)
        try {
            scene.constraints = Constraints(maxWidth = 1000, maxHeight = 1000)
            scene.setContent { CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) { PatchEditorCanvas(f.editor, null); PatchDisableConfirmation(f.editor) }
            } }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() } }
            fun nodes(): List<SemanticsNode> { fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) } }
            fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            fun click(value: String) { assertTrue(tag(value).config[SemanticsActions.OnClick].action!!.invoke()) }
            fun input(key: String, value: String) { assertTrue(tag("native-patch-$key").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))) }
            render(); val before = Json.encodeToString(SmEditProject.serializer(), f.project); val revision = f.workspace.sourceBufferRevision
            input("seconds", ""); render(); input("seconds", "9"); render()
            assertEquals(revision, f.workspace.sourceBufferRevision); assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
            input("seconds", "95"); render(); assertTrue(timer.enabled); assertEquals(95, f.bridge.view(timer.configType!!, "seconds").value)
            click("native-patch-asm-seconds"); render()
            assertEquals("bank_80.asm", f.workspace.selectedFileId)
            click("patch-header-toggle"); render(); click("patch-disable-cancel"); render(); assertTrue(timer.enabled)
            click("patch-header-toggle"); render(); click("patch-disable-confirm"); render(); assertFalse(timer.enabled)
            assertEquals("60", tag("native-patch-seconds").config[SemanticsProperties.EditableText].text)
            click("patch-header-toggle"); render(); assertEquals("95", tag("native-patch-seconds").config[SemanticsProperties.EditableText].text)
            val fanfare = f.patch(FANFARE_CONFIG_TYPE); f.editor.selectPatch(fanfare.id); render(); input(FANFARE_FRAMES_KEY, "120"); render()
            assertEquals(List(10) { 120 }, f.bridge.catalog.field(dataPatchKey(FANFARE_CONFIG_TYPE, FANFARE_FRAMES_KEY), f.bridge.texts()).values)
            val controller = f.patch(ControllerBindings.CONFIG_TYPE); f.editor.selectPatch(controller.id); render()
            click("native-patch-jump"); render(); click("native-controller-jump-Y"); render()
            assertEquals(0x4000, f.bridge.view(ControllerBindings.CONFIG_TYPE, "jump").value)
            assertEquals(0x80, f.bridge.view(ControllerBindings.CONFIG_TYPE, "item_cancel").value)
        } finally { scene.close(); surface.close() }
    }
}
