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
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.TestRomHelper
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
    private suspend fun panel(font: FontSize, theme: EditorTheme, width: Int, bankTarget: String? = null, bank: SampleBankPreview? = null,
        bankProblem: String? = null, onPlayInBank: () -> Unit = {}, check: suspend (ComposeScene, SampleWorkbenchState, suspend () -> Unit) -> Unit) {
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
                            SampleWorkbenchContent(state, true, onPlay = {}, onImport = {}, onOpen = {}, onSave = {}, onExport = {},
                                bankTarget = bankTarget, bank = bank, bankProblem = bankProblem, onPlayInBank = onPlayInBank)
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
                val file = File("build/test-output/sample-workbench-${if (bankTarget != null) "bank-" else ""}${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
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

    @Test fun `bank tryout is explicitly temporary with a real fit check and reachable actions at all editor sizes`() = runBlocking {
        val bank = SampleBankPreview.load(TestRomHelper.requireRomParser(), SampleBankPreview.Target(3,
            NspcSequence.Note(0, 120, 0xA4, instrument = 24), "Title Screen", "Test sound", SmEditProject(romPath = "test.sfc")))
        var played = 0
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 680)) {
            panel(font, theme, width, "Title Screen · Test sound · C4", bank, onPlayInBank = { played++ }) { scene, _, render ->
                assertTrue(tag(scene, "sample-bank-target").boundsInRoot.right <= width)
                assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> "Tryout only" in text.text } })
                assertTrue(tag(scene, "sample-bank-fit").config[SemanticsProperties.Text].any { "fits" in it.text })
                val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 400f); render()
                val play = tag(scene, "sample-play-in-bank")
                assertFalse(play.config.contains(SemanticsProperties.Disabled))
                assertTrue(play.boundsInRoot.left >= 0 && play.boundsInRoot.right <= width)
                assertTrue(play.boundsInRoot.top >= 0 && play.boundsInRoot.bottom <= 760, "Sound tryout must be reachable at every size")
                assertTrue(play.config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Add to bank" } })
            }
        }
        assertEquals(FontSize.entries.size * EditorTheme.entries.size * 2, played)
    }

    @Test fun `full or unsupported banks disable only sound tryout and keep standalone asset work available`() = runBlocking {
        val bank = SampleBankPreview.load(TestRomHelper.requireRomParser(), SampleBankPreview.Target(3,
            NspcSequence.Note(0, 120, 0xA4, instrument = 24), "Title Screen", "Test sound", SmEditProject(romPath = "test.sfc")))
        panel(FontSize.LARGER, EditorTheme.LIGHT, 420, "Title Screen · Test sound", bank) { scene, state, render ->
            val before = tag(scene, "sample-bank-fit").boundsInRoot
            state.update(SampleDraft("Long recording", List(SampleAuthoring.MAX_FRAMES) { 1000 })); state.prepare(); render()
            assertEquals(before, tag(scene, "sample-bank-fit").boundsInRoot, "Fit/error transitions must not resize the dialog")
            assertTrue(tag(scene, "sample-bank-fit").config[SemanticsProperties.Text].any { "Trim" in it.text })
            assertTrue(tag(scene, "sample-play-in-bank").config.contains(SemanticsProperties.Disabled))
            assertFalse(tag(scene, "sample-play-brr").config.contains(SemanticsProperties.Disabled))
            assertFalse(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
        }
        for (problem in listOf(null, "Bank tryout requires the original SPC engine")) {
            panel(FontSize.MEDIUM, EditorTheme.DARK, 680, "Title Screen · Test sound", bankProblem = problem) { scene, _, _ ->
                assertTrue(tag(scene, "sample-play-in-bank").config.contains(SemanticsProperties.Disabled))
                assertFalse(tag(scene, "sample-play-brr").config.contains(SemanticsProperties.Disabled))
            }
        }
    }
}
