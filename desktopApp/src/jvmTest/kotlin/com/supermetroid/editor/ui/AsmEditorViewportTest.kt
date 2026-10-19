package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.*

/** The editor must scroll a large bank without needing a ROM or touching a user's project. */
@OptIn(ExperimentalComposeUiApi::class)
class AsmEditorViewportTest {
    @TempDir lateinit var directory: File

    @Test fun `reopening a target near EOF keeps preceding context visible`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_751)
        withEditor(state) { scene, render ->
            val editor = editableNode(scene)
            val text = editor.config[SemanticsProperties.EditableText].text
            assertTrue(text.lines().size >= 40, "Tail target must not isolate a four-line page: ${text.lines().size} lines")
            val before = visibleLine(scene)
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 300f), scrollDelta = Offset(0f, -1f))
            render()
            val after = visibleLine(scene)
            assertTrue(before - after in 1..12, "One wheel notch must scroll without jumping a whole page: $before -> $after")
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `wheel scrolling through page boundaries is continuous in both directions`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_240)
        withEditor(state) { scene, render ->
            assertTrue(editableNode(scene).config[SemanticsActions.OnClick].action!!.invoke())
            render()
            for (direction in listOf(1f, -1f)) {
                repeat(30) {
                    val before = visibleLine(scene)
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 300f), scrollDelta = Offset(0f, direction))
                    render()
                    val after = visibleLine(scene)
                    assertTrue((after - before) * direction in 0f..12f,
                        "Wheel direction $direction skipped or reversed rows: $before -> $after")
                }
            }
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `large font five digit gutter has one visual row per source row`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 15_620)
        withEditor(state) { scene, _ ->
            val gutter = nodes(scene).first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.lineSequence()
                    ?.all { it.toIntOrNull() != null } == true
            }
            val text = gutter.config[SemanticsProperties.Text].single().text
            val layout = textLayout(gutter)
            assertEquals(text.lines().size, layout.lineCount, "Line numbers must not wrap at the largest font size")
            val editor = editableNode(scene)
            val editorLayout = textLayout(editor)
            assertEquals(editorLayout.lineCount, layout.lineCount)
            for (line in listOf(0, 125, 249)) {
                assertEquals(editor.positionInRoot.y + editorLayout.getLineTop(line),
                    gutter.positionInRoot.y + layout.getLineTop(line), 1f, "Gutter row $line must align with its source")
            }
        }
    }

    @Test fun `scrollbar drag reaches end and returns to start without moving source or history`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 0)
        val original = state.sourceEditText("bank_8F.asm")
        val navigation = state.navigationSerial
        withEditor(state) { scene, render ->
            val top = editableNode(scene).positionInRoot.y + 6f
            scene.sendPointerEvent(PointerEventType.Press, Offset(995f, top), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(995f, 780f))
            render()
            scene.sendPointerEvent(PointerEventType.Release, Offset(995f, 780f), button = PointerButton.Primary)
            render()
            assertTrue(visibleLine(scene) > 15_700, "Dragging the thumb to the bottom must reach the actual end")
            scene.sendPointerEvent(PointerEventType.Press, Offset(995f, 780f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(995f, top))
            render()
            scene.sendPointerEvent(PointerEventType.Release, Offset(995f, top), button = PointerButton.Primary)
            render()
            val topLine = visibleLine(scene)
            assertTrue(topLine < 10, "Dragging back to the top must not recenter on the previous target: $topLine")
            assertEquals(original, state.sourceEditText("bank_8F.asm"))
            assertEquals(navigation, state.navigationSerial)
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `typing at a window boundary keeps the inserted line visible and preserves following source`() = runBlocking {
        val state = workspace()
        state.openSource("bank_8F.asm", 249, columnIndex = "; source line 249".length)
        val original = state.sourceEditText("bank_8F.asm")
        withEditor(state) { scene, render ->
            val node = editableNode(scene)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(node.config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("\n")))
            render()
            assertEquals(original.replace("; source line 249\n", "; source line 249\n\n"), state.sourceEditText("bank_8F.asm"))
            val editor = editableNode(scene)
            val layout = textLayout(editor)
            val selection = editor.config[SemanticsProperties.TextSelectionRange]
            val cursor = layout.getCursorRect(selection.end)
            val caretY = editor.positionInRoot.y + cursor.top
            assertTrue(caretY in 60f..780f, "Typing across the window boundary must keep the new caret visible: $caretY")
        }
    }

    @Test fun `gutter cycles without source changes and missing addresses stay blank`() = runBlocking {
        val raw = "LDA #1 ;A08000; Load counter\nSTA \$12\nRTL ;A08005;"
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 0)
        val navigation = state.navigationSerial
        withEditor(state) { scene, render ->
            val expected = "LDA #1 ; Load counter\nSTA \$12\nRTL "
            fun toggle() = nodes(scene).first { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("asm-source-gutter-toggle-") == true }
            fun addressNode() = nodes(scene).first { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("asm-source-addresses-") == true }
            assertEquals(expected, editableNode(scene).config[SemanticsProperties.EditableText].text)
            assertTrue(nodes(scene).none { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "↻" in it.text } },
                "There must be no gutter header button or visible mode label")
            assertTrue(editableNode(scene).config[SemanticsActions.OnClick].action!!.invoke()); render()
            val selection = editableNode(scene).config[SemanticsProperties.TextSelectionRange]
            repeat(3) { step ->
                val target = toggle()
                val layout = textLayout(editableNode(scene))
                // Click blank space below EOF, a row with no address, then the
                // right-hand address column in Both mode. All are the same target.
                val localY = when (step) {
                    0 -> target.size.height - 12f
                    1 -> editableNode(scene).positionInRoot.y - target.positionInRoot.y + layout.getLineTop(1) + 4f
                    else -> editableNode(scene).positionInRoot.y - target.positionInRoot.y + layout.getLineTop(2) + 4f
                }
                val position = target.positionInRoot + Offset(target.size.width - 3f, localY)
                scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
                render()
                assertEquals(listOf(AsmSourceGutterMode.ADDRESSES, AsmSourceGutterMode.BOTH, AsmSourceGutterMode.LINES)[step], state.sourceGutterMode)
                if (step < 2) {
                    assertEquals("A08000\n\nA08005", addressNode().config[SemanticsProperties.Text].single().text)
                    val code = editableNode(scene)
                    val addresses = addressNode()
                    assertEquals(textLayout(code).lineCount, textLayout(addresses).lineCount)
                    for (line in 0..2) assertEquals(code.positionInRoot.y + textLayout(code).getLineTop(line),
                        addresses.positionInRoot.y + textLayout(addresses).getLineTop(line), 1f)
                }
                assertEquals(raw, state.sourceEditText("bank_8F.asm"))
                assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
                assertEquals(navigation, state.navigationSerial)
                assertEquals(selection, editableNode(scene).config[SemanticsProperties.TextSelectionRange])
                assertTrue(editableNode(scene).config[SemanticsProperties.Focused], "Gutter clicks must not take typing focus")
            }
        }
    }

    @Test fun `native arrows and backspace cross hidden annotation in one step and undo restores exact source`() = runBlocking {
        val raw = "LDA #1 ;A08000;\n : STA \$12 ;A08003;"
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 0)
        withEditor(state) { scene, render ->
            fun select(offset: Int) {
                val node = editableNode(scene)
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue(node.config[SemanticsActions.SetSelection].action!!.invoke(offset, offset, false))
                render()
            }
            fun key(code: Int) {
                scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, code, AwtKeyEvent.CHAR_UNDEFINED)))
                render()
            }
            val lineEnd = "LDA #1 ".length
            select(lineEnd)
            key(AwtKeyEvent.VK_RIGHT)
            assertEquals(lineEnd + 1, editableNode(scene).config[SemanticsProperties.TextSelectionRange].end)
            key(AwtKeyEvent.VK_LEFT)
            assertEquals(lineEnd, editableNode(scene).config[SemanticsProperties.TextSelectionRange].end)
            select(lineEnd + 1)
            key(AwtKeyEvent.VK_BACK_SPACE)
            assertEquals("LDA #1  : STA \$12 ;A08000;", state.sourceEditText("bank_8F.asm"))
            assertTrue(state.undoSourceEdit("bank_8F.asm")); render()
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
            assertEquals(AsmSourcePresentation(raw).text, editableNode(scene).config[SemanticsProperties.EditableText].text)
            assertTrue(state.redoSourceEdit("bank_8F.asm")); render()
            assertEquals("LDA #1  : STA \$12 ;A08000;", state.sourceEditText("bank_8F.asm"))
        }
    }

    @Test fun `wheel scrolling over the gutter still scrolls project and reference source`() = runBlocking {
        for (reference in listOf(false, true)) {
            val state = workspace()
            if (reference) state.showReferenceWorkspace()
            state.openSource("bank_8F.asm", 0)
            withEditor(state) { scene, render ->
                fun scrollOffset() = nodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                val gutter = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("asm-source-gutter-toggle-") == true }
                val before = scrollOffset()
                scene.sendPointerEvent(PointerEventType.Scroll, gutter.positionInRoot + Offset(4f, 80f), scrollDelta = Offset(0f, 1f))
                render()
                assertTrue(scrollOffset() > before, "The gutter must forward wheel scrolling in reference=$reference")
                assertEquals(AsmSourceGutterMode.LINES, state.sourceGutterMode)
                assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
            }
        }
    }

    @Test fun `horizontally hidden gutter cannot intercept clicks on source code`() = runBlocking {
        val raw = (0 until 1000).joinToString("\n") { "; " + "x".repeat(160) + " source line $it" }
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 0)
        withEditor(state) { scene, render ->
            val gutter = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("asm-source-gutter-toggle-") == true }
            val position = gutter.positionInRoot + Offset(4f, 10f)
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(100f, 0f))
            render()
            assertTrue(editableNode(scene).positionInRoot.x < 0f, "Horizontal scrolling must move the gutter out of view")
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            render()
            assertEquals(AsmSourceGutterMode.LINES, state.sourceGutterMode)
            assertTrue(editableNode(scene).config[SemanticsProperties.Focused])
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
        }
    }

    @Test fun `copy and comment edits use visible text while stored source retains annotation`() = runBlocking {
        val raw = "LDA #1 ;A08000; Load counter\nRTL ;A08003;"
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 0)
        var clipboardText: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = clipboardText
            override fun setText(annotatedString: AnnotatedString) { clipboardText = annotatedString }
        }
        withEditor(state, clipboard) { scene, render ->
            fun select(start: Int, end: Int) {
                val node = editableNode(scene)
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue(node.config[SemanticsActions.SetSelection].action!!.invoke(start, end, false)); render()
            }
            val visible = AsmSourcePresentation(raw).text
            select(0, visible.length)
            assertTrue(editableNode(scene).config[SemanticsActions.CopyText].action!!.invoke()); render()
            assertEquals(visible, clipboardText?.text)
            val start = visible.indexOf("Load counter")
            select(start, start + "Load counter".length)
            assertTrue(editableNode(scene).config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("Initialize counter"))); render()
            assertEquals(raw.replace("Load counter", "Initialize counter"), state.sourceEditText("bank_8F.asm"))
            assertTrue(state.undoSourceEdit("bank_8F.asm")); render()
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
            assertTrue(state.redoSourceEdit("bank_8F.asm")); render()
        }
        assertTrue(state.saveSource("bank_8F.asm"), state.error)
        assertEquals(raw.replace("Load counter", "Initialize counter"),
            File(state.projectWorkspace!!.root, "overrides/src/bank_8F.asm").readText())
        assertEquals(raw, File(state.projectWorkspace!!.originalDirectory, "src/bank_8F.asm").readText())
    }

    @Test fun `middle click navigation after earlier hidden tags uses authoritative source offsets`() = runBlocking {
        val raw = "Target:\nRTL ;8F8000;\n\nCaller:\nJSL Target ;8F8001;"
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 4)
        withEditor(state) { scene, render ->
            val field = editableNode(scene)
            val layout = textLayout(field)
            val position = layout.getBoundingBox(layout.layoutInput.text.text.lastIndexOf("Target") + 2).center + field.positionInRoot
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Tertiary)
            render()
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Tertiary)
            render()
            assertEquals(0, state.selectedLineIndex)
            assertEquals("bank_8F.asm", state.selectedFileId)
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
            assertFalse(state.hasUnsavedSourceChanges("bank_8F.asm"))
        }
    }

    @Test fun `reference viewer uses the same projection and retains the gutter mode across workspace changes`() = runBlocking {
        val raw = "LDA #1 ;8F8000; useful comment\nRTL ;8F8003;"
        val state = workspace(raw)
        state.showReferenceWorkspace()
        state.openSource("bank_8F.asm", 0)
        state.sourceGutterMode = AsmSourceGutterMode.BOTH
        withEditor(state) { scene, render ->
            assertEquals(AsmWorkspaceKind.REFERENCE, state.workspaceKind)
            assertTrue(nodes(scene).none { it.config.contains(SemanticsProperties.EditableText) })
            val texts = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertContains(texts, "LDA #1 ; useful comment")
            assertContains(texts, "8F8000")
            assertTrue(texts.none { ";8F8000;" in it })
            val gutter = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("asm-source-gutter-toggle-") == true }
            val position = gutter.positionInRoot + Offset(gutter.size.width - 3f, gutter.size.height - 12f)
            scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            render()
            assertEquals(AsmSourceGutterMode.LINES, state.sourceGutterMode)
            state.showProjectWorkspace(); render()
            assertEquals(AsmSourceGutterMode.LINES, state.sourceGutterMode)
            assertEquals(AsmSourcePresentation(raw).text, editableNode(scene).config[SemanticsProperties.EditableText].text)
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
        }
    }

    @Test fun `completion after hidden tags replaces the raw token and preserves its annotation`() = runBlocking {
        val raw = "Target:\nRTL ;8F8000;\nCaller:\nJSL Tar ;8F8001;"
        val state = workspace(raw)
        state.openSource("bank_8F.asm", 3, columnIndex = 7)
        state.sourceGutterMode = AsmSourceGutterMode.BOTH
        withEditor(state) { scene, render ->
            assertTrue(editableNode(scene).config[SemanticsActions.OnClick].action!!.invoke()); render()
            val modifier = if (System.getProperty("os.name").contains("Mac", true)) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, modifier, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
            render()
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, AwtKeyEvent.VK_ENTER, '\n')))
            render()
            assertEquals(raw.replace("JSL Tar ", "JSL Target "), state.sourceEditText("bank_8F.asm"))
            assertEquals(AsmSourcePresentation(state.sourceEditText("bank_8F.asm")).text,
                editableNode(scene).config[SemanticsProperties.EditableText].text)
            assertTrue(state.undoSourceEdit("bank_8F.asm")); render()
            assertEquals(raw, state.sourceEditText("bank_8F.asm"))
        }
    }

    private suspend fun workspace(bankText: String? = null): AsmWorkspaceState {
        val root = File(directory, "reference")
        val src = File(root, "src").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_8F.asm\n")
        File(src, "bank_8F.asm").writeText(bankText ?: (0 until 15_754).joinToString("\n") { "; source line $it" })
        val reference = AsmReferenceWorkspace(root,
            AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                "test", "test.sfc", "ab".repeat(32), 0x300000, 0),
            AsmSourceParser().parse(src, data, emptyList()))
        val projectFile = File(directory, "Test.smedit").apply { writeText("{}") }
        val repository = AsmProjectWorkspaceRepository(emptyList(), emptyList())
        repository.initialize(projectFile.path, reference)
        // Bind a new workspace, as when the app reopens a saved project.
        return AsmWorkspaceState(projectRepository = repository, installedReferenceLoader = { reference }).also {
            it.bindProject(projectFile.path, true)
            it.loadInstalled()
        }
    }

    private fun withEditor(state: AsmWorkspaceState, clipboard: ClipboardManager? = null, test: (ComposeScene, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1000, 800)
        try {
            scene.constraints = Constraints.fixed(1000, 800)
            val theme = EditorThemeState().apply { fontSize.value = FontSize.LARGER }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides theme) {
                    MaterialTheme {
                        if (clipboard == null) AsmWorkspaceCanvas(state)
                        else CompositionLocalProvider(LocalClipboardManager provides clipboard) { AsmWorkspaceCanvas(state) }
                    }
                }
            }
            var frameTime = System.nanoTime()
            val render = { repeat(12) { frameTime += 16_666_667; scene.render(surface.canvas, frameTime) }; Unit }
            render()
            test(scene, render)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    private fun editableNode(scene: ComposeScene) = nodes(scene).first { it.config.contains(SemanticsProperties.EditableText) }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        return layouts.single()
    }

    private fun visibleLine(scene: ComposeScene): Int {
        val node = editableNode(scene)
        val layout = textLayout(node)
        val localLine = layout.getLineForVerticalPosition((70f - node.positionInRoot.y).coerceAtLeast(0f))
        return layout.layoutInput.text.text.lines()[localLine].substringAfterLast(' ').toInt()
    }
}
