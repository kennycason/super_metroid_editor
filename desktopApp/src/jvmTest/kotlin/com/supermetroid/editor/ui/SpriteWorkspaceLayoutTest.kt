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
import com.supermetroid.editor.data.CommunitySamusSpriteSource
import com.supermetroid.editor.rom.MapRandoSamusSprite
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
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

    private fun withScene(width: Int, font: FontSize, theme: EditorTheme = EditorTheme.DARK, height: Int = 800,
        content: @Composable () -> Unit, check: (ComposeScene, Surface, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            scene.constraints = Constraints.fixed(width, height)
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
    fun `animation preview keeps its requested size in a narrow scrollable viewport`() {
        val previewSize = 320
        val frameLabel = "fixed-size-preview"
        val animation = SpriteAnimation(
            "Narrow fixture",
            listOf(SpriteAnimationFrame(IntArray(16), 4, 4, label = frameLabel)),
        )

        withScene(180, FontSize.MEDIUM, content = {
            AnimationPlayer(animation, previewSize = previewSize)
        }) { scene, _, _ ->
            val preview = nodes(scene).single {
                it.config.contains(SemanticsProperties.ContentDescription) &&
                    frameLabel in it.config[SemanticsProperties.ContentDescription]
            }
            assertEquals(previewSize, preview.size.width)
            assertEquals(previewSize, preview.size.height)
            assertTrue(nodes(scene).any {
                it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                    it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
            })
        }
    }

    @Test
    fun `community region preview keeps fixed bounds in a narrow scrolling pane`() {
        val image = SamusCommunitySheetDecoder.DecodedImage(
            name = "stand_right",
            width = 2,
            height = 2,
            pixels = IntArray(4),
            paletteIndices = ByteArray(4),
            paletteInterval = 0 until 15,
        )
        val sheet = SamusCommunitySheetDecoder.DecodedSheet(
            sourceName = "layout-fixture",
            width = 2,
            height = 2,
            images = mapOf(image.name to image),
            masterPaletteRgb = IntArray(105),
            opaqueIndexedPixelCount = 0,
            quantizedPixelCount = 0,
        )

        withScene(180, FontSize.MEDIUM, height = 220, content = {
            CommunityRegionBrowser(sheet, sessionKey = "layout-fixture", modifier = Modifier.fillMaxSize())
        }) { scene, _, _ ->
            val preview = nodes(scene).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "community-samus-region-preview"
            }
            assertEquals(COMMUNITY_REGION_PREVIEW_SIZE, preview.size.width)
            assertEquals(COMMUNITY_REGION_PREVIEW_SIZE, preview.size.height)
            assertTrue(nodes(scene).any {
                it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                    it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
            })
            assertTrue(nodes(scene).any {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
            })
        }
    }

    @Test
    fun `community catalog detail pane fills wide windows but keeps its scrollable minimum`() {
        val sprite = MapRandoSamusSprite(
            name = "layout_fixture",
            version = 1,
            displayName = "Layout Fixture",
            authors = listOf("SMEDIT"),
            creditsName = null,
            category = "Custom",
        )
        val snapshot = CommunitySamusCatalogSnapshot("fixture", listOf(sprite))

        for ((width, expectedDetailsWidth) in listOf(600 to COMMUNITY_CATALOG_DETAILS_WIDTH, 1200 to 859)) {
            withScene(width, FontSize.MEDIUM, content = {
                CommunitySamusCatalogBrowser(
                    snapshot = snapshot,
                    activeCatalogName = null,
                    downloadingName = null,
                    isDownloaded = { false },
                    loadShowcase = { null },
                    onDownloadPreview = {},
                    onUseInProject = {},
                    onOpenDetails = {},
                    onRefresh = {},
                    onImportFile = {},
                    onBack = {},
                )
            }) { scene, _, _ ->
                val details = nodes(scene).single {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "community-samus-catalog-details"
                }
                assertEquals(expectedDetailsWidth, details.size.width)
                if (width == 600) {
                    assertTrue(nodes(scene).any {
                        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                            it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
                    })
                }
            }
        }
    }

    @Test
    fun `community project header badges stay on one line and animation details fill remaining width`() {
        val image = SamusCommunitySheetDecoder.DecodedImage(
            name = "stand_right",
            width = 2,
            height = 2,
            pixels = IntArray(4),
            paletteIndices = ByteArray(4),
            paletteInterval = 0 until 15,
        )
        val sheet = SamusCommunitySheetDecoder.DecodedSheet(
            sourceName = "layout-fixture",
            width = 2,
            height = 2,
            images = mapOf(image.name to image),
            masterPaletteRgb = IntArray(105),
            opaqueIndexedPixelCount = 0,
            quantizedPixelCount = 0,
        )
        val metadata = CommunitySamusPreviewMetadata(
            name = "yoshi_baby_mario",
            version = 1,
            displayName = "Yoshi & Baby Mario",
            authors = listOf("Dawn"),
            creditsName = null,
            category = "Custom",
        )
        val source = CommunitySamusSpriteSource(
            formatId = SamusCommunitySheetDecoder.FORMAT_ID,
            pngBase64 = "",
            sha256 = "ab".repeat(32),
            sourceName = "yoshi_baby_mario.png",
            displayName = metadata.displayName,
            authors = metadata.authors,
            category = metadata.category,
        )
        val session = CommunitySamusPreviewSession(
            sourceName = source.sourceName,
            sourceKey = "layout-fixture",
            pngBytes = ByteArray(0),
            result = SamusCommunitySheetDecoder.Result(sheet, emptyList()),
            metadata = metadata,
        )

        withScene(1087, FontSize.MEDIUM, content = {
            CommunitySamusSheetPreview(
                session = session,
                onChooseAnother = {},
                onExportSource = {},
                isProjectSource = true,
                projectSource = source,
                onRestoreBase = {},
            )
        }) { scene, _, _ ->
            for (label in listOf("PROJECT SOURCE", "SOURCE ONLY", "VALID")) {
                val badge = nodes(scene).single { text(it) == label }
                assertTrue(badge.size.height < 40, "$label badge wrapped vertically: ${badge.size}")
                assertEquals(badge.size.width.toFloat(), badge.boundsInRoot.width, "$label badge is clipped")
            }

            val animationDetails = nodes(scene).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "community-samus-animation-details"
            }
            assertEquals(1087 - COMMUNITY_ANIMATION_SIDEBAR_WIDTH - COMMUNITY_PANE_DIVIDER_WIDTH, animationDetails.size.width)
        }
    }

    @Test
    fun `community catalog showcase wraps fixed pose cells into visible rows`() {
        val images = (0 until 12).map { index ->
            SamusCommunitySheetDecoder.DecodedImage(
                name = "pose_$index",
                width = 2,
                height = 2,
                pixels = IntArray(4) { 0xFFFFFFFF.toInt() },
                paletteIndices = ByteArray(4) { 1 },
                paletteInterval = 0 until 15,
            )
        }

        withScene(500, FontSize.MEDIUM, content = {
            CommunitySamusShowcase(CommunitySamusCatalogShowcase(images), "Fixture")
        }) { scene, _, _ ->
            val poses = nodes(scene).filter { node ->
                node.config.contains(SemanticsProperties.ContentDescription) &&
                    node.config[SemanticsProperties.ContentDescription].any { it.startsWith("Fixture pose_") }
            }
            assertEquals(images.size, poses.size)
            assertTrue(poses.map { it.positionInRoot.y }.distinct().size >= 3, "Pose cells did not wrap into rows")
            poses.forEach { pose ->
                assertEquals(pose.size.width.toFloat(), pose.boundsInRoot.width, "Pose is horizontally clipped")
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
