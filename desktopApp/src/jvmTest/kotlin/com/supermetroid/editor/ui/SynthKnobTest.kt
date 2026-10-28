package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SynthKnobTest {
    private class Fixture {
        val value = mutableStateOf(40)
        val enabled = mutableStateOf(true)
        val commits = mutableListOf<Int>()
    }
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, key: String = "knob") = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == key }
    private fun focus(scene: ComposeScene, key: String = "knob") { assertTrue(tag(scene, key).config[SemanticsActions.RequestFocus].action!!.invoke()) }
    private fun key(scene: ComposeScene, code: Int, released: Boolean = false) = scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(),
        if (released) AwtKeyEvent.KEY_RELEASED else AwtKeyEvent.KEY_PRESSED, 0L, 0, code, AwtKeyEvent.CHAR_UNDEFINED)))
    private suspend fun knob(check: suspend (ComposeScene, Fixture, suspend () -> Unit) -> Unit) {
        val f = Fixture()
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(360, 640)
        try {
            scene.constraints = Constraints.fixed(360, 640)
            scene.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().padding(start = 40.dp, top = 180.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SynthKnob("Level", f.value.value, 0..100, "${f.value.value}%", "knob", { f.value.value = it }, { f.commits += f.value.value }, f.enabled.value)
                        SynthKnob("Other", 50, 0..100, "50%", "other", {}, {})
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, f, render)
        } finally { scene.close(); surface.close() }
    }

    @Test fun `clicks small jitter horizontal movement and secondary drags never jump the value`() = runBlocking {
        knob { scene, f, render ->
            val start = tag(scene).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            for (button in listOf(PointerButton.Primary, PointerButton.Secondary)) {
                scene.sendPointerEvent(PointerEventType.Press, start, button = button); render()
                val end = start + if (button == PointerButton.Primary) Offset(20f, 1f) else Offset(0f, -40f)
                scene.sendPointerEvent(PointerEventType.Move, end); render()
                scene.sendPointerEvent(PointerEventType.Release, end, button = button); render()
                assertEquals(40, f.value.value)
                assertTrue(f.commits.isEmpty())
            }
        }
    }

    @Test fun `vertical drag previews locally and commits once on release`() = runBlocking {
        knob { scene, f, render ->
            val start = tag(scene).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            for (distance in listOf(16f, 32f, 48f)) {
                scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, distance)); render()
                assertTrue(f.commits.isEmpty())
            }
            assertEquals(70, f.value.value)
            scene.sendPointerEvent(PointerEventType.Release, start - Offset(0f, 48f), button = PointerButton.Primary); render()
            assertEquals(listOf(70), f.commits)
        }
    }

    @Test fun `shift slows drag sensitivity without changing the native step size`() = runBlocking {
        for (fine in listOf(false, true)) knob { scene, f, render ->
            val start = tag(scene).boundsInRoot.center
            val modifiers = PointerKeyboardModifiers(isShiftPressed = fine)
            scene.sendPointerEvent(PointerEventType.Move, start, keyboardModifiers = modifiers); render()
            scene.sendPointerEvent(PointerEventType.Press, start, keyboardModifiers = modifiers, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 80f), keyboardModifiers = modifiers); render()
            scene.sendPointerEvent(PointerEventType.Release, start - Offset(0f, 80f), keyboardModifiers = modifiers, button = PointerButton.Primary); render()
            assertEquals(if (fine) 50 else 90, f.value.value)
            assertEquals(listOf(f.value.value), f.commits)
        }
    }

    @Test fun `cancelled drag restores the starting value without committing`() = runBlocking {
        knob { scene, f, render ->
            val start = tag(scene).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 40f)); render()
            assertEquals(65, f.value.value)
            f.enabled.value = false; render()
            assertEquals(40, f.value.value)
            assertTrue(f.commits.isEmpty())
        }
    }

    @Test fun `returning a drag to its origin creates no edit`() = runBlocking {
        knob { scene, f, render ->
            val start = tag(scene).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Press, start, button = PointerButton.Primary); render()
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 32f)); render()
            scene.sendPointerEvent(PointerEventType.Move, start); render()
            scene.sendPointerEvent(PointerEventType.Release, start, button = PointerButton.Primary); render()
            assertEquals(40, f.value.value)
            assertTrue(f.commits.isEmpty())
        }
    }

    @Test fun `accessible progress has units clamps to native steps and refuses disabled nonfinite or unchanged values`() = runBlocking {
        knob { scene, f, render ->
            assertEquals("40%", tag(scene).config[SemanticsProperties.StateDescription])
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 40f)) assertFalse(tag(scene).config[SemanticsActions.SetProgress].action!!.invoke(invalid))
            assertTrue(tag(scene).config[SemanticsActions.SetProgress].action!!.invoke(70.2f)); render()
            assertEquals(listOf(70), f.commits)
            assertTrue(tag(scene).config[SemanticsActions.SetProgress].action!!.invoke(1000f)); render()
            assertEquals(listOf(70, 100), f.commits)
            f.enabled.value = false; render()
            assertTrue(tag(scene).config.contains(SemanticsProperties.Disabled))
            assertFalse(tag(scene).config[SemanticsActions.SetProgress].action!!.invoke(5f))
            assertEquals(100, f.value.value)
        }
    }

    @Test fun `unfocused wheel scrolling is ignored and focused wheel bursts commit once`() = runBlocking {
        knob { scene, f, render ->
            val center = tag(scene).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Scroll, center, scrollDelta = Offset(0f, -1f)); render()
            assertEquals(40, f.value.value)
            focus(scene); render()
            repeat(3) { scene.sendPointerEvent(PointerEventType.Scroll, center, scrollDelta = Offset(0f, -1f)); render() }
            assertEquals(43, f.value.value)
            assertTrue(f.commits.isEmpty())
            delay(250); render()
            assertEquals(listOf(43), f.commits)
        }
    }

    @Test fun `focus loss finishes pending wheel input without a later duplicate commit`() = runBlocking {
        knob { scene, f, render ->
            focus(scene); render()
            scene.sendPointerEvent(PointerEventType.Scroll, tag(scene).boundsInRoot.center, scrollDelta = Offset(0f, -1f)); render()
            focus(scene, "other"); render()
            assertEquals(listOf(41), f.commits)
            delay(250); render()
            assertEquals(listOf(41), f.commits)
        }
    }

    @Test fun `Home End repeat and focus loss commit complete keyboard gestures`() = runBlocking {
        knob { scene, f, render ->
            focus(scene); render()
            repeat(3) { key(scene, AwtKeyEvent.VK_UP); render() }
            assertEquals(43, f.value.value); assertTrue(f.commits.isEmpty())
            key(scene, AwtKeyEvent.VK_UP, true); render()
            assertEquals(listOf(43), f.commits)
            key(scene, AwtKeyEvent.VK_END); render()
            assertEquals(100, f.value.value)
            focus(scene, "other"); render()
            assertEquals(listOf(43, 100), f.commits)
            focus(scene); render(); key(scene, AwtKeyEvent.VK_HOME); render(); key(scene, AwtKeyEvent.VK_HOME, true); render()
            assertEquals(listOf(43, 100, 0), f.commits)
        }
    }
}
