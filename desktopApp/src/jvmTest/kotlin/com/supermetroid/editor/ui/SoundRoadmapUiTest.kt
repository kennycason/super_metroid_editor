package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
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

    @Test fun `new track form exposes checked creation and room selection includes authored track identities`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val initial = SmEditProject(romPath = "test.sfc")
        val plan = MusicCreation.plan(parser, initial, "My room theme", 3, "My palette", null)
        val banks = MusicCatalog.banks(initial).map { it to MusicCatalog.bankName(initial, it) }
        for (font in FontSize.entries) for (theme in EditorTheme.entries) for (width in listOf(420, 720)) {
            val draft = MusicCreationDraft(3)
            var created = 0
            var checking by mutableStateOf(false)
            panel("music-create", font, theme, width, {
                MusicCreationForm(draft, SpcData.KNOWN_TRACKS.first(), banks, plan.takeUnless { checking }, checking, null, { created++ }, {})
            }) { scene, render ->
                fits(scene, width, "new-track-name", "new-track-palette", "new-track-bank-name", "new-track-create", "new-track-status")
                tag(scene, "new-track-name").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("My room theme")); render()
                assertEquals("My room theme", draft.name)
                tag(scene, "new-track-create").config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, created)
                checking = true; render()
                assertTrue(tag(scene, "new-track-create").config.contains(SemanticsProperties.Disabled))
                tag(scene, "new-track-existing-bank").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertFalse(draft.newBank)
                assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.TestTag) == "new-track-bank-name" })
                tag(scene, "new-track-new-bank").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertTrue(draft.newBank)
            }
        }
        var set by mutableStateOf(plan.track.songSet)
        var play by mutableStateOf(plan.track.playIndex)
        panel("music-room-choice", FontSize.LARGER, EditorTheme.DARK, 720, {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                MusicDropdown(set, play, { bank, track -> set = bank; play = track }, MusicCatalog.tracks(plan.after))
            }
        }) { scene, render ->
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> plan.track.name in text.text } })
            tag(scene, "room-music-selector").config[SemanticsActions.OnClick].action!!.invoke(); render()
            tag(scene, "room-music-0-3").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(0, set); assertEquals(3, play)
            tag(scene, "room-music-selector").config[SemanticsActions.OnClick].action!!.invoke(); render()
            tag(scene, "room-music-${plan.track.songSet}-${plan.track.playIndex}").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(plan.track.songSet, set); assertEquals(plan.track.playIndex, play)
        }
    }
    @Test fun `independent effect controls hold pitch structural actions and copy forms work across fonts and themes`() = runBlocking {
        val file = File(directory, "effects-source.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(file.readBytes())
        for (font in FontSize.entries) for (theme in EditorTheme.entries) {
            val editor = EditorState().apply { testMode = true; initForRom(file.path) }
            val state = SoundEffectEditorState(); state.load(parser, editor)
            try {
                panel("effect-authoring", font, theme, 1120, {
                    Row(Modifier.fillMaxSize()) {
                        SoundEffectsList(state, Modifier.width(260.dp).fillMaxHeight())
                        SoundEffectsView(parser, editor, state, Modifier.weight(1f))
                    }
                }) { scene, render ->
                    while (state.loading) { delay(10); render() }
                    tag(scene, "effect-independent").config[SemanticsActions.OnClick].action!!.invoke(); render()
                    while (state.loading) { delay(10); render() }
                    assertEquals(1, editor.project.soundEffectPrograms.size)
                    fits(scene, 1120, "effect-step-add", "effect-step-earlier", "effect-step-later", "effect-step-remove", "effect-field-LEVEL")
                    tag(scene, "effect-hold-pitch").config[SemanticsActions.OnClick].action!!.invoke(); render()
                    while (state.loading) { delay(10); render() }
                    assertTrue(tag(scene, "effect-hold-pitch").config[SemanticsProperties.Selected])
                    assertEquals(0xF6, state.owned!!.voices[0].steps.first { it.opcode in 0..41 }.params[2])
                    tag(scene, "effect-undo").config[SemanticsActions.OnClick].action!!.invoke(); render()
                    while (state.loading) { delay(10); render() }
                    assertFalse(tag(scene, "effect-hold-pitch").config[SemanticsProperties.Selected])
                }
                for (width in listOf(420, 720)) for (duplicate in listOf(true, false)) {
                    var name by mutableStateOf("My new effect")
                    var submitted = 0
                    panel("effect-${if (duplicate) "copy" else "cue"}", font, theme, width, {
                        EffectAuthoringForm(duplicate, name, { name = it }, 2, {}, "2:02", {}, state.selected!!, null, { submitted++ }, {})
                    }) { scene, render ->
                        fits(scene, width, "effect-copy-target", "effect-copy-apply")
                        tag(scene, "effect-copy-apply").config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, submitted)
                        if (duplicate) {
                            tag(scene, "effect-copy-name").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("")); render()
                            assertTrue(tag(scene, "effect-copy-apply").config.contains(SemanticsProperties.Disabled))
                        }
                    }
                }
            } finally { state.stop() }
        }
    }

    @Test fun `the sole three voice cue has no alternate assignment and remains duplicable`() = runBlocking {
        val effect = SoundEffects.catalog(SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())).single { it.voices.size == 3 }
        for (duplicate in listOf(false, true)) panel("effect-three-voice-$duplicate", FontSize.LARGE, EditorTheme.DARK, 720, {
            EffectAuthoringForm(duplicate, "Rainbow copy", {}, 1, {}, "", {}, effect, null, {}, {})
        }) { scene, _ ->
            fits(scene, 720, "effect-copy-target", "effect-copy-apply")
            assertEquals(!duplicate, tag(scene, "effect-copy-apply").config.contains(SemanticsProperties.Disabled))
        }
    }

    private suspend fun tapExpression(scene: ComposeScene, tick: Int, extent: Int, lane: Int, render: suspend () -> Unit) {
        val bounds = tag(scene, "expression-timeline").boundsInRoot
        val at = Offset(bounds.left + 8 + tick.toFloat() / extent * (bounds.width - 16), bounds.top + (lane + 0.5f) * bounds.height / 4)
        scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary); render()
    }
    private fun text(scene: ComposeScene, key: String): String {
        fun read(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::read)
        return read(tag(scene, key)).joinToString(" ")
    }

    @Test fun `expression markers select existing events and additions remain editable during in place song updates`() = runBlocking {
        var song by mutableStateOf(NspcSequence.Song().apply {
            channels[0].notes += NspcSequence.Note(0, 192, 0xA4, instrument = 24)
            channels[0].commands += NspcSequence.ControlCommand(0, 0xE0, intArrayOf(24))
            channels[0].commands += NspcSequence.ControlCommand(48, 0xE1, intArrayOf(0xCA))
            channels[0].commands += NspcSequence.ControlCommand(96, 0xED, intArrayOf(128))
        }, neverEqualPolicy())
        val original = PianoRollPreviewLogic.deepCopySong(song)
        var edits = 0
        panel("expression-select", FontSize.LARGER, EditorTheme.DARK, 1040, {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                MusicExpressionPanel(song, 0) { updated, _ ->
                    for (channel in 0..7) { song.channels[channel].commands.clear(); song.channels[channel].commands.addAll(updated.channels[channel].commands) }
                    song = song; edits++
                }
            }
        }) { scene, render ->
            tapExpression(scene, 48, 192, 1, render)
            assertTrue(tag(scene, "expression-event-1").config[SemanticsProperties.Selected])
            assertTrue(text(scene, "expression-selection").contains("Pan"))
            assertTrue(text(scene, "expression-description").contains("center"))
            assertEquals("48", tag(scene, "expression-tick").config[SemanticsProperties.EditableText].text)
            assertEquals(10f, tag(scene, "expression-value-0").config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertEquals(0, edits); assertEquals(original, song)
            tag(scene, "expression-value-0").config[SemanticsActions.SetProgress].action!!.invoke(4f); render()
            tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(1, edits); assertEquals(48, song.channels[0].commands[1].tick)
            assertEquals(0xC4, song.channels[0].commands[1].params[0]); assertEquals(3, song.channels[0].commands.size)
            tapExpression(scene, 144, 192, 1, render)
            assertTrue(text(scene, "expression-selection").startsWith("New"))
            assertEquals(1, edits)
            tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(2, edits); assertEquals(4, song.channels[0].commands.size)
            assertEquals(NspcSequence.ControlCommand(144, 0xE1, intArrayOf(10)), song.channels[0].commands[3])
            assertTrue(tag(scene, "expression-event-3").config[SemanticsProperties.Selected])
            tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(2, edits, "Saving an unchanged selected event does not create history or another event")
            tag(scene, "expression-value-0").config[SemanticsActions.SetProgress].action!!.invoke(18f); render()
            tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(4, song.channels[0].commands.size); assertEquals(18, song.channels[0].commands[3].params[0])
            tapExpression(scene, 96, 192, 0, render)
            assertTrue(text(scene, "expression-selection").contains("Volume"))
            assertEquals(128f, tag(scene, "expression-value-0").config[SemanticsProperties.ProgressBarRangeInfo].current)
            tag(scene, "expression-type").config[SemanticsActions.OnClick].action!!.invoke(); render()
            tag(scene, "expression-kind-e1").config[SemanticsActions.OnClick].action!!.invoke(); render()
            tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(4, song.channels[0].commands.size)
            assertEquals(NspcSequence.ControlCommand(96, 0xE1, intArrayOf(10)), song.channels[0].commands[2])
            tag(scene, "expression-delete").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertEquals(3, song.channels[0].commands.size); assertTrue(song.channels[0].commands.none { it.command == 0xED })
            tapExpression(scene, 0, 192, 3, render)
            assertTrue(text(scene, "expression-selection").contains("Sound change"))
            assertTrue(tag(scene, "expression-apply").config.contains(SemanticsProperties.Disabled))
            assertEquals(original.channels[0].notes, song.channels[0].notes)
        }
    }

    @Test fun `dense expression events can be searched by type and position and selected values are inspectable`() = runBlocking {
        for (width in listOf(420, 1040)) {
            var song by mutableStateOf(NspcSequence.Song().apply {
                channels[0].notes += NspcSequence.Note(0, 4800, 0xA4, instrument = 24)
                repeat(120) { channels[0].commands += NspcSequence.ControlCommand(it * 24, 0xE1, intArrayOf(it % 21)) }
            })
            var edits = 0
            panel("expression-dense", FontSize.LARGER, EditorTheme.DARK, width, {
                Column(Modifier.fillMaxSize().padding(12.dp)) { MusicExpressionPanel(song, 0) { updated, _ -> song = updated; edits++ } }
            }) { scene, render ->
                fits(scene, width, "expression-event-list", "expression-search", "expression-value-0", "expression-apply")
                tag(scene, "expression-search").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Pan 696")); render()
                assertTrue(text(scene, "expression-event-29").contains("696"))
                tag(scene, "expression-event-29").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertEquals("696", tag(scene, "expression-tick").config[SemanticsProperties.EditableText].text)
                assertEquals(8f, tag(scene, "expression-value-0").config[SemanticsProperties.ProgressBarRangeInfo].current)
                assertEquals(0, edits)
                tag(scene, "expression-value-0").config[SemanticsActions.SetProgress].action!!.invoke(12f); render()
                tag(scene, "expression-apply").config[SemanticsActions.OnClick].action!!.invoke(); render()
                assertEquals(1, edits); assertEquals(12, song.channels[0].commands[29].params[0])
                assertEquals(120, song.channels[0].commands.size)
            }
        }
    }

    @Test fun `coincident expression markers cycle selection and external undo cannot redirect an edit to another event`() = runBlocking {
        var song by mutableStateOf(NspcSequence.Song().apply {
            channels[0].notes += NspcSequence.Note(0, 192, 0xA4, instrument = 24)
            channels[0].commands += NspcSequence.ControlCommand(48, 0xED, intArrayOf(128))
            channels[0].commands += NspcSequence.ControlCommand(48, 0xE5, intArrayOf(192))
        })
        var edits = 0
        panel("expression-stale", FontSize.MEDIUM, EditorTheme.LIGHT, 1040, {
            Column(Modifier.fillMaxSize().padding(12.dp)) { MusicExpressionPanel(song, 0) { updated, _ -> song = updated; edits++ } }
        }) { scene, render ->
            tapExpression(scene, 48, 192, 0, render)
            assertTrue(tag(scene, "expression-event-0").config[SemanticsProperties.Selected])
            tapExpression(scene, 48, 192, 0, render)
            assertTrue(tag(scene, "expression-event-1").config[SemanticsProperties.Selected])
            assertTrue(text(scene, "expression-description").contains("whole track"))
            song = PianoRollPreviewLogic.deepCopySong(song).also { it.channels[0].commands.removeAt(0) }; render()
            assertTrue(tag(scene, "expression-apply").config.contains(SemanticsProperties.Disabled))
            assertTrue(tag(scene, "expression-delete").config.contains(SemanticsProperties.Disabled))
            assertTrue(text(scene, "expression-problem").contains("Select an event again"))
            assertEquals(0, edits)
            tag(scene, "expression-event-0").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertFalse(tag(scene, "expression-apply").config.contains(SemanticsProperties.Disabled))
        }
    }

}
