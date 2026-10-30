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
        val live = mutableStateOf(true)
        val original = mutableStateOf(false)
        val auditions = mutableListOf<Pair<Int, NspcSequence.Note>>()
        val browser = mutableStateOf(MusicSampleBrowserState())
        val samplePreviews = mutableListOf<Triple<Int, Int, NspcSequence.Note>>()
        val sampleChoices = mutableListOf<Pair<Int, Int>>()
        val sampleTryouts = mutableListOf<NspcSequence.Note>()
        val history = mutableListOf<String>()
        var deleted = 0
        var closed = 0
        var stopped = 0
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(scene: ComposeScene, tag: String) { tagged(scene, tag).config[SemanticsActions.OnClick].action!!.invoke() }
    private fun setText(scene: ComposeScene, tag: String, value: String) {
        val input = walk(tagged(scene, tag)).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
    }
    private suspend fun withPanel(font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK, height: Int = 900, captureSuffix: String = "", tryoutAvailable: Boolean = false,
        auditionAvailable: Boolean = false,
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
                                sampleBrowser = fixture.browser.value,
                                onPreviewSample = { index, srcn, note -> fixture.samplePreviews += Triple(index, srcn, note) },
                                onChooseSample = { index, srcn -> fixture.sampleChoices += index to srcn },
                                onTrySample = if (tryoutAvailable) { { fixture.sampleTryouts += it } } else null,
                                instrumentBankLabel = "Lower Norfair sound bank", instrumentSourceLabel = "ROM bank",
                                sharedInstrumentTracks = listOf("Lower Norfair"), sharedInstrumentEdits = emptySet(),
                                onResetSharedInstrument = {}, instrumentEditingEnabled = true,
                                nativeInstrumentPreviewAvailable = true, previewInstrument = fixture.preview.value,
                                instrumentPreviewBusy = fixture.busy.value, instrumentPreviewMessage = fixture.message.value,
                                liveInstrumentPreview = fixture.live.value,
                                onLiveInstrumentPreviewChanged = { fixture.live.value = it; fixture.preview.value = null },
                                auditionOriginal = fixture.original.value, originalAuditionIds = instruments.map { it.index }.toSet(),
                                onAuditionOriginalChanged = if (auditionAvailable) { { fixture.original.value = it } } else null,
                                onAuditionInstrument = if (auditionAvailable) { { index, note -> fixture.auditions += index to note } } else null,
                                onPreviewInstrument = { _, _ -> }, onStopInstrumentPreview = { fixture.stopped++ }, onRenameInstrument = { _, _ -> },
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
                val file = File("build/test-output/note-properties-${theme.name}-${font.name}$suffix$captureSuffix.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `new recording tryout receives a detached selected note without history or source edits`() = runBlocking {
        withPanel(tryoutAvailable = true) { scene, render, fixture ->
            click(scene, "instrument-edit-sound"); render()
            click(scene, "sample-try-draft"); render()
            val note = fixture.sampleTryouts.single()
            assertEquals(fixture.note.value, note)
            assertNotSame(fixture.note.value, note)
            note.noteValue = NspcSequence.nameToNote("C4")
            assertEquals("F1", fixture.note.value.name)
            assertTrue(fixture.history.isEmpty()); assertTrue(fixture.sampleChoices.isEmpty())
        }
    }

    @Test fun `Live control is selected by default and changes audition mode without editing the note`() = runBlocking {
        withPanel { scene, render, fixture ->
            val note = fixture.note.value.copy()
            assertTrue(tagged(scene, "instrument-preview-live").config[SemanticsProperties.Selected])
            click(scene, "instrument-preview-live"); render()
            assertFalse(fixture.live.value)
            assertFalse(tagged(scene, "instrument-preview-live").config[SemanticsProperties.Selected])
            assertEquals(note, fixture.note.value)
            assertTrue(fixture.history.isEmpty())
        }
    }

    @Test fun `audition keyboard changes preview pitch and comparison without mutating selected notes or history`() = runBlocking {
        withPanel(auditionAvailable = true, captureSuffix = "-audition") { scene, render, fixture ->
            val original = fixture.note.value.copy()
            click(scene, "instrument-edit-sound"); render()
            val pitch = NspcSequence.nameToNote("G1")
            click(scene, "audition-key-$pitch"); render()
            assertEquals(24, fixture.auditions.single().first)
            assertEquals(original.copy(noteValue = pitch), fixture.auditions.single().second)
            assertNotSame(fixture.note.value, fixture.auditions.single().second)
            click(scene, "audition-original"); render()
            assertTrue(fixture.original.value)
            click(scene, "instrument-preview-current"); render()
            assertEquals(pitch, fixture.auditions.last().second.noteValue, "Play keeps the chosen audition pitch")
            click(scene, "audition-note-pitch"); render()
            assertEquals(original.noteValue, fixture.auditions.last().second.noteValue)
            assertEquals(original, fixture.note.value)
            assertTrue(fixture.history.isEmpty()); assertTrue(fixture.sampleChoices.isEmpty())
            click(scene, "sound-bank-select-31"); render()
            assertTrue(fixture.stopped > 0, "Selecting another sound ends the previous audition")
        }
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

    @Test fun `visible bank sound chips switch only the note instrument and keep settings available`() = runBlocking {
        withPanel(captureSuffix = "-sound-values") { scene, render, fixture ->
            val original = fixture.note.value.copy()
            click(scene, "instrument-edit-sound"); render()
            assertNotNull(tagged(scene, "sound-values"))
            click(scene, "sound-bank-select-24"); render()
            assertTrue(fixture.history.isEmpty())
            click(scene, "sound-bank-select-31"); render()
            assertEquals(original.copy(instrument = 31), fixture.note.value)
            assertEquals(listOf("Set note instrument"), fixture.history)
            assertNotNull(tagged(scene, "sound-value-pitch"))
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
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "instrument-advanced" })
            assertNotNull(tagged(scene, "sound-values"))
            val content = tagged(scene, "note-properties-content")
            val range = content.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.maxValue() > 0)
            assertTrue(content.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f))
            render()
            val panel = tagged(scene, "note-properties").boundsInRoot
            val apply = tagged(scene, "sound-bank-details").boundsInRoot
            assertTrue(apply.height > 0)
            assertTrue(apply.top >= panel.top && apply.bottom <= panel.bottom)
        }
    }

    @Test fun `synth knobs keep their full target size and can be reached in a short inspector`() = runBlocking {
        withPanel(FontSize.LARGER, height = 480, captureSuffix = "-synth") { scene, render, _ ->
            click(scene, "instrument-edit-sound"); render()
            val content = tagged(scene, "note-properties-content")
            val fine = tagged(scene, "sound-fine")
            val delta = fine.positionInRoot.y - content.boundsInRoot.top - 180f
            content.config[SemanticsActions.ScrollBy].action!!.invoke(0f, delta); render()
            val bounds = tagged(scene, "sound-fine").boundsInRoot
            assertEquals(72f, bounds.width)
            assertEquals(72f, bounds.height)
            assertTrue(bounds.top >= 0 && bounds.bottom <= 480)
        }
    }

    private fun browser() = MusicSampleBrowserState(samples = listOf(0, 24).map { srcn ->
        MusicSample(srcn, "brr:${srcn.toString().padStart(64, '0')}", 160, 90, null, List(16) { MusicSamplePeak(-0.5f, 0.5f) }, "Bank ROM")
    })

    @Test fun `note inspector routes sample preview pitches and shared choices without changing note identity`() = runBlocking {
        withPanel { scene, render, f ->
            f.browser.value = browser(); render()
            click(scene, "instrument-edit-sound"); render()
            click(scene, "sample-choose"); render()
            click(scene, "sample-preview-0"); render()
            assertEquals(24, f.samplePreviews.single().first)
            assertEquals(0, f.samplePreviews.single().second)
            assertEquals(NspcSequence.nameToNote("F1"), f.samplePreviews.single().third.noteValue)
            click(scene, "sample-reference-pitch"); render()
            click(scene, "sample-preview-0"); render()
            assertEquals(NspcSequence.nameToNote("C4"), f.samplePreviews.last().third.noteValue)
            click(scene, "sample-apply"); render()
            assertEquals(listOf(24 to 0), f.sampleChoices)
            assertTrue(f.history.isEmpty(), "Recording selection must use shared bank history, not note-edit history")
            assertEquals(24, f.note.value.instrument)
        }
    }

    @Test fun `bottom anchored sample browser stays stationary during native preview transitions`() = runBlocking {
        withPanel(FontSize.LARGER, captureSuffix = "-samples") { scene, render, f ->
            f.browser.value = browser(); render()
            click(scene, "instrument-edit-sound"); render()
            click(scene, "sample-choose"); render()
            val tags = listOf("note-properties", "sample-browser", "sample-row-24", "sample-apply")
            val bounds = tags.associateWith { tagged(scene, it).boundsInRoot }
            for ((message, busy, srcn) in listOf(Triple("Preparing preview…", true, 24), Triple("Playing · sample #24 · F1", false, 24),
                Triple("Preview finished · sample #24 · F1", false, null), Triple("Could not open audio output", false, null))) {
                f.message.value = message; f.busy.value = busy; f.preview.value = if (srcn != null) 24 else null
                f.browser.value = f.browser.value.copy(previewSource = srcn, previewBusy = busy); render()
                tags.forEach { assertEquals(bounds[it], tagged(scene, it).boundsInRoot, message) }
            }
        }
    }
}
