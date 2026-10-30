package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MusicSamplePickerTest {
    private val samples = listOf(
        MusicSample(0, "brr:${"0".repeat(64)}", 3200, 1800, null, List(32) { MusicSamplePeak(-0.5f, 0.8f) }, "Shared ROM"),
        MusicSample(24, "brr:${"1".repeat(64)}", 1600, 900, 800, List(32) { MusicSamplePeak(-0.9f, 0.5f) }, "Import", listOf("custom.nspc")),
        MusicSample(31, "brr:${"2".repeat(64)}", 160, 90, null, List(32) { MusicSamplePeak(-0.2f, 0.4f) }, "Bank ROM"),
    ) + (0..31).map { MusicSample(0x80 + it, "noise:$it", 0, 0, null, emptyList(), "Generated") }
    private val instruments = listOf(InstrumentEntry(24, 0x8F, 0xE0, 0x60, 0x0001, 28, 0x6CA8),
        InstrumentEntry(31, 0x8F, 0xE0, 0x60, 0x0001, 29, 0x6CAE))
    private class Fixture(initial: MusicSampleBrowserState) {
        val browser = mutableStateOf(initial)
        val chosen = mutableListOf<Int>()
        val preview = mutableListOf<Pair<Int, Boolean>>()
        val renamed = mutableListOf<Pair<String, String>>()
        val controls = mutableListOf<InstrumentEntry>()
        var tryouts = 0
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ComposeScene) = scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun hasTag(scene: ComposeScene, tag: String) = nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(scene: ComposeScene, tag: String) { assertTrue(tagged(scene, tag).config[SemanticsActions.OnClick].action!!.invoke()) }
    private fun setText(scene: ComposeScene, tag: String, value: String) {
        assertTrue(tagged(scene, tag).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
    }
    private fun text(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.map(::text)).joinToString(" ")
    private suspend fun withEditor(font: FontSize = FontSize.MEDIUM, theme: EditorTheme = EditorTheme.DARK, width: Int = 680,
        available: Boolean = true, loading: Boolean = false, current: Int = 24, capture: String? = null, tryoutAvailable: Boolean = false,
        check: suspend (ComposeScene, suspend () -> Unit, Fixture) -> Unit) {
        val fixture = Fixture(MusicSampleBrowserState(if (loading) emptyList() else samples,
            mapOf(samples[0].resourceId to "Metal hit", samples[1].resourceId to "Warm loop"), loading))
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 900)
        try {
            scene.constraints = Constraints.fixed(width, 900)
            val settings = EditorThemeState().apply { fontSize.value = font; this.theme.value = theme }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        ComposeSurface {
                        Column(Modifier.padding(10.dp)) {
                            InstrumentRecordingEditor(instruments[0].copy(srcn = current), 256, fixture.browser.value,
                                instruments, mapOf(28 to "Lead", 29 to "Bass"), setOf(28), "F1", available,
                                onPreview = { srcn, reference -> fixture.preview += srcn to reference }, onChoose = fixture.chosen::add,
                                onRename = { id, name -> fixture.renamed += id to name
                                    fixture.browser.value = fixture.browser.value.copy(names = fixture.browser.value.names + (id to name)) },
                                onControlCommit = fixture.controls::add, onTrySample = if (tryoutAvailable) { { fixture.tryouts++ } } else null)
                        }
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(theme.colorScheme.background.toArgb()); scene.render(surface.canvas, time); delay(1)
            } }
            render(); check(scene, render, fixture)
            if (capture != null && System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/sample-browser-${theme.name}-${font.name}-$width-$capture.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `opening a sample tryout is separate from choosing a source or changing shared controls`() = runBlocking {
        withEditor(tryoutAvailable = true, capture = "new-recording") { scene, render, f ->
            val change = tagged(scene, "sample-choose").boundsInRoot
            val create = tagged(scene, "sample-try-draft").boundsInRoot
            assertEquals("New sample", text(tagged(scene, "sample-try-draft")).trim())
            assertEquals(change.top, create.top)
            assertTrue(create.left > change.right)
            click(scene, "sample-try-draft"); render()
            assertEquals(1, f.tryouts)
            assertTrue(f.chosen.isEmpty()); assertTrue(f.controls.isEmpty()); assertTrue(f.preview.isEmpty())
        }
        withEditor { scene, _, _ -> assertFalse(hasTag(scene, "sample-try-draft")) }
    }

    @Test fun `candidate search selection and pitch previews do not apply until Use sample`() = runBlocking {
        withEditor { scene, render, f ->
            assertTrue(hasTag(scene, "native-sound-controls"))
            click(scene, "sample-choose"); render()
            assertFalse(hasTag(scene, "native-sound-controls"))
            assertTrue(text(tagged(scene, "sample-count")).contains("3 of 3 samples"))
            assertTrue(tagged(scene, "sample-row-24").boundsInRoot.top < tagged(scene, "sample-row-0").boundsInRoot.top)
            setText(scene, "sample-search", "metal"); render()
            assertTrue(hasTag(scene, "sample-row-0")); assertFalse(hasTag(scene, "sample-row-24"))
            assertTrue(tagged(scene, "sample-apply").config.contains(SemanticsProperties.Disabled))
            click(scene, "sample-row-0"); render()
            click(scene, "sample-preview-0"); render()
            assertEquals(listOf(0 to false), f.preview)
            click(scene, "sample-reference-pitch"); render()
            click(scene, "sample-preview-0"); render()
            assertEquals(listOf(0 to false, 0 to true), f.preview)
            assertTrue(f.chosen.isEmpty()); assertTrue(f.controls.isEmpty())
            click(scene, "sample-apply"); render()
            assertEquals(listOf(0), f.chosen)
            assertFalse(hasTag(scene, "sample-browser"))
        }
    }

    @Test fun `usage and import source are searchable and used filter is based on this track`() = runBlocking {
        withEditor { scene, render, f ->
            click(scene, "sample-choose"); render()
            assertTrue(text(tagged(scene, "sample-usage")).contains("Lead"))
            setText(scene, "sample-search", "custom.nspc"); render()
            assertTrue(hasTag(scene, "sample-row-24")); assertFalse(hasTag(scene, "sample-row-0"))
            setText(scene, "sample-search", "0x18"); render()
            assertTrue(hasTag(scene, "sample-row-24")); assertFalse(hasTag(scene, "sample-row-0"))
            setText(scene, "sample-search", "Bass"); render()
            assertTrue(hasTag(scene, "sample-row-31"))
            setText(scene, "sample-search", ""); click(scene, "sample-used"); render()
            assertTrue(hasTag(scene, "sample-row-24")); assertFalse(hasTag(scene, "sample-row-31"))
            click(scene, "sample-cancel"); render()
            assertTrue(f.chosen.isEmpty()); assertTrue(f.controls.isEmpty())
        }
    }

    @Test fun `renaming uses content identity and updates descriptions without choosing a source`() = runBlocking {
        withEditor { scene, render, f ->
            click(scene, "sample-choose"); render()
            click(scene, "sample-row-0"); render()
            click(scene, "sample-rename"); render()
            setText(scene, "sample-name", "Bright hit"); render()
            click(scene, "sample-name-save"); render()
            assertEquals(listOf(samples[0].resourceId to "Bright hit"), f.renamed)
            assertTrue(text(tagged(scene, "sample-row-0")).contains("Bright hit"))
            assertTrue(f.chosen.isEmpty()); assertTrue(f.controls.isEmpty())
        }
    }

    @Test fun `noise sources are generated not recordings and can be previewed and chosen`() = runBlocking {
        withEditor { scene, render, f ->
            click(scene, "sample-choose"); render()
            click(scene, "sample-noise"); render()
            assertTrue(text(tagged(scene, "sample-count")).contains("32 of 32 noise sources"))
            assertFalse(hasTag(scene, "sample-row-24"))
            assertTrue(tagged(scene, "sample-reference-pitch").config.contains(SemanticsProperties.Disabled))
            click(scene, "sample-preview-133"); render()
            assertEquals(listOf(133 to false), f.preview)
            assertTrue(text(tagged(scene, "sample-apply")).contains("Use noise"))
            click(scene, "sample-apply"); render()
            assertEquals(listOf(133), f.chosen)
        }
    }

    @Test fun `loading unlisted current sources and unavailable native preview stay explicit`() = runBlocking {
        withEditor(loading = true) { scene, _, f ->
            assertTrue(tagged(scene, "sample-choose").config.contains(SemanticsProperties.Disabled))
            assertTrue(f.chosen.isEmpty())
        }
        withEditor(available = false, current = 7) { scene, render, f ->
            click(scene, "sample-choose"); render()
            assertTrue(tagged(scene, "sample-apply").config.contains(SemanticsProperties.Disabled))
            assertTrue(tagged(scene, "sample-preview-24").config.contains(SemanticsProperties.Disabled))
            setText(scene, "sample-search", "absent"); render()
            assertTrue(text(tagged(scene, "sample-count")).contains("0 of 3"))
            assertTrue(f.chosen.isEmpty()); assertTrue(f.preview.isEmpty())
        }
    }

    @Test fun `browser preview states retain exact geometry at all fonts themes and narrow widths`() = runBlocking {
        for (font in FontSize.entries) for (theme in EditorTheme.entries) {
            for (width in listOf(680, 420)) withEditor(font, theme, width, capture = "recordings") { scene, render, f ->
                click(scene, "sample-choose"); render()
                val tags = listOf("sample-browser", "sample-row-24", "sample-apply", "sample-usage")
                val bounds = tags.associateWith { tagged(scene, it).boundsInRoot }
                for ((source, busy) in listOf(24 to true, 24 to false, null to false)) {
                    f.browser.value = f.browser.value.copy(previewSource = source, previewBusy = busy); render()
                    tags.forEach { assertEquals(bounds[it], tagged(scene, it).boundsInRoot, "$font / $theme / $width / $it") }
                }
                val apply = tagged(scene, "sample-apply").boundsInRoot
                assertTrue(apply.height > 0 && apply.right <= width && apply.bottom <= 900)
                assertTrue(f.chosen.isEmpty()); assertTrue(f.controls.isEmpty())
            }
        }
    }
}
