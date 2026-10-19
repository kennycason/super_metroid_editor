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
import kotlinx.coroutines.CompletableDeferred
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
        val bindings = (PHYSICS_PATCH_DEFINITIONS.flatMap(::physicsBindings) + SHORT_CHARGE_BINDINGS).distinct()
        for ((file, entries) in DATA_PATCH_DEFINITIONS.flatMap { def -> def.sites.map { def to it } }.groupBy { it.second.file }) {
            put(file, buildString {
                val grouped = entries.groupBy { it.second.symbol }
                val extra = bindings.filter { it.file == file }.groupBy { it.symbol }
                for (symbol in grouped.keys + extra.keys) {
                    val sites = grouped[symbol].orEmpty()
                    appendLine("$symbol:")
                    if (sites.isNotEmpty() && sites.all { it.second.op in PATCH_DATA_OPS }) {
                        val op = sites.first().second.op
                        for (index in 0..maxOf(sites.maxOf { it.second.index }, if (op == "db") 8 else 0)) {
                            val site = sites.firstOrNull { it.second.index == index }?.second
                            val token = site?.nativeExpression ?: if (sites.first().first.type == SHORT_CHARGE_CONFIG_TYPE)
                                patchHex(com.supermetroid.editor.rom.ShortChargePatch.defaults[index], 2) else when (op) {
                                "db" -> if (index == 5) "regional(${ '$' }10, ${ '$' }0C)" else "0"
                                "dw" -> "regional(${ '$' }003C, ${ '$' }0034)"
                                else -> "0"
                            }
                            appendLine(" $op $token")
                        }
                    } else {
                    for ((def, site) in sites) {
                        val value = when (def.type) {
                            "ceres_escape_seconds" -> 0x0100
                            ZEBES_ESCAPE_CONFIG_TYPE -> 0x0300
                            FANFARE_CONFIG_TYPE -> 360
                            ControllerBindings.CONFIG_TYPE -> ControllerBindings.defaults.getValue(def.key)
                            else -> when (def.key) { BOMB_MAX_ACTIVE_KEY -> 5; BOMB_FUSE_FRAMES_KEY -> 60; BOMB_COOLDOWN_FRAMES_KEY -> 16; else -> 1 }
                        }
                            site.before.forEach { appendLine(" $it") }
                            val token = site.nativeExpression ?: patchHex(if (!site.immediate) 2 else value, site.size)
                            appendLine(" ${site.op} ${if (site.immediate) "#" else ""}$token${if (site.immediate) "" else ",X"}")
                            site.consumer?.let { appendLine(" $it") }
                    }
                    }
                    extra[symbol].orEmpty().forEach { binding -> binding.instructions.forEach { appendLine(" $it") } }
                    if (sites.none { it.second.op in PATCH_DATA_OPS }) appendLine(" RTS")
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
        put("bank_94.asm", "NormalSpeedBinding:\n LDA.W #SamusXSpeedTable_Normal\n STA.W XSpeedTablePointer\n RTS\n")
        put("main.asm", "!PAL ?= 0\n" + keys.joinToString("\n") { "incsrc $it" } + "\nincsrc bank_8F.asm\n")
        put("macros.asm", "function regional(value_ntsc, value_pal) = select(!PAL, value_pal, value_ntsc)\n")
    }
    @Test fun `renamed native tables retain every numerical field consumer and navigation binding`() {
        val source = File(directory, "rename-source").apply { mkdirs() }
        val before = originals
        before.forEach { (file, text) -> File(source, file).writeText(text) }
        val augmented = AsmSourceAugmentation("rename-test", renames = listOf(
            AsmSourceRename("SpeedBoostTimerResetValues", "SpeedBoostTimerReloadTable"),
            AsmSourceRename("SamusXSpeedTable_Normal", "SamusNormalMovementSpeeds"),
        )).apply(source)
        val original = AsmDataPatchSource(before)
        val renamed = AsmDataPatchSource(augmented.texts, AsmSourceSymbolNames(augmented.symbolNames))
        for (definition in DATA_PATCH_DEFINITIONS) {
            val old = original.field(definition.id, before)
            val current = renamed.field(definition.id, augmented.texts)
            assertEquals(old.values, current.values, definition.id)
            assertEquals(original.guards(old, before), renamed.guards(current, augmented.texts), definition.id)
            val (file, line) = renamed.line(definition.id, augmented.texts)
            assertTrue(renamed.editorLinks(file, line, augmented.texts).any {
                it.target == AsmEditorTarget.PatchData(definition.type, definition.key)
            }, definition.id)
        }
    }

    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState) {
        val bridge get() = assertNotNull(editor.dataPatchBridge)
        val project get() = editor.project
        fun patch(type: String) = editor.findOrCreateConfigPatch(type)
    }
    private suspend fun fixture(
        progressive: Boolean = false,
        createWorkspace: (AsmProjectWorkspaceRepository) -> AsmWorkspaceState = { AsmWorkspaceState(projectRepository = it) },
    ): Fixture {
        val root = File(directory, "reference"); val src = File(root, "src").apply { mkdirs() }; val data = File(root, "data").apply { mkdirs() }
        originals.forEach { (file, text) -> File(src, file).writeText(text) }; File(src, PLM_SOURCE_FILE).writeText("; Unrelated\n")
        val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0), AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = createWorkspace(repository).apply { bindProject(file.path, true, progressive = progressive) }
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), RomParser(ByteArray(0x300000)), emptyMap()), workspace)
        return Fixture(editor, workspace)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `ASM tab renders during progressive startup and links recover after indexing`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val f = fixture(progressive = true) { repository ->
            AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
                release.await(); repository.load(path)
            })
        }
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1000, 800)
        try {
            assertFalse(assertNotNull(f.workspace.projectWorkspace).indexComplete)
            assertNull(f.workspace.workspace?.index?.file("bank_80.asm"))
            assertTrue(f.workspace.sourceIndexingInProgress)
            f.workspace.openSource(PLM_SOURCE_FILE, 0)
            val before = Json.encodeToString(SmEditProject.serializer(), f.project)
            val revision = f.workspace.sourceBufferRevision
            val liveLinks = assertNotNull(f.workspace.sourceEditorLinks)
            var linkLookups = 0
            f.workspace.sourceEditorLinks = { file, line -> linkLookups++; liveLinks(file, line) }
            scene.constraints = Constraints.fixed(1000, 800)
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemeState()) {
                    MaterialTheme { AsmWorkspaceCanvas(f.workspace) }
                }
            }
            var time = System.nanoTime()
            suspend fun render() { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                scene.render(surface.canvas, time); yield()
            } }
            render() // Exercise the real link resolver called during Compose measurement.
            assertTrue(assertNotNull(f.workspace.sourceEditorLinks)(PLM_SOURCE_FILE, 0).isEmpty())
            for (file in f.bridge.catalog.originals.keys) assertTrue(f.bridge.editorLinks(file, 0).isEmpty(), file)
            assertFailsWith<IllegalArgumentException> { f.workspace.projectSourceText("bank_80.asm") }
            assertFailsWith<IllegalArgumentException> { f.bridge.editValues("ceres_escape_seconds", mapOf("seconds" to 95)) }
            assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(f.workspace.unsavedSourceFileIds.isEmpty())
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))

            val lookupsBeforeIndex = linkLookups
            release.complete(Unit)
            f.workspace.awaitSourceIndexing()
            render() // The same canvas must survive the partial -> complete transition.
            assertTrue(linkLookups > lookupsBeforeIndex, "Recheck cached links without moving the caret or reopening the tab")
            assertTrue(assertNotNull(f.workspace.projectWorkspace).indexComplete)
            val target = f.bridge.line("ceres_escape_seconds", "seconds")
            f.workspace.openSource(target.first, target.second)
            render()
            assertEquals(listOf(AsmEditorTarget.PatchData("ceres_escape_seconds", "seconds")),
                assertNotNull(f.workspace.sourceEditorLinks)(target.first, target.second).map { it.target })
            assertTrue(f.workspace.unsavedSourceFileIds.isEmpty())
            assertEquals(before, Json.encodeToString(SmEditProject.serializer(), f.project))
        } finally {
            scene.close(); surface.close()
            release.complete(Unit); f.workspace.awaitSourceIndexing()
        }
    }

    @Test fun `navigation remains optional after index failure and recovers on retry`() = runBlocking {
        var fail = true
        val f = fixture(progressive = true) { repository ->
            AsmWorkspaceState(projectRepository = repository, sourceIndexLoader = { path ->
                if (fail) error("broken index fixture") else repository.load(path)
            })
        }
        f.workspace.awaitSourceIndexing()
        val problem = f.workspace.error
        assertEquals("broken index fixture", f.workspace.sourceIndexFailure)
        val links = assertNotNull(f.workspace.sourceEditorLinks)
        assertTrue(links(PLM_SOURCE_FILE, 0).isEmpty())
        assertTrue(links("bank_80.asm", 0).isEmpty())
        assertEquals(problem, f.workspace.error, "Optional navigation must not clear the index failure")
        assertFailsWith<IllegalArgumentException> { f.bridge.texts() }
        fail = false
        f.workspace.rebuildSourceIndex()
        f.workspace.awaitSourceIndexing()
        val target = f.bridge.line("ceres_escape_seconds", "seconds")
        assertTrue(links(target.first, target.second).isNotEmpty())
        assertNull(f.workspace.sourceIndexFailure)
        assertTrue(f.workspace.unsavedSourceFileIds.isEmpty())
    }

    @Test fun `all finite codecs are readable and inspection and unchanged edits do not dirty the project`() = runBlocking {
        val f = fixture(); val before = Json.encodeToString(SmEditProject.serializer(), f.project); val revision = f.workspace.sourceBufferRevision
        assertEquals(DATA_PATCH_DEFINITIONS.size, f.bridge.catalog.definitions.size)
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

    @Test fun `short charge stage edits preserve packed timer and all later delays through undo and toggles`() = runBlocking {
        val f = fixture(); val patch = f.patch(SHORT_CHARGE_CONFIG_TYPE)
        val id = dataPatchKey(SHORT_CHARGE_CONFIG_TYPE, "stages")
        val original = f.workspace.projectSourceText("bank_91.asm")
        val words = f.bridge.catalog.field(id, f.bridge.texts()).words
        var custom = original
        val authored = listOf(7, 3, 5, 7, 9)
        for ((word, value) in words.zip(authored).reversed()) custom = custom.replaceRange(word.span, patchHex(value, 2))
        assertTrue(f.workspace.updateSourceEditText("bank_91.asm", custom), f.workspace.error)
        f.editor.setPatchConfigValue(patch.id, 2)
        fun table() = f.bridge.catalog.field(id, f.bridge.texts()).values
        val expected = listOf(0x0207, 3, 5, 7, 9)
        assertEquals(expected, table()); assertNull(patch.configValue); assertNull(patch.configData); assertTrue(patch.enabled)
        assertTrue(f.workspace.undoSourceEdit("bank_91.asm")); assertEquals(authored, table())
        assertTrue(f.workspace.redoSourceEdit("bank_91.asm")); assertEquals(expected, table())
        f.editor.requestTogglePatch(patch.id); assertNotNull(f.editor.pendingPatchDisable); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled); assertEquals(listOf(1, 1, 1, 1, 2), table())
        val saved = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(expected, saved.asmWorkspace.disabledPatchValues[SHORT_CHARGE_CONFIG_TYPE]?.get("stages"))
        f.editor.requestTogglePatch(patch.id); assertTrue(patch.enabled); assertEquals(expected, table())
        val line = f.bridge.line(SHORT_CHARGE_CONFIG_TYPE, "stages")
        assertEquals(listOf(AsmEditorTarget.PatchData(SHORT_CHARGE_CONFIG_TYPE, "stages")), f.bridge.editorLinks(line.first, line.second).map { it.target })
    }

    @Test fun `short charge raw edits retire scalar stages and taps together and undo restores disabled aliases`() = runBlocking {
        val f = fixture(); val patch = f.patch(SHORT_CHARGE_CONFIG_TYPE)
        patch.configValue = 3; patch.configData = mutableMapOf("stages" to 3, "taps" to 3)
        val word = f.bridge.catalog.field(dataPatchKey(SHORT_CHARGE_CONFIG_TYPE, "stages"), f.bridge.texts()).words.first()
        val before = f.workspace.projectSourceText("bank_91.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_91.asm", before.replaceRange(word.span, "\$0201")))
        assertNull(patch.configValue); assertNull(patch.configData); assertTrue(patch.enabled)
        assertEquals(2, f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").value)
        assertTrue(f.workspace.undoSourceEdit("bank_91.asm"))
        assertFalse(patch.enabled); assertEquals(3, patch.configValue)
        assertEquals(mapOf("stages" to 3, "taps" to 3), patch.configData?.toMap())
        f.editor.setPatchConfigData(patch.id, "taps", 1)
        assertEquals(1, f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").value)
        assertNull(patch.configData); assertNull(patch.configValue); assertTrue(patch.enabled)
    }

    @Test fun `short charge invalid drafts and changed transition logic lock its control without coercion`() = runBlocking<Unit> {
        val f = fixture(); val patch = f.patch(SHORT_CHARGE_CONFIG_TYPE)
        f.editor.setPatchConfigValue(patch.id, 2)
        val id = dataPatchKey(SHORT_CHARGE_CONFIG_TYPE, "stages")
        val word = f.bridge.catalog.field(id, f.bridge.texts()).words.first()
        val valid = f.workspace.projectSourceText("bank_91.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_91.asm", valid.replaceRange(word.span, "CustomCounter")))
        assertEquals(2, f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").value)
        assertNotNull(f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").problem)
        assertNull(f.bridge.view(PHYSICS_CONFIG_TYPE, "gravity").problem)
        assertTrue(f.workspace.updateSourceEditText("bank_91.asm", valid))
        val source = f.workspace.projectSourceText("bank_90.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", source.replace("ADC.W #\$0100", "ADC.W #\$0200")))
        assertNotNull(f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").problem)
        val blocked = f.workspace.sourceBufferRevision
        f.editor.setPatchConfigValue(patch.id, 1); assertEquals(blocked, f.workspace.sourceBufferRevision)
    }

    @Test fun `physics byte edits preserve complete native words and swapped dwords with reciprocal undo`() = runBlocking {
        val f = fixture(); val type = PHYSICS_CONFIG_TYPE; val patch = f.patch(type)
        val source = f.bridge.texts().getValue("bank_90.asm")
            .replace("regional(\$1C00, \$2800)", "\$1C35")
            .replace("wordSwap(\$02C0*!SPF*\$100)", "\$C1230002")
            .replace("wordSwap(\$0030*!SPF*\$100)", "\$30120000")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", source), f.workspace.error)
        assertTrue(patch.enabled)
        f.editor.setNativePatchValues(patch.id, mapOf("jump_height" to 6, "gravity" to 40, "run_max" to 3, "run_accel" to 0x38,
            "air_spin" to 2, "air_normal" to 2, "max_fall" to 7, "air_physics" to 4))
        fun raw(key: String) = f.bridge.catalog.field(dataPatchKey(type, key), f.bridge.texts()).values.single()
        assertEquals(6, raw("jump_height")); assertEquals(0x2835, raw("gravity"))
        assertEquals(0xC1230003.toInt(), raw("run_max")); assertEquals(0x38120000, raw("run_accel"))
        assertEquals(0x60000002, raw("air_spin")); assertEquals(0x40000002, raw("air_normal"))
        assertEquals(7, raw("max_fall")); assertEquals(4, raw("air_physics")); assertNull(patch.configData)
        assertTrue(f.workspace.undoSourceEdit("bank_90.asm"), f.workspace.error)
        assertEquals(source, f.workspace.projectSourceText("bank_90.asm"))
        assertTrue(f.workspace.redoSourceEdit("bank_90.asm"), f.workspace.error)
        assertEquals(0x2835, raw("gravity"))
        val (file, line) = f.bridge.line(type, "run_max")
        assertTrue(f.bridge.editorLinks(file, line).any { it.target == AsmEditorTarget.PatchData(type, "run_max") })
        f.editor.requestTogglePatch(patch.id); assertNotNull(f.editor.pendingPatchDisable); f.editor.confirmPatchDisable()
        assertFalse(patch.enabled); assertEquals(0xC0000002.toInt(), raw("run_max")); assertEquals(0x1C00, raw("gravity"))
        val serialized = Json.encodeToString(SmEditProject.serializer(), f.project)
        val restored = Json.decodeFromString<SmEditProject>(serialized)
        assertEquals(listOf(0xC1230003.toInt()), restored.asmWorkspace.disabledPatchValues[type]?.get("run_max"))
        f.editor.requestTogglePatch(patch.id); assertTrue(patch.enabled)
        assertEquals(0xC1230003.toInt(), raw("run_max")); assertEquals(0x2835, raw("gravity"))
    }

    @Test fun `physics source edits retire enabled and disabled legacy values in the same undo transaction`() = runBlocking {
        val f = fixture(); val patch = f.patch(PHYSICS_CONFIG_TYPE)
        patch.configData = mutableMapOf("jump_height" to 7, "gravity" to 30)
        val before = f.workspace.projectSourceText("bank_90.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", before.replace("\$04E0*!SPF/\$100", "\$0006")))
        assertEquals(mapOf("gravity" to 30), patch.configData?.toMap())
        assertTrue(patch.enabled); assertEquals(6, f.bridge.view(PHYSICS_CONFIG_TYPE, "jump_height").value)
        assertTrue(f.workspace.undoSourceEdit("bank_90.asm"), f.workspace.error)
        assertFalse(patch.enabled); assertEquals(mapOf("jump_height" to 7, "gravity" to 30), patch.configData?.toMap())
    }

    @Test fun `physics unsupported drafts keep last good values and only lock associated controls`() = runBlocking {
        val f = fixture(); val patch = f.patch(PHYSICS_CONFIG_TYPE)
        f.editor.setPatchConfigData(patch.id, "jump_height", 6)
        val valid = f.workspace.projectSourceText("bank_90.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", valid.replaceFirst("dw \$0006", "dw CustomJump")))
        val stale = f.bridge.view(PHYSICS_CONFIG_TYPE, "jump_height")
        assertEquals(6, stale.value); assertNotNull(stale.problem)
        assertNull(f.bridge.view(PHYSICS_CONFIG_TYPE, "gravity").problem)
        assertFails { f.bridge.editValues(PHYSICS_CONFIG_TYPE, mapOf("jump_height" to 8)) }
        f.editor.requestTogglePatch(patch.id); f.editor.confirmPatchDisable()
        assertTrue(patch.enabled); assertContains(f.editor.statusMessage, "blocked")
        f.editor.setPatchConfigData(patch.id, "gravity", 40)
        assertEquals(40, f.bridge.view(PHYSICS_CONFIG_TYPE, "gravity").value)
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", valid.replaceFirst("dw \$0006", "dw \$0106")))
        val large = f.bridge.view(PHYSICS_CONFIG_TYPE, "jump_height")
        assertNull(large.value); assertContains(large.problem.orEmpty(), "managed in ASM")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", valid))
        assertNull(f.bridge.view(PHYSICS_CONFIG_TYPE, "jump_height").problem)
    }

    @Test fun `physics changed consumers and region definitions are read only and cannot be overwritten`() = runBlocking<Unit> {
        val f = fixture(); val type = PHYSICS_CONFIG_TYPE
        val source = f.workspace.projectSourceText("bank_90.asm")
        val retargeted = source.replace("LDA.W SamusPhysicsConstants_InitialYSpeeds_Jumping,X", "LDA.W CustomJumpTable,X")
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", retargeted))
        assertNotNull(f.bridge.view(type, "jump_height").problem)
        assertNull(f.bridge.view(type, "hijump_height").problem)
        assertTrue(f.workspace.updateSourceEditText("bank_90.asm", source))
        val bank94 = f.workspace.projectSourceText("bank_94.asm")
        assertTrue(f.workspace.updateSourceEditText("bank_94.asm", bank94.replace("#SamusXSpeedTable_Normal", "#OtherSpeedTable")))
        assertNotNull(f.bridge.view(type, "run_max").problem)
        assertNull(f.bridge.view(type, "jump_height").problem)
        assertTrue(f.workspace.updateSourceEditText("bank_94.asm", bank94))
        val main = f.workspace.projectSourceText("main.asm")
        assertTrue(f.workspace.updateSourceEditText("main.asm", main.replace("!PAL ?= 0", "!PAL ?= 1")))
        assertNotNull(f.bridge.view(type, "gravity").problem)
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
            val physics = f.patch(PHYSICS_CONFIG_TYPE); f.editor.selectPatch(physics.id); render()
            val untouched = f.workspace.sourceBufferRevision
            input("jump_height", "256"); render(); assertEquals(untouched, f.workspace.sourceBufferRevision)
            input("jump_height", "6"); render()
            assertEquals(6, f.bridge.view(PHYSICS_CONFIG_TYPE, "jump_height").value)
            click("native-patch-asm-jump_height"); render(); assertEquals("bank_90.asm", f.workspace.selectedFileId)
            val selected = f.bridge.line(PHYSICS_CONFIG_TYPE, "jump_height")
            assertEquals(listOf(AsmEditorTarget.PatchData(PHYSICS_CONFIG_TYPE, "jump_height")), f.bridge.editorLinks(selected.first, selected.second).map { it.target })
            val text = f.workspace.projectSourceText("bank_90.asm")
            assertTrue(f.workspace.updateSourceEditText("bank_90.asm", text.replaceFirst("dw \$0006", "dw \$0007")))
            render(); assertEquals("7", tag("native-patch-jump_height").config[SemanticsProperties.EditableText].text)
            assertTrue(f.workspace.undoSourceEdit("bank_90.asm")); render()
            assertEquals("6", tag("native-patch-jump_height").config[SemanticsProperties.EditableText].text)
            val charge = f.patch(SHORT_CHARGE_CONFIG_TYPE); f.editor.selectPatch(charge.id); render()
            val inspected = f.workspace.sourceBufferRevision
            click("native-short-charge-4"); render(); assertEquals(inspected, f.workspace.sourceBufferRevision); assertFalse(charge.enabled)
            click("native-short-charge-2"); render(); assertTrue(charge.enabled)
            assertEquals(2, f.bridge.view(SHORT_CHARGE_CONFIG_TYPE, "stages").value)
            click("native-patch-asm-stages"); render(); assertEquals("bank_91.asm", f.workspace.selectedFileId)
            val packed = f.bridge.catalog.field(dataPatchKey(SHORT_CHARGE_CONFIG_TYPE, "stages"), f.bridge.texts()).words.first()
            assertTrue(f.workspace.updateSourceEditText("bank_91.asm", f.workspace.projectSourceText("bank_91.asm").replaceRange(packed.span, "\$0307")))
            render(); assertTrue(tag("native-short-charge-1").config[SemanticsProperties.Selected])
            click("patch-header-toggle"); render(); click("patch-disable-confirm"); render()
            assertTrue(tag("native-short-charge-4").config[SemanticsProperties.Selected])
            click("patch-header-toggle"); render(); assertTrue(tag("native-short-charge-1").config[SemanticsProperties.Selected])
            val doors = f.project.patches.single { it.id == AsmFastDoorsModule.PATCH_ID }
            f.editor.selectPatch(doors.id); render()
            assertFalse(f.editor.fastDoorsBridge!!.installed, "Inspection does not install a module")
            assertTrue(tag("fast-doors-asm").config.contains(SemanticsProperties.Disabled))
            click("patch-header-toggle"); render(); assertTrue(doors.enabled, f.editor.statusMessage)
            click("fast-doors-asm"); render(); assertEquals(AsmFastDoorsModule.FILE, f.workspace.selectedFileId)
            val customDoors = f.workspace.projectSourceText(AsmFastDoorsModule.FILE) + "; Keep my notes\n"
            assertTrue(f.workspace.updateSourceEditText(AsmFastDoorsModule.FILE, customDoors)); render()
            click("patch-header-toggle"); render(); assertFalse(doors.enabled)
            assertNull(f.editor.pendingPatchDisable, "Code is retained, so disabling needs no discard confirmation")
            assertContains(f.workspace.projectSourceText(AsmFastDoorsModule.FILE), "; Keep my notes")
            assertTrue(f.workspace.undoSourceEdit(AsmFastDoorsModule.FILE)); render(); assertTrue(doors.enabled)
        } finally { scene.close(); surface.close() }
    }
}
