package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.AsmIntegrationVisualEditor
import com.supermetroid.editor.asm.AsmWorkspaceState
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AsmIntegrationCoverageUiTest {
    @Test
    fun `library coverage expands where evidence and opens its visual destination`() {
        val state = AsmWorkspaceState().apply {
            showLibraryBrowser()
            openLibraryGuide("asm-visual-capability-matrix")
        }
        var opened: AsmIntegrationVisualEditor? = null
        withScene({ AsmWorkspaceCanvas(state, onOpenIntegrationEditor = { opened = it }) }) { scene, render ->
            assertNotNull(nodeWithTag(scene, "asm-integration-coverage"))
            val row = assertNotNull(nodeWithTag(scene, "asm-integration-room-records"))
            assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
            render()
            assertTrue(texts(scene).any { it == "Authority" })
            val open = assertNotNull(nodeWithTag(scene, "asm-integration-open-visual-room-records-room_info"))
            assertTrue(open.config[SemanticsActions.OnClick].action!!.invoke())
            render()
            assertEquals(AsmIntegrationVisualEditor.ROOM_INFO, opened)
        }
    }

    @Test
    fun `library search filters the central registry by source location`() {
        val state = AsmWorkspaceState().apply {
            showLibraryBrowser()
            openLibraryGuide("asm-visual-capability-matrix")
            query = "bank_B5.asm"
        }
        withScene({ AsmWorkspaceCanvas(state) }) { scene, _ ->
            assertNotNull(nodeWithTag(scene, "asm-integration-minimap"))
            assertTrue(nodeWithTag(scene, "asm-integration-room-records") == null)
        }
    }

    @Test
    fun `coverage exposes every visual destination for a cross-editor integration`() {
        val state = AsmWorkspaceState().apply {
            showLibraryBrowser()
            openLibraryGuide("asm-visual-capability-matrix")
            query = "gameplay-tuning"
        }
        val opened = mutableListOf<AsmIntegrationVisualEditor>()
        withScene({ AsmWorkspaceCanvas(state, onOpenIntegrationEditor = opened::add) }) { scene, render ->
            val row = assertNotNull(nodeWithTag(scene, "asm-integration-gameplay-tuning"))
            assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
            render()

            val expected = listOf(
                AsmIntegrationVisualEditor.ENEMY_TUNING,
                AsmIntegrationVisualEditor.BOSS_TUNING,
                AsmIntegrationVisualEditor.PATCHES,
            )
            expected.forEach { destination ->
                val button = assertNotNull(nodeWithTag(
                    scene,
                    "asm-integration-open-visual-gameplay-tuning-${destination.name.lowercase()}",
                ))
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            }
            assertEquals(expected, opened)
        }
    }

    private fun withScene(content: @androidx.compose.runtime.Composable () -> Unit, test: (ComposeScene, () -> Unit) -> Unit) {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(1100, 900)
        try {
            scene.constraints = Constraints.fixed(1100, 900)
            scene.setContent { MaterialTheme { content() } }
            var frameTime = System.nanoTime()
            val render = { repeat(12) { frameTime += 16_666_667; scene.render(surface.canvas, frameTime) }; Unit }
            render()
            test(scene, render)
        } finally {
            scene.close()
            surface.close()
        }
    }

    private fun nodeWithTag(scene: ComposeScene, tag: String): SemanticsNode? = nodes(scene).firstOrNull {
        it.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    private fun texts(scene: ComposeScene): List<String> = nodes(scene).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
    }

    private fun nodes(scene: ComposeScene): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
}
