package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SampleWorkbenchTest {
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private suspend fun panel(font: FontSize, theme: EditorTheme, width: Int, check: suspend (ComposeScene, SampleWorkbenchState, suspend () -> Unit) -> Unit) {
        val state = SampleWorkbenchState().also { it.prepare() }
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 760)
        try {
            scene.constraints = Constraints.fixed(width, 760)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            SampleWorkbenchContent(state, true, onPlay = {}, onImport = {}, onOpen = {}, onSave = {}, onExport = {})
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, state, render)
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/sample-workbench-${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { state.stop(); scene.close(); surface.close() }
    }

    @Test fun `workbench uses editor fonts themes narrow wrapping and scrollable asset actions`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 680)) panel(font, theme, width) { scene, _, render ->
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> "not added to a music bank" in text.text } })
            for (key in listOf("sample-import-wav", "sample-generate-SINE", "sample-generate-NOISE", "sample-encoded-waveform")) {
                val bounds = tag(scene, key).boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= width, "$font/$theme/$width/$key")
            }
            val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f); render()
            val save = tag(scene, "sample-save-draft")
            assertTrue(save.boundsInRoot.bottom <= 760 && save.boundsInRoot.top >= 0, "Save must be reachable at every size")
            assertFalse(save.config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun `previews have stationary status geometry and invalid edits disable save and playback`() = runBlocking {
        panel(FontSize.LARGER, EditorTheme.DARK, 680) { scene, state, render ->
            val before = tag(scene, "sample-workbench-status").boundsInRoot
            state.message = "Playing encoded BRR"; render()
            assertEquals(before, tag(scene, "sample-workbench-status").boundsInRoot)
            state.message = "Cannot open audio output"; render()
            assertEquals(before, tag(scene, "sample-workbench-status").boundsInRoot)
            tag(scene, "sample-level").config[SemanticsActions.SetProgress].action!!.invoke(25f); render()
            assertEquals(25, state.draft.gainPercent)
            assertTrue(tag(scene, "sample-play-brr").config.contains(SemanticsProperties.Disabled))
            state.prepare(); render()
            assertFalse(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
            tag(scene, "sample-frame-End").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("invalid")); render()
            assertTrue(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled), "Uncommitted invalid text must not save old settings")
            tag(scene, "sample-frame-End").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("512")); render()
            state.prepare(); render()
            assertFalse(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
            state.update(state.draft.copy(loopStart = 1)); state.prepare(); render()
            assertTrue(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
        }
    }
}
