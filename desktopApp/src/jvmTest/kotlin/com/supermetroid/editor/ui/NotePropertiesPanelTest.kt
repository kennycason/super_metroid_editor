package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class NotePropertiesPanelTest {
    private class Fixture {
        val note = mutableStateOf(NspcSequence.Note(0, 48, NspcSequence.nameToNote("F1"), instrument = 24), neverEqualPolicy())
        val message = mutableStateOf("")
        val busy = mutableStateOf(false)
        val preview = mutableStateOf<Int?>(null)
        val history = mutableListOf<String>()
        var deleted = 0
        var closed = 0
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(scene: ComposeScene, tag: String) { tagged(scene, tag).config[SemanticsActions.OnClick].action!!.invoke() }
    private fun setText(scene: ComposeScene, tag: String, value: String) {
        val input = walk(tagged(scene, tag)).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
    }
    private suspend fun withPanel(font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK, height: Int = 900,
        check: suspend (ComposeScene, suspend () -> Unit, Fixture) -> Unit) {
        val fixture = Fixture()
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(680, height)
        val instruments = (0 until 42).map { index -> NspcRenderer.InstrumentEntry(index % 40, 0x8F, 0xE0, 0x60,
            0x0001, index, 0x6C00 + index * 6, 0x9000, 0x9000, 144, false) }
        try {
            scene.constraints = Constraints.fixed(680, height)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Box(Modifier.fillMaxSize()) {
                            NotePropertiesPanel(note = fixture.note.value, channel = 3, totalTicks = 960,
                                instrumentInfos = instruments, instrumentNames = emptyMap(),
                                instrumentSourcePitches = instruments.associate { it.index to it.nativePitch },
                                instrumentBankLabel = "Lower Norfair sound bank", instrumentSourceLabel = "ROM bank",
                                sharedInstrumentTracks = listOf("Lower Norfair"), sharedInstrumentEdits = emptySet(),
                                onResetSharedInstrument = {}, instrumentEditingEnabled = true,
                                nativeInstrumentPreviewAvailable = true, previewInstrument = fixture.preview.value,
                                instrumentPreviewBusy = fixture.busy.value, instrumentPreviewMessage = fixture.message.value,
                                onPreviewInstrument = { _, _ -> }, onStopInstrumentPreview = {}, onRenameInstrument = { _, _ -> },
                                usedInstrumentIds = listOf(24, 31), onInstrumentChanged = {},
                                onClose = { fixture.closed++ }, onDelete = { fixture.deleted++ },
                                onBeforeChange = fixture.history::add,
                                onChanged = { fixture.note.value = fixture.note.value.copy() },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, render, fixture)
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val suffix = if (height == 900) "" else "-$height"
                val file = File("build/test-output/note-properties-${theme.name}-${font.name}$suffix.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `note title follows editor fonts with aligned right-hand actions and optional details`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) withPanel(font, theme) { scene, render, fixture ->
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "note-details" })
            val panel = tagged(scene, "note-properties").boundsInRoot
            val close = tagged(scene, "note-close").boundsInRoot
            val delete = tagged(scene, "note-delete").boundsInRoot
            assertTrue(abs(close.right - (panel.right - 8)) < 1)
            assertTrue(abs(close.center.y - delete.center.y) < 1)
            assertTrue(delete.left > panel.width / 2)
            val title = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Note F1" } }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertEquals(font.heading, layouts.single().layoutInput.style.fontSize)
            click(scene, "note-details-toggle"); render()
            assertNotNull(tagged(scene, "note-number-Start (ticks)"))
            assertNotNull(tagged(scene, "note-number-Velocity (0–15)"))
            assertNotNull(tagged(scene, "note-number-Gate"))
            click(scene, "note-delete"); click(scene, "note-close")
            assertEquals(1, fixture.deleted); assertEquals(1, fixture.closed)
        }
    }

    @Test fun `musical pitch and detailed timing edits preserve undo labels and reject invalid pitches`() = runBlocking {
        withPanel { scene, render, fixture ->
            click(scene, "note-details-toggle"); render()
            setText(scene, "note-pitch", "G#3"); render()
            assertEquals(NspcSequence.nameToNote("G#3"), fixture.note.value.noteValue)
            assertEquals("Set note pitch", fixture.history.last())
            val edits = fixture.history.size
            setText(scene, "note-pitch", "Z9"); render()
            assertEquals(NspcSequence.nameToNote("G#3"), fixture.note.value.noteValue)
            assertEquals(edits, fixture.history.size)
            setText(scene, "note-number-Start (ticks)", "13"); render()
            assertEquals(12, fixture.note.value.tick)
            setText(scene, "note-number-Velocity (0–15)", "0"); render()
            assertEquals(0, fixture.note.value.velocity)
            setText(scene, "note-number-Gate", "3"); render()
            assertEquals(3, fixture.note.value.quantize)
        }
    }

    @Test fun `bottom anchored note panel stays stationary while preview status changes`() = runBlocking {
        withPanel(FontSize.LARGER) { scene, render, fixture ->
            click(scene, "instrument-choose"); render()
            val bounds = tagged(scene, "note-properties").boundsInRoot
            val apply = tagged(scene, "instrument-apply").boundsInRoot
            for ((message, preparing, preview) in listOf(
                Triple("Preparing preview…", true, 24), Triple("Playing · F1", false, 24),
                Triple("Preview finished · F1", false, null), Triple("Could not open audio output", false, null))) {
                fixture.message.value = message; fixture.busy.value = preparing; fixture.preview.value = preview; render()
                assertEquals(bounds, tagged(scene, "note-properties").boundsInRoot)
                assertEquals(apply, tagged(scene, "instrument-apply").boundsInRoot)
                assertTrue(fixture.history.isEmpty())
            }
        }
    }

    @Test fun `expanded inspector remains scrollable in a short window at the largest font size`() = runBlocking {
        withPanel(FontSize.LARGER, height = 480) { scene, render, _ ->
            click(scene, "note-details-toggle"); render()
            click(scene, "instrument-edit-sound"); render()
            click(scene, "instrument-advanced"); render()
            val content = tagged(scene, "note-properties-content")
            val range = content.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.maxValue() > 0)
            assertTrue(content.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f))
            render()
            val panel = tagged(scene, "note-properties").boundsInRoot
            val apply = tagged(scene, "instrument-advanced").boundsInRoot
            assertTrue(apply.height > 0)
            assertTrue(apply.top >= panel.top && apply.bottom <= panel.bottom)
        }
    }
}
