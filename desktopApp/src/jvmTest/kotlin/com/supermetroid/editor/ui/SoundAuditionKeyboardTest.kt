package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.NspcSequence
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SoundAuditionKeyboardTest {
    private class Fixture {
        var pitch by mutableStateOf(NspcSequence.nameToNote("C4"))
        var original by mutableStateOf(false)
        val played = mutableListOf<Int>()
        val compared = mutableListOf<Boolean>()
    }
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, name: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == name }
    private fun click(scene: ComposeScene, name: String) { assertTrue(tag(scene, name).config[SemanticsActions.OnClick].action!!.invoke()) }
    private suspend fun keyboard(font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK, width: Int = 680,
        enabled: Boolean = true, originalAvailable: Boolean = true, editedAvailable: Boolean = true,
        check: suspend (ComposeScene, Fixture, suspend () -> Unit) -> Unit) {
        val fixture = Fixture()
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 340)
        try {
            scene.constraints = Constraints.fixed(width, 340)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Column(Modifier.fillMaxWidth()) {
                            SoundAuditionKeyboard(fixture.pitch, NspcSequence.nameToNote("C4"), fixture.original,
                                originalAvailable, enabled, editedAvailable,
                                onPitch = { fixture.pitch = it; fixture.played += it },
                                onOriginal = { fixture.original = it; fixture.compared += it })
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, fixture, render)
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/audition-keyboard-${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `keyboard and comparison controls fit every editor font theme and width without playing on open`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 680, 1280)) {
            keyboard(font, theme, width) { scene, fixture, _ ->
                assertTrue(fixture.played.isEmpty()); assertTrue(fixture.compared.isEmpty())
                for (name in listOf("audition-edited", "audition-original", "audition-octave-up", "audition-octave-down") +
                    (0..12).map { "audition-key-${NspcSequence.nameToNote("C4") + it}" }) {
                    val bounds = tag(scene, name).boundsInRoot
                    assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 340,
                        "$font/$theme/$width/$name: $bounds")
                }
            }
        }
    }

    @Test fun `black and white keys respond to actual pointer input with no click through and comparison keeps pitch`() = runBlocking {
        keyboard { scene, fixture, render ->
            val c = NspcSequence.nameToNote("C4")
            val sharp = tag(scene, "audition-key-${c + 1}").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, sharp, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Release, sharp, button = PointerButton.Primary); render()
            assertEquals(listOf(c + 1), fixture.played)
            val white = tag(scene, "audition-key-${c + 2}").boundsInRoot
            val bottom = Offset(white.center.x, white.bottom - 6f)
            scene.sendPointerEvent(PointerEventType.Press, bottom, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Release, bottom, button = PointerButton.Primary); render()
            assertEquals(listOf(c + 1, c + 2), fixture.played)
            click(scene, "audition-original"); render()
            assertEquals(c + 2, fixture.pitch)
            assertEquals(listOf(true), fixture.compared)
            assertTrue(tag(scene, "audition-original").config[SemanticsProperties.Selected])
            click(scene, "audition-edited"); render()
            click(scene, "audition-note-pitch"); render()
            assertEquals(c, fixture.played.last())
        }
    }

    @Test fun `octave navigation is silent and pitches stay within native C1 through B6`() = runBlocking {
        keyboard { scene, fixture, render ->
            repeat(3) { click(scene, "audition-octave-down"); render() }
            assertTrue(tag(scene, "audition-octave-down").config.contains(SemanticsProperties.Disabled))
            click(scene, "audition-key-${NspcSequence.NOTE_MIN}"); render()
            repeat(5) { click(scene, "audition-octave-up"); render() }
            assertEquals(listOf(NspcSequence.NOTE_MIN), fixture.played)
            assertTrue(tag(scene, "audition-octave-up").config.contains(SemanticsProperties.Disabled))
            assertTrue(tag(scene, "audition-key-${NspcSequence.NOTE_MAX + 1}").config.contains(SemanticsProperties.Disabled))
            click(scene, "audition-key-${NspcSequence.NOTE_MAX}"); render()
            assertEquals(listOf(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX), fixture.played)
        }
    }

    @Test fun `focused piano keys support Enter and unavailable sources disable only their audition variant`() = runBlocking {
        keyboard(originalAvailable = false) { scene, fixture, render ->
            assertTrue(tag(scene, "audition-original").config.contains(SemanticsProperties.Disabled))
            val c = NspcSequence.nameToNote("C4")
            assertTrue(tag(scene, "audition-key-$c").config[SemanticsActions.RequestFocus].action!!.invoke()); render()
            for (type in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED))
                scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), type, 0L, 0, AwtKeyEvent.VK_ENTER, AwtKeyEvent.CHAR_UNDEFINED)))
            render()
            assertEquals(listOf(c), fixture.played)
        }
        keyboard(editedAvailable = false) { scene, fixture, render ->
            assertTrue(tag(scene, "audition-edited").config.contains(SemanticsProperties.Disabled))
            click(scene, "audition-original"); render()
            val c = NspcSequence.nameToNote("C4")
            assertFalse(tag(scene, "audition-key-$c").config.contains(SemanticsProperties.Disabled))
            click(scene, "audition-key-$c"); render()
            assertEquals(listOf(c), fixture.played)
        }
        keyboard(enabled = false) { scene, fixture, _ ->
            assertTrue(tag(scene, "audition-key-${NspcSequence.nameToNote("C4")}").config.contains(SemanticsProperties.Disabled))
            assertTrue(fixture.played.isEmpty())
        }
    }
}
