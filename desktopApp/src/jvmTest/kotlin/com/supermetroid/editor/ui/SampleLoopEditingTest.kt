package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SampleLoopEditingTest {
    @Test fun `dragged markers snap clamp and retain a complete block with an interior loop`() {
        for (frames in listOf(16, 32, 512, SampleAuthoring.MAX_FRAMES)) {
            val source = SampleDraft("Test", List(frames) { it % 30000 }, loopStart = 0)
            for (marker in SampleLoopEditing.Marker.entries) for (frame in listOf(Int.MIN_VALUE, -1, 0, 7, 8, 15, 16, 31, frames / 2, frames, Int.MAX_VALUE)) {
                val updated = SampleLoopEditing.move(source, marker, frame)
                SampleAuthoring.validate(updated)
                assertTrue(updated.trimEnd - updated.trimStart >= 16)
                assertTrue(updated.loopStart!! in updated.trimStart until updated.trimEnd)
                assertSame(source.pcm, updated.pcm)
                assertEquals(source.name, updated.name)
            }
        }
        val source = SampleAuthoring.generate(SampleAuthoring.Shape.SINE).copy(loopStart = 128)
        assertEquals(16, SampleLoopEditing.move(source, SampleLoopEditing.Marker.START, 9).trimStart)
        val moved = SampleLoopEditing.move(source, SampleLoopEditing.Marker.END, 127)
        assertEquals(128, moved.trimEnd); assertEquals(112, moved.loopStart)
        val encoded = SampleAuthoring.encode(moved)
        assertEquals(112, encoded.loopFrame); assertEquals(63, encoded.loopByteOffset)
        assertEquals(3, encoded.brr[encoded.brr.size - 9].toInt() and 3)
    }

    @Test fun `nearby join keeps trim and source intact and prefers a quieter local seam`() {
        val source = SampleDraft("Join", List(512) { if (it in 64..127) 24000 else 0 }, trimEnd = 256, loopStart = 64)
        val result = SampleLoopEditing.nearbyJoin(source)
        assertTrue(abs(result.loopStart!! - source.loopStart!!) <= 128)
        assertTrue(abs(result.pcm[result.trimEnd - 1] - result.pcm[result.loopStart]) < abs(source.pcm[source.trimEnd - 1] - source.pcm[source.loopStart]))
        assertEquals(source.trimStart, result.trimStart); assertEquals(source.trimEnd, result.trimEnd)
        assertSame(source.pcm, result.pcm); SampleAuthoring.validate(result)
        assertEquals(result, SampleLoopEditing.nearbyJoin(result), "A stable nearby join must not drift on repeat clicks")
        val oneShot = source.copy(loopStart = null)
        assertEquals(oneShot, SampleLoopEditing.nearbyJoin(oneShot))
    }

    @Test fun `repeated marker positions retain the prepared sample instead of invalidating an unchanged draft`() = runBlocking {
        val state = SampleWorkbenchState()
        state.prepare()
        val encoded = state.encoded
        state.update(SampleLoopEditing.move(state.draft, SampleLoopEditing.Marker.LOOP, 0))
        assertSame(encoded, state.encoded)
        state.update(state.draft.copy())
        assertSame(encoded, state.encoded); assertFalse(state.preparing)
    }

    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, key: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == key }

    @Test fun `loop handles support pointer drags exact accessible positions and all editor fonts and themes`() = runBlocking {
        for (theme in EditorTheme.entries) for (font in FontSize.entries) {
            var draft by mutableStateOf(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
            val scene = ComposeScene(coroutineContext = coroutineContext)
            val surface = Surface.makeRasterN32Premul(420, 440)
            val settings = EditorThemeState().apply { this.theme.value = theme; fontSize.value = font }
            try {
                scene.constraints = Constraints.fixed(420, 440)
                scene.setContent {
                    CompositionLocalProvider(LocalEditorTheme provides settings) {
                        MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                            SynthModule(Modifier.fillMaxWidth()) {
                                SampleLoopEditor(draft, true) { draft = it }
                                SampleLoopJoin(draft, SampleAuthoring.encode(draft), true) { draft = it }
                            }
                        }
                    }
                }
                var time = System.nanoTime()
                suspend fun render() { repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); delay(1) } }
                render()
                tag(scene, "sample-marker-END").config[SemanticsActions.OnClick].action!!.invoke(); render()
                tag(scene, "sample-encoded-waveform").config[SemanticsActions.SetProgress].action!!.invoke(256f); render()
                assertEquals(256, draft.trimEnd)
                tag(scene, "sample-marker-LOOP").config[SemanticsActions.OnClick].action!!.invoke(); render()
                tag(scene, "sample-encoded-waveform").config[SemanticsActions.SetProgress].action!!.invoke(130f); render()
                assertEquals(128, draft.loopStart)
                val bounds = tag(scene, "sample-encoded-waveform").boundsInRoot
                val from = Offset(bounds.left + bounds.width / 4, bounds.top + bounds.height / 2)
                val to = Offset(bounds.left + bounds.width * 3 / 8, from.y)
                scene.sendPointerEvent(PointerEventType.Move, from); render()
                scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary); render()
                scene.sendPointerEvent(PointerEventType.Move, to); render()
                scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary); render()
                assertEquals(192, draft.loopStart); SampleAuthoring.validate(draft)
                val join = tag(scene, "sample-loop-join").boundsInRoot
                assertTrue(join.height > 0 && join.bottom <= 440)
                tag(scene, "sample-marker-back").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertEquals(176, draft.loopStart)
            } finally { scene.close(); surface.close() }
        }
    }
}
