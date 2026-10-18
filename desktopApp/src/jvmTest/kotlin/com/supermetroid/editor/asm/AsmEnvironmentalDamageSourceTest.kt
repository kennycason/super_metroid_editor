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
import com.supermetroid.editor.rom.EnvironmentalDamagePatch as Rates
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmEnvironmentalDamageSourceTest {
    @TempDir lateinit var directory: File
    private fun accumulator(low: String, high: String) = """
            LDA.W PeriodicSubDamage
            CLC
            ADC.W $low
            STA.W PeriodicSubDamage
            LDA.W PeriodicDamage
            ADC.W $high
            STA.W PeriodicDamage
    """.trimIndent() + "\n"
    private val heat = "PreInstruction_PaletteFXObject_SamusInHeat:\n    LDA.W EquippedItems\n    AND.W #${'$'}0021\n    BNE +\n" +
        accumulator("#${'$'}4000", "#${'$'}0000") + "+   RTS\nOtherHeatCode:\n    RTS\n"
    private val liquid = "AnimateSamus_FX_Lava:\n  .periodicDamage:\n" +
        accumulator("SamusPhysicsConstants_LavaSubDamagePerFrame", "SamusPhysicsConstants_LavaDamagePerFrame") +
        "AnimateSamus_FX_Acid:\n" + accumulator("SamusPhysicsConstants_AcidSubDamagePerFrame", "SamusPhysicsConstants_AcidDamagePerFrame") +
        """
            SamusPhysicsConstants:
              .LavaSubDamagePerFrame:
                dw ${'$'}80*!SPF*${'$'}100
              .LavaDamagePerFrame:
                dw ${'$'}0000
              .AcidSubDamagePerFrame:
                dw ${'$'}180*!SPF*${'$'}100
              .AcidDamagePerFrame:
                dw ${'$'}0001
              .AnimationDelayInWater:
                dw 3
        """.trimIndent() + "\n"
    private val main = "incsrc bank_8D.asm\nincsrc bank_90.asm\nincsrc bank_8F.asm\n"
    private val originals get() = mapOf(ENVIRONMENT_HEAT_FILE to heat, ENVIRONMENT_LIQUID_FILE to liquid, "main.asm" to main)
    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState) {
        val bridge get() = assertNotNull(editor.environmentalDamageBridge)
        val project get() = editor.project
    }
    private suspend fun fixture(): Fixture {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_8D.asm\nincsrc bank_90.asm\nincsrc bank_8F.asm\n")
        originals.forEach { (file, text) -> File(src, file).writeText(text) }
        File(src, PLM_SOURCE_FILE).writeText("; Unrelated\n")
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

    @Test fun `native expressions pairs navigation and unchanged GUI saves are inspection only`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val before = Json.encodeToString(SmEditProject.serializer(), f.project)
        val revision = f.workspace.sourceBufferRevision
        assertEquals(listOf(15, 30, 90), Rates.FIELDS.map { bridge.view(it.key).value })
        for (field in Rates.FIELDS) {
            assertNull(bridge.view(field.key).problem)
            assertTrue(f.editor.openEnvironmentalDamageSource(field.key))
            val (file, line) = bridge.line(field.key)
            assertEquals(listOf(AsmEditorTarget.EnvironmentalDamage(field.key)), bridge.editorLinks(file, line).map { it.target })
            bridge.edit(field.key, field.defaultEnergyPerSecond)
        }
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertEquals(originals, bridge.texts()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertFalse(f.workspace.undoSourceEdit(ENVIRONMENT_HEAT_FILE))
        assertTrue(bridge.editorLinks(ENVIRONMENT_HEAT_FILE, heat.lines().indexOf("OtherHeatCode:")).isEmpty())
    }

    @Test fun `GUI pair edits retire only touched configs and two bank actions undo atomically`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val patch = SmPatch("hazards", "Hazards", enabled = true, configType = Rates.CONFIG_TYPE,
            configData = mutableMapOf(Rates.HEAT_KEY to 5, Rates.LAVA_KEY to 40, Rates.ACID_KEY to 120))
        f.project.patches.add(patch)
        bridge.editValues(mapOf(Rates.HEAT_KEY to 0, Rates.ACID_KEY to 60))
        assertEquals<Map<String, Int>?>(mapOf(Rates.LAVA_KEY to 40), patch.configData)
        assertEquals(setOf(environmentalSourceKey(Rates.HEAT_KEY), environmentalSourceKey(Rates.ACID_KEY)), f.project.asmWorkspace.patchSourceKeys)
        assertEquals(listOf(0, 0), bridge.catalog.field(Rates.HEAT_KEY, bridge.texts()).words.map { it.value })
        assertEquals(listOf(0, 1), bridge.catalog.field(Rates.ACID_KEY, bridge.texts()).words.map { it.value })
        val after = bridge.texts()
        assertTrue(f.workspace.undoSourceEdit(ENVIRONMENT_HEAT_FILE), f.workspace.error)
        assertEquals(originals, bridge.texts()); assertEquals(5, patch.configData?.get(Rates.HEAT_KEY)); assertEquals(120, patch.configData?.get(Rates.ACID_KEY))
        assertTrue(f.project.asmWorkspace.patchSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENVIRONMENT_LIQUID_FILE), f.workspace.error)
        assertEquals(after, bridge.texts()); assertEquals<Map<String, Int>?>(mapOf(Rates.LAVA_KEY to 40), patch.configData)
        val newer = SmPatch("newer", "Newer", configType = Rates.CONFIG_TYPE, configData = mutableMapOf(Rates.ACID_KEY to 99))
        f.project.patches.add(newer)
        assertFalse(f.workspace.undoSourceEdit(ENVIRONMENT_HEAT_FILE))
        assertEquals(after, bridge.texts()); assertEquals<Map<String, Int>?>(mapOf(Rates.LAVA_KEY to 40), patch.configData)
    }

    @Test fun `ASM edits retire the complete logical rate preserve fractional precision and isolate invalid drafts`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val patch = SmPatch("hazards", "Hazards", enabled = true, configType = Rates.CONFIG_TYPE, configData = mutableMapOf(Rates.ACID_KEY to 120))
        f.project.patches.add(patch)
        val acid = bridge.catalog.field(Rates.ACID_KEY, bridge.texts())
        assertTrue(f.workspace.updateSourceEditText(acid.file, liquid.replaceRange(acid.words[0].span, "1")))
        assertNull(patch.configData)
        assertEquals("60.00091552734375", bridge.view(Rates.ACID_KEY).display)
        assertNull(bridge.view(Rates.ACID_KEY).value)
        assertNotNull(bridge.view(Rates.ACID_KEY).problem)
        assertNull(bridge.view(Rates.LAVA_KEY).problem)
        val fractional = bridge.texts()
        assertFails { bridge.editValues(mapOf(Rates.HEAT_KEY to 0, Rates.ACID_KEY to 90)) }
        assertEquals(fractional, bridge.texts())
        assertTrue(f.workspace.undoSourceEdit(acid.file), f.workspace.error)
        assertEquals(originals, bridge.texts()); assertEquals(120, patch.configData?.get(Rates.ACID_KEY))
        bridge.edit(Rates.HEAT_KEY, 20)
        val heatField = bridge.catalog.field(Rates.HEAT_KEY, bridge.texts())
        val good = bridge.texts()
        assertTrue(f.workspace.updateSourceEditText(ENVIRONMENT_HEAT_FILE, good.getValue(ENVIRONMENT_HEAT_FILE).replaceRange(heatField.words[0].span, "1+2")))
        assertEquals("20", bridge.view(Rates.HEAT_KEY).display)
        assertNotNull(bridge.view(Rates.HEAT_KEY).problem)
        assertNull(bridge.view(Rates.LAVA_KEY).problem)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENVIRONMENT_HEAT_FILE), f.workspace.error)
        assertEquals(good, bridge.texts())
    }

    @Test fun `external source conflicts have an undoable use ASM values repair`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val patch = SmPatch("hazards", "Hazards", enabled = true, configType = Rates.CONFIG_TYPE, configData = mutableMapOf(Rates.LAVA_KEY to 20))
        f.project.patches.add(patch)
        val field = bridge.catalog.field(Rates.LAVA_KEY, bridge.texts())
        f.workspace.replaceProjectSource(field.file, liquid, liquid.replaceRange(field.words[0].span, "${'$'}C000"))
        assertTrue(bridge.view(Rates.LAVA_KEY).conflict)
        val before = bridge.texts()
        bridge.useSourceValue(Rates.LAVA_KEY)
        assertEquals(before, bridge.texts()); assertNull(patch.configData); assertEquals(45, bridge.view(Rates.LAVA_KEY).value)
        assertTrue(f.workspace.undoSourceEdit(field.file), f.workspace.error)
        assertEquals(20, patch.configData?.get(Rates.LAVA_KEY)); assertTrue(bridge.view(Rates.LAVA_KEY).conflict)
    }

    @Test fun `disabling exact fractional hazards preserves both words and cross bank source undo`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch(Rates.CONFIG_TYPE)
        f.bridge.editValues(mapOf(Rates.HEAT_KEY to 20, Rates.LAVA_KEY to 45))
        val acid = f.bridge.catalog.field(Rates.ACID_KEY, f.bridge.texts())
        assertTrue(f.workspace.updateSourceEditText(acid.file, f.bridge.texts().getValue(acid.file).replaceRange(acid.words[0].span, "1")))
        assertTrue(patch.enabled)
        val custom = f.bridge.texts()
        f.editor.requestTogglePatch(patch.id); assertSame(patch, f.editor.pendingPatchDisable); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled); assertEquals(listOf(15, 30, 90), Rates.FIELDS.map { f.bridge.view(it.key).value })
        assertEquals(listOf(1, 1), f.project.asmWorkspace.disabledPatchValues[Rates.CONFIG_TYPE]?.get(Rates.ACID_KEY))
        assertTrue(f.workspace.undoSourceEdit(ENVIRONMENT_HEAT_FILE), f.workspace.error)
        assertTrue(patch.enabled); assertEquals(custom, f.bridge.texts())
        assertTrue(f.workspace.redoSourceEdit(ENVIRONMENT_LIQUID_FILE), f.workspace.error)
        assertFalse(patch.enabled)
        f.editor.setPatchConfigData(patch.id, Rates.HEAT_KEY, 25)
        assertTrue(patch.enabled); assertEquals(25, f.bridge.view(Rates.HEAT_KEY).value); assertEquals(45, f.bridge.view(Rates.LAVA_KEY).value)
        assertEquals("60.00091552734375", f.bridge.view(Rates.ACID_KEY).display)
        assertTrue(f.project.asmWorkspace.disabledPatchValues.isEmpty())
    }

    @Test fun `toolbar confirms only customized rates and cancel is nonmutating`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch(Rates.CONFIG_TYPE)
        f.editor.selectPatch(patch.id)
        val scene = ComposeScene(); val surface = Surface.makeRasterN32Premul(1000, 850)
        try {
            scene.constraints = Constraints(maxWidth = 1000, maxHeight = 850)
            scene.setContent { CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) {
                    PatchEditorCanvas(f.editor, null)
                    PatchDisableConfirmation(f.editor)
                }
            } }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() } }
            fun nodes(): List<SemanticsNode> { fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) } }
            fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            fun click(value: String) { assertTrue(tag(value).config[SemanticsActions.OnClick].action!!.invoke()) }
            render(); click("patch-header-toggle"); render()
            assertTrue(patch.enabled); assertNull(f.editor.pendingPatchDisable)
            click("patch-header-toggle"); render(); assertFalse(patch.enabled); assertNull(f.editor.pendingPatchDisable)
            assertTrue(tag("environmental-rate-${Rates.LAVA_KEY}").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("45"))); render()
            assertTrue(patch.enabled)
            val source = f.bridge.texts(); val model = Json.encodeToString(SmEditProject.serializer(), f.project)
            click("patch-header-toggle"); render(); assertTrue(patch.enabled)
            click("patch-disable-cancel"); render()
            assertEquals(source, f.bridge.texts()); assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
            click("patch-header-toggle"); render(); click("patch-disable-confirm"); render()
            assertFalse(patch.enabled); assertEquals("30", tag("environmental-rate-${Rates.LAVA_KEY}").config[SemanticsProperties.EditableText].text)
            click("patch-header-toggle"); render()
            assertTrue(patch.enabled); assertEquals("45", tag("environmental-rate-${Rates.LAVA_KEY}").config[SemanticsProperties.EditableText].text)
        } finally { scene.close(); surface.close() }
    }

    private fun word(bytes: ByteArray, pc: Int, value: Int) { bytes[pc] = value.toByte(); bytes[pc + 1] = (value ushr 8).toByte() }
    private fun nativeSymbols() = mutableMapOf(
        "PreInstruction_PaletteFXObject_SamusInHeat" to 0x8DE379,
        "AnimateSamus_FX_Lava_periodicDamage" to 0x9081F7, "AnimateSamus_FX_Acid" to 0x908239,
        "SamusPhysicsConstants_LavaSubDamagePerFrame" to 0x909E8B, "SamusPhysicsConstants_LavaDamagePerFrame" to 0x909E8D,
        "SamusPhysicsConstants_AcidSubDamagePerFrame" to 0x909E8F, "SamusPhysicsConstants_AcidDamagePerFrame" to 0x909E91,
        "PeriodicSubDamage" to 0xA4E, "PeriodicDamage" to 0xA50,
    )
    private fun emitted(texts: Map<String, String> = originals, labels: Map<String, Int> = nativeSymbols()): ByteArray {
        val bytes = ByteArray(0x300000); val parser = RomParser(bytes); val catalog = AsmEnvironmentalDamageSource(originals)
        for (key in catalog.keys) {
            val field = catalog.field(key, texts)
            val pc = parser.snesToPc(labels.getValue(field.coreSymbol) + field.coreOffset)
            val words = field.words.map { parser.snesToPc(labels.getValue(it.symbol) + it.offset) }
            val heat = key == Rates.HEAT_KEY
            for ((offset, opcode) in mapOf(0 to 0xAD, 3 to 0x18, 4 to if (heat) 0x69 else 0x6D, 7 to 0x8D, 10 to 0xAD, 13 to if (heat) 0x69 else 0x6D, 16 to 0x8D)) bytes[pc + offset] = opcode.toByte()
            for ((offset, name) in listOf(1 to "PeriodicSubDamage", 8 to "PeriodicSubDamage", 11 to "PeriodicDamage", 17 to "PeriodicDamage")) word(bytes, pc + offset, labels.getValue(name))
            if (!heat) { word(bytes, pc + 5, labels.getValue(field.words[0].symbol)); word(bytes, pc + 14, labels.getValue(field.words[1].symbol)) }
            words.forEachIndexed { i, where -> word(bytes, where, field.words[i].value) }
        }
        return bytes
    }
    private fun compiled(bytes: ByteArray = emitted(), labels: Map<String, Int> = nativeSymbols(), ranges: List<AsmSourceOwnedRange> = emptyList()) = AsmCompiledBuildBase(
        bytes, emitted(), "test", ranges, assemblerVersion = "test", compilerOutput = emptyList(),
        compilerSymbols = ("[labels]\n" + labels.entries.joinToString("\n") { (name, address) -> "${(address ushr 16).toString(16).padStart(2, '0')}:${(address and 65535).toString(16).padStart(4, '0')} $name" } + "\n").toByteArray())

    @Test fun `compiled guards check pair words opcodes accumulator and table bindings and copier headers`() {
        val catalog = AsmEnvironmentalDamageSource(originals); val project = SmEditProject("test.sfc")
        project.asmWorkspace.patchSourceKeys.addAll(catalog.keys.map(::environmentalSourceKey))
        val checked = protectEnvironmentalDamageSource(catalog, originals, project, compiled())
        assertEquals(checked.patchDataLocations, checked.copyForReuse().patchDataLocations)
        val headered = protectEnvironmentalDamageSource(catalog, originals, project, compiled(ByteArray(512) { 0x5A } + emitted()))
        assertEquals(checked.patchDataLocations, headered.patchDataLocations)
        assertEquals(checked.sourceOwnedRanges, headered.sourceOwnedRanges)
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == 0x81E91 && it.length == 2 })
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(emitted().also { word(it, 0x81E91, 5) })) }
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(emitted().also { it[0x6E385] = 0x6D })) }
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(emitted().also { word(it, 0x6E382, 0xFFFF) })) }
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(emitted().also { word(it, 0x801FC, 0x9E8F) })) }
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(labels = nativeSymbols() - "SamusPhysicsConstants_AcidDamagePerFrame")) }
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled(labels = nativeSymbols() + ("SamusPhysicsConstants_LavaSubDamagePerFrame" to 0x91FF00))) }
        assertFails { protectEnvironmentalDamageSource(null, originals, project, compiled()) }
        project.asmWorkspace.patchSourceKeys.clear()
        assertTrue(protectEnvironmentalDamageSource(catalog, originals, project, compiled(labels = emptyMap())).sourceOwnedRanges.isEmpty(),
            "Unemitted, unowned code must not block unrelated custom ASM builds")
    }

    @Test fun `pending overrides follow moved pairs and cannot compete with authored or shared words`() {
        val catalog = AsmEnvironmentalDamageSource(originals); val project = SmEditProject("test.sfc")
        val patch = SmPatch("legacy", "Legacy", enabled = true, configType = Rates.CONFIG_TYPE, configData = mutableMapOf(Rates.LAVA_KEY to 40))
        project.patches.add(patch)
        val labels = nativeSymbols() + ("SamusPhysicsConstants_LavaSubDamagePerFrame" to 0x90FF00) + ("SamusPhysicsConstants_LavaDamagePerFrame" to 0x90FF02)
        val pc = RomParser(ByteArray(0x300000)).snesToPc(0x90FF00)
        val checked = protectEnvironmentalDamageSource(catalog, originals, project, compiled(emitted(labels = labels), labels, listOf(AsmSourceOwnedRange(pc, 6))))
        assertEquals(AsmPatchWordLocations(pc, pc + 2), checked.patchDataLocations[environmentalSourceKey(Rates.LAVA_KEY)])
        assertTrue(checked.sourceOwnedRanges.none { pc in it.pcOffset until it.pcOffset + it.length })
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == pc + 4 && it.length == 2 })
        project.asmWorkspace.patchSourceKeys.add(environmentalSourceKey(Rates.LAVA_KEY))
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled()) }
        project.asmWorkspace.patchSourceKeys.clear(); patch.configData = mutableMapOf(Rates.LAVA_KEY to 10000)
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled()) }
        patch.configData = mutableMapOf("unknown" to 1)
        assertFails { protectEnvironmentalDamageSource(catalog, originals, project, compiled()) }
    }

    @Test fun `source routine movement is checked and raw exact non GUI pairs remain buildable`() {
        val catalog = AsmEnvironmentalDamageSource(originals); val project = SmEditProject("test.sfc")
        val acid = catalog.original(Rates.ACID_KEY)
        val exact = originals + (acid.file to liquid.replaceRange(acid.words[0].span, "1"))
        val output = protectEnvironmentalDamageSource(catalog, exact, project, compiled(emitted(exact)))
        assertTrue(output.sourceOwnedRanges.any { it.pcOffset == 0x81E8F && it.length == 2 })
        val shifted = originals + (ENVIRONMENT_HEAT_FILE to heat.replace("    AND.W", "    NOP\n    AND.W"))
        project.asmWorkspace.patchSourceKeys.add(environmentalSourceKey(Rates.HEAT_KEY))
        val safe = protectEnvironmentalDamageSource(catalog, shifted, project, compiled(emitted(shifted)))
        assertEquals(0x6E387, safe.patchDataLocations[environmentalSourceKey(Rates.HEAT_KEY)]?.lowPc)
        val broken = originals + (ENVIRONMENT_HEAT_FILE to heat.replace("ADC.W #", "SBC.W #"))
        assertFails { protectEnvironmentalDamageSource(catalog, broken, project, compiled()) }
    }

    @Test fun `changed frame defines never silently reinterpret native SPF expressions`() {
        val baseline = originals + ("main.asm" to "!PAL = 0\n!SPF = 1\n")
        val catalog = AsmEnvironmentalDamageSource(baseline)
        val changed = baseline + ("main.asm" to "!PAL = 0\n!SPF = 2\n")
        assertEquals(30, catalog.field(Rates.LAVA_KEY, baseline).rate)
        assertFails { catalog.field(Rates.LAVA_KEY, changed) }
        assertFalse(catalog.changed(Rates.LAVA_KEY, changed), "Global define editing must not adopt unrelated field metadata")
        assertEquals(15, catalog.field(Rates.HEAT_KEY, changed).rate)
        val lava = catalog.original(Rates.LAVA_KEY)
        val explicit = changed + (lava.file to liquid.replaceRange(lava.words[0].span, "${'$'}8000"))
        assertEquals(30, catalog.field(Rates.LAVA_KEY, explicit).editableRate)
        val conditional = originals + ("main.asm" to "!PAL=0\nif !PAL\n!SPF=6/5\nelse\n!SPF=1\nendif\n")
        val guarded = AsmEnvironmentalDamageSource(conditional)
        assertEquals(30, guarded.field(Rates.LAVA_KEY, conditional).rate)
        assertFails { guarded.field(Rates.LAVA_KEY, conditional + ("main.asm" to conditional.getValue("main.asm").replace("if !PAL", "if 1"))) }
        assertEquals(30, guarded.field(Rates.LAVA_KEY, conditional + ("main.asm" to conditional.getValue("main.asm").replace("!SPF=", "!SPF  =  "))).rate)
    }

    @Test fun `shared hazard words stay ASM managed and cannot accept competing overrides`() = runBlocking {
        val f = fixture(); val bridge = f.bridge
        val shared = liquid.replace("  .AcidSubDamagePerFrame:\n    dw ${'$'}180*!SPF*${'$'}100\n", "")
            .replace("  .LavaSubDamagePerFrame:\n    dw ${'$'}80*!SPF*${'$'}100", "  .LavaSubDamagePerFrame:\n  .AcidSubDamagePerFrame:\n    dw ${'$'}8000")
        f.workspace.replaceProjectSource(ENVIRONMENT_LIQUID_FILE, liquid, shared)
        assertNotNull(bridge.view(Rates.LAVA_KEY).problem)
        assertFails { bridge.edit(Rates.LAVA_KEY, 40) }
        assertEquals(shared, bridge.texts().getValue(ENVIRONMENT_LIQUID_FILE))
        val catalog = bridge.catalog
        val project = f.project
        project.asmWorkspace.patchSourceKeys.add(environmentalSourceKey(Rates.LAVA_KEY))
        val patch = SmPatch("shared", "Shared", enabled = true, configType = Rates.CONFIG_TYPE, configData = mutableMapOf(Rates.ACID_KEY to 120))
        project.patches.add(patch)
        val labels = nativeSymbols() + ("SamusPhysicsConstants_AcidSubDamagePerFrame" to 0x909E8B)
        assertTrue(bridge.view(Rates.ACID_KEY).conflict)
        assertFails { protectEnvironmentalDamageSource(catalog, bridge.texts(), project, compiled(emitted(bridge.texts(), labels), labels)) }
        bridge.useSourceValue(Rates.ACID_KEY)
        assertNull(patch.configData)
        val checked = protectEnvironmentalDamageSource(catalog, bridge.texts(), project, compiled(emitted(bridge.texts(), labels), labels))
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == 0x81E8B && it.length == 2 })
    }

    @Test fun `canvas syncs both directions preserves fractional display and offers source repair`() = runBlocking {
        val f = fixture(); val patch = f.editor.findOrCreateConfigPatch(Rates.CONFIG_TYPE)
        val scene = ComposeScene(); val surface = Surface.makeRasterN32Premul(1000, 850)
        var navigations = 0
        try {
            selectEnvironmentalDamage(null)
            scene.constraints = Constraints.fixed(1000, 850)
            scene.setContent { CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) { androidx.compose.material3.Surface {
                    EnvironmentalDamageEditor(patch, f.editor, null, onNavigateToAsm = { navigations++ })
                } }
            } }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); yield() } }
            fun nodes(): List<SemanticsNode> { fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) } }
            fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            fun input(key: String) = tag("environmental-rate-$key")
            fun value(key: String) = input(key).config[SemanticsProperties.EditableText].text
            render()
            val revision = f.workspace.sourceBufferRevision
            assertTrue(tag("environmental-asm-${Rates.LAVA_KEY}").config[SemanticsActions.OnClick].action!!.invoke()); render()
            assertEquals(1, navigations); assertEquals(revision, f.workspace.sourceBufferRevision); assertTrue(f.project.asmWorkspace.patchSourceKeys.isEmpty())
            assertTrue(input(Rates.LAVA_KEY).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("45"))); render()
            assertEquals("45", value(Rates.LAVA_KEY))
            val lava = f.bridge.catalog.field(Rates.LAVA_KEY, f.bridge.texts())
            assertTrue(f.workspace.updateSourceEditText(lava.file, f.bridge.texts().getValue(lava.file).replaceRange(lava.words[0].span, "1"))); render()
            assertEquals("0.00091552734375", value(Rates.LAVA_KEY))
            assertTrue(input(Rates.LAVA_KEY).config.contains(SemanticsProperties.Disabled))
            assertFalse(input(Rates.HEAT_KEY).config.contains(SemanticsProperties.Disabled))
            assertTrue(f.workspace.undoSourceEdit(lava.file), f.workspace.error); render()
            assertEquals("45", value(Rates.LAVA_KEY)); assertFalse(input(Rates.LAVA_KEY).config.contains(SemanticsProperties.Disabled))
            patch.configData = mutableMapOf(Rates.LAVA_KEY to 99); patch.enabled = false; f.editor.togglePatch(patch.id)
            render(); assertTrue(input(Rates.LAVA_KEY).config.contains(SemanticsProperties.Disabled))
            assertTrue(tag("environmental-use-asm-${Rates.LAVA_KEY}").config[SemanticsActions.OnClick].action!!.invoke()); render()
            assertNull(patch.configData); assertFalse(input(Rates.LAVA_KEY).config.contains(SemanticsProperties.Disabled))
        } finally { scene.close(); surface.close(); selectEnvironmentalDamage(null) }
    }
}
