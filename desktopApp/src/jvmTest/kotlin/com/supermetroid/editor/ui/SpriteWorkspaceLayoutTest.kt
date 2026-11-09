package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import com.supermetroid.editor.rom.TestRomHelper
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.*

class SpriteWorkspaceLayoutTest {
    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    private fun text(node: SemanticsNode): String? =
        if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else null

    private fun withScene(width: Int, font: FontSize, theme: EditorTheme = EditorTheme.DARK,
        content: @Composable () -> Unit, check: (ComposeScene, Surface, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(width, 800)
        try {
            scene.constraints = Constraints.fixed(width, 800)
            val settings = EditorThemeState().apply { fontSize.value = font }
            scene.setContent {
                CompositionLocalProvider(LocalEditorTheme provides settings) {
                    MaterialTheme(colorScheme = theme.colorScheme, typography = font.materialTypography()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
            val render = { repeat(8) { scene.render(surface.canvas, System.nanoTime()) }; Unit }
            render()
            check(scene, surface, render)
        } finally { scene.close(); surface.close() }
    }

    @Test
    fun `pixel toolbar wraps and keeps editing and save controls on screen at every font size`() {
        for (width in listOf(480, 700, 1000, 1600)) for (font in FontSize.entries) {
            withScene(width, font, content = {
                SpritePixelEditor("Golden Four statue · source 87B364", IntArray(64), 8, 8,
                    onApply = {}, onClose = {})
            }) { scene, _, _ ->
                val texts = nodes(scene).filter { text(it) != null }
                val label = texts.single { text(it) == "Golden Four statue · source 87B364" }
                val close = texts.single { text(it) == "Close" }
                val apply = texts.single { text(it) == "Apply" }
                for (node in texts.filter { it.boundsInRoot.top <= close.boundsInRoot.top }) {
                    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width,
                        "$width/$font/${text(node)} overflows: ${node.boundsInRoot}")
                }
                assertTrue(apply.boundsInRoot.width > 0 && close.boundsInRoot.width > 0)
                assertTrue(close.boundsInRoot.bottom < 300, "Toolbar leaves room for the canvas")
                if (width <= 700) assertTrue(close.boundsInRoot.top > label.boundsInRoot.bottom, "Narrow toolbar should wrap")
            }
        }
    }

    @Test
    fun `shared native animation player keeps every export action visible`() {
        val frames = listOf(
            SpriteAnimationFrame(IntArray(16), 4, 4, durationTicks = 4, label = "first"),
            SpriteAnimationFrame(IntArray(16), 4, 4, durationTicks = 6, label = "second"),
        )
        for (width in listOf(700, 1200)) for (font in FontSize.entries) {
            withScene(width, font, content = {
                NativeSpriteAnimationPlayer(SpriteAnimation("Export fixture", frames, loop = true))
            }) { scene, _, _ ->
                for (label in listOf("Frame PNG", "Animation GIF", "Sprite Sheet")) {
                    val action = nodes(scene).single { text(it) == label }
                    assertTrue(action.boundsInRoot.left >= 0 && action.boundsInRoot.right <= width, "$width/$font/$label")
                    assertTrue(action.boundsInRoot.top >= 0 && action.boundsInRoot.bottom <= 800, "$width/$font/$label")
                }
            }
        }
    }

    @Test
    fun `Samus background and animation checkerboard follow both editor themes`() {
        val parser = TestRomHelper.requireRomParser()
        for (theme in EditorTheme.entries) {
            val editor = EditorState().apply { testMode = true }
            withScene(1000, FontSize.LARGER, theme, content = {
                SamusSpriteViewer(parser, editor)
            }) { scene, surface, render ->
                surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    assertEquals(theme.colorScheme.background.toArgb(), bitmap.getColor(5, 790))
                } }
                val deathText = nodes(scene).single { text(it) == "Death" }
                var clickable: SemanticsNode? = deathText
                while (clickable != null && !clickable.config.contains(SemanticsActions.OnClick)) clickable = clickable.parent
                assertNotNull(clickable).config[SemanticsActions.OnClick].action!!.invoke()
                render()
                assertTrue(nodes(scene).any { text(it)?.startsWith("Samus suit explosion") == true })
                assertTrue(nodes(scene).any { text(it) == "Edit source pixels" })
            }
            withScene(400, FontSize.MEDIUM, theme, content = {
                AnimationPlayer(SpriteAnimation("Transparent fixture", listOf(SpriteAnimationFrame(IntArray(4), 2, 2, label = "checkerboard"))), previewSize = 240)
            }) { scene, surface, _ ->
                val preview = nodes(scene).single { it.config.contains(SemanticsProperties.ContentDescription) &&
                    "checkerboard" in it.config[SemanticsProperties.ContentDescription] }.boundsInRoot
                val left = preview.left.toInt()
                val top = preview.top.toInt()
                surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    // Interior checker cells, away from the rounded border.
                    assertEquals(theme.colorScheme.surface.toArgb(), bitmap.getColor(left + 30, top + 30))
                    assertEquals(theme.colorScheme.surfaceVariant.toArgb(), bitmap.getColor(left + 42, top + 30))
                } }
            }
        }
    }

    @Test
    fun `invalid project graphics report a preview error rather than crashing or showing base sprites`() {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState().apply {
            testMode = true
            project.customGfx.visualTileBlocks["invalid-native-source"] = ""
        }
        withScene(1000, FontSize.MEDIUM, content = { SamusSpriteViewer(parser, editor) }) { scene, _, _ ->
            assertTrue(nodes(scene).any { text(it)?.startsWith("Could not preview project sprite edits:") == true })
            assertFalse(nodes(scene).any { text(it)?.startsWith("Anim 0x") == true })
        }
    }
}
