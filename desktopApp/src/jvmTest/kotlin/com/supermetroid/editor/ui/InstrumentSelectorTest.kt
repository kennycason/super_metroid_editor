package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class InstrumentSelectorTest {
    private val instruments = (0..2).map { index ->
        NspcRenderer.InstrumentEntry(index, 0x8F, 0xE0, 0x7F, 0x0001, index,
            0x6C00 + index * 6, 0x9000, 0x9000, 144, brrLoops = index == 1)
    }
    private val names = mapOf(0 to "Metal hit", 1 to "Soft pulse", 2 to "Glass chime")

    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tagged(scene: ComposeScene, tag: String): SemanticsNode =
        nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun hasTag(scene: ComposeScene, tag: String) =
        nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun click(scene: ComposeScene, tag: String) {
        assertTrue(tagged(scene, tag).config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun query(scene: ComposeScene, text: String) {
        assertTrue(tagged(scene, "instrument-search").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
    }

    private suspend fun withPicker(
        font: FontSize = FontSize.MEDIUM,
        width: Int = 600,
        available: Boolean = true,
        check: suspend (ComposeScene, suspend () -> Unit, MutableList<Int>, MutableList<Pair<Int, Boolean>>) -> Unit,
    ) {
        // Keep recomposition and semantics reads on this test's event loop.
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(width, 650)
        val applied = mutableListOf<Int>()
        val previews = mutableListOf<Pair<Int, Boolean>>()
        try {
            scene.constraints = Constraints.fixed(width, 650)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme, typography = font.materialTypography()) {
                        androidx.compose.material3.Surface {
                            InstrumentPicker(instruments, names, setOf(1), 0, "Lower Norfair sound bank", "C3",
                                available, null, false, { index, reference -> previews += index to reference },
                                applied::add, { applied += -1 })
                        }
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = {
                repeat(8) {
                    Snapshot.sendApplyNotifications()
                    yield()
                    time += 16_666_667
                    surface.canvas.clear(0xFF1A1A1A.toInt())
                    scene.render(surface.canvas, time)
                    delay(1)
                }
            }
            render()
            check(scene, render, applied, previews)
        } finally { scene.close(); surface.close() }
    }

    @Test fun `search filtering and audition never apply an instrument until Use is pressed`() = runBlocking {
        withPicker { scene, render, applied, previews ->
            query(scene, "pulse"); render()
            assertTrue(hasTag(scene, "instrument-row-1"))
            assertFalse(hasTag(scene, "instrument-row-0"))
            assertTrue(tagged(scene, "instrument-apply").config.contains(SemanticsProperties.Disabled))
            click(scene, "instrument-row-1"); render()
            click(scene, "instrument-preview-1"); render()
            assertEquals(listOf(1 to false), previews)
            assertTrue(applied.isEmpty())
            click(scene, "instrument-reference-pitch"); render()
            click(scene, "instrument-preview-1"); render()
            assertEquals(listOf(1 to false, 1 to true), previews)
            assertTrue(applied.isEmpty())
            click(scene, "instrument-apply"); render()
            assertEquals(listOf(1), applied)
        }
    }

    @Test fun `used filter empty results cancellation and unavailable native audio stay explicit`() = runBlocking {
        withPicker(available = false) { scene, render, applied, previews ->
            click(scene, "instrument-used-filter"); render()
            assertTrue(hasTag(scene, "instrument-row-1"))
            assertFalse(hasTag(scene, "instrument-row-0"))
            assertTrue(tagged(scene, "instrument-preview-1").config.contains(SemanticsProperties.Disabled))
            query(scene, "unknown sound"); render()
            assertFalse(hasTag(scene, "instrument-row-1"))
            assertTrue(tagged(scene, "instrument-apply").config.contains(SemanticsProperties.Disabled))
            click(scene, "instrument-cancel"); render()
            assertEquals(listOf(-1), applied)
            assertTrue(previews.isEmpty())
        }
    }

    @Test fun `compact note inspector hides raw controls and browser fits at every font size`() = runBlocking {
        for (font in FontSize.entries) {
            val scene = ComposeScene(coroutineContext = coroutineContext)
            val surface = Surface.makeRasterN32Premul(680, 740)
            var measuredHeight = 0
            try {
                scene.constraints = Constraints.fixed(680, 740)
                val settings = EditorThemeState().apply { fontSize.value = font }
                scene.setContent {
                    CompositionLocalProvider(LocalEditorTheme provides settings) {
                        MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme, typography = font.materialTypography()) {
                            androidx.compose.foundation.layout.Column(Modifier.onSizeChanged { measuredHeight = it.height }) {
                                NoteInstrumentSelector(NspcSequence.Note(0, 48, 0x98), instruments, names, setOf(1),
                                    "Lower Norfair sound bank", true, null, false, "", { _, _ -> }, {}, { _, _ -> }, {},
                                    advancedContent = { androidx.compose.material3.Text("Raw DSP settings") },
                                    sharedTracks = listOf("Title Screen", "Title Screen (After Button)"), sharedInstrumentEdited = true)
                            }
                        }
                    }
                }
                var time = System.nanoTime()
                suspend fun render() { repeat(8) {
                    Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                    surface.canvas.clear(0xFF1A1A1A.toInt())
                    scene.render(surface.canvas, time); delay(1)
                } }
                render()
                assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Raw DSP settings" } })
                assertTrue(hasTag(scene, "instrument-common-warning"))
                click(scene, "instrument-bank-scope"); render()
                assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Title Screen (After Button)" } })
                for (type in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED)) {
                    scene.sendKeyEvent(KeyEvent(AwtKeyEvent(Canvas(), type, 0L, 0, AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.CHAR_UNDEFINED)))
                }
                render(); render()
                assertFalse(hasTag(scene, "instrument-bank-tracks"), "Escape must dismiss the scope popup")
                click(scene, "instrument-choose"); render()
                val apply = tagged(scene, "instrument-apply")
                assertTrue(apply.boundsInRoot.bottom <= 740, "$font must keep browser actions in view")
                assertTrue(apply.boundsInRoot.width > 80)
                if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                    val file = File("build/test-output/instrument-browser-${font.name}.png").apply { parentFile.mkdirs() }
                    surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
                }
                assertTrue(measuredHeight > 0)
            } finally { scene.close(); surface.close() }
        }
    }

    @Test fun `legacy conflict panel requires an explicit version choice`() = runBlocking {
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(680, 310)
        val chosen = mutableListOf<Pair<Int, MusicInstrumentBanks.Choice>>()
        val choices = listOf(
            MusicInstrumentBanks.Choice("03:05", "Title Screen", MusicInstrumentBanks.entry(instruments[0].copy(gain = 31))),
            MusicInstrumentBanks.Choice("03:06", "Title Screen (After Button)", MusicInstrumentBanks.entry(instruments[0].copy(gain = 63)))
        )
        try {
            scene.constraints = Constraints.fixed(680, 310)
            scene.setContent { MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme) {
                androidx.compose.foundation.layout.Column {
                    InstrumentBankConflictPanel(listOf(MusicInstrumentBanks.Conflict(0, choices))) { index, choice -> chosen += index to choice }
                }
            } }
            var time = System.nanoTime()
            repeat(8) { Snapshot.sendApplyNotifications(); yield(); time += 16_666_667; scene.render(surface.canvas, time); delay(1) }
            assertTrue(chosen.isEmpty())
            assertTrue(hasTag(scene, "instrument-bank-choice-03:05"))
            assertTrue(hasTag(scene, "instrument-bank-choice-03:06"))
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true") {
                val file = File("build/test-output/instrument-bank-conflict.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
            click(scene, "instrument-bank-choice-03:06")
            assertEquals(listOf(0 to choices[1]), chosen)
        } finally { scene.close(); surface.close() }
    }
}
