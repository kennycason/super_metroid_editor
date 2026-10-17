package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmRoomSourceActionsTest {
    @TempDir lateinit var directory: File

    @Test fun `door Source action opens the normal editor and first typing undo redo keep the staged text and caret usable`() = runBlocking {
        val src = File(directory, "reference/src").apply { mkdirs() }
        val data = File(directory, "reference/data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_8F.asm\nincsrc bank_83.asm\n")
        File(src, PLM_SOURCE_FILE).writeText("; Native room source\n")
        File(src, DOOR_SOURCE_FILE).writeText("Door_LandingSite_0: ;838000;\n; Native door source\n")
        val reference = AsmReferenceWorkspace(src.parentFile,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val file = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(file.path, reference)
        val workspace = AsmWorkspaceState(projectRepository = repository)
        workspace.bindProject(file.path, true)
        val project = SmEditProject("base.sfc").apply {
            asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
        }
        val rom = saveSourceTestRom()
        val level = ByteArray(2 + 32 * 32 * 3).also { it[1] = 8 }
        LZ5Compressor.compress(level).copyInto(rom, 0x200000)
        for (pc in listOf(0x791F8 + 18, 0x79300)) {
            rom[pc + 1] = 0x80.toByte(); rom[pc + 2] = 0xC0.toByte()
        }
        val parser = RomParser(rom)
        assertTrue(AsmSemanticBridge.buildIndex(parser).roomSourcesFor(0x838000).any { it.kind == RoomAsmSourceKind.DOORS })
        val editor = EditorState().apply { testMode = true; initForProject(file, project) }
        editor.bindRoomSources(AsmPlmSourceCatalog("", "", emptyMap(), parser, emptyMap()), workspace)
        val navigation = RoomAsmNavigation(editor, workspace, { "No canvas room" })
        workspace.openSource(DOOR_SOURCE_FILE)
        val source = workspace.projectSourceText(DOOR_SOURCE_FILE)
        val model = Json.encodeToString(SmEditProject.serializer(), project)
        val revision = workspace.sourceBufferRevision
        var openedRoom: AsmEditorTarget? = null
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1200, 900)
        try {
            scene.constraints = Constraints.fixed(1200, 900)
            scene.setContent { MaterialTheme {
                AsmWorkspaceCanvas(workspace, parser, onOpenInEditor = { openedRoom = it }, onOpenRoomSource = { navigation.openFromAsm(it) })
            } }
            var time = System.nanoTime()
            fun render() { repeat(12) { time += 16_666_667; scene.render(surface.canvas, time) } }
            fun field() = assertNotNull(nodes(scene).singleOrNull { it.config.contains(SemanticsProperties.EditableText) })
            render()
            click(scene, "Edit door data…"); render()
            val planned = workspace.sourceEditorText(DOOR_SOURCE_FILE)
            assertContains(planned, "macro SmeditDoors_91F8_Door_0()")
            assertEquals(planned, field().config[SemanticsProperties.EditableText].text)
            assertTrue(nodes(scene).none { text(it) == "Create editable source" })
            val cursor = planned.indexOf("dw ${'$'}0000 ; Distance") + 4
            assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(cursor, cursor + 4, false))
            render()
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), project))
            assertEquals(source, workspace.projectSourceText(DOOR_SOURCE_FILE)); assertEquals(revision, workspace.sourceBufferRevision)
            val edited = planned.replace("dw ${'$'}0000 ; Distance", "dw ${'$'}1234 ; Distance")
            assertNotEquals(planned, edited)
            assertTrue(field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("1234")))
            render()
            assertNull(workspace.roomSourceInspection)
            assertEquals(edited, workspace.projectSourceText(DOOR_SOURCE_FILE))
            assertEquals(edited, field().config[SemanticsProperties.EditableText].text)
            assertEquals(cursor + 4, field().config[SemanticsProperties.TextSelectionRange].start, "First-edit promotion must preserve the typing caret")
            assertEquals(1, project.rooms.getValue("91F8").asmDoorSourceVersion)
            click(scene, "Edit room"); assertEquals(AsmEditorTarget.Room(0x91F8), openedRoom)
            assertTrue(workspace.undoSourceEdit(DOOR_SOURCE_FILE), workspace.error); render()
            assertEquals(source, workspace.projectSourceText(DOOR_SOURCE_FILE))
            assertEquals(model, Json.encodeToString(SmEditProject.serializer(), project))
            assertEquals(planned, field().config[SemanticsProperties.EditableText].text)
            assertTrue(workspace.canRedoSource(DOOR_SOURCE_FILE))
            assertTrue(workspace.redoSourceEdit(DOOR_SOURCE_FILE), workspace.error); render()
            assertEquals(edited, field().config[SemanticsProperties.EditableText].text)
            assertEquals(0, editor.currentRoomId); assertTrue(editor.undoStack.isEmpty())
        } finally { scene.close(); surface.close() }
    }

    @Test fun `Source and Assets layout buttons open normal editing and first typing adopts the selected state without entering the visual editor`() = runBlocking {
        for (fromAsset in listOf(false, true)) {
            val parent = File(directory, "asset-$fromAsset").apply { mkdirs() }
            val src = File(parent, "reference/src").apply { mkdirs() }
            val data = File(parent, "reference/data").apply { mkdirs() }
            val rom = TestRomHelper.requireRomBytes()
            val parser = RomParser(rom)
            val asset = AsmAssetManifest.loadBundled().single { it.path == "LevelData_Flyway.bin" }
            File(data, asset.path).writeBytes(rom.copyOfRange(asset.pcOffset, asset.endExclusive))
            val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
            File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
            File(src, "macros.asm").writeText(macros)
            File(src, PLM_SOURCE_FILE).writeText("RoomHeader_Flyway: ;8F9879;\n db \$16\n")
            File(src, "bank_B9..CE.asm").writeText("LevelData_Flyway:\nincbin \"../data/LevelData_Flyway.bin\"\n")
            val reference = AsmReferenceWorkspace(src.parentFile,
                AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), rom.size, 1),
                AsmSourceParser().parse(src, data, listOf(asset)))
            val file = File(parent, "Test.smedit").apply { writeText("{}") }
            val repository = AsmProjectWorkspaceRepository(listOf(asset), emptyList())
            repository.initialize(file.path, reference)
            val workspace = AsmWorkspaceState(projectRepository = repository)
            workspace.bindProject(file.path, true)
            val project = SmEditProject("base.sfc").apply {
                asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            }
            val editor = EditorState().apply { testMode = true; initForProject(file, project) }
            editor.bindRoomSources(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace)
            val navigation = RoomAsmNavigation(editor, workspace, { "No GUI room is selected" })
            if (fromAsset) workspace.openAsset(asset.path) else workspace.openSource("bank_B9..CE.asm", 0)
            val before = Json.encodeToString(SmEditProject.serializer(), project)
            val revision = workspace.sourceBufferRevision
            val buildRequired = workspace.sourceBuildRequired
            val scene = ComposeScene()
            val surface = Surface.makeRasterN32Premul(1000, 800)
            try {
                scene.constraints = Constraints.fixed(1000, 800)
                scene.setContent { MaterialTheme {
                    AsmWorkspaceCanvas(workspace, parser, onOpenRoomSource = { navigation.openFromAsm(it) })
                } }
                var time = System.nanoTime()
                fun render() { repeat(12) { time += 16_666_667; scene.render(surface.canvas, time) } }
                render()
                click(scene, "Edit layout ASM…"); render()
                click(scene, "Native state 2 · " + parser.parseRoomStatesWithData(0x9879)[1].stateInfo.conditionName); render()
                val preview = assertNotNull(workspace.roomSourceInspection, editor.statusMessage)
                assertEquals("state-2", preview.stateId)
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), project))
                assertEquals(revision, workspace.sourceBufferRevision); assertFalse(editor.dirty)
                assertEquals(buildRequired, workspace.sourceBuildRequired)
                assertFalse(workspace.hasProjectSourceChanges)
                assertTrue(workspace.isProjectSourceEditable)
                assertTrue(nodes(scene).none { text(it) == "Create editable source" })
                val draft = assertNotNull(preview.automaticEdit)
                fun field() = nodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }
                assertEquals(draft.text, field().config[SemanticsProperties.EditableText].text)
                val cursor = draft.text.length
                assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(cursor, cursor, false))
                render()
                assertTrue(field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("; My layout note\n")))
                render()
                assertNull(workspace.roomSourceInspection, editor.statusMessage)
                assertEquals(1, project.rooms.getValue("9879").asmLayoutSourceVersion)
                assertTrue(workspace.isProjectSourceEditable); assertTrue(workspace.sourceBuildRequired)
                assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("macro SmeditLayout_9879_"))
                assertEquals(cursor + "; My layout note\n".length, nodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }
                    .config[SemanticsProperties.TextSelectionRange].start, "First-edit handoff preserves the caret")
                assertTrue(workspace.undoSourceEdit(PLM_SOURCE_FILE), workspace.error); render()
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), project))
                assertEquals(draft.text, workspace.sourceEditorText(PLM_SOURCE_FILE))
                assertTrue(workspace.redoSourceEdit(PLM_SOURCE_FILE), workspace.error); render()
                assertNull(workspace.roomSourceInspection)
                assertEquals(0, editor.currentRoomId); assertNull(editor.roomSourceParser)
                assertTrue(editor.undoStack.isEmpty())
            } finally { scene.close(); surface.close() }
        }
    }

    @Test fun `room adoption controls are absent when project mode supplies no authoring callback`() {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(600, 200)
        try {
            scene.constraints = Constraints.fixed(600, 200)
            scene.setContent { MaterialTheme { AsmRoomSourceActions(listOf(AsmRoomSourceLink(0x9879, "Flyway", RoomAsmSourceKind.LAYOUT)), null) } }
            repeat(3) { scene.render(surface.canvas, System.nanoTime() + it * 16_666_667) }
            assertTrue(nodes(scene).none { text(it).contains("Preview / make editable") || text(it) == "Edit layout ASM…" })
        } finally { scene.close(); surface.close() }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ComposeScene): List<SemanticsNode> = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
    private fun click(scene: ComposeScene, label: String) {
        val button = nodes(scene).firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && descendants(it).any { child -> text(child) == label } }
        assertNotNull(button, "Missing action: $label").config[SemanticsActions.OnClick].action!!.invoke()
    }
}
