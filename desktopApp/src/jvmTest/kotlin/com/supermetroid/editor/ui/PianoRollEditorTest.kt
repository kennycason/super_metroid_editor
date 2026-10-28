package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.NspcSequence
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PianoRollEditorTest {
    private class Fixture(initial: NspcSequence.Song) {
        val song = mutableStateOf(initial)
        val errors = mutableListOf<Throwable>()
        val history = PianoRollEditLogic.EditHistory()
        var changes = 0
    }
    private fun song(length: Int = 30) = NspcSequence.Song(tempo = 36).also {
        it.channels[0].notes += NspcSequence.Note(0, length, NspcSequence.nameToNote("C3"), instrument = 24)
        it.channels[0].notes += NspcSequence.Note(length, 24, NspcSequence.nameToNote("D3"), instrument = 24)
    }
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, key: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == key }
    private fun hasTag(scene: ComposeScene, key: String) = nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == key }
    private fun click(scene: ComposeScene, key: String) { assertTrue(tag(scene, key).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun assertConflict(scene: ComposeScene, conflict: Boolean) {
        assertEquals(conflict, hasTag(scene, "piano-roll-encoding-problem"))
        assertEquals(conflict, tag(scene, "piano-roll-play").config.contains(SemanticsProperties.Disabled))
        if (conflict) assertTrue(tag(scene, "piano-roll-encoding-problem").config[SemanticsProperties.Text].any { "Voice 1 has overlapping notes" in it.text })
    }

    private suspend fun editor(initial: NspcSequence.Song = song(), font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK,
        width: Int = 1400, capture: Boolean = false, check: suspend (ComposeScene, Fixture, suspend () -> Unit) -> Unit) {
        val fixture = Fixture(initial)
        val scene = ComposeScene(coroutineContext = coroutineContext + CoroutineExceptionHandler { _, error -> fixture.errors += error })
        val surface = Surface.makeRasterN32Premul(width, 1000)
        try {
            scene.constraints = Constraints.fixed(width, 1000)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        PianoRollEditor(song = fixture.song.value, activeChannel = 0,
                            canUndo = fixture.history.undoDepth > 0, canRedo = fixture.history.redoDepth > 0,
                            onRecordUndo = { fixture.history.record(fixture.song.value, emptyList(), it) },
                            onUndo = {
                                fixture.history.undo(fixture.song.value, emptyList())?.let { fixture.song.value = it.song; true } ?: false
                            },
                            onRedo = {
                                fixture.history.redo(fixture.song.value, emptyList())?.let { fixture.song.value = it.song; true } ?: false
                            },
                            onActiveChannelChanged = {}, onSongChanged = { fixture.changes++; fixture.song.value = PianoRollPreviewLogic.deepCopySong(it) },
                            onPlay = {}, onStop = {}, onDone = {}, onReset = {}, isPlaying = false)
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb())
                scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, fixture, render)
            if (capture && System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/piano-roll-conflict-${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }
    private fun point(scene: ComposeScene, tick: Float, pitch: String): Offset {
        // Default 4x: half a tick per pixel; 28px rows. Use the actual scrolled grid origin.
        val grid = tag(scene, "piano-roll-grid")
        val row = 71 - (NspcSequence.nameToNote(pitch) - NspcSequence.NOTE_MIN)
        return grid.positionInRoot + Offset(tick / 0.5f, row * 28f + 14f)
    }

    @Test fun `click jitter at a native note end does not quantize its length create overlap or record an edit`() = runBlocking {
        for (duration in listOf(3, 30)) editor(song(duration)) { scene, fixture, render ->
            val start = point(scene, duration - 1f, "C3")
            assertTrue(start.y in 0f..1000f)
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            val jitter = start + Offset(1f, 0f)
            scene.sendPointerEvent(PointerEventType.Move, jitter); render()
            scene.sendPointerEvent(PointerEventType.Release, jitter, button = PointerButton.Primary); render()
            assertEquals(duration, fixture.song.value.channels[0].notes.first().duration)
            assertEquals(0, fixture.changes)
            assertEquals(0, fixture.history.undoDepth)
            assertConflict(scene, false)
            assertTrue(fixture.errors.isEmpty(), fixture.errors.joinToString { it.message.orEmpty() })
        }
    }

    @Test fun `painting over an occupied voice stays editable with an explicit warning instead of crashing`() = runBlocking {
        editor { scene, fixture, render ->
            val emptyPitch = point(scene, 12f, "E3")
            scene.sendPointerEvent(PointerEventType.Press, emptyPitch, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, emptyPitch, button = PointerButton.Primary); render()
            assertEquals(3, fixture.song.value.channels[0].notes.size)
            assertConflict(scene, true)
            assertEquals(1, fixture.history.undoDepth)
            assertFailsWith<IllegalArgumentException> { NspcSequence.encode(fixture.song.value, 5) }
            click(scene, "piano-roll-undo"); render()
            assertEquals(2, fixture.song.value.channels[0].notes.size)
            assertConflict(scene, false)
            click(scene, "piano-roll-redo"); render()
            assertEquals(3, fixture.song.value.channels[0].notes.size)
            assertConflict(scene, true)
            // Repair the colliding note using the actual pointer path, not a model shortcut.
            val start = point(scene, 18f, "E3")
            val end = point(scene, 90f, "E3")
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary); render()
            assertEquals(84, fixture.song.value.channels[0].notes.single { it.name == "E3" }.tick)
            assertConflict(scene, false)
            assertTrue(NspcSequence.encode(fixture.song.value, 5).isNotEmpty())
            assertTrue(fixture.errors.isEmpty(), fixture.errors.joinToString { it.message.orEmpty() })
        }
    }

    @Test fun `existing overlapping draft opens without changing notes and can be repaired`() = runBlocking {
        val draft = song().also { it.channels[0].notes[1].tick = 12; it.isModified = true }
        val before = PianoRollPreviewLogic.deepCopySong(draft)
        editor(draft) { scene, fixture, render ->
            assertEquals(before, fixture.song.value)
            assertConflict(scene, true)
            val start = point(scene, 15f, "D3")
            val end = point(scene, 51f, "D3")
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary); render()
            assertEquals(48, fixture.song.value.channels[0].notes[1].tick)
            assertConflict(scene, false)
            assertEquals(1, fixture.history.undoDepth)
            assertTrue(fixture.errors.isEmpty(), fixture.errors.joinToString { it.message.orEmpty() })
        }
    }

    @Test fun `real resize preserves native timing offset and dragging back creates no undo entry`() = runBlocking {
        editor { scene, fixture, render ->
            val start = point(scene, 29f, "C3")
            val end = start + Offset(24f, 0f) // Twelve ticks, relative to the original 30.
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Release, start, button = PointerButton.Primary); render()
            assertEquals(30, fixture.song.value.channels[0].notes[0].duration)
            assertEquals(0, fixture.history.undoDepth)
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, end); render()
            scene.sendPointerEvent(PointerEventType.Release, end, button = PointerButton.Primary); render()
            assertEquals(42, fixture.song.value.channels[0].notes[0].duration)
            assertEquals(1, fixture.history.undoDepth)
            assertConflict(scene, true)
            assertTrue(fixture.errors.isEmpty(), fixture.errors.joinToString { it.message.orEmpty() })
        }
    }

    @Test fun `conflict warning follows editor fonts and remains readable in both themes`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) {
            val draft = song().also { it.channels[0].notes[1].tick = 12 }
            editor(draft, font, theme, width = 680, capture = true) { scene, fixture, _ ->
                assertConflict(scene, true)
                val warning = tag(scene, "piano-roll-encoding-problem")
                assertTrue(warning.boundsInRoot.left >= 0 && warning.boundsInRoot.right <= 680)
                assertTrue(warning.boundsInRoot.top >= 0 && warning.boundsInRoot.bottom <= 1000)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                warning.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertEquals(font.detail, layouts.single().layoutInput.style.fontSize)
                assertTrue(tag(scene, "piano-roll-grid").boundsInRoot.height > 120)
                assertTrue(fixture.errors.isEmpty(), fixture.errors.joinToString { it.message.orEmpty() })
            }
        }
    }
}
