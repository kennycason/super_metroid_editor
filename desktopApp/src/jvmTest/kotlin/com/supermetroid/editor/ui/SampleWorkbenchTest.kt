package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
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
        bankProblem: String? = null, onPlayInBank: () -> Unit = {}, application: MusicSampleBanks.Application? = null,
        useProblem: String? = null, onUseSample: (() -> Unit)? = null,
        check: suspend (ComposeScene, SampleWorkbenchState, suspend () -> Unit) -> Unit) {
        val state = SampleWorkbenchState().also { it.prepare() }
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 760)
        try {
            scene.constraints = Constraints.fixed(width, 760)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            SampleWorkbenchContent(state, true, onPlay = {}, onImport = {}, onOpen = {}, onSave = {}, onExport = {},
                                bankTarget = bankTarget, bank = bank, bankProblem = bankProblem, onPlayInBank = onPlayInBank,
                                application = application, useProblem = useProblem, onUseSample = onUseSample)
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
                val file = File("build/test-output/sample-workbench-${if (onUseSample != null) "use-${if (useProblem != null) "blocked-" else if (application == null) "checking-" else ""}" else if (bankTarget != null) "bank-" else ""}${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { state.stop(); scene.close(); surface.close() }
    }

    @Test fun `workbench uses editor fonts themes narrow wrapping and always visible asset actions`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 680, 1120)) panel(font, theme, width) { scene, _, render ->
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> "not added to a music bank" in text.text } })
            for (key in listOf("sample-import-wav", "sample-generate-SINE", "sample-generate-NOISE")) {
                val bounds = tag(scene, key).boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= width, "$font/$theme/$width/$key")
            }
            val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            val saveBeforeScroll = tag(scene, "sample-save-draft").boundsInRoot
            assertTrue(saveBeforeScroll.top >= 0 && saveBeforeScroll.bottom <= 760, "Save must be visible without scrolling")
            scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f); render()
            val save = tag(scene, "sample-save-draft")
            assertEquals(saveBeforeScroll, save.boundsInRoot, "Save must stay visible while editing")
            assertFalse(save.config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun `wide workbench shows source preview and trim side by side without scrolling`() = runBlocking {
        for (font in FontSize.entries) panel(font, EditorTheme.DARK, 1120,
            bankTarget = "Lower Norfair · Looped sound · C3") { scene, _, _ ->
            val source = tag(scene, "sample-source-module").boundsInRoot
            val preview = tag(scene, "sample-preview-module").boundsInRoot
            assertEquals(source.top, preview.top)
            assertTrue(source.right < preview.left)
            for (key in listOf("sample-import-wav", "sample-level", "sample-period", "sample-encoded-waveform",
                "sample-frame-Loop start", "sample-play-in-bank", "sample-save-draft", "sample-export-brr")) {
                val bounds = tag(scene, key).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= 1120 && bounds.top >= 0 && bounds.bottom <= 760,
                    "$font/$key must be visible without scrolling: $bounds")
            }
            val scroll = nodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            assertEquals(0f, scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), "$font should fit the wide layout")
        }
    }

    @Test fun `Live control switches between repeating and one shot audition without changing the sample`() = runBlocking {
        panel(FontSize.LARGER, EditorTheme.DARK, 1120) { scene, state, render ->
            val draft = state.draft
            assertTrue(tag(scene, "sample-live").config[SemanticsProperties.Selected])
            tag(scene, "sample-live").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertFalse(state.live)
            assertFalse(tag(scene, "sample-live").config[SemanticsProperties.Selected])
            assertEquals(draft, state.draft)
        }
    }

    @Test fun `previews have stationary status geometry and invalid edits disable save and playback`() = runBlocking {
        panel(FontSize.LARGER, EditorTheme.DARK, 680) { scene, state, render ->
            val before = tag(scene, "sample-workbench-status").boundsInRoot
            state.message = "Playing encoded BRR"; render()
            assertEquals(before, tag(scene, "sample-workbench-status").boundsInRoot)
            state.message = "Cannot open audio output"; render()
            assertEquals(before, tag(scene, "sample-workbench-status").boundsInRoot)
            tag(scene, "sample-period").config[SemanticsActions.SetProgress].action!!.invoke(16f); render()
            assertEquals(256, state.draft.periodFrames, "The period knob uses whole BRR blocks")
            assertEquals(1024, state.draft.pcm.size)
            tag(scene, "sample-level").config[SemanticsActions.SetProgress].action!!.invoke(25f); render()
            assertEquals(25, state.draft.gainPercent)
            assertTrue(tag(scene, "sample-play-brr").config.contains(SemanticsProperties.Disabled))
            state.prepare(); render()
            assertFalse(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
            tag(scene, "sample-frame-End").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("invalid")); render()
            assertTrue(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled),
                "Uncommitted invalid text must not save old settings: input=${tag(scene, "sample-frame-End").config[SemanticsProperties.EditableText]}, " +
                    "save=${tag(scene, "sample-save-draft").config}, status=${nodes(scene).mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }.flatten().map { it.text }.filter { "boundaries" in it }}")
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
    @Test fun `Use sample stays visible at every size with real remaining memory and one explicit apply action`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val target = SampleBankPreview.Target(3, NspcSequence.Note(0, 120, 0xA4, instrument = 24),
            "Title Screen", "Test sound", SmEditProject(romPath = "test.sfc"))
        val bank = SampleBankPreview.load(parser, target)
        val application = MusicSampleBanks.plan(parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)), "Sine")
        var used = 0
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 1120)) {
            panel(font, theme, width, "Title Screen · Test sound · C4", bank, application = application,
                onUseSample = { used++ }) { scene, state, render ->
                val button = tag(scene, "sample-use-in-sound")
                val bounds = button.boundsInRoot
                assertTrue(bounds.height > 0 && bounds.top >= 0 && bounds.bottom <= 760 && bounds.right <= width)
                assertFalse(button.config.contains(SemanticsProperties.Disabled))
                assertTrue(tag(scene, "sample-bank-fit").config[SemanticsProperties.Text].any { "${application.remainingBytes} B left" in it.text })
                val scroll = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 2000f); render()
                assertEquals(bounds, tag(scene, "sample-use-in-sound").boundsInRoot)
                button.config[SemanticsActions.OnClick].action!!.invoke()
                assertTrue(target.project.musicInstrumentBanks.isEmpty(), "Rendering and auditioning cannot apply a sample")
                tag(scene, "sample-frame-End").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("invalid")); render()
                assertTrue(tag(scene, "sample-use-in-sound").config.contains(SemanticsProperties.Disabled), "Invalid trim text must disable applying the old encoded sample")
                tag(scene, "sample-frame-End").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("512"))
                state.prepare(); render()
            }
        }
        assertEquals(FontSize.entries.size * EditorTheme.entries.size * 2, used)
        for (problem in listOf(null, "No custom sample slots left in this bank")) {
            panel(FontSize.LARGER, EditorTheme.DARK, 1120, "Title Screen · Test sound", bank, useProblem = problem,
                onUseSample = {}) { scene, _, _ ->
                assertTrue(tag(scene, "sample-use-in-sound").config.contains(SemanticsProperties.Disabled))
                assertFalse(tag(scene, "sample-save-draft").config.contains(SemanticsProperties.Disabled))
                assertFalse(tag(scene, "sample-play-brr").config.contains(SemanticsProperties.Disabled))
            }
        }
    }

}
