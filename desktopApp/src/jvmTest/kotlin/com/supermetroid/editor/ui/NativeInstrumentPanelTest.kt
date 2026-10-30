package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import com.supermetroid.editor.rom.NspcSequence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class NativeInstrumentPanelTest {
    private val original = InstrumentEntry(24, 0xAD, 0x95, 0x64, 0x0001, 28, 0x6CA8, 0x9000, 0x9000, 144, true)
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun hasTag(scene: ComposeScene, tag: String) = nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(scene: ComposeScene, tag: String) { assertTrue(tagged(scene, tag).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun progress(scene: ComposeScene, tag: String, value: Float) {
        assertTrue(tagged(scene, tag).config[SemanticsActions.SetProgress].action!!.invoke(value))
    }
    private suspend fun withPanel(font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK,
        sound: InstrumentEntry = original, sourcePitch: Int? = 256, width: Int = 680, captureName: String? = null, editingEnabled: Boolean = true,
        audition: Boolean = false,
        check: suspend (ComposeScene, suspend () -> Unit, MutableList<InstrumentEntry>) -> Unit) {
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 900)
        val entry = mutableStateOf(sound)
        val commits = mutableListOf<InstrumentEntry>()
        val compareOriginal = mutableStateOf(false)
        try {
            scene.constraints = Constraints.fixed(width, 900)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                            NoteInstrumentSelector(NspcSequence.Note(0, 48, 0xA4, instrument = sound.index), listOf(entry.value),
                                mapOf(sound.index to "Looped lead"), setOf(sound.index), "Test sound bank", true,
                                null, false, "", { _, _ -> }, {}, { _, _ -> }, {}, bankContent = { androidx.compose.material3.Text("Bank details") },
                                auditionOriginal = compareOriginal.value, originalAuditionIds = setOf(sound.index),
                                onAuditionOriginalChanged = if (audition) { { compareOriginal.value = it } } else null,
                                onAuditionPitch = if (audition) { { _, _ -> } } else null,
                                sharedTracks = listOf("First track", "Sibling track"), instrumentEditingEnabled = editingEnabled, soundContent = {
                                    NativeInstrumentPanel(entry.value, sourcePitch) { commits += it; entry.value = it }
                                })
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, render, commits)
            if (captureName != null && System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/native-sound-${theme.name}-${font.name}-$width-$captureName.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `sound editor exposes musical and exact values together without an advanced toggle or opening edits`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) withPanel(font, theme, captureName = "envelope") { scene, render, commits ->
            assertFalse(hasTag(scene, "native-sound-controls"))
            assertFalse(hasTag(scene, "instrument-advanced"))
            click(scene, "instrument-edit-sound"); render()
            for (tag in listOf("sound-envelope-guide", "sound-attack", "sound-decay", "sound-sustain", "sound-fade", "sound-transpose", "sound-fine")) {
                assertTrue(hasTag(scene, tag))
                val bounds = tagged(scene, tag).boundsInRoot
                assertTrue(bounds.height > 0 && bounds.left >= 0 && bounds.right <= 680 && bounds.bottom <= 900, "$font / $theme / $tag")
            }
            assertFalse(hasTag(scene, "sound-gain-value"), "Inactive GAIN must not look editable in envelope mode")
            val envelope = tagged(scene, "sound-envelope-module").boundsInRoot
            val tuning = tagged(scene, "sound-tuning-module").boundsInRoot
            assertEquals(tuning.top, envelope.top)
            assertEquals(tuning.bottom, envelope.bottom, "The envelope module must match the tuning module's height")
            assertTrue(tagged(scene, "sound-envelope-guide").boundsInRoot.height > 88f, "The graph should fill the extra height")
            assertEquals(72f, tagged(scene, "sound-attack").boundsInRoot.height, "Knobs keep their standard size")
            assertFalse(hasTag(scene, "instrument-advanced"))
            for (tag in listOf("sound-value-adsr1", "sound-value-adsr2", "sound-value-gain", "sound-value-pitch")) {
                val bounds = tagged(scene, tag).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= 680 && bounds.bottom <= 900,
                    "$font / $theme / $tag")
            }
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Bank details" } })
            click(scene, "instrument-choose"); render()
            assertFalse(hasTag(scene, "native-sound-controls"))
            assertTrue(hasTag(scene, "instrument-browser"))
            assertTrue(commits.isEmpty())
            click(scene, "instrument-edit-sound"); render()
        }
    }

    private fun key(scene: ComposeScene, code: Int) {
        for (type in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED))
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), type, 0L, 0, code, AwtKeyEvent.CHAR_UNDEFINED)))
    }

    @Test fun `desktop sound values sit beside envelope and tuning while keeping every field visible`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) withPanel(font, theme, width = 1280,
            captureName = "sound-values") { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            val envelope = tagged(scene, "sound-envelope-module").boundsInRoot
            val tuning = tagged(scene, "sound-tuning-module").boundsInRoot
            val values = tagged(scene, "sound-values").boundsInRoot
            assertEquals(envelope.top, values.top)
            assertEquals(envelope.bottom, values.bottom)
            assertTrue(values.left > tuning.right)
            for (tag in listOf("sound-value-adsr1", "sound-value-adsr2", "sound-value-gain", "sound-value-pitch")) {
                val bounds = tagged(scene, tag).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.right <= 1280 && bounds.bottom < 900)
            }
            assertTrue(commits.isEmpty())
        }
    }

    @Test fun `audition keyboard fits with the desktop synth modules and comparing creates no edits`() = runBlocking {
        withPanel(FontSize.LARGER, width = 1280, captureName = "audition", audition = true) { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            click(scene, "audition-original"); render()
            val keyboard = tagged(scene, "sound-audition").boundsInRoot
            val envelope = tagged(scene, "sound-envelope-module").boundsInRoot
            assertTrue(keyboard.bottom < envelope.top)
            assertTrue(tagged(scene, "sound-value-pitch").boundsInRoot.bottom <= 900)
            assertTrue(commits.isEmpty())
        }
    }

    private suspend fun editValue(scene: ComposeScene, render: suspend () -> Unit, tag: String, text: String) {
        tagged(scene, tag).config[SemanticsActions.RequestFocus].action!!.invoke(); render()
        tagged(scene, tag).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)); render()
    }

    @Test fun `exact sound fields accept decimal and hex and synchronize knobs with one bank edit per input`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            editValue(scene, render, "sound-value-adsr1", "0x8F")
            assertTrue(commits.isEmpty(), "Typing must not create partial bank edits")
            key(scene, AwtKeyEvent.VK_ENTER); render()
            assertEquals(listOf(original.copy(adsr1 = 0x8F)), commits)
            assertEquals(0f, tagged(scene, "sound-attack").config[SemanticsProperties.ProgressBarRangeInfo].current)
            editValue(scene, render, "sound-value-adsr2", "224")
            tagged(scene, "sound-value-gain").config[SemanticsActions.RequestFocus].action!!.invoke(); render()
            assertEquals(2, commits.size)
            assertEquals(0xE0, commits.last().adsr2)
            assertEquals(7f, tagged(scene, "sound-sustain").config[SemanticsProperties.ProgressBarRangeInfo].current)
            editValue(scene, render, "sound-value-pitch", "0x0200")
            key(scene, AwtKeyEvent.VK_ENTER); render()
            assertEquals(3, commits.size)
            assertEquals(512, commits.last().nativePitch)
            assertEquals(12f, tagged(scene, "sound-transpose").config[SemanticsProperties.ProgressBarRangeInfo].current)
            progress(scene, "sound-attack", 3f); render()
            assertEquals(commits.last().adsr1.toString(), tagged(scene, "sound-value-adsr1").config[SemanticsProperties.EditableText].text)
            editValue(scene, render, "sound-value-adsr1", "0")
            key(scene, AwtKeyEvent.VK_ENTER); render()
            assertTrue(hasTag(scene, "sound-gain-value"))
            editValue(scene, render, "sound-value-gain", "64")
            key(scene, AwtKeyEvent.VK_ENTER); render()
            assertEquals(6, commits.size)
            assertEquals(64f, tagged(scene, "sound-gain-value").config[SemanticsProperties.ProgressBarRangeInfo].current)
        }
    }

    @Test fun `invalid canceled and unchanged sound values never change the bank`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            for ((tag, value) in listOf("sound-value-adsr1" to "256", "sound-value-adsr2" to "-1",
                "sound-value-gain" to "0x", "sound-value-pitch" to "65536")) {
                editValue(scene, render, tag, value)
                key(scene, AwtKeyEvent.VK_ENTER); render()
                assertTrue(commits.isEmpty())
                assertTrue(walk(tagged(scene, tag)).any { it.config.contains(SemanticsProperties.Error) }, "$tag must explain invalid input")
                tagged(scene, tag).config[SemanticsActions.RequestFocus].action!!.invoke(); render()
                key(scene, AwtKeyEvent.VK_ESCAPE); render()
                assertFalse(walk(tagged(scene, tag)).any { it.config.contains(SemanticsProperties.Error) })
            }
            editValue(scene, render, "sound-value-adsr1", "1")
            key(scene, AwtKeyEvent.VK_ESCAPE); render()
            editValue(scene, render, "sound-value-adsr1", original.adsr1.toString())
            key(scene, AwtKeyEvent.VK_ENTER); render()
            assertTrue(commits.isEmpty())
        }
    }

    @Test fun `knobs commit native bitfields and source relative tuning with no-op changes ignored`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            progress(scene, "sound-attack", 12f); render()
            assertEquals(1, commits.size)
            assertEquals(3, NativeInstrumentSound.attack(commits.last()))
            assertEquals(original.adsr1 and 0xF0, commits.last().adsr1 and 0xF0)
            assertEquals(original.gain, commits.last().gain)
            progress(scene, "sound-transpose", 12f); render()
            assertEquals(2, commits.size)
            assertEquals(512, commits.last().nativePitch)
            click(scene, "sound-tuning-reset"); render()
            assertEquals(3, commits.size)
            assertEquals(256, commits.last().nativePitch)
            assertTrue(tagged(scene, "sound-tuning-reset").config.contains(SemanticsProperties.Disabled))
            assertFalse(tagged(scene, "sound-attack").config[SemanticsActions.SetProgress].action!!.invoke(12f))
            assertEquals(3, commits.size)
        }
    }

    @Test fun `a drag updates the guide locally and commits only once after pointer release`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            val knob = tagged(scene, "sound-attack").boundsInRoot
            val start = knob.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            for (dy in listOf(20f, 40f, 60f)) {
                scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, dy)); render()
                assertTrue(commits.isEmpty(), "A shared-bank edit must not be recorded for every drag frame")
            }
            scene.sendPointerEvent(PointerEventType.Release, start - Offset(0f, 60f), button = PointerButton.Primary); render()
            assertEquals(1, commits.size)
            assertTrue(NativeInstrumentSound.attack(commits.single()) != NativeInstrumentSound.attack(original))
        }
    }

    @Test fun `keyboard knob edits commit once on key release and turn clockwise for longer times`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            assertTrue(tagged(scene, "sound-attack").config[SemanticsActions.RequestFocus].action!!.invoke())
            render()
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED)))
            render()
            assertTrue(commits.isEmpty())
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_RELEASED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED)))
            render()
            assertEquals(1, commits.size)
            assertEquals(NativeInstrumentSound.attack(original) - 1, NativeInstrumentSound.attack(commits.single()))
        }
    }

    @Test fun `keyboard repeats and losing focus finish one gesture rather than leaving an unsaved draft`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            tagged(scene, "sound-attack").config[SemanticsActions.RequestFocus].action!!.invoke(); render()
            repeat(3) {
                scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED)))
                render()
            }
            assertTrue(commits.isEmpty())
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_RELEASED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED))); render()
            assertEquals(1, commits.size)
            assertEquals(10, NativeInstrumentSound.attack(commits.last()))
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, AwtKeyEvent.VK_LEFT, AwtKeyEvent.CHAR_UNDEFINED))); render()
            assertEquals(1, commits.size)
            tagged(scene, "sound-fine").config[SemanticsActions.RequestFocus].action!!.invoke(); render()
            assertEquals(2, commits.size)
            assertEquals(11, NativeInstrumentSound.attack(commits.last()))
        }
    }

    @Test fun `gain modes hide inactive envelope fields and warn when a new note starts silent`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            click(scene, "sound-mode-gain"); render()
            assertFalse(hasTag(scene, "sound-attack"))
            assertTrue(hasTag(scene, "sound-gain-value"))
            assertEquals(original.adsr1 and 127, commits.last().adsr1)
            assertEquals(original.gain, commits.last().gain)
            click(scene, "sound-gain-mode"); render()
            click(scene, "sound-gain-FALL"); render()
            assertTrue(hasTag(scene, "sound-gain-silent"))
            assertEquals(NativeInstrumentSound.GainMode.FALL, NativeInstrumentSound.gainMode(commits.last()))
            progress(scene, "sound-gain-value", 31f); render()
            assertEquals(0x9F, commits.last().gain)
            click(scene, "sound-mode-envelope"); render()
            assertEquals(original.adsr1, commits.last().adsr1)
            assertEquals(original.adsr2, commits.last().adsr2)
            assertEquals(0x9F, commits.last().gain)
        }
    }

    @Test fun `fine tune drag stays on its own axis when native rounding crosses half a semitone`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            val knob = tagged(scene, "sound-fine").boundsInRoot
            val end = knob.center - Offset(0f, 160f)
            scene.sendPointerEvent(PointerEventType.Move, knob.center); render()
            scene.sendPointerEvent(PointerEventType.Press, knob.center, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            assertEquals(50f, tagged(scene, "sound-fine").config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertEquals(0f, tagged(scene, "sound-transpose").config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertTrue(commits.isEmpty())
            scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary); render()
            assertEquals(1, commits.size)
            assertEquals(NativeInstrumentSound.tuning(original, 256, 50.0).nativePitch, commits.single().nativePitch)
        }
    }

    @Test fun `noise and missing source tuning cannot acquire a guessed musical tuning baseline`() = runBlocking {
        for ((sound, baseline) in listOf(original.copy(srcn = 0x85) to 256, original to 0, original to null)) {
            withPanel(sound = sound, sourcePitch = baseline) { scene, render, commits ->
                click(scene, "instrument-edit-sound"); render()
                assertTrue(hasTag(scene, "sound-tuning-unavailable"))
                assertFalse(hasTag(scene, "sound-transpose"))
                assertTrue(commits.isEmpty())
            }
        }
    }

    @Test fun `native controls wrap at narrow widths and full sustain disables irrelevant decay`() = runBlocking {
        withPanel(FontSize.LARGER, sound = original.copy(adsr2 = 0xE0), width = 420, captureName = "full-sustain") { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            val decay = tagged(scene, "sound-decay")
            assertTrue(decay.config.contains(SemanticsProperties.Disabled))
            for (tag in listOf("sound-attack", "sound-sustain", "sound-transpose", "sound-fine")) {
                val bounds = tagged(scene, tag).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.right <= 420)
            }
            nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f); render()
            val fine = tagged(scene, "sound-fine").boundsInRoot
            assertEquals(72f, fine.height, "Narrow layouts scroll instead of shrinking knobs")
            assertTrue(fine.top >= 0 && fine.bottom <= 900)
            assertTrue(commits.isEmpty())
        }
    }

    @Test fun `gain editor fits all fonts and themes and unavailable sound banks cannot be edited`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) {
            withPanel(font, theme, sound = original.copy(adsr1 = original.adsr1 and 127, gain = 0xF6), captureName = "gain") { scene, render, commits ->
                click(scene, "instrument-edit-sound"); render()
                val bounds = tagged(scene, "sound-gain-mode").boundsInRoot
                assertTrue(bounds.height > 0 && bounds.bottom < 900)
                assertFalse(hasTag(scene, "sound-attack"))
                assertTrue(commits.isEmpty())
            }
        }
        withPanel(editingEnabled = false) { scene, _, commits ->
            assertTrue(tagged(scene, "instrument-edit-sound").config.contains(SemanticsProperties.Disabled))
            assertFalse(hasTag(scene, "native-sound-controls"))
            assertTrue(commits.isEmpty())
        }
    }

    @Test fun `shape shortcuts commit one native envelope edit and leave source gain and tuning alone`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            for ((index, shape) in NativeInstrumentSound.EnvelopeShape.entries.withIndex()) {
                click(scene, "sound-shape-menu"); render()
                click(scene, "sound-shape-${shape.name}"); render()
                assertEquals(index + 1, commits.size)
                val shaped = commits.last()
                assertEquals(shape, NativeInstrumentSound.matchingShape(shaped))
                assertEquals(original, shaped.copy(adsr1 = original.adsr1, adsr2 = original.adsr2))
                click(scene, "sound-shape-menu"); render()
                click(scene, "sound-shape-${shape.name}"); render()
                assertEquals(index + 1, commits.size, "Choosing the same shape is not another undo entry")
            }
        }
    }

    @Test fun `noise has a native rate knob instead of fabricated musical transpose controls`() = runBlocking {
        withPanel(sound = original.copy(srcn = 0x85), captureName = "noise") { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            assertTrue(hasTag(scene, "sound-noise-rate"))
            assertFalse(hasTag(scene, "sound-transpose"))
            progress(scene, "sound-noise-rate", 9f); render()
            assertEquals(1, commits.size)
            assertEquals(original.copy(srcn = 0x89), commits.single())
        }
    }

    @Test fun `returning fine tuning to its original position preserves exact high precision native pitch`() = runBlocking {
        val nativePitch = 33001
        val precise = original.copy(pitchAdj = ((nativePitch and 255) shl 8) or (nativePitch ushr 8))
        withPanel(sound = precise, sourcePitch = 32768) { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            val start = tagged(scene, "sound-fine").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 24f)); render()
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Release, start, button = PointerButton.Primary); render()
            assertTrue(commits.isEmpty())
            click(scene, "sound-shape-menu"); render(); click(scene, "sound-shape-PLUCK"); render()
            assertEquals(nativePitch, commits.single().nativePitch, "A cancelled tuning draft must not leak rounded pitch into the next edit")
        }
    }

    @Test fun `changing mode finishes the latest bank settings without a stale keyboard gesture restoring envelope mode`() = runBlocking {
        withPanel { scene, render, commits ->
            click(scene, "instrument-edit-sound"); render()
            tagged(scene, "sound-attack").config[SemanticsActions.RequestFocus].action!!.invoke(); render()
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_PRESSED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED))); render()
            assertTrue(commits.isEmpty())
            click(scene, "sound-mode-gain"); render()
            assertEquals(1, commits.size)
            assertFalse(NativeInstrumentSound.usesEnvelope(commits.single()))
            scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), AwtKeyEvent.KEY_RELEASED, 0L, 0, AwtKeyEvent.VK_RIGHT, AwtKeyEvent.CHAR_UNDEFINED))); render()
            assertEquals(1, commits.size)
            assertTrue(hasTag(scene, "sound-gain-value"))
        }
    }
}
