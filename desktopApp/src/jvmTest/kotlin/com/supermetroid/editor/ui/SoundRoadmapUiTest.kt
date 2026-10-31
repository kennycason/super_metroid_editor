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
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SoundRoadmapUiTest {
    @TempDir lateinit var directory: File
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, key: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == key }
    private fun fits(scene: ComposeScene, width: Int, vararg keys: String) {
        for (key in keys) {
            val bounds = tag(scene, key).boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 760,
                "$key must be visible at $width: $bounds")
        }
    }
    private suspend fun panel(name: String, font: FontSize, theme: EditorTheme, width: Int, content: @Composable () -> Unit,
        check: suspend (ComposeScene, suspend () -> Unit) -> Unit) {
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 760)
        try {
            scene.constraints = Constraints.fixed(width, 760)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) { content() }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(2)
            } }
            render(); check(scene, render)
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/$name-${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `effects browser keeps native editing reachable and knob changes have one undo across editor fonts and themes`() = runBlocking {
        val file = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(file.readBytes())
        for (font in FontSize.entries) for (theme in EditorTheme.entries) {
            val editor = EditorState().apply { testMode = true; initForRom(file.path) }
            val state = SoundEffectEditorState()
            state.load(parser, editor)
            try {
                panel("sound-effects", font, theme, 1120, {
                    Row(Modifier.fillMaxSize()) {
                        SoundEffectsList(state, Modifier.width(260.dp).fillMaxHeight())
                        SoundEffectsView(parser, editor, state, Modifier.weight(1f))
                    }
                }) { scene, render ->
                    while (state.loading) { delay(10); render() }
                    fits(scene, 1120, "effect-search", "effect-play", "effect-waveform", "effect-status", "effect-field-LEVEL")
                    assertFalse(tag(scene, "effect-play").config.contains(SemanticsProperties.Disabled))
                    assertTrue(tag(scene, "effect-field-LEVEL").config[SemanticsActions.SetProgress].action!!.invoke(0f)); render()
                    while (state.loading) { delay(10); render() }
                    assertEquals(1, editor.project.soundEffectEdits.size); assertEquals(1, state.undoDepth)
                    assertTrue(tag(scene, "effect-undo").config[SemanticsActions.OnClick].action!!.invoke()); render()
                    while (state.loading) { delay(10); render() }
                    assertTrue(editor.project.soundEffectEdits.isEmpty())
                    tag(scene, "effect-search").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("door")); render()
                    assertTrue(state.visible.isNotEmpty()); assertTrue(state.visible.all { it.identity.name.contains("door", true) })
                }
            } finally { state.stop() }
        }
    }

    @Test fun `expression editing preserves source commands notes and pan phase while invalid ticks cannot commit`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 1040)) {
            val initial = NspcSequence.Song().apply {
                channels[0].notes += NspcSequence.Note(0, 192, 0xA4, instrument = 24)
                channels[0].commands += NspcSequence.ControlCommand(0, 0xE0, intArrayOf(24))
                channels[0].commands += NspcSequence.ControlCommand(24, 0xE1, intArrayOf(0xCA))
            }
            var song by mutableStateOf(initial)
            var edits = 0
            panel("sound-expression", font, theme, width, {
                Column(Modifier.fillMaxSize().padding(12.dp)) { MusicExpressionPanel(song, 0) { updated, _ -> song = updated; edits++ } }
            }) { scene, render ->
                fits(scene, width, "expression-apply", "expression-timeline", "expression-tick", "expression-value-0")
                tag(scene, "expression-event-0").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertTrue(tag(scene, "expression-apply").config.contains(SemanticsProperties.Disabled), "Native instrument selection stays read only")
                tag(scene, "expression-event-1").config[SemanticsActions.OnClick].action!!.invoke(); render()
                tag(scene, "expression-value-0").config[SemanticsActions.SetProgress].action!!.invoke(4f); render()
                tag(scene, "expression-tick").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("invalid")); render()
                assertTrue(tag(scene, "expression-apply").config.contains(SemanticsProperties.Disabled)); assertEquals(0, edits)
                tag(scene, "expression-tick").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("24")); render()
                tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertEquals(1, edits); assertEquals(0xC4, song.channels[0].commands[1].params[0])
                assertEquals(initial.channels[0].notes, song.channels[0].notes)
                assertEquals(initial.channels[0].commands[0], song.channels[0].commands[0])
            }
        }
    }

    @Test fun `transport toggles retain their behavior and share aligned geometry across tracks samples and effects`() = runBlocking {
        val file = File(directory, "transport.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(file.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(file.path) }
        val effects = SoundEffectEditorState().also { it.load(parser, editor) }
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 }
        val pcm = ShortArray(32)
        val sample = SoundEditorState.DecodedSample(SpcData.SampleDirEntry(0, 0x6E00, 0x6E00), pcm, 0)
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 1120)) {
            var playSize: androidx.compose.ui.geometry.Size? = null
            for (view in listOf("tracks", "samples", "effects")) {
                val state = SoundEditorState()
                effects.setLiveEnabled(true)
                panel("sound-toolbar-$view", font, theme, width, {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        if (view == "effects") SoundEffectsToolbar(parser, editor, effects)
                        else SoundTransportToolbar(state, editor, parser, track.takeIf { view == "tracks" },
                            sample.takeIf { view == "samples" }, pcm, false)
                    }
                }) { scene, render ->
                    val prefix = if (view == "effects") "effect" else "sound"
                    val play = tag(scene, "$prefix-play").boundsInRoot
                    if (playSize == null) playSize = play.size else assertEquals(playSize, play.size, "Common Play size must match across views")
                    val controls = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.TestTag)?.let { key ->
                        key.startsWith("$prefix-") && key != "$prefix-toolbar"
                    } == true }
                    for (node in controls) {
                        assertEquals(play.height, node.boundsInRoot.height, "$font/$view/${node.config[SemanticsProperties.TestTag]}")
                        assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width)
                    }
                    val stop = tag(scene, "$prefix-stop").boundsInRoot
                    assertEquals(play.top, stop.top); assertEquals(play.width, stop.width)
                    if (view == "effects") {
                        tag(scene, "effect-live").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        assertFalse(effects.live); assertFalse(tag(scene, "effect-live").config[SemanticsProperties.Selected])
                        tag(scene, "effect-duration-6").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        assertEquals(6, effects.duration)
                    } else {
                        val wasLooping = state.loopEnabled
                        tag(scene, "sound-loop").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        assertEquals(!wasLooping, state.loopEnabled)
                        assertEquals(state.loopEnabled, tag(scene, "sound-loop").config[SemanticsProperties.Selected])
                        tag(scene, "sound-rate-16000").config[SemanticsActions.OnClick].action!!.invoke(); render()
                        assertEquals(16000, state.sampleRate)
                    }
                    assertTrue(editor.project.musicEdits.isEmpty()); assertTrue(editor.project.soundEffectEdits.isEmpty())
                }
            }
        }
    }

    @Test fun `bank manager exposes memory and actions while referenced project samples cannot be removed`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val target = SampleBankPreview.Target(6, NspcSequence.Note(0, 120, 0xA4, instrument = 24), "Crateria", "Sound", SmEditProject(romPath = "test.sfc"))
        val overview = SoundBankManagement.inspect(parser, target).copy(samples = listOf(
            SoundBankManagement.Sample(40, "Used sample", 72, listOf(24)), SoundBankManagement.Sample(41, "Unused sample", 72, emptyList())))
        assertTrue(overview.emptySounds.isNotEmpty())
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 1040)) {
            var created = 0; var removed: Int? = null
            panel("sound-bank", font, theme, width, {
                SoundBankManagerContent(target.bankName, 24, overview, false, "Resources are shared across this bank's tracks and effects.",
                    { created++ }, { _, _ -> }, { removed = it }, {})
            }) { scene, render ->
                fits(scene, width, "bank-new-sound", "bank-copy-sound", "bank-remove-sample-40", "bank-remove-sample-41")
                assertTrue(tag(scene, "bank-remove-sample-40").config.contains(SemanticsProperties.Disabled))
                assertFalse(tag(scene, "bank-remove-sample-41").config.contains(SemanticsProperties.Disabled))
                assertTrue(tag(scene, "bank-copy-sound").config.contains(SemanticsProperties.Disabled), "Copy requires an explicit destination")
                tag(scene, "bank-new-sound").config[SemanticsActions.OnClick].action!!.invoke(); render(); assertEquals(1, created)
                tag(scene, "bank-remove-sample-41").config[SemanticsActions.OnClick].action!!.invoke(); render(); assertEquals(41, removed)
            }
        }
    }
}
