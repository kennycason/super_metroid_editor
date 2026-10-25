package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MusicBankSafetyStatusTest {
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.roots.flatMap { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun tagged(scene: ComposeScene, tag: String) = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == tag
    }
    private fun hasText(scene: ComposeScene, text: String) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> text in value.text }
    }
    private suspend fun withStatus(
        report: MusicBankTransitions.Report?, problem: String? = null, font: FontSize = FontSize.MEDIUM,
        check: suspend (ComposeScene, suspend () -> Unit) -> Unit,
    ) {
        val scene = ComposeScene(coroutineContext = coroutineContext)
        val surface = Surface.makeRasterN32Premul(520, 740)
        try {
            // Popups receive available bounds, not a forced full-window minimum size.
            scene.constraints = Constraints(maxWidth = 520, maxHeight = 740)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = EditorTheme.DARK.colorScheme, typography = font.materialTypography()) {
                        MusicBankSafetyStatus(report, problem)
                    }
                }
            }
            var time = System.nanoTime()
            val render: suspend () -> Unit = { repeat(8) {
                Snapshot.sendApplyNotifications(); yield(); time += 16_666_667
                surface.canvas.clear(0xFF1A1A1A.toInt()); scene.render(surface.canvas, time); delay(1)
            } }
            render()
            check(scene, render)
            if (System.getenv("SMEDIT_CAPTURE_INSTRUMENT_UI") == "true" && report?.canExport == false) {
                val file = File("build/test-output/music-bank-safety-${font.name}.png").apply { parentFile.mkdirs() }
                surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
            }
        } finally { scene.close(); surface.close() }
    }

    @Test fun `checking is non actionable and a passed resource check is not presented as a full export guarantee`() = runBlocking {
        withStatus(null) { scene, _ ->
            assertTrue(hasText(scene, "Checking bank loads"))
            assertTrue(tagged(scene, "music-bank-check").config.contains(SemanticsProperties.Disabled))
        }
        withStatus(MusicBankTransitions.Report(emptyList(), 22, mapOf(6 to emptyMap()))) { scene, render ->
            assertTrue(hasText(scene, "Bank-load check passed"))
            tagged(scene, "music-bank-check").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertTrue(hasText(scene, "22 bank transitions checked"))
            assertTrue(hasText(scene, "1 bank will reload instrument settings"))
            assertTrue(hasText(scene, "not a ROM-space or effect-usage guarantee"))
        }
    }

    @Test fun `blocked status stays compact with readable grouped details at every font size`() = runBlocking {
        val issues = listOf(0x0F, 0x18).map { target ->
            MusicBankTransitions.Issue(3, target, MusicBankTransitions.Resource.COMMON_INSTRUMENT, 0x6C03, 0x6C04)
        }
        for (font in FontSize.entries) withStatus(MusicBankTransitions.Report(issues, 22), font = font) { scene, render ->
            assertTrue(hasText(scene, "ROM export blocked"))
            assertFalse(hasText(scene, "Common instrument #0"), "Details must be opt-in")
            tagged(scene, "music-bank-check").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertTrue(hasText(scene, "Common instrument #0"))
            assertTrue(hasText(scene, "Title Screen · 2 affected banks"))
            assertTrue(hasText(scene, "SPC $6C03"))
            assertTrue(hasText(scene, "Audition and SPC/N-SPC export still work"))
            assertTrue(tagged(scene, "music-bank-check-details").boundsInRoot.height <= 440,
                "$font must keep the details popup compact")
            val close = tagged(scene, "music-bank-check-close")
            assertTrue(close.boundsInRoot.bottom <= 740, "$font must keep Close visible")
            assertTrue(close.boundsInRoot.width > 40)
        }
    }

    @Test fun `invalid arrangements expose a blocking reason instead of a passed check`() = runBlocking {
        withStatus(null, "Shared sound-bank data overlaps") { scene, render ->
            assertTrue(hasText(scene, "ROM export blocked"))
            tagged(scene, "music-bank-check").config[SemanticsActions.OnClick].action!!.invoke(); render()
            assertTrue(hasText(scene, "Shared sound-bank data overlaps"))
            tagged(scene, "music-bank-check-close").config[SemanticsActions.OnClick].action!!.invoke(); render(); render()
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "music-bank-check-details" })
        }
    }
}
