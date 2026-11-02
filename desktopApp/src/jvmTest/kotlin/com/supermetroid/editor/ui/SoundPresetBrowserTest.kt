package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SoundPresetBrowserTest {
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tag(scene: ComposeScene, name: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == name }
    private fun click(scene: ComposeScene, name: String) { assertTrue(tag(scene, name).config[SemanticsActions.OnClick].action!!.invoke()) }

    @Test fun `preset browser fits editor fonts themes and widths with visible piano and apply action`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()
        val target = SampleBankPreview.Target(3, NspcSequence.Note(0, 96, NspcSequence.nameToNote("C4"), instrument = 28),
            "Test bank", "Test sound", MusicBankTransitions.snapshot(editor.project))
        val preset = SoundPresets.capture(parser, MusicBankResolver.sourceRam(parser, editor.project, 3), 24, "Glass pluck")
        val plan = SoundPresets.plan(parser, target, preset)
        assertTrue(plan.changed)
        for (theme in EditorTheme.entries) for (font in FontSize.entries) for (width in listOf(620, 1088)) {
            val audition = SoundPresetAudition(parser, target, 36).also { it.select(preset) }
            var query by mutableStateOf("")
            var name by mutableStateOf("My sound")
            var cannotFit by mutableStateOf(false)
            var saved = 0; var applied = 0; var deleted = 0; var played = 0
            val scene = ComposeScene(coroutineContext = coroutineContext)
            val surface = Surface.makeRasterN32Premul(width, 690)
            val settings = EditorThemeState().apply { this.theme.value = theme; fontSize.value = font }
            try {
                scene.constraints = Constraints.fixed(width, 690)
                scene.setContent {
                    CompositionLocalProvider(LocalEditorTheme provides settings) {
                        MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                            SoundPresetBrowserContent(listOf(preset), audition, query, { query = it }, name, { name = it },
                                false, true, if (cannotFit) null else plan, if (cannotFit) "Needs 8000 B · largest bank gap 1800 B" else null, null,
                                onSave = { saved++ }, onDelete = { deleted++ }, onPlay = { played++ }, onApply = { applied++ }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                var time = System.nanoTime()
                suspend fun render() { repeat(8) {
                    Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                    surface.canvas.clear(theme.colorScheme.surfaceVariant.toArgb()); scene.render(surface.canvas, time); delay(1)
                } }
                render()
                assertEquals(0, played); assertEquals(0, applied)
                for (nameTag in listOf("preset-search", "preset-${preset.id}", "preset-name", "preset-save", "preset-play", "preset-apply", "preset-status", "audition-key-164")) {
                    val bounds = tag(scene, nameTag).boundsInRoot
                    assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 690,
                        "$theme/$font/$width/$nameTag: $bounds")
                }
                assertFalse(tag(scene, "preset-apply").config.contains(SemanticsProperties.Disabled))
                click(scene, "preset-apply"); assertEquals(1, applied)
                click(scene, "audition-original"); render(); assertTrue(audition.current)
                click(scene, "audition-key-166"); render(); assertEquals(NspcSequence.nameToNote("D4"), audition.pitch); assertEquals(1, played)
                click(scene, "preset-save"); assertEquals(1, saved)
                click(scene, "preset-delete"); assertEquals(1, deleted)
                cannotFit = true; render()
                assertTrue(tag(scene, "preset-apply").config.contains(SemanticsProperties.Disabled))
                assertFalse(tag(scene, "preset-play").config.contains(SemanticsProperties.Disabled), "Memory fit must not block audition")
                cannotFit = false
                audition.select(preset.copy(id = "876d0487-2918-4733-9d18-490c4fe85cb8", name = "Different preset")); render()
                assertTrue(tag(scene, "preset-apply").config.contains(SemanticsProperties.Disabled), "A previous preset's plan cannot be applied to a new selection")
                audition.select(preset)
                query = "No results"; render()
                assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "No matching presets." } == true })
                if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                    query = ""; cannotFit = false; render()
                    val file = File("build/test-output/preset-browser-${theme.name}-${font.name}-$width.png").apply { parentFile.mkdirs() }
                    surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
                }
            } finally { audition.stop(); scene.close(); surface.close() }
        }
    }
}
