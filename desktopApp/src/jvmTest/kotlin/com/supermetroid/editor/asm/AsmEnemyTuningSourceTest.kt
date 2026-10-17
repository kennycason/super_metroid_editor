package com.supermetroid.editor.asm

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.EnemyTabCanvas
import com.supermetroid.editor.ui.selectEnemyTuning
import com.supermetroid.editor.ui.ENEMY_DEFS
import com.supermetroid.editor.ui.EditorTheme
import com.supermetroid.editor.ui.EditorThemeState
import com.supermetroid.editor.ui.FontSize
import com.supermetroid.editor.ui.LocalEditorTheme
import com.supermetroid.editor.ui.materialTypography
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.jetbrains.skia.Surface
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmEnemyTuningSourceTest {
    @TempDir lateinit var directory: File
    private val headers = """
        EnemyHeaders_TestZoomer: ;A0DCFF;
            %EnemyHeader(\
            %health(15), ; keep HP note
            %damage(5),
            %drops(EnemyDropChances_Test),
            %vulnerabilities(EnemyVulnerabilities_Test),
            %name(0))
        EnemyHeaders_TestRipper: ;A0D47F;
            %EnemyHeader(\
            %health(200),
            %damage(5),
            %drops(EnemyDropChances_Alias),
            %vulnerabilities(EnemyVulnerabilities_Alias),
            %name(0))
    """.trimIndent() + "\n"
    private val drops = "EnemyDropChances_Test:\nEnemyDropChances_Alias:\n    db ${'$'}37, ${'$'}19, ${'$'}AA, ${'$'}00, ${'$'}05, ${'$'}00 ; keep drop note\nNext_Table:\n    db 0\n" +
        "EnemyVulnerabilities_Test:\nEnemyVulnerabilities_Alias:\n" +
        "    db ${'$'}00, ${'$'}80, ${'$'}82, ${'$'}FF, 2, 2, 2, 2, 2, 2, 2, 2 ; keep beam note\n" +
        "    db 2, 2, 4, 4, 2, 2, 2, 2, 2, 0 ; keep unused byte\n"

    private data class Fixture(val editor: EditorState, val workspace: AsmWorkspaceState, val bridge: AsmEnemyTuningBridge, val parser: RomParser) {
        val project get() = editor.project
        fun a() = workspace.projectSourceText(ENEMY_HEADER_SOURCE_FILE)
        fun b() = workspace.projectSourceText(ENEMY_DROP_SOURCE_FILE)
    }
    private suspend fun fixture(headerText: String = headers, tableText: String = drops): Fixture {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_A0.asm\nincsrc bank_B4.asm\nincsrc bank_8F.asm\n")
        File(src, ENEMY_HEADER_SOURCE_FILE).writeText(headerText.replace("\n", "\r\n"))
        File(src, ENEMY_DROP_SOURCE_FILE).writeText(tableText.replace("\n", "\r\n"))
        File(src, PLM_SOURCE_FILE).writeText("; Unrelated room source\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList()).also { it.initialize(file.path, reference) }
        val workspace = AsmWorkspaceState(projectRepository = repository).apply { bindProject(file.path, true) }
        val parser = RomParser(ByteArray(0x300000))
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE }
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), parser, emptyMap()), workspace)
        return Fixture(editor, workspace, assertNotNull(editor.enemyTuningBridge), parser)
    }

    private suspend fun withVulnerabilityEditor(f: Fixture, width: Int = 2300, height: Int = 1000, font: FontSize = FontSize.MEDIUM,
        captureName: String = "vulnerability",
        check: suspend (ComposeScene, suspend () -> Unit, List<Int>) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(width, height)
        val opened = mutableListOf<Int>()
        try {
            scene.constraints = Constraints.fixed(width, height)
            selectEnemyTuning(0, AsmEnemyTuningSection.VULNERABILITIES)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme, typography = font.materialTypography()) {
                        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                            EnemyTabCanvas(f.editor, f.parser, onNavigateToAsm = opened::add)
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                // Focus/scroll actions run outside composition; make their state
                // notifications deterministic before advancing the headless frame.
                Snapshot.sendApplyNotifications()
                yield()
                surface.canvas.clear(0xFF1A1A1A.toInt())
                time += 16_666_667; scene.render(surface.canvas, time)
                yield()
            }; Unit }
            render()
            check(scene, render, opened)
            if (System.getenv("SMEDIT_CAPTURE_VULNERABILITY_UI") == "true") {
                val output = File("build/test-output/$captureName-${font.name}-${width}.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { snapshot -> snapshot.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            }
        } finally {
            scene.close(); surface.close()
            selectEnemyTuning(0, AsmEnemyTuningSection.STATS)
        }
    }
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun input(scene: ComposeScene, key: String) = tagged(scene, "enemy-vulnerability-$key")
    private fun inputText(scene: ComposeScene, key: String) = input(scene, key).config[SemanticsProperties.EditableText].text
    private fun clickInput(scene: ComposeScene, key: String) {
        val point = input(scene, key).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
    }
    private suspend fun awaitFrame(render: suspend () -> Unit, condition: () -> Boolean) = withTimeout(2000) {
        while (!condition()) { render(); delay(1) }
    }

    private suspend fun chartFixture(): Fixture {
        val extra = ENEMY_DEFS.filter { it.speciesId !in setOf(0xDCFF, 0xD47F) }
        val allHeaders = headers + extra.joinToString("\n") { enemy ->
            "EnemyHeaders_${enemy.key}: ;A0${enemy.speciesId.toString(16).uppercase()};\n" +
                "    %health(15),\n    %damage(5),\n    %drops(EnemyDropChances_Test),\n" +
                "    %vulnerabilities(EnemyVulnerabilities_${enemy.key}),\n"
        } + "\n"
        val allTables = drops + extra.joinToString("\n") { enemy ->
            "EnemyVulnerabilities_${enemy.key}:\n" + drops.substringAfter("EnemyVulnerabilities_Alias:\n")
        }
        return fixture(allHeaders, allTables)
    }

    @Test fun `focusing a vulnerability highlights only physical table peers across categories without editing source`() = runBlocking {
        val f = chartFixture()
        val before = f.b(); val a = f.a(); val revision = f.workspace.sourceBufferRevision
        withVulnerabilityEditor(f, captureName = "vulnerability-shared-focus") { scene, render, _ ->
            val model = Json.encodeToString(SmEditProject.serializer(), f.project)
            val dirty = f.editor.dirty
            fun selected(key: String) = tagged(scene, "enemy-vulnerability-row-$key").config[SemanticsProperties.Selected]
            suspend fun focus(key: String) {
                assertTrue(input(scene, key).config[SemanticsActions.RequestFocus].action!!.invoke())
                awaitFrame(render) { selected(key.substringBeforeLast('_')) }
                render()
            }
            assertFalse(selected("zoomer")); assertFalse(selected("ripper"))
            clickInput(scene, "zoomer_vuln1")
            awaitFrame(render) { selected("zoomer") }
            assertTrue(selected("zoomer")); assertTrue(selected("ripper")); assertFalse(selected("geemer_horiz"))
            assertTrue(nodes(scene).none { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("Shared table:") } })
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
            assertEquals(dirty, f.editor.dirty)
            assertEquals(before, f.b()); assertEquals(a, f.a()); assertEquals(revision, f.workspace.sourceBufferRevision)
            focus("geemer_horiz_vuln1")
            assertTrue(selected("geemer_horiz")); assertFalse(selected("zoomer")); assertFalse(selected("ripper"))
            focus("ripper_vuln1")
            assertTrue(selected("zoomer")); assertTrue(selected("ripper")); assertFalse(selected("geemer_horiz"))
            focus("zoomer_vuln1")
            // Rebinding ASM while focused must update peer highlighting, not retain a stale group.
            assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE,
                a.replace("%vulnerabilities(EnemyVulnerabilities_Test)", "%vulnerabilities(EnemyVulnerabilities_geemer_horiz)")))
            awaitFrame(render) { selected("geemer_horiz") && !selected("ripper") }
            render()
            assertTrue(selected("zoomer")); assertTrue(selected("geemer_horiz")); assertFalse(selected("ripper"))
        }
    }

    @Test fun `ROM mode shared rows highlight and update together without source ownership`() = runBlocking {
        val f = chartFixture()
        f.project.asmWorkspace.enabled = false
        val rom = f.parser.getRomData()
        for ((species, pointer) in listOf(0xDCFF to 0xF900, 0xD47F to 0xF900, 0xDC3F to 0xFA00)) {
            val pc = f.parser.snesToPc(0xA00000 or species) + 0x3C
            rom[pc] = pointer.toByte(); rom[pc + 1] = (pointer ushr 8).toByte()
        }
        val patch = f.editor.findOrCreateConfigPatch("enemy_vuln")
        val before = f.b(); val revision = f.workspace.sourceBufferRevision
        withVulnerabilityEditor(f) { scene, render, _ ->
            clickInput(scene, "zoomer_vuln1"); render()
            awaitFrame(render) { tagged(scene, "enemy-vulnerability-row-ripper").config[SemanticsProperties.Selected] }
            assertTrue(tagged(scene, "enemy-vulnerability-row-ripper").config[SemanticsProperties.Selected])
            assertFalse(tagged(scene, "enemy-vulnerability-row-geemer_horiz").config[SemanticsProperties.Selected])
            assertTrue(input(scene, "zoomer_vuln1").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("255"))); render()
            assertEquals("255", inputText(scene, "ripper_vuln1"))
            assertEquals(255, patch.configData!!["ripper_vuln1"])
            assertNull(patch.configData!!["geemer_horiz_vuln1"])
            assertEquals(before, f.b()); assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        }
    }

    @Test fun `diagonal headings fit every font and a pinned scrollbar reaches the final weapon in narrow panels`() = runBlocking {
        val f = chartFixture()
        for (font in FontSize.entries) withVulnerabilityEditor(f, width = 700, height = 650, font = font) { scene, render, _ ->
            val viewport = tagged(scene, "enemy-vulnerability-horizontal-viewport")
            val range = viewport.config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(range.maxValue() > 0, "Narrow $font chart must expose a real scroll range")
            val headers = tagged(scene, "enemy-vulnerability-headers-Crawler")
            val headerNodes = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("enemy-vulnerability-header-") == true }
                .take(22)
            assertEquals(22, headerNodes.size)
            for (header in headerNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(header.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertEquals(1, layouts.single().lineCount)
                assertFalse(layouts.single().hasVisualOverflow, "$font angled labels must not wrap/clip")
                val size = layouts.single().size
                val paintedTop = header.positionInRoot.y + size.height - (size.width + size.height) / kotlin.math.sqrt(2f)
                assertTrue(paintedTop >= headers.positionInRoot.y - 1f, "$font reserve the rotated heading's full height")
            }
            val bar = tagged(scene, "enemy-vulnerability-horizontal-scrollbar").boundsInRoot
            assertTrue(bar.top > 600f, "$font scrollbar must remain at the panel bottom")
            assertTrue(tagged(scene, "enemy-vulnerability-row-zoomer").boundsInRoot.height < 65f, "$font ordinary rows stay compact")
            val start = Offset(bar.left + 5f, bar.center.y)
            val end = Offset(bar.right - 3f, bar.center.y)
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary); render()
            val scrolled = tagged(scene, "enemy-vulnerability-horizontal-viewport").config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(scrolled.value() >= scrolled.maxValue() - 2f, "$font dragging to the right reaches the end")
            val last = input(scene, "zoomer_vuln21").boundsInRoot
            assertTrue(last.width > 30f && last.right <= viewport.boundsInRoot.right + 1f, "$font final column is visible")
            assertEquals(0, f.bridge.view("zoomer_vuln21").value)
            assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        }
    }

    @Test fun `vulnerability canvas edits source shared rows refresh on raw typing and undo and per enemy links inspect only`() = runBlocking {
        val f = fixture()
        val patch = f.editor.findOrCreateConfigPatch("enemy_vuln")
        val before = f.b(); val revision = f.workspace.sourceBufferRevision
        withVulnerabilityEditor(f) { scene, render, opened ->
            assertEquals("128", inputText(scene, "zoomer_vuln1"))
            assertEquals("128", inputText(scene, "ripper_vuln1"))
            assertTrue(tagged(scene, "enemy-vulnerability-source-zoomer").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(listOf(0xA0DCFF), opened)
            assertEquals(ENEMY_DROP_SOURCE_FILE, f.workspace.selectedFileId)
            assertEquals(f.bridge.line("zoomer_vuln0").second, f.workspace.selectedLineIndex)
            assertEquals(revision, f.workspace.sourceBufferRevision)
            assertEquals(before, f.b()); assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())

            assertTrue(input(scene, "zoomer_vuln1").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("255")))
            render()
            assertEquals("255", inputText(scene, "ripper_vuln1"))
            assertEquals(255, f.bridge.view("zoomer_vuln1").value)
            assertNull(patch.configData)
            val raw = f.bridge.catalog.edit("zoomer_vuln1", 130, f.a(), f.b()).second
            assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, raw))
            render()
            assertEquals("130", inputText(scene, "zoomer_vuln1"))
            assertEquals("130", inputText(scene, "ripper_vuln1"))
            assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
            render()
            assertEquals("255", inputText(scene, "zoomer_vuln1"))
        }
    }

    @Test fun `invalid source grays vulnerability cells with last good values and source repair reenables them`() = runBlocking {
        val f = fixture()
        withVulnerabilityEditor(f) { scene, render, _ ->
            val good = f.b()
            assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, good.replace("${'$'}00, ${'$'}80,", "${'$'}00, unfinished,")))
            render()
            assertEquals("128", inputText(scene, "zoomer_vuln1"))
            assertTrue(input(scene, "zoomer_vuln1").config.contains(SemanticsProperties.Disabled))
            assertTrue(input(scene, "ripper_vuln21").config.contains(SemanticsProperties.Disabled))
            val revision = f.workspace.sourceBufferRevision
            assertTrue(tagged(scene, "enemy-vulnerability-source-zoomer").config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(ENEMY_DROP_SOURCE_FILE, f.workspace.selectedFileId)
            assertContains(f.b().lineSequence().elementAt(f.workspace.selectedLineIndex), "unfinished")
            assertEquals(revision, f.workspace.sourceBufferRevision)
            assertTrue(nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                it.text.startsWith("Last-good / read-only:") && "byte literals" in it.text
            } })
            assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, good))
            render()
            assertFalse(input(scene, "zoomer_vuln1").config.contains(SemanticsProperties.Disabled))
            assertEquals("128", inputText(scene, "zoomer_vuln1"))
        }
    }

    @Test fun `ROM mode vulnerability controls retain patch authoring without adopting or editing ASM`() = runBlocking {
        val f = fixture()
        f.project.asmWorkspace.enabled = false
        val patch = f.editor.findOrCreateConfigPatch("enemy_vuln")
        val before = f.b(); val revision = f.workspace.sourceBufferRevision
        withVulnerabilityEditor(f) { scene, render, opened ->
            assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("enemy-vulnerability-source-") == true })
            assertTrue(input(scene, "zoomer_vuln1").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("255")))
            render()
            assertEquals("255", inputText(scene, "zoomer_vuln1"))
            assertEquals(255, patch.configData!!["zoomer_vuln1"])
            assertTrue(opened.isEmpty())
            assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
            assertEquals(before, f.b()); assertEquals(revision, f.workspace.sourceBufferRevision)
        }
    }

    @Test fun `native literal lenses preserve comments CRLF and aliases without accepting expressions or malformed tables`() {
        val source = AsmEnemyTuningSource(headers, drops)
        assertEquals(15, source.original("zoomer_hp").value)
        assertEquals(200, source.original("ripper_hp").value)
        assertEquals(0x37, source.original("zoomer_drop0").value)
        assertEquals(source.original("zoomer_drop0").span, source.original("ripper_drop0").span)
        assertEquals(setOf(0xDCFF, 0xD47F), source.tableConsumers("zoomer_drop0", headers, drops).toSet())
        val (a, b) = source.edit("zoomer_hp", 65535, headers.replace("\n", "\r\n"), drops)
        assertContains(a, "%health(${ '$'}FFFF), ; keep HP note\r\n")
        assertEquals(drops, b)
        assertEquals(5, source.field("zoomer_dmg", a, b).value)
        assertFails { source.field("zoomer_hp", a.replace("${'$'}FFFF", "1+2"), b) }
        assertFails { source.edit("zoomer_hp", -1, a, b) }
        assertFails { source.field("zoomer_drop0", a, b.replace("${'$'}37,", "${'$'}100,")) }
        assertFails { source.field("zoomer_drop0", a, b.replace("; keep drop note", ", 1 ; keep drop note")) }
    }

    @Test fun `category shortcuts open live declarations without changing source ownership project or build state`() = runBlocking {
        val f = fixture()
        f.workspace.showProjectWorkspace()
        val projectBefore = Json.encodeToString(SmEditProject.serializer(), f.project)
        val dirtyBefore = f.editor.dirty
        val revision = f.workspace.sourceBufferRevision
        val buildBefore = f.workspace.sourceBuildRequired
        val a = f.a(); val b = f.b()
        for (section in AsmEnemyTuningSection.entries) {
            val (file, line) = f.bridge.sectionLine(section)
            f.workspace.showProjectWorkspace()
            f.workspace.openSource(file, line)
            assertEquals(section.fileId, f.workspace.selectedFileId)
            assertEquals(line, f.workspace.selectedLineIndex)
            val text = if (file == ENEMY_HEADER_SOURCE_FILE) a else b
            assertTrue(text.lineSequence().elementAt(line).startsWith(section.labelPrefix))
        }
        assertEquals(a, f.a()); assertEquals(b, f.b())
        assertEquals(projectBefore, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(dirtyBefore, f.editor.dirty)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(buildBefore, f.workspace.sourceBuildRequired)

        // Index coordinates can be stale after typing; shortcuts use the live CRLF buffer.
        assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, "; inserted\r\n" + b))
        val (file, line) = f.bridge.sectionLine(AsmEnemyTuningSection.VULNERABILITIES)
        assertTrue(f.workspace.projectSourceText(file).lineSequence().elementAt(line).startsWith("EnemyVulnerabilities_Test:"))
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            f.bridge.catalog.sectionLine(AsmEnemyTuningSection.DROPS, "; EnemyDropChances_Comment:\nUnrelated:\n db 0\n")
        }
        // Category navigation still works with non-literal/unfinished scalar drafts.
        val invalid = a.replace("%health(15)", "%health(unfinished)")
        assertEquals(0, f.bridge.catalog.sectionLine(AsmEnemyTuningSection.STATS, invalid))
    }

    @Test fun `vulnerability lenses roundtrip every raw byte and preserve aliases flags comments CRLF and slot order`() {
        val source = AsmEnemyTuningSource(headers, drops)
        assertEquals(listOf(0, 128, 130, 255), (0..3).map { source.original("zoomer_vuln$it").value })
        assertEquals(0, source.original("zoomer_vuln21").value)
        assertEquals(setOf(0xDCFF, 0xD47F), source.tableConsumers("zoomer_vuln0", headers, drops).toSet())
        assertEquals(source.original("zoomer_vuln21").span, source.original("ripper_vuln21").span)
        for (raw in 0..255) {
            val (a, b) = source.edit("zoomer_vuln21", raw, headers, drops.replace("\n", "\r\n"))
            assertEquals(headers, a)
            assertEquals(raw, source.field("ripper_vuln21", a, b).value)
            assertEquals(128, source.field("zoomer_vuln1", a, b).value)
            assertContains(b, "; keep beam note\r\n")
            assertContains(b, "; keep unused byte\r\n")
            assertEquals(0x37, source.field("zoomer_drop0", a, b).value)
        }
        assertFails { source.edit("zoomer_vuln0", 256, headers, drops) }
        assertFails { source.field("zoomer_vuln0", headers, drops.replace("${'$'}80,", "1+127,")) }
        assertFails { source.field("zoomer_vuln0", headers, drops.replace("2, 0 ; keep unused", "2 ; keep unused")) }
        assertFails { source.field("zoomer_vuln0", headers, drops.replace("; keep unused", ", 2 ; keep unused")) }
        assertFails { source.field("zoomer_vuln0", headers.replace("%vulnerabilities(EnemyVulnerabilities_Test)", "%vulnerabilities(${ '$'}0000)"), drops) }
        assertFails { source.field("zoomer_vuln0", headers, drops + "\nEnemyVulnerabilities_Test:\n db 0\n") }
        val missing = headers.replace("%vulnerabilities(EnemyVulnerabilities_Test)", "%vulnerabilities(Missing_Table)")
        val (file, line) = source.line("zoomer_vuln0", missing, drops)
        assertEquals(ENEMY_HEADER_SOURCE_FILE, file)
        assertContains(missing.lineSequence().elementAt(line), "%vulnerabilities(Missing_Table)")
    }

    @Test fun `first vulnerability edit retires shared slot overrides only and restores ownership and bytes on source undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("vuln", "Vulnerabilities", enabled = true, configType = "enemy_vuln",
            configData = mutableMapOf("zoomer_vuln1" to 8, "ripper_vuln1" to 6, "zoomer_vuln2" to 9))
        val disabled = SmPatch("old-vuln", "Disabled old tuning", enabled = false, configType = "enemy_vuln",
            configData = mutableMapOf("ripper_vuln1" to 10))
        f.project.patches.addAll(listOf(patch, disabled))
        val before = f.b(); val a = f.a(); val revision = f.workspace.sourceBufferRevision
        assertEquals(8, f.bridge.value(patch, "zoomer_vuln1").value)
        assertTrue(f.editor.openEnemyTuningSource("zoomer_vuln1"), f.editor.statusMessage)
        val links = f.bridge.editorLinks(ENEMY_DROP_SOURCE_FILE, f.bridge.line("zoomer_vuln1").second)
        assertEquals(setOf(AsmEditorTarget.EnemyTuning(0xDCFF, AsmEnemyTuningSection.VULNERABILITIES),
            AsmEditorTarget.EnemyTuning(0xD47F, AsmEnemyTuningSection.VULNERABILITIES)), links.map { it.target }.toSet())
        val bindingLine = a.lineSequence().indexOfFirst { "%vulnerabilities(EnemyVulnerabilities_Test)" in it }
        assertEquals(AsmEditorTarget.EnemyTuning(0xDCFF, AsmEnemyTuningSection.VULNERABILITIES),
            f.bridge.editorLinks(ENEMY_HEADER_SOURCE_FILE, bindingLine).single().target)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        f.editor.setPatchConfigData(patch.id, "zoomer_vuln1", 255)
        assertEquals(255, f.bridge.view("zoomer_vuln1").value)
        assertEquals(255, f.bridge.view("ripper_vuln1").value)
        assertEquals(mapOf("zoomer_vuln2" to 9), patch.configData?.toMap())
        assertNull(disabled.configData)
        assertEquals(setOf("zoomer_vuln1", "ripper_vuln1"), f.project.asmWorkspace.enemyTuningSourceKeys)
        assertEquals(a, f.a())
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.b()); assertEquals(8, patch.configData!!["zoomer_vuln1"])
        assertEquals(10, disabled.configData!!["ripper_vuln1"])
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(setOf("zoomer_vuln1", "ripper_vuln1"), cold.asmWorkspace.enemyTuningSourceKeys)
        assertNull(cold.patches.single { it.id == "old-vuln" }.configData)
    }

    @Test fun `invalid vulnerability drafts retain last good shared values and lock only that table while staying saveable`() = runBlocking {
        val f = fixture()
        f.bridge.edit("zoomer_vuln1", 255)
        val good = f.b()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_DROP_SOURCE_FILE, good.replace("${'$'}00, ${'$'}FF,", "${'$'}00, unfinished,")))
        assertEquals(255, f.bridge.view("zoomer_vuln1").value)
        assertEquals(255, f.bridge.view("ripper_vuln1").value)
        assertNotNull(f.bridge.view("ripper_vuln21").problem)
        assertFails { f.bridge.edit("zoomer_vuln2", 4) }
        assertNull(f.bridge.view("zoomer_drop0").problem)
        f.bridge.edit("zoomer_drop0", 72)
        f.bridge.edit("zoomer_hp", 61)
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertContains(f.b(), "unfinished")
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertEquals(good, f.b())
        assertNull(f.bridge.view("ripper_vuln21").problem)
    }

    @Test fun `vulnerability reset is one atomic source action and disagreeing shared requests publish nothing`() = runBlocking {
        val f = fixture()
        f.bridge.editValues(mapOf("zoomer_vuln0" to 255, "zoomer_vuln21" to 130))
        val edited = f.b(); val ownership = f.project.asmWorkspace.enemyTuningSourceKeys.toSet()
        val revision = f.workspace.sourceBufferRevision
        f.bridge.resetToOriginal(AsmEnemyTuningSection.VULNERABILITIES)
        assertEquals(revision + 1, f.workspace.sourceBufferRevision)
        assertEquals(0, f.bridge.view("zoomer_vuln0").value)
        assertEquals(0, f.bridge.view("zoomer_vuln21").value)
        assertEquals(128, f.bridge.view("zoomer_vuln1").value)
        val resetRevision = f.workspace.sourceBufferRevision
        f.bridge.resetToOriginal(AsmEnemyTuningSection.VULNERABILITIES)
        assertEquals(resetRevision, f.workspace.sourceBufferRevision)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertEquals(edited, f.b()); assertEquals(ownership, f.project.asmWorkspace.enemyTuningSourceKeys)
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        assertFails { f.bridge.editValues(mapOf("zoomer_vuln0" to 2, "ripper_vuln0" to 4)) }
        assertEquals(edited, f.b()); assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
        assertFails { f.bridge.editValues(mapOf("zoomer_hp" to 62, "zoomer_vuln0" to 256)) }
        assertEquals(15, f.bridge.view("zoomer_hp").value)
        assertEquals(edited, f.b())
    }

    @Test fun `reset rejects newly shared species with incompatible original defaults instead of silently choosing the last`() = runBlocking {
        val privateTable = "EnemyVulnerabilities_Private:\n    db " + List(22) { "4" }.joinToString(", ") + "\n"
        val f = fixture(headers.replace("%vulnerabilities(EnemyVulnerabilities_Alias)", "%vulnerabilities(EnemyVulnerabilities_Private)"), drops + privateTable)
        val a = f.a()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, a.replace("%vulnerabilities(EnemyVulnerabilities_Private)", "%vulnerabilities(EnemyVulnerabilities_Alias)")))
        val model = Json.encodeToString(SmEditProject.serializer(), f.project)
        val before = f.b(); val revision = f.workspace.sourceBufferRevision
        assertFailsWith<IllegalArgumentException> { f.bridge.resetToOriginal(AsmEnemyTuningSection.VULNERABILITIES) }
        assertEquals(before, f.b()); assertEquals(revision, f.workspace.sourceBufferRevision)
        assertEquals(model, Json.encodeToString(SmEditProject.serializer(), f.project))
    }

    @Test fun `first GUI field edit retires only its legacy config with reciprocal ownership and source undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_dmg" to 17))
        f.project.patches.add(patch)
        val before = f.a(); val revision = f.workspace.sourceBufferRevision
        assertEquals(40, f.bridge.value(patch, "zoomer_hp").value)
        f.bridge.line("zoomer_hp")
        assertEquals(revision, f.workspace.sourceBufferRevision); assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        f.editor.setPatchConfigData("stats", "zoomer_hp", 61)
        assertEquals(61, f.bridge.value(patch, "zoomer_hp").value)
        assertEquals(mapOf("zoomer_dmg" to 17), assertNotNull(patch.configData).toMap())
        assertEquals(setOf("zoomer_hp"), f.project.asmWorkspace.enemyTuningSourceKeys)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(before, f.a()); assertEquals(40, patch.configData!!["zoomer_hp"])
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        val cold = Json.decodeFromString<SmEditProject>(Json.encodeToString(SmEditProject.serializer(), f.project))
        assertEquals(setOf("zoomer_hp"), cold.asmWorkspace.enemyTuningSourceKeys)
        assertNull(cold.patches.single { it.id == "stats" }.configData!!["zoomer_hp"])
    }

    @Test fun `raw invalid drafts retain latest good values isolate fields and restore legacy values with source undo`() = runBlocking {
        val f = fixture()
        f.bridge.edit("zoomer_hp", 61)
        val good = f.a()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, good.replace("${'$'}003D", "unfinished")))
        assertEquals(61, f.bridge.view("zoomer_hp").value)
        assertNotNull(f.bridge.view("zoomer_hp").problem)
        assertNull(f.bridge.view("zoomer_dmg").problem)
        f.bridge.edit("zoomer_dmg", 19)
        assertEquals(19, f.bridge.view("zoomer_dmg").value)
        assertContains(f.a(), "unfinished")
        assertTrue(f.workspace.saveAllSources(), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(good, f.a())
        assertNull(f.bridge.view("zoomer_hp").problem)
    }

    @Test fun `a shared drop byte updates all species aliases and retires their config while preserving other weights`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("drops", "Drops", enabled = true, configType = "enemy_drops", configData = mutableMapOf("zoomer_drop0" to 10, "ripper_drop0" to 20, "zoomer_drop1" to 8))
        f.project.patches.add(patch)
        f.editor.setPatchConfigData("drops", "zoomer_drop0", 73)
        assertEquals(73, f.bridge.view("zoomer_drop0").value)
        assertEquals(73, f.bridge.view("ripper_drop0").value)
        assertEquals(mapOf("zoomer_drop1" to 8), assertNotNull(patch.configData).toMap())
        assertEquals(0x19, f.bridge.view("zoomer_drop1").value)
        assertEquals(setOf("zoomer_drop0", "ripper_drop0"), f.project.asmWorkspace.enemyTuningSourceKeys)
        assertContains(f.b(), "; keep drop note\r\n")
        assertEquals(2, f.bridge.editorLinks(ENEMY_DROP_SOURCE_FILE, f.bridge.line("zoomer_drop0").second).size)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_DROP_SOURCE_FILE), f.workspace.error)
        assertEquals(10, patch.configData!!["zoomer_drop0"]); assertEquals(20, patch.configData!!["ripper_drop0"])
    }

    @Test fun `raw source edits migrate just touched scalar config and unchanged inspection never dirties ownership`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40))
        f.project.patches.add(patch)
        val before = f.a()
        assertTrue(f.workspace.updateSourceEditText(ENEMY_HEADER_SOURCE_FILE, before.replace("%health(15)", "%health(37)")))
        assertEquals(37, f.bridge.value(patch, "zoomer_hp").value)
        assertNull(patch.configData)
        assertEquals(AsmEditorTarget.EnemyTuning(0xDCFF), f.bridge.editorLinks(ENEMY_HEADER_SOURCE_FILE, f.bridge.line("zoomer_hp").second).single().target)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(40, patch.configData!!["zoomer_hp"])
        val revision = f.workspace.sourceBufferRevision
        f.bridge.edit("zoomer_dmg", 5)
        assertEquals(revision, f.workspace.sourceBufferRevision)
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
    }

    @Test fun `build protection follows drop symbols but rejects moved headers wrong banks dual authority and typed byte drift`() {
        val source = AsmEnemyTuningSource(headers, drops)
        val project = SmEditProject("test.sfc").apply { asmWorkspace.enemyTuningSourceKeys.addAll(listOf("zoomer_hp", "zoomer_drop0")) }
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        val hp = parser.snesToPc(0xA0DCFF) + 4
        word(hp, 15); word(parser.snesToPc(0xA0DCFF) + 0x3A, 0xF900)
        rom[parser.snesToPc(0xB4F900)] = 0x37
        fun compiled(header: String = "A0:DCFF", table: String = "B4:F900") = AsmCompiledBuildBase(rom.copyOf(), rom.copyOf(), "test", emptyList(),
            assemblerVersion = "test", compilerOutput = emptyList(), compilerSymbols = "[labels]\n$header EnemyHeaders_TestZoomer\n$table EnemyDropChances_Test\n".toByteArray())
        val checked = protectEnemyTuningSource(source, headers, drops, project, compiled())
        val headeredInput = compiled().let { it.copy(romBytes = ByteArray(512) { 0x5A } + it.romBytes) }
        assertEquals(checked.sourceOwnedRanges, protectEnemyTuningSource(source, headers, drops, project, headeredInput).sourceOwnedRanges,
            "HP words, drop bytes and pointer claims must stay headerless")
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == hp && it.length == 2 }, "The whole word is claimed even though it equals native source")
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == parser.snesToPc(0xB4F900) && it.length == 1 })
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled("A0:DD00")) }
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled(table = "B5:F900")) }
        assertFails { protectEnemyTuningSource(source, headers.replace("%health(15)", "%health(16)"), drops, project, compiled()) }
        project.patches.add(SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 20)))
        assertFails { protectEnemyTuningSource(source, headers, drops, project, compiled()) }
    }

    @Test fun `vulnerability builds protect all native consumer bindings typed unchanged bytes and complete relocated tables`() {
        val outsideHeader = "EnemyHeaders_OutsidePicker: ;A08000;\n    %vulnerabilities(EnemyVulnerabilities_Test),\n"
        val a = headers + outsideHeader
        val source = AsmEnemyTuningSource(a, drops)
        val project = SmEditProject("test.sfc").apply {
            asmWorkspace.enemyTuningSourceKeys.addAll(listOf("zoomer_vuln0", "zoomer_vuln21"))
        }
        val rom = ByteArray(0x300000)
        val parser = RomParser(rom)
        val species = listOf(0xDCFF, 0xD47F, 0x8000)
        fun word(bytes: ByteArray, pc: Int, value: Int) { bytes[pc] = value.toByte(); bytes[pc + 1] = (value ushr 8).toByte() }
        for (id in species) word(rom, parser.snesToPc(0xA00000 or id) + 0x3C, 0xF900)
        val tablePc = parser.snesToPc(0xB4F900)
        for (slot in 0..21) rom[tablePc + slot] = source.original("zoomer_vuln$slot").value.toByte()
        val reference = rom.copyOf()
        fun compiled(bytes: ByteArray = rom, table: String = "B4:F900", header: String = "A0:DCFF") = AsmCompiledBuildBase(
            bytes.copyOf(), reference, "test", emptyList(), assemblerVersion = "test", compilerOutput = emptyList(),
            compilerSymbols = ("[labels]\n$header EnemyHeaders_TestZoomer\nA0:D47F EnemyHeaders_TestRipper\n" +
                "A0:8000 EnemyHeaders_OutsidePicker\n$table EnemyVulnerabilities_Test\n$table EnemyVulnerabilities_Alias\n").toByteArray())
        val checked = protectEnemyTuningSource(source, a, drops, project, compiled())
        val headered = protectEnemyTuningSource(source, a, drops, project, compiled(ByteArray(512) { 0x5A } + rom))
        assertEquals(checked.sourceOwnedRanges, headered.sourceOwnedRanges, "Copier headers must not shift typed ownership claims")
        for (id in species) assertTrue(checked.sourceOwnedRanges.any {
            it.pcOffset == parser.snesToPc(0xA00000 or id) + 0x3C && it.length == 2
        }, "Protect the full pointer word, including species outside the picker: $id")
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == tablePc && it.length == 1 })
        assertTrue(checked.sourceOwnedRanges.any { it.pcOffset == tablePc + 21 && it.length == 1 })
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled(header = "A0:DD00")) }
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled(table = "B5:F900")) }
        val competing = rom.copyOf().also { word(it, parser.snesToPc(0xA08000) + 0x3C, 0xF800) }
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled(competing)) }
        // A relocated table must verify/claim all 22 bytes, not only the two edited slots.
        val moved = rom.copyOf().also { bytes ->
            rom.copyInto(bytes, parser.snesToPc(0xB4FFEA), tablePc, tablePc + 22)
            species.forEach { word(bytes, parser.snesToPc(0xA00000 or it) + 0x3C, 0xFFEA) }
        }
        val relocated = protectEnemyTuningSource(source, a, drops, project, compiled(moved, table = "B4:FFEA"))
        for (slot in 0..21) assertTrue(relocated.sourceOwnedRanges.any { it.pcOffset == parser.snesToPc(0xB4FFEA) + slot })
        val drift = moved.copyOf().also { it[parser.snesToPc(0xB4FFEA) + 20] = 9 }
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled(drift, table = "B4:FFEA")) }
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled(moved, table = "B4:FFEB")) }
        project.patches.add(SmPatch("vuln", "Competing vulnerability override", enabled = true,
            configType = "enemy_vuln", configData = mutableMapOf("zoomer_vuln0" to 0)))
        assertFails { protectEnemyTuningSource(source, a, drops, project, compiled()) }
        project.patches.last().enabled = false
        protectEnemyTuningSource(source, a, drops, project, compiled())
    }

    @Test fun `external ASM config conflicts have one atomic repair and newer patch consumers block ownership undo`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_dmg" to 17))
        f.project.patches.add(patch)
        val a = f.a()
        f.workspace.replaceProjectSource(ENEMY_HEADER_SOURCE_FILE, a, a.replace("%health(15)", "%health(37)").replace("%damage(5)", "%damage(19)"))
        assertNotNull(f.bridge.view("zoomer_hp").problem)
        val external = f.a()
        f.bridge.useSourceValues(setOf("zoomer_hp", "zoomer_dmg"))
        assertNull(f.bridge.view("zoomer_hp").problem); assertNull(f.bridge.view("zoomer_dmg").problem)
        assertNull(patch.configData); assertEquals(external, f.a())
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(mapOf("zoomer_hp" to 40, "zoomer_dmg" to 17), assertNotNull(patch.configData).toMap())
        assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        val newer = SmPatch("newer", "Newer", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 90))
        f.project.patches.add(newer)
        assertFalse(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE))
        assertEquals(external, f.a()); assertNull(patch.configData)
        assertEquals(setOf("zoomer_hp", "zoomer_dmg"), f.project.asmWorkspace.enemyTuningSourceKeys)
        f.project.patches.remove(newer)
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
    }

    @Test fun `advanced ROM-only override removal is undoable and does not touch scalar settings or ASM`() = runBlocking {
        val f = fixture()
        val patch = SmPatch("stats", "Stats", enabled = true, configType = "enemy_stats", configData = mutableMapOf("zoomer_hp" to 40, "zoomer_mainAi" to 0x9000))
        f.project.patches.add(patch)
        val before = f.a()
        f.bridge.removeAdvancedOverrides()
        assertEquals(mapOf("zoomer_hp" to 40), assertNotNull(patch.configData).toMap())
        assertEquals(before, f.a()); assertTrue(f.project.asmWorkspace.enemyTuningSourceKeys.isEmpty())
        assertTrue(f.workspace.undoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertEquals(0x9000, patch.configData!!["zoomer_mainAi"])
        assertTrue(f.workspace.redoSourceEdit(ENEMY_HEADER_SOURCE_FILE), f.workspace.error)
        assertNull(patch.configData!!["zoomer_mainAi"])
    }
}
