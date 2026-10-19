package com.supermetroid.editor.asm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.jetbrains.skia.Surface
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmBeamDamageSourceTest {
    @TempDir lateinit var directory: File
    private fun table(key: String) = "Projectile_Test_$key"
    private val source = buildString {
        for (charged in listOf(false, true)) {
            appendLine("${beamPointerLabel(charged)}:")
            for (key in BEAM_POINTER_KEYS) appendLine("    dw ${table(if (charged) "${key}_charged" else key)}")
        }
        for (beam in ALL_BEAMS) for (key in listOf(beam.key, beam.chargedKey)) {
            appendLine("${table(key)}:")
            appendLine("    dw ${if (key == beam.key) beam.defaultDamage else beam.defaultDamage * 3} ; keep damage note")
            appendLine("    dw " + List(10) { "InstList_Test" }.joinToString(", "))
        }
        appendLine("End_Test:")
    }.replace("\n", "\r\n")
    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState) {
        val project get() = editor.project
        val bridge get() = assertNotNull(editor.beamDamageBridge)
        fun text() = workspace.projectSourceText(BEAM_DAMAGE_SOURCE_FILE)
    }

    @Test fun `renamed pointer and projectile tables retain every beam field and source link`() {
        val root = File(directory, "renamed-source").apply { mkdirs() }
        File(root, "main.asm").writeText("incsrc bank_93.asm\n")
        File(root, BEAM_DAMAGE_SOURCE_FILE).writeText(source)
        val augmented = AsmSourceAugmentation("renamed-beams", renames = listOf(
            AsmSourceRename(beamPointerLabel(false), "NormalBeamProjectilePointers"),
            AsmSourceRename(beamPointerLabel(true), "ChargedBeamProjectilePointers"),
            AsmSourceRename(table("power"), "NormalPowerBeamProjectile"),
        )).apply(root)
        val text = augmented.texts.getValue(BEAM_DAMAGE_SOURCE_FILE)
        val before = AsmBeamDamageSource(source)
        val renamed = AsmBeamDamageSource(text, AsmSourceSymbolNames(augmented.symbolNames))
        for (key in before.keys) {
            assertEquals(before.original(key).value, renamed.original(key).value, key)
            assertContains(renamed.navigationKeysAt(text, renamed.line(key, text)), key)
        }
        assertTrue(renamed.field("power", text).table == "NormalPowerBeamProjectile")
    }

    private suspend fun fixture(roomSourceText: String = "; Unrelated room source\n", saved: SmEditProject? = null): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_93.asm\nincsrc bank_8F.asm\n")
        File(src, BEAM_DAMAGE_SOURCE_FILE).writeText(source)
        File(src, PLM_SOURCE_FILE).writeText(roomSourceText)
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val project = saved ?: SmEditProject("test.sfc").apply { asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), RomParser(ByteArray(0x300000)), emptyMap()), workspace)
        return Fixture(editor, workspace)
    }
    @Test fun `native lenses preserve comments line endings and all unrelated pointers`() {
        val catalog = AsmBeamDamageSource(source)
        for (beam in ALL_BEAMS) {
            assertEquals(beam.defaultDamage, catalog.original(beam.key).value)
            assertEquals(beam.defaultDamage * 3, catalog.original(beam.chargedKey).value)
        }
        val field = catalog.field("power", source)
        val edited = source.replaceRange(field.span, "\$FFFF")
        assertEquals(65535, catalog.field("power", edited).value)
        assertEquals(source, edited.replaceRange(catalog.field("power", edited).span, "20"))
        assertContains(edited, "\r\n"); assertContains(edited, "; keep damage note")
        assertFails { catalog.field("power", source.replaceRange(field.span, "20+1")) }
        assertFails { catalog.field("power", source.replaceRange(field.span, "65536")) }
        assertFails { catalog.field("power", source + "${table("power")}:\n dw 0\n") }
        assertFails { catalog.field("power", source.replace("    dw 20 ;", "    db 20 ;")) }
    }
    @Test fun `navigation and unchanged edits are nonmutating and first GUI edit is undoable source ownership`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val revision = f.workspace.sourceBufferRevision
        val project = Json.encodeToString(SmEditProject.serializer(), f.project)
        val dirty = f.editor.dirty
        bridge.view("power"); bridge.line("power"); bridge.line(null)
        val labelLine = f.text().take(f.text().indexOf("${table("power")}:" )).count { it == '\n' }
        assertTrue(bridge.editorLinks(BEAM_DAMAGE_SOURCE_FILE, labelLine).any { it.target == AsmEditorTarget.BeamDamage("power") })
        assertTrue(bridge.editorLinks(BEAM_DAMAGE_SOURCE_FILE, bridge.line("power")).any { it.target == AsmEditorTarget.BeamDamage("power") })
        assertTrue(f.editor.openBeamDamageSource("power"))
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text()))
        bridge.edit("power", 20)
        assertEquals(project, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(revision, f.workspace.sourceBufferRevision); assertEquals(dirty, f.editor.dirty)
        val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.editor.setPatchConfigData(patch.id, "power", 41)
        assertEquals(41, bridge.view("power").value); assertEquals(60, bridge.view("power_charged").value)
        assertNull(patch.configData?.get("power")); assertEquals(setOf("power"), f.project.asmWorkspace.beamDamageSourceKeys)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(source, f.text()); assertTrue(f.project.asmWorkspace.beamDamageSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(41, bridge.view("power").value)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(setOf("power"), cold.asmWorkspace.beamDamageSourceKeys)
    }
    @Test fun `legacy adoption preserves untouched charged damage and restores patch state in one undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("legacy", "Legacy", enabled = true, configType = "beam_damage", configData = mutableMapOf("power" to 40, "ice" to 50))
        f.project.patches.add(patch)
        assertEquals(120, f.bridge.value(patch, "power_charged").value)
        f.bridge.edit("power", 45)
        assertEquals(45, f.bridge.view("power").value); assertEquals(120, f.bridge.view("power_charged").value)
        assertEquals(mapOf("ice" to 50), assertNotNull(patch.configData).toMap())
        assertEquals(setOf("power", "power_charged"), f.project.asmWorkspace.beamDamageSourceKeys)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(source, f.text()); assertEquals(mapOf("power" to 40, "ice" to 50), assertNotNull(patch.configData).toMap())
        assertTrue(f.workspace.redoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        f.bridge.edit("power_charged", 65535)
        f.bridge.edit("power", 65535)
        assertEquals(65535, f.bridge.view("power_charged").value)
        assertEquals(65535, f.bridge.view("power").value)
    }
    @Test fun `raw charged edit migrates legacy normal value and invalid draft locks only that field`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("legacy", "Legacy", enabled = true, configType = "beam_damage", configData = mutableMapOf("power" to 40))
        f.project.patches.add(patch)
        val field = f.bridge.catalog.field("power_charged", f.text())
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(field.span, "91")))
        assertEquals(40, f.bridge.view("power").value); assertEquals(91, f.bridge.view("power_charged").value); assertNull(patch.configData)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(mapOf("power" to 40), assertNotNull(patch.configData).toMap())
        assertTrue(f.workspace.redoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        val normal = f.bridge.catalog.field("power", f.text())
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(normal.span, "40+1")))
        assertNotNull(f.bridge.view("power").problem); assertEquals(40, f.bridge.view("power").value)
        assertTrue(f.bridge.editorLinks(BEAM_DAMAGE_SOURCE_FILE, f.bridge.line("power")).any { it.target == AsmEditorTarget.BeamDamage("power") })
        assertNull(f.bridge.view("power_charged").problem); assertNull(f.bridge.view("ice").problem)
        assertTrue(f.bridge.line("power") >= 0)
        f.bridge.edit("ice", 57)
        assertEquals(57, f.bridge.view("ice").value)
    }
    @Test fun `adoption enables the customization without activating retired disabled legacy values`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("legacy", "Legacy", enabled = false, configType = "beam_damage", configData = mutableMapOf("power" to 40))
        f.project.patches.add(patch)
        f.bridge.edit("power_charged", 97)
        assertEquals(20, f.bridge.view("power").value); assertEquals(97, f.bridge.view("power_charged").value)
        assertTrue(patch.enabled); assertNull(patch.configData)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(source, f.text()); assertFalse(patch.enabled)
        patch.enabled = true; patch.configData = mutableMapOf("power" to 40, "power_charged" to 91)
        val field = f.bridge.catalog.field("power_charged", f.text())
        f.workspace.replaceProjectSource(BEAM_DAMAGE_SOURCE_FILE, f.text(), f.text().replaceRange(field.span, "111"))
        assertNotNull(f.bridge.view("power_charged").problem)
        f.bridge.useSourceValues("power")
        assertEquals(111, f.bridge.view("power_charged").value); assertNull(patch.configData)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(mapOf("power" to 40, "power_charged" to 91), assertNotNull(patch.configData).toMap())
    }
    @Test fun `checkbox confirms custom data and disable restore and source undo keep values and flags atomic`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        val initial = f.text()
        f.editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled); assertNull(f.editor.pendingPatchDisable)
        f.editor.requestTogglePatch(patch.id)
        assertFalse(patch.enabled); assertNull(f.editor.pendingPatchDisable)
        assertEquals(initial, f.text()); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
        f.bridge.editValues(mapOf("power" to 83, "power_charged" to 177, "wave" to 129))
        assertTrue(patch.enabled)
        val custom = f.text()
        f.editor.requestTogglePatch(patch.id)
        assertSame(patch, f.editor.pendingPatchDisable); assertTrue(patch.enabled); assertEquals(custom, f.text())
        f.editor.dismissPatchDisable()
        assertTrue(patch.enabled); assertEquals(custom, f.text())
        f.editor.requestTogglePatch(patch.id); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled); assertEquals(20, f.bridge.view("power").value); assertEquals(60, f.bridge.view("power_charged").value)
        assertTrue(f.project.asmWorkspace.beamDamageSourceKeys.isEmpty())
        val snapshot = mapOf("power" to listOf(83), "power_charged" to listOf(177), "wave" to listOf(129))
        assertEquals(snapshot, f.project.asmWorkspace.disabledPatchValues["beam_damage"])
        val disabled = f.text()
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertTrue(patch.enabled); assertEquals(custom, f.text()); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(disabled, f.text())
        f.editor.requestTogglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(custom, f.text()); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(disabled, f.text()); assertEquals(snapshot, f.project.asmWorkspace.disabledPatchValues["beam_damage"])
    }

    @Test fun `GUI and ASM edits while disabled restore other saved settings and undo to the disabled snapshot`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.editValues(mapOf("power" to 80, "wave" to 120, "wave_charged" to 250))
        f.editor.togglePatch(patch.id)
        val unrelated = f.text().replace("keep damage note", "independent comment edit")
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, unrelated)); assertFalse(patch.enabled)
        val disabled = f.text(); val saved = f.project.asmWorkspace.disabledPatchValues.toMap()
        f.editor.setPatchConfigData(patch.id, "power", 91)
        assertTrue(patch.enabled); assertEquals(91, f.bridge.view("power").value)
        assertEquals(120, f.bridge.view("wave").value); assertEquals(250, f.bridge.view("wave_charged").value)
        assertContains(f.text(), "independent comment edit")
        assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(disabled, f.text()); assertEquals(saved, f.project.asmWorkspace.disabledPatchValues)
        val field = f.bridge.catalog.field("power", f.text())
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(field.span, "92")))
        assertTrue(patch.enabled); assertEquals(92, f.bridge.view("power").value); assertEquals(120, f.bridge.view("wave").value)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(disabled, f.text()); assertEquals(saved, f.project.asmWorkspace.disabledPatchValues)
        f.bridge.resetToOriginal()
        assertTrue(patch.enabled); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
        assertEquals(20, f.bridge.view("power").value); assertEquals(50, f.bridge.view("wave").value)
    }

    @Test fun `disabled snapshots survive cold reload and later external ASM edits auto enable without discarding other saved values`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.editValues(mapOf("power" to 82, "ice" to 93)); f.editor.togglePatch(patch.id)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        val serialized = Json.encodeToString(SmEditProject.serializer(), f.project)
        val g = fixture(saved = Json.decodeFromString<SmEditProject>(serialized))
        val restored = g.project.patches.single { it.configType == "beam_damage" }
        assertFalse(restored.enabled); assertEquals(20, g.bridge.view("power").value)
        assertEquals(serialized, Json.encodeToString(SmEditProject.serializer(), g.project))
        val field = g.bridge.catalog.field("power", g.text())
        g.workspace.replaceProjectSource(BEAM_DAMAGE_SOURCE_FILE, g.text(), g.text().replaceRange(field.span, "101"))
        assertFails { validatePatchToggleSources(g.project, patchToggleFamilies(g.bridge.catalog, null, null), mapOf(BEAM_DAMAGE_SOURCE_FILE to g.text())) }
        assertNotNull(g.editor.patchToggleController).reconcileSourceFlags()
        assertTrue(restored.enabled); assertEquals(101, g.bridge.view("power").value); assertEquals(93, g.bridge.view("ice").value)
        assertTrue(g.project.asmWorkspace.disabledPatchValues.isEmpty())
        validatePatchToggleSources(g.project, patchToggleFamilies(g.bridge.catalog, null, null), mapOf(BEAM_DAMAGE_SOURCE_FILE to g.text()))
    }

    @Test fun `unsupported drafts and shared bindings cannot cause a partial reset or false disabled flag`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.edit("power", 99)
        val field = f.bridge.catalog.field("ice", f.text())
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(field.bindingSpan, table("power"))))
        val before = f.text(); val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        f.editor.togglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(before, f.text()); assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertContains(f.editor.statusMessage, "Shared source words")
        val shared = f.bridge.catalog.field("power", f.text())
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(shared.span, "99+1")))
        val draft = f.text(); f.editor.togglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(draft, f.text()); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
    }

    @Test fun `repairing an externally invalid beam value still auto enables its customization`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        val field = f.bridge.catalog.field("power", f.text())
        f.workspace.replaceProjectSource(BEAM_DAMAGE_SOURCE_FILE, f.text(), f.text().replaceRange(field.span, "83+1"))
        assertFalse(patch.enabled)
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replace("83+1", "84")))
        assertTrue(patch.enabled); assertEquals(84, f.bridge.view("power").value)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertContains(f.text(), "83+1")
    }

    @Test fun `rebinding a disabled beam preserves saved damage in its new shared destination`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.editValues(mapOf("power" to 82, "ice" to 93)); f.editor.togglePatch(patch.id)
        val before = f.text(); val saved = f.project.asmWorkspace.disabledPatchValues.toMap()
        val ice = f.bridge.catalog.field("ice", before)
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, before.replaceRange(ice.bindingSpan, table("power"))))
        assertTrue(patch.enabled); assertEquals(82, f.bridge.view("power").value); assertEquals(82, f.bridge.view("ice").value)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(before, f.text()); assertEquals(saved, f.project.asmWorkspace.disabledPatchValues)
    }

    @Test fun `source toggles preserve the existing placed custom item dependency guard before any source mutation`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.edit("power", 82)
        patch.customItems.add(CustomItemDef("custom", "Custom", "C", visiblePlmId = 0xF200))
        f.project.getOrCreateRoom(0x91F8).plmChanges.add(PlmChange("add", 0xF200, 71, 55, 0x53))
        val before = f.text(); val keys = f.project.asmWorkspace.beamDamageSourceKeys.toSet()
        f.editor.togglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(before, f.text()); assertEquals(keys, f.project.asmWorkspace.beamDamageSourceKeys)
        assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty()); assertContains(f.editor.statusMessage, "Cannot disable")
    }

    @Test fun `repeating disable preserves saved work and inconsistent active snapshots cannot be discarded by toggling`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        f.bridge.edit("power", 82); f.editor.togglePatch(patch.id)
        val saved = f.project.asmWorkspace.disabledPatchValues.toMap(); val before = f.text()
        f.editor.updatePatch(patch.id, enabled = false)
        assertFalse(patch.enabled); assertEquals(saved, f.project.asmWorkspace.disabledPatchValues); assertEquals(before, f.text())
        patch.enabled = true // Simulate an inconsistent external project edit.
        f.editor.togglePatch(patch.id)
        assertTrue(patch.enabled); assertEquals(saved, f.project.asmWorkspace.disabledPatchValues); assertEquals(before, f.text())
        assertContains(f.editor.statusMessage, "no values were discarded")
        f.editor.updatePatch(patch.id, enabled = true)
        assertEquals(82, f.bridge.view("power").value); assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
    }
    @Test fun `shared records adopt together and conflicting bulk reset is atomic`() = runBlocking {
        val f = fixture()
        val binding = f.bridge.catalog.field("ice", f.text()).bindingSpan
        assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(binding, table("power"))))
        f.bridge.edit("power", 99)
        assertEquals(99, f.bridge.view("ice").value)
        assertTrue(f.project.asmWorkspace.beamDamageSourceKeys.containsAll(setOf("power", "ice")))
        val before = f.text(); val metadata = f.project.asmWorkspace.beamDamageSourceKeys.toSet()
        assertFails { f.bridge.resetToOriginal() }
        assertEquals(before, f.text()); assertEquals(metadata, f.project.asmWorkspace.beamDamageSourceKeys)
        assertTrue(f.bridge.editorLinks(BEAM_DAMAGE_SOURCE_FILE, f.bridge.line("power")).map { it.target }.containsAll(
            listOf(AsmEditorTarget.BeamDamage("power"), AsmEditorTarget.BeamDamage("ice"))))
    }
    @Test fun `external conflicts have explicit undoable source repair and protect undo from new patch consumers`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("legacy", "Legacy", enabled = true, configType = "beam_damage", configData = mutableMapOf("power" to 40))
        f.project.patches.add(patch)
        val field = f.bridge.catalog.field("power", f.text())
        f.workspace.replaceProjectSource(BEAM_DAMAGE_SOURCE_FILE, f.text(), f.text().replaceRange(field.span, "25"))
        assertNotNull(f.bridge.view("power").problem)
        f.bridge.useSourceValues("power")
        assertEquals(25, f.bridge.view("power").value); assertEquals(60, f.bridge.view("power_charged").value)
        assertNull(patch.configData)
        val newer = SmPatch("newer", "Newer", enabled = true, configType = "beam_damage", configData = mutableMapOf("power" to 90))
        f.project.patches.add(newer)
        assertFalse(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE))
        assertEquals(25, f.bridge.view("power").value)
        f.project.patches.remove(newer)
        assertTrue(f.workspace.undoSourceEdit(BEAM_DAMAGE_SOURCE_FILE), f.workspace.error)
        assertEquals(mapOf("power" to 40), assertNotNull(patch.configData).toMap())
    }
    @Test fun `config semantics retain old triples but explicit charged words never get multiplied`() {
        assertEquals(mapOf("power" to 40, "power_charged" to 120), beamDamageConfigValues(mapOf("power" to 40)))
        assertEquals(mapOf("power" to 65535, "power_charged" to 91), beamDamageConfigValues(mapOf("power" to 65535, "power_charged" to 91)))
        assertEquals(mapOf("ice_charged" to 0), beamDamageConfigValues(mapOf("ice_charged" to 0)))
        assertFails { beamDamageConfigValues(mapOf("power" to 65535)) }
        assertFails { beamDamageConfigValues(mapOf("unknown" to 2)) }
        assertFails { beamDamageConfigValues(mapOf("power_charged" to -1)) }
    }

    @Test fun `compiler guards verify bindings bank bounds full words and relocated legacy destinations`() {
        val catalog = AsmBeamDamageSource(source)
        val project = SmEditProject("test.sfc")
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        fun word(bytes: ByteArray, pc: Int, value: Int) { bytes[pc] = value.toByte(); bytes[pc + 1] = (value ushr 8).toByte() }
        val addresses = mutableMapOf(beamPointerLabel(false) to 0x9383C1, beamPointerLabel(true) to 0x9383D9)
        for (beam in ALL_BEAMS) for (charged in listOf(false, true)) {
            val key = if (charged) beam.chargedKey else beam.key
            val address = if (charged) beam.chargedSnesAddress else beam.snesAddress
            addresses[table(key)] = address
            word(rom, parser.snesToPc(address), catalog.original(key).value)
            word(rom, parser.snesToPc(addresses.getValue(beamPointerLabel(charged))) + BEAM_POINTER_KEYS.indexOf(beam.key) * 2, address and 65535)
        }
        val reference = rom.copyOf()
        fun compiled(bytes: ByteArray = rom, symbols: Map<String, Int> = addresses, ranges: List<AsmSourceOwnedRange> = emptyList()) = AsmCompiledBuildBase(
            bytes.copyOf(), reference.copyOf(), "test", ranges, assemblerVersion = "test", compilerOutput = emptyList(),
            compilerSymbols = ("[labels]\n" + symbols.entries.joinToString("\n") { (name, address) ->
                "${(address ushr 16).toString(16)}:${(address and 65535).toString(16)} $name"
            } + "\n").toByteArray())
        project.asmWorkspace.beamDamageSourceKeys.add("power")
        val pc = parser.snesToPc(0x938431)
        val checked = protectBeamDamageSource(catalog, source, project, compiled(ranges = listOf(AsmSourceOwnedRange(pc, 22))))
        val headered = protectBeamDamageSource(catalog, source, project, compiled(ByteArray(512) { 0x5A } + rom,
            ranges = listOf(AsmSourceOwnedRange(pc, 22))))
        assertEquals(checked.beamDamageLocations, headered.beamDamageLocations)
        assertEquals(checked.sourceOwnedRanges, headered.sourceOwnedRanges)
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == pc && it.length == 2 && it.sourceFileId == BEAM_DAMAGE_SOURCE_FILE })
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == parser.snesToPc(0x9383C1) && it.length == 2 })
        assertEquals(pc, checked.beamDamageLocations["power"])
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(rom.copyOf().also { word(it, pc, 21) })) }
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(rom.copyOf().also { word(it, parser.snesToPc(0x9383C1), 0x9000) })) }
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(symbols = addresses - table("power"))) }
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(symbols = addresses + (table("power") to 0x948431))) }
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(symbols = addresses + (table("power") to 0x93FFEB))) }
        assertFails { protectBeamDamageSource(catalog, source, project, compiled(symbols = addresses + (beamPointerLabel(false) to 0x93FFF0))) }
        val patch = SmPatch("legacy", "Legacy", enabled = true, configType = "beam_damage", configData = mutableMapOf("power" to 40))
        project.patches.add(patch)
        assertFails { protectBeamDamageSource(catalog, source, project, compiled()) }
        project.asmWorkspace.beamDamageSourceKeys.clear()
        val movedPc = parser.snesToPc(0x93FF00)
        val moved = rom.copyOf().also { word(it, movedPc, 20); word(it, parser.snesToPc(0x9383C1), 0xFF00) }
        val relocated = protectBeamDamageSource(catalog, source, project,
            compiled(moved, addresses + (table("power") to 0x93FF00), listOf(AsmSourceOwnedRange(movedPc, 22))))
        assertEquals(movedPc, relocated.beamDamageLocations["power"])
        assertTrue(relocated.sourceOwnedRanges.none { movedPc in it.pcOffset until it.pcOffset + it.length })
        assertTrue(relocated.sourceOwnedRanges.any { it.pcOffset == movedPc + 2 && it.length == 20 })
    }

    private suspend fun withSourceCanvas(f: Fixture, width: Int = 1400,
        check: suspend (ComposeScene, suspend () -> Unit, MutableList<AsmEditorTarget>) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(width, 1000)
        val opened = mutableListOf<AsmEditorTarget>()
        try {
            scene.constraints = Constraints(maxWidth = width, maxHeight = 1000)
            scene.setContent { MaterialTheme { AsmWorkspaceCanvas(f.workspace, onOpenInEditor = opened::add) } }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(10) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() }; Unit }
            render(); check(scene, render, opened)
        } finally { scene.close(); surface.close() }
    }
    private fun sourceNodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun sourceField(scene: ComposeScene, pane: Long) = sourceNodes(scene).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "asm-source-editor-$pane"
    }
    private fun fieldOffset(node: SemanticsNode, key: String): Int {
        val text = node.config[SemanticsProperties.EditableText].text
        val label = text.indexOf("${table(key)}:")
        assertTrue(label >= 0, "$key must be in the current source page")
        return text.indexOf("dw ", label) + 3
    }
    private fun sourceText(scene: ComposeScene, text: String) = sourceNodes(scene).single {
        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == text
    }
    private fun clickSourceAction(scene: ComposeScene, text: String) {
        fun contains(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } || node.children.any(::contains)
        val action = sourceNodes(scene).lastOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && contains(it) }
        assertTrue(assertNotNull(action, "Missing action: $text").config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun selectField(scene: ComposeScene, pane: Long, key: String) {
        val node = sourceField(scene, pane); val at = fieldOffset(node, key)
        assertTrue(node.config[SemanticsActions.SetSelection].action!!.invoke(at, at, false))
    }
    private fun rightClickField(scene: ComposeScene, pane: Long, key: String) {
        val node = sourceField(scene, pane)
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        val rect = layouts.single().getCursorRect(fieldOffset(node, key))
        val point = node.positionInRoot + Offset(rect.left + 3f, rect.center.y)
        assertTrue(point.y in 0f..1000f, "Right-click target must be visible: $point")
        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
    }

    @Test fun `source caret follows beam fields without changing text history or Find Usages`() = runBlocking {
        val f = fixture()
        val labelLine = f.text().take(f.text().indexOf("${table("ice")}:" )).count { it == '\n' }
        f.workspace.openSource(BEAM_DAMAGE_SOURCE_FILE, labelLine)
        assertTrue(f.workspace.findLabelUsages(BEAM_DAMAGE_SOURCE_FILE, labelLine, table("ice")))
        val pane = f.workspace.activeSourcePaneId
        val before = f.text(); val revision = f.workspace.sourceBufferRevision
        val navigation = f.workspace.navigationSerial; val references = f.workspace.activeReferenceSymbol
        val back = f.workspace.canGoBack; val forward = f.workspace.canGoForward
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        withSourceCanvas(f) { scene, render, opened ->
            selectField(scene, pane, "wave"); render()
            assertTrue(sourceText(scene, "Edit Wave Beam damage").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(AsmEditorTarget.BeamDamage("wave"), opened.single())
            selectField(scene, pane, "wave_charged"); render()
            sourceText(scene, "Edit Wave Beam charged damage")
            assertEquals(navigation, f.workspace.navigationSerial)
            assertEquals(references, f.workspace.activeReferenceSymbol)
            assertEquals(back, f.workspace.canGoBack); assertEquals(forward, f.workspace.canGoForward)
            assertEquals(revision, f.workspace.sourceBufferRevision); assertEquals(before, f.text())
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        }
    }

    @Test fun `right click uses clicked wave field rather than ice caret and offers shared destinations explicitly`() = runBlocking {
        val f = fixture()
        f.workspace.openSource(BEAM_DAMAGE_SOURCE_FILE, f.bridge.line("ice"))
        val pane = f.workspace.activeSourcePaneId
        withSourceCanvas(f) { scene, render, opened ->
            selectField(scene, pane, "ice"); render()
            sourceText(scene, "Edit Ice Beam damage")
            val before = f.text(); val revision = f.workspace.sourceBufferRevision; val navigation = f.workspace.navigationSerial
            rightClickField(scene, pane, "wave"); render()
            assertTrue(sourceText(scene, "Undo").positionInRoot.y < sourceText(scene, "Redo").positionInRoot.y)
            assertTrue(sourceText(scene, "Redo").positionInRoot.y < sourceText(scene, "Visual Editor").positionInRoot.y)
            assertTrue(sourceText(scene, "Visual Editor").positionInRoot.y < sourceText(scene, "Find in this file").positionInRoot.y)
            clickSourceAction(scene, "Visual Editor"); render()
            assertEquals<List<AsmEditorTarget>>(listOf(AsmEditorTarget.BeamDamage("wave")), opened.toList())
            // Desktop text selection may select the right-clicked word; it must not navigate or edit source.
            assertEquals(before, f.text()); assertEquals(revision, f.workspace.sourceBufferRevision); assertEquals(navigation, f.workspace.navigationSerial)
            // A live rebinding creates two consumers. Neither the menu nor the link bar may choose one silently.
            val binding = f.bridge.catalog.field("power", f.text()).bindingSpan
            assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(binding, table("wave"))))
            render()
            rightClickField(scene, pane, "wave"); render()
            assertTrue(sourceText(scene, "Redo").positionInRoot.y < sourceText(scene, "Visual Editor…").positionInRoot.y)
            clickSourceAction(scene, "Visual Editor…"); render()
            assertEquals(1, opened.size)
            clickSourceAction(scene, "Edit Wave Beam damage"); render()
            assertEquals<List<AsmEditorTarget>>(listOf(AsmEditorTarget.BeamDamage("wave"), AsmEditorTarget.BeamDamage("wave")), opened.toList())
        }
    }

    @Test fun `split panes keep independent visual editor caret contexts`() = runBlocking {
        val f = fixture()
        f.workspace.openSource(BEAM_DAMAGE_SOURCE_FILE, f.bridge.line("ice"))
        val left = f.workspace.activeSourcePaneId
        f.workspace.splitActiveSource()
        val right = f.workspace.activeSourcePaneId
        assertNotEquals(left, right)
        withSourceCanvas(f, width = 1800) { scene, render, _ ->
            selectField(scene, left, "wave"); render()
            selectField(scene, right, "power"); render()
            fun textInBar(pane: Long): List<String> {
                val bar = sourceNodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "asm-visual-editor-links-$pane" }
                fun walk(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::walk)
                return walk(bar)
            }
            assertContains(textInBar(left), "Edit Wave Beam damage")
            assertContains(textInBar(right), "Edit Power Beam damage")
            assertTrue(f.project.asmWorkspace.beamDamageSourceKeys.isEmpty())
        }
    }

    @Test fun `read only source menu follows the clicked room and leaves unrelated code without a visual action`() = runBlocking {
        val f = fixture("macro SmeditRoomInfo_91F8_Header()\n    dw ${'$'}0001\nendmacro\n" +
            "macro SmeditRoomInfo_9879_Header()\n    dw ${'$'}0002\nendmacro\n    nop\n")
        val reference = assertNotNull(f.workspace.projectWorkspace).referenceWorkspace
        val readonly = AsmWorkspaceState(installedReferenceLoader = { reference })
        readonly.loadInstalled(); readonly.openSource(PLM_SOURCE_FILE, 0)
        val navigation = readonly.navigationSerial
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        withSourceCanvas(Fixture(f.editor, readonly)) { scene, render, opened ->
            fun rightClick(text: String) {
                val point = sourceText(scene, text).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
            }
            rightClick("    dw ${'$'}0002"); render()
            clickSourceAction(scene, "Visual Editor"); render()
            assertEquals(AsmEditorTarget.Room(0x9879), opened.single())
            rightClick("    nop"); render()
            assertFalse(sourceNodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.startsWith("Visual Editor") } })
            assertEquals(AsmBrowserMode.SOURCE, readonly.browserMode)
            assertEquals(navigation, readonly.navigationSerial)
            assertFalse(readonly.hasProjectWorkspace)
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        }
    }

    @Test fun `beam ASM links fit one line at every font size in both panel layouts`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1600, 1200)
        try {
            selectBeamDamage(null)
            val settings = EditorThemeState()
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) {
                        androidx.compose.material3.Surface { BeamDamageEditor(patch, f.editor, null) }
                    }
                }
            }
            var time = System.nanoTime()
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            val before = f.text(); val revision = f.workspace.sourceBufferRevision
            for (width in listOf(760, 1600)) for (font in FontSize.values()) {
                settings.fontSize.value = font
                scene.constraints = Constraints.fixed(width, 1200)
                repeat(8) { Snapshot.sendApplyNotifications(); yield(); surface.canvas.clear(0xFF1A1A1A.toInt()); time += 16_666_667; scene.render(surface.canvas, time); yield() }
                val nodes = scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
                for (beam in ALL_BEAMS) {
                    val button = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "beam-asm-${beam.key}" }
                    val label = walk(button).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "ASM" }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                    val layout = layouts.single()
                    assertEquals(1, layout.lineCount, "${beam.key}, $font, $width")
                    assertFalse(layout.didOverflowWidth, "${beam.key}, $font, $width")
                    assertTrue(label.boundsInRoot.width <= button.boundsInRoot.width)
                }
            }
            assertEquals(before, f.text()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(f.project.asmWorkspace.beamDamageSourceKeys.isEmpty())
            if (System.getenv("SMEDIT_CAPTURE_BEAM_UI") == "true") {
                val output = File("build/test-output/beam-damage-links-larger-1600.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { snapshot -> snapshot.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close(); selectBeamDamage(null) }
    }

    @Test fun `beam canvas navigates without adoption and edits both directions with field scoped invalid drafts`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("beam_damage")
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(760, 1150)
        var navigations = 0
        try {
            selectBeamDamage(null)
            scene.constraints = Constraints.fixed(760, 1150)
            val settings = EditorThemeState()
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) {
                        androidx.compose.material3.Surface {
                            BeamDamageEditor(patch, f.editor, null, onNavigateToAsm = { navigations++ })
                        }
                    }
                }
            }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
            }
            fun tagged(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            fun input(key: String) = tagged("beam-damage-$key")
            fun displayed(key: String) = input(key).config[SemanticsProperties.EditableText].text
            fun set(key: String, value: String) { assertTrue(input(key).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))) }
            render()
            val before = f.text(); val ownership = f.project.asmWorkspace.beamDamageSourceKeys.toSet()
            val revision = f.workspace.sourceBufferRevision
            assertTrue(tagged("beam-damage-asm").config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(tagged("beam-asm-power").config[SemanticsActions.OnClick].action!!.invoke())
            render()
            assertEquals(2, navigations); assertEquals(before, f.text())
            assertEquals(ownership, f.project.asmWorkspace.beamDamageSourceKeys); assertEquals(revision, f.workspace.sourceBufferRevision)
            set("power", "41"); render()
            assertEquals("41", displayed("power")); assertEquals("60", displayed("power_charged"))
            set("power_charged", "97"); render()
            assertEquals(41, f.bridge.view("power").value); assertEquals(97, f.bridge.view("power_charged").value)
            val field = f.bridge.catalog.field("power_charged", f.text())
            assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(field.span, "111")))
            render(); assertEquals("111", displayed("power_charged"))
            val normal = f.bridge.catalog.field("power", f.text())
            assertTrue(f.workspace.updateSourceEditText(BEAM_DAMAGE_SOURCE_FILE, f.text().replaceRange(normal.span, "41+1")))
            render()
            assertTrue(input("power").config.contains(SemanticsProperties.Disabled))
            assertFalse(input("power_charged").config.contains(SemanticsProperties.Disabled))
            assertEquals("41", displayed("power"))
            selectBeamDamage("power_charged"); render()
            assertTrue(tagged("beam-asm-power").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(f.bridge.line("power"), f.workspace.selectedLineIndex, "Broken field repair wins over the previously focused charged input")
            if (System.getenv("SMEDIT_CAPTURE_BEAM_UI") == "true") {
                val output = File("build/test-output/beam-damage-760.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { snapshot -> snapshot.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close(); selectBeamDamage(null) }
    }
}
